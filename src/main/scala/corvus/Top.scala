package corvus

import chisel3._
import chisel3.util._
import corvus.SatelliteStation
import corvus.state_bus.RingNode
import corvus.sync_tree.SyncTree
import corvus.xiangshan.XSTopWrap
import chisel3.experimental.{CloneModuleAsRecord, noPrefix}
import chisel3.experimental.dataview._
import corvus.axi4_network.{AXI4ControllerRegion, AXI4Network, AXI4NetworkParams}
import device.standalone.{StandAloneCLINT, StandAlonePLIC}
import freechips.rocketchip.amba.axi4.AXI4BundleParameters
import freechips.rocketchip.diplomacy.{AddressSet, LazyModule}
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.amba.axi4.AXI4Bundle
import freechips.rocketchip.diplomacy.DisableMonitors
import freechips.rocketchip.util.HeterogeneousBag


class Top(implicit p: CorvusConfig) extends Module with RequireAsyncReset {
  val numTotalCore = p.numSCore + 1 // 1 master + numSCore satellites
  println(s"Top: NUM_S_CORE = ${p.numSCore}, NUM_TOTAL_CORE = $numTotalCore (1 master + ${p.numSCore} satellites)")

  val xsMod = Module(new XSTopWrap).suggestName("xs")
  val xs = xsMod.ioRecord.toMap +: Seq.fill(numTotalCore - 1)(noPrefix(CloneModuleAsRecord(xsMod).suggestName("xs")).elements.toMap)

  val satelliteAddr = AddressSet(0x30000000L, 0xffffL)
  val simUartAddr = AddressSet(0x310b0000L, 0xfffL)
  val peripheralAddressSets =
    AddressSet.misaligned(0x0L, 0x38000000L).flatMap(_.subtract(satelliteAddr)).flatMap(_.subtract(simUartAddr)) ++
      AddressSet.misaligned(0x38010000L, 0x03ff0000L) ++
      AddressSet.misaligned(0x40000000L, 0x40000000L)
  val peripheralBundleParams = xsMod.xstop.peripheral.params
  val peripheralBusMod = Module(new AXI4Network(
    AXI4NetworkParams(
      numCores = 1,
      controllers = Seq(
        AXI4ControllerRegion("clint", 0x38000000L, 0x10000L),
        AXI4ControllerRegion("plic", 0x3c000000L, 0x4000000L),
        AXI4ControllerRegion("sat", satelliteAddr.base, satelliteAddr.mask + 1),
        AXI4ControllerRegion("peripheral", peripheralAddressSets),
        AXI4ControllerRegion("simuart", 0x310b0000L, 0x1000L)
      ),
      core0Base = 0x0L,
      perCoreMemoryBytes = 0x80000000L,
      bundleParams = peripheralBundleParams,
      maxTransferBytes = peripheralBundleParams.dataBits / 8
    )
  )).suggestName("peripheral_bus")
  val peripheralBuses = peripheralBusMod.io +: Seq.fill(numTotalCore - 1)(noPrefix(CloneModuleAsRecord(peripheralBusMod).suggestName("peripheral_bus")).elements("io").asInstanceOf[peripheralBusMod.io.type])

  // Index 0 = master station, indices 1..numSCore = satellite stations
  // Master station reuses SatelliteStation but only exposes MBus queues
  val masterStation = Module(new SatelliteStation(LocalBusCounts(p.nMBus)))
  val satelliteStations = Seq.fill(numTotalCore - 1)(Module(new SatelliteStation(LocalBusCounts(p.nMBus, p.nSBus))))
  val allStations = masterStation +: satelliteStations
  val mBusRingNodes = Seq.fill(numTotalCore)(Seq.fill(p.nMBus)(Module(new RingNode)))
  val sBusRingNodes = Seq.fill(numTotalCore - 1)(Seq.fill(p.nSBus)(Module(new RingNode)))
  val syncTree = Module(new SyncTree)

  val clintBus = Module(new AXI4Network(
    AXI4NetworkParams(
      numCores = numTotalCore,
      controllers = Seq(AXI4ControllerRegion("clint", 0x38000000L, 0x10000L)),
      core0Base = 0x38000000L,
      perCoreMemoryBytes = 0x1L,
      bundleParams = peripheralBundleParams,
      maxTransferBytes = peripheralBundleParams.dataBits / 8
    )
  ))

  val peripheralOutBus = Module(new AXI4Network(
    AXI4NetworkParams(
      numCores = numTotalCore,
      controllers = Seq(AXI4ControllerRegion("peripheral", peripheralAddressSets)),
      core0Base = 0x0L,
      perCoreMemoryBytes = 0x1L,
      bundleParams = peripheralBundleParams,
      maxTransferBytes = peripheralBundleParams.dataBits / 8
    )
  ))

