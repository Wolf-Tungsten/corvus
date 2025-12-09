package corvus

import chisel3._
import chisel3.util._
import corvus.ctrl_axi4_slave._
import corvus.state_bus.StateBusPacket

class SatelliteStation(implicit p: CorvusConfig) extends Module {
  private val addrBits = p.simCoreDBusAddrWidth
  private val dataBits = p.simCoreDBusDataWidth
  private val nStateBus = p.nStateBus
  private val dstWidth = p.stateBusConfig.dstWidth
  private val payloadWidth = p.stateBusConfig.payloadWidth

  require(dataBits == 32 || dataBits == 64, "simCoreDBusDataWidth must be 32 or 64")
  require(nStateBus > 0 && isPow2(nStateBus), "nStateBus must be power of 2 and > 0")
  require(
    dstWidth + payloadWidth == dataBits,
    "stateBusConfig.dstWidth + payloadWidth must equal simCoreDBusDataWidth"
  )
  require(p.toCoreStateBusBufferDepth > 0, "toCoreStateBusBufferDepth must be > 0")
  require(p.fromCoreStateBusBufferDepth > 0, "fromCoreStateBusBufferDepth must be > 0")

  private val nRS = 1 << log2Ceil(1 + nStateBus)
  private val nWS = 2
  private val nRQ = nStateBus
  private val nWQ = nStateBus

  val io = IO(new Bundle {
    val ctrlAXI4Slave = new CtrlAXI4IO(addrBits, dataBits)
    val stateBusBufferFullInterrupt = Output(Bool())
    val inSyncFlag = Input(UInt(p.syncTreeConfig.flagWidth.W))
    val outSyncFlag = Output(UInt(p.syncTreeConfig.flagWidth.W))
    val nodeId = Output(UInt(dstWidth.W))
    val toCoreStateBusPort = Vec(nStateBus, Decoupled(new StateBusPacket))
    val fromCoreStateBusPort = Vec(nStateBus, Flipped(Decoupled(new StateBusPacket)))
  })

  private val ctrlAXI =
    Module(new CtrlAXI4Slave(addrBits, dataBits, nRS, nWS, nRQ, nWQ))

  private val toCoreStateBusBuffers = Seq.fill(nStateBus) {
    Module(new Queue(UInt(dataBits.W), p.toCoreStateBusBufferDepth))
  }
  private val fromCoreStateBusBuffers = Seq.fill(nStateBus) {
    Module(new Queue(UInt(dataBits.W), p.fromCoreStateBusBufferDepth))
  }

  ctrlAXI.io.axi <> io.ctrlAXI4Slave

  for (i <- 0 until nStateBus) {
    toCoreStateBusBuffers(i).io.enq <> ctrlAXI.io.writeQueues(i)
    ctrlAXI.io.readQueues(i) <> fromCoreStateBusBuffers(i).io.deq
  }

  private val statusRegs = Wire(Vec(nRS, UInt(dataBits.W)))
  for (i <- 0 until nRS) {
    statusRegs(i) := 0.U
  }
  statusRegs(0) := io.inSyncFlag
  for (i <- 0 until nStateBus) {
    statusRegs(1 + i) := toCoreStateBusBuffers(i).io.count
  }
  ctrlAXI.io.status := statusRegs

  io.outSyncFlag := ctrlAXI.io.control(0)(p.syncTreeConfig.flagWidth - 1, 0)
  io.nodeId := ctrlAXI.io.control(1)(dstWidth - 1, 0)

  toCoreStateBusBuffers.zip(io.toCoreStateBusPort).foreach { case (buf, port) =>
    val raw = buf.io.deq.bits
    port.valid := buf.io.deq.valid
    port.bits.dst := raw(dataBits - 1, payloadWidth)
    port.bits.payload := raw(payloadWidth - 1, 0)
    buf.io.deq.ready := port.ready
  }

  fromCoreStateBusBuffers.zip(io.fromCoreStateBusPort).foreach { case (buf, port) =>
    buf.io.enq.valid := port.valid
    buf.io.enq.bits := Cat(port.bits.dst, port.bits.payload)
    port.ready := buf.io.enq.ready
  }

  io.stateBusBufferFullInterrupt := toCoreStateBusBuffers
    .map(buf => !buf.io.enq.ready)
    .reduce(_ || _)
}