  val memoryBus = Module(new AXI4Network(
    AXI4NetworkParams(
      numCores = numTotalCore,
      controllers = Seq(
        AXI4ControllerRegion("mem0", AddressSet.misaligned(0x80000000L, numTotalCore.toLong * 0x10000000L)),
        // AXI4ControllerRegion("mem1", ...)
      ),
      core0Base = 0x80000000L,
      perCoreMemoryBytes = 0x10000000L,
      bundleParams = AXI4BundleParameters(
        addrBits = 48,
        dataBits = 256,
        idBits = 14
      ),
      maxTransferBytes = 64
    )
  ))

  val NrExtIntr = 64

  val xsParameters: Parameters = XSTopWrap.config

  val standAlonePlicLMs = Seq.fill(numTotalCore)(DisableMonitors(q => LazyModule(new StandAlonePLIC(
    useTL = false,
    baseAddress = 0x3c000000L,
    addrWidth = 32,
    dataWidth = peripheralBundleParams.dataBits,
    hartNum = numTotalCore,
    extIntrNum = NrExtIntr
  )(q)))(xsParameters))
  val standAlonePlics =  standAlonePlicLMs.foreach(lm => Module(lm.module))

  val io = IO(new Bundle {
    val memory = chiselTypeOf(memoryBus.io.controllers)
    val peripheral = chiselTypeOf(peripheralOutBus.io.controllers.head)
    val simUart = Vec(numTotalCore, chiselTypeOf(peripheralBusMod.io.controllers.head))
    val riscv_rst_vec = Vec(numTotalCore, chiselTypeOf(xsMod.xstop.io.riscv_rst_vec))
    val rtc_clock = Input(Bool())
    val extIntrs = Vec(numTotalCore, Vec(NrExtIntr, Input(Bool())))
  })

  memoryBus.io.controllers <> io.memory

  peripheralBuses.zipWithIndex.foreach { case (bus, idx) =>
    bus.controllers(0) <> clintBus.io.cores(idx)
    bus.controllers(1) <> standAlonePlicLMs(idx).axi4node.get
    val sat = allStations(idx)
    val satAxi = bus.controllers(2).viewAs[AXI4Bundle]

    val satArId = RegInit(0.U(satAxi.ar.bits.id.getWidth.W))
    val satAwId = RegInit(0.U(satAxi.aw.bits.id.getWidth.W))

    sat.io.ctrlAXI4Slave.ar.valid := satAxi.ar.valid
    sat.io.ctrlAXI4Slave.ar.bits.addr := satAxi.ar.bits.addr - satelliteAddr.base.U
    sat.io.ctrlAXI4Slave.ar.bits.len := satAxi.ar.bits.len
    sat.io.ctrlAXI4Slave.ar.bits.size := satAxi.ar.bits.size
    sat.io.ctrlAXI4Slave.ar.bits.burst := satAxi.ar.bits.burst
    sat.io.ctrlAXI4Slave.ar.bits.prot := satAxi.ar.bits.prot
    satAxi.ar.ready := sat.io.ctrlAXI4Slave.ar.ready
    when(satAxi.ar.fire) { satArId := satAxi.ar.bits.id }

    sat.io.ctrlAXI4Slave.aw.valid := satAxi.aw.valid
    sat.io.ctrlAXI4Slave.aw.bits.addr := satAxi.aw.bits.addr - satelliteAddr.base.U
    sat.io.ctrlAXI4Slave.aw.bits.len := satAxi.aw.bits.len
    sat.io.ctrlAXI4Slave.aw.bits.size := satAxi.aw.bits.size
    sat.io.ctrlAXI4Slave.aw.bits.burst := satAxi.aw.bits.burst
    sat.io.ctrlAXI4Slave.aw.bits.prot := satAxi.aw.bits.prot
    satAxi.aw.ready := sat.io.ctrlAXI4Slave.aw.ready
    when(satAxi.aw.fire) { satAwId := satAxi.aw.bits.id }

    sat.io.ctrlAXI4Slave.w.valid := satAxi.w.valid
    sat.io.ctrlAXI4Slave.w.bits.data := satAxi.w.bits.data
    sat.io.ctrlAXI4Slave.w.bits.strb := satAxi.w.bits.strb
    sat.io.ctrlAXI4Slave.w.bits.last := satAxi.w.bits.last
    satAxi.w.ready := sat.io.ctrlAXI4Slave.w.ready

    satAxi.r.valid := sat.io.ctrlAXI4Slave.r.valid
    satAxi.r.bits.data := sat.io.ctrlAXI4Slave.r.bits.data
    satAxi.r.bits.resp := sat.io.ctrlAXI4Slave.r.bits.resp
    satAxi.r.bits.last := sat.io.ctrlAXI4Slave.r.bits.last
    satAxi.r.bits.id := satArId
    sat.io.ctrlAXI4Slave.r.ready := satAxi.r.ready

    satAxi.b.valid := sat.io.ctrlAXI4Slave.b.valid
    satAxi.b.bits.resp := sat.io.ctrlAXI4Slave.b.bits.resp
    satAxi.b.bits.id := satAwId
    sat.io.ctrlAXI4Slave.b.ready := satAxi.b.ready

    // SyncTree connections
    if (idx == 0) {
      // Master core: drives broadcast tree input, reads merge tree output
      syncTree.io.masterIn := sat.io.outSyncFlag
      sat.io.inSyncFlag := syncTree.io.masterOut
    } else {
      // Satellite core: feeds merge tree, receives broadcast
      syncTree.io.slaveIn(idx - 1) := sat.io.outSyncFlag
      sat.io.inSyncFlag := syncTree.io.slaveOut(idx - 1)
    }

    (0 until p.nMBus).foreach { i =>
      val ring = mBusRingNodes(idx)(i)
      ring.io.nodeId := sat.io.nodeId

      ring.io.fromCore <> sat.io.fromCoreStateBusPort(i)
      ring.io.toCore <> sat.io.toCoreStateBusPort(i)
    }

    if (idx > 0) {
      (0 until p.nSBus).foreach { i =>
        val ring = sBusRingNodes(idx - 1)(i)
        ring.io.nodeId := sat.io.nodeId

        ring.io.fromCore <> sat.io.fromCoreStateBusPort(p.nMBus + i)
        ring.io.toCore <> sat.io.toCoreStateBusPort(p.nMBus + i)
      }
    }

    bus.controllers(3) <> peripheralOutBus.io.cores(idx)
    bus.controllers(4) <> io.simUart(idx)
  }

  mBusRingNodes.transpose.foreach { ringSeq =>
    ringSeq.zip(ringSeq.tail :+ ringSeq.head).foreach { case (curr, next) =>
      curr.io.toNext <> next.io.fromPrev
    }
  }
  sBusRingNodes.transpose.foreach { ringSeq =>
    ringSeq.zip(ringSeq.tail :+ ringSeq.head).foreach { case (curr, next) =>
      curr.io.toNext <> next.io.fromPrev
    }
  }

  val standAloneClintLM = DisableMonitors(q => LazyModule(new StandAloneCLINT(
    useTL = false,
    baseAddress = 0x38000000L,
    addrWidth = 32,
    dataWidth = peripheralBundleParams.dataBits,
    hartNum = numTotalCore
  )(q)))(xsParameters)
  val standAloneClint = Module(standAloneClintLM.module)
  clintBus.io.controllers.head <> standAloneClintLM.axi4node.get
  // positive edge sampling of the lower-speed rtc_clock
  val rtcTick = RegInit(0.U(3.W))
  rtcTick := Cat(rtcTick(1, 0), io.rtc_clock)
  standAloneClint.io.rtcTick := rtcTick(1) && !rtcTick(2)

  standAlonePlicLMs.zip(io.extIntrs).foreach { case (lm, intrs) =>
    lm.extIntrs.head := intrs
  }

  peripheralOutBus.io.controllers.head <> io.peripheral

  xs.zip(memoryBus.io.cores).zipWithIndex.foreach { case ((xsio, busio), idx) =>
    val xsio_io = xsio("io").asInstanceOf[xsMod.xstop.io.type]
    xsio_io.clock := clock
    xsio_io.reset := reset
    xsio_io.hartId := idx.U
    xsio_io.clintTime := standAloneClint.io.time
    xsio_io.riscv_rst_vec := io.riscv_rst_vec(idx)

    val xsio_clint = xsio("clint").asInstanceOf[HeterogeneousBag[Vec[Bool]]]
    xsio_clint.head := standAloneClintLM.int(idx)

    val xsio_plic = xsio("plic").asInstanceOf[HeterogeneousBag[Vec[Bool]]]
    println(s"xsio_plic width: ${xsio_plic.getWidth}, standAlonePlic int width: ${standAlonePlicLMs(idx).int.getWidth}")
    xsio_plic.toSeq.zip(standAlonePlicLMs(idx).int.slice(2 * idx, 2 * idx + 2)).foreach {
      case (l, r) => l := r
    }
    // Master core (idx == 0) uses polling; satellites connect stateBusBufferFullInterrupt to PLIC
    if (idx > 0) {
      standAlonePlicLMs(idx).extIntrs.head(p.satelliteIRQNum - 1) := satelliteStations(idx - 1).io.stateBusBufferFullInterrupt
    }

    val xsio_nmi = xsio("nmi").asInstanceOf[HeterogeneousBag[Vec[Bool]]]
    xsio_nmi := 0.U.asTypeOf(xsio_nmi)

    val xsio_debug = xsio("debug").asInstanceOf[HeterogeneousBag[Vec[Bool]]]
    xsio_debug := 0.U.asTypeOf(xsio_debug)

    val xsio_memory = xsio("memory").asInstanceOf[xsMod.xstop.memory.type]
    xsio_memory <> busio

    val xsio_peripheral = xsio("peripheral").asInstanceOf[xsMod.xstop.peripheral.type]
    xsio_peripheral <> peripheralBuses(idx).cores.head

    val xsio_dma = xsio("dma").asInstanceOf[xsMod.xstop.dma.type]
    xsio_dma <> WireDefault(0.U.asTypeOf(xsio_dma))
  }
}
