package corvus

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util._
import corvus.ctrl_axi4_slave._
import corvus.state_bus.StateBusPacket
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class SatelliteStationSpec extends AnyFlatSpec {
  implicit private val p: CorvusConfig = CorvusConfig()

  private val addrBits = p.simCoreDBusAddrWidth
  private val dataBits = p.simCoreDBusDataWidth
  private val nStateBus = p.nStateBus
  private val dstWidth = p.stateBusConfig.dstWidth
  private val payloadWidth = p.stateBusConfig.payloadWidth
  private val wordBytes = dataBits / 8
  private val sizeVal = log2Ceil(wordBytes)
  private val fullStrbMask = (BigInt(1) << wordBytes) - 1
  private val nRS = 1 << log2Ceil(1 + nStateBus)
  private val nWS = 2
  private val nRQ = nStateBus
  private val nWQ = nStateBus

  /** Harness exposing the DUT IO for easier pokes/peeks. */
  private class SatelliteStationHarness extends Module {
    val io = IO(new Bundle {
      val axi = new CtrlAXI4IO(addrBits, dataBits)
      val inSyncFlag = Input(UInt(p.syncTreeConfig.flagWidth.W))
      val outSyncFlag = Output(UInt(p.syncTreeConfig.flagWidth.W))
      val nodeId = Output(UInt(dstWidth.W))
      val toCore = Vec(nStateBus, Decoupled(new StateBusPacket))
      val fromCore = Vec(nStateBus, Flipped(Decoupled(new StateBusPacket)))
      val fullIntr = Output(Bool())
    })
    val dut = Module(new SatelliteStation)

    dut.io.ctrlAXI4Slave <> io.axi
    dut.io.inSyncFlag := io.inSyncFlag
    io.outSyncFlag := dut.io.outSyncFlag
    io.nodeId := dut.io.nodeId
    io.fullIntr := dut.io.stateBusBufferFullInterrupt
    io.toCore <> dut.io.toCoreStateBusPort
    dut.io.fromCoreStateBusPort <> io.fromCore
  }

  private def statusBase = 0
  private def writeBase = nRS * wordBytes
  private def readQBase = (nRS + nWS) * wordBytes
  private def writeQBase = (nRS + nWS + nRQ) * wordBytes

  private def statusAddr(idx: Int) = statusBase + idx * wordBytes
  private def controlAddr(idx: Int) = writeBase + idx * wordBytes
  private def readQueueAddr(idx: Int) = readQBase + idx * wordBytes
  private def writeQueueAddr(idx: Int) = writeQBase + idx * wordBytes

  private def resetAndInit(c: SatelliteStationHarness): Unit = {
    c.reset.poke(true.B)
    c.clock.step()
    c.reset.poke(false.B)
    c.clock.step()

    c.io.axi.ar.valid.poke(false.B)
    c.io.axi.aw.valid.poke(false.B)
    c.io.axi.w.valid.poke(false.B)
    c.io.axi.r.ready.poke(false.B)
    c.io.axi.b.ready.poke(false.B)
    c.io.inSyncFlag.poke(0.U)
    for (q <- c.io.toCore) {
      q.ready.poke(false.B)
    }
    for (q <- c.io.fromCore) {
      q.valid.poke(false.B)
      q.bits.dst.poke(0.U)
      q.bits.payload.poke(0.U)
    }
  }

  private def startRead(
      c: SatelliteStationHarness,
      addr: Int,
      len: Int,
      size: Int = sizeVal,
      burst: UInt = CtrlAXI4Consts.BURST_INCR
  ): Unit = {
    c.io.axi.ar.bits.addr.poke(addr.U)
    c.io.axi.ar.bits.len.poke(len.U)
    c.io.axi.ar.bits.size.poke(size.U)
    c.io.axi.ar.bits.burst.poke(burst)
    c.io.axi.ar.bits.prot.poke(0.U)
    c.io.axi.ar.valid.poke(true.B)
    var guard = 0
    while (!c.io.axi.ar.ready.peek().litToBoolean && guard < 40) {
      guard += 1; c.clock.step()
    }
    assert(guard < 40, "AR handshake timeout")
    c.clock.step()
    c.io.axi.ar.valid.poke(false.B)
  }

  private def collectReadBeats(
      c: SatelliteStationHarness,
      beats: Int,
      maxCycles: Int = 200
  ): Seq[BigInt] = {
    val buf = ArrayBuffer[BigInt]()
    var cycles = 0
    c.io.axi.r.ready.poke(true.B)
    while (buf.size < beats && cycles < maxCycles) {
      if (c.io.axi.r.valid.peek().litToBoolean) {
        buf += c.io.axi.r.bits.data.peek().litValue
        val last = c.io.axi.r.bits.last.peek().litToBoolean
        assert(!last || buf.size == beats, "R channel marked last early")
        c.clock.step()
      } else {
        cycles += 1
        c.clock.step()
      }
    }
    c.io.axi.r.ready.poke(false.B)
    assert(buf.size == beats, s"Timed out waiting for $beats read beats")
    buf.toSeq
  }

  private def writeBurst(
      c: SatelliteStationHarness,
      addr: Int,
      data: Seq[BigInt],
      strb: Seq[BigInt] = Nil,
      size: Int = sizeVal,
      burst: UInt = CtrlAXI4Consts.BURST_INCR,
      waitForB: Boolean = true
  ): Unit = {
    require(data.nonEmpty)
    val strbSeq =
      if (strb.nonEmpty) strb else Seq.fill(data.length)(fullStrbMask)

    c.io.axi.aw.bits.addr.poke(addr.U)
    c.io.axi.aw.bits.len.poke((data.length - 1).U)
    c.io.axi.aw.bits.size.poke(size.U)
    c.io.axi.aw.bits.burst.poke(burst)
    c.io.axi.aw.bits.prot.poke(0.U)
    c.io.axi.aw.valid.poke(true.B)
    var awGuard = 0
    while (!c.io.axi.aw.ready.peek().litToBoolean && awGuard < 40) {
      awGuard += 1; c.clock.step()
    }
    assert(awGuard < 40, "AW handshake timeout")
    c.clock.step()
    c.io.axi.aw.valid.poke(false.B)

    for (((d, s), idx) <- data.zip(strbSeq).zipWithIndex) {
      c.io.axi.w.bits.data.poke(d.U)
      c.io.axi.w.bits.strb.poke(s.U(wordBytes.W))
      c.io.axi.w.bits.last.poke(idx == data.length - 1)
      c.io.axi.w.valid.poke(true.B)
      var wGuard = 0
      while (!c.io.axi.w.ready.peek().litToBoolean && wGuard < 40) {
        wGuard += 1; c.clock.step()
      }
      assert(wGuard < 40, "W handshake timeout")
      c.clock.step()
    }
    c.io.axi.w.valid.poke(false.B)
    c.io.axi.w.bits.last.poke(false.B)

    if (waitForB) {
      var bGuard = 0
      while (!c.io.axi.b.valid.peek().litToBoolean && bGuard < 60) {
        bGuard += 1; c.clock.step()
      }
      assert(bGuard < 60, "B response timeout")
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
      c.io.axi.b.ready.poke(false.B)
    }
  }

  private def enqueueFromCore(
      c: SatelliteStationHarness,
      idx: Int,
      dst: BigInt,
      payload: BigInt
  ): Unit = {
    c.io.fromCore(idx).bits.dst.poke(dst.U)
    c.io.fromCore(idx).bits.payload.poke(payload.U)
    c.io.fromCore(idx).valid.poke(true.B)
    var guard = 0
    while (!c.io.fromCore(idx).ready.peek().litToBoolean && guard < 40) {
      guard += 1; c.clock.step()
    }
    assert(guard < 40, s"fromCore enqueue timeout for idx $idx")
    c.clock.step()
    c.io.fromCore(idx).valid.poke(false.B)
  }

  private def expectToCorePacket(
      c: SatelliteStationHarness,
      idx: Int,
      dst: BigInt,
      payload: BigInt,
      maxCycles: Int = 80
  ): Unit = {
    var cycles = 0
    c.io.toCore(idx).ready.poke(true.B)
    while (!c.io.toCore(idx).valid.peek().litToBoolean && cycles < maxCycles) {
      cycles += 1; c.clock.step()
    }
    assert(cycles < maxCycles, s"Timed out waiting toCore[$idx] packet")
    c.io.toCore(idx).bits.dst.expect(dst.U)
    c.io.toCore(idx).bits.payload.expect(payload.U)
    c.clock.step()
  }

  behavior of "SatelliteStation"

  it should "read status and write control registers via AXI" in {
    simulate(new SatelliteStationHarness) { c =>
      resetAndInit(c)
      val inSyncVal = 0x3
      c.io.inSyncFlag.poke(inSyncVal.U)

      startRead(c, statusAddr(0), len = 0)
      val status0 = collectReadBeats(c, 1).head
      assert(status0 == inSyncVal, s"Unexpected inSyncFlag value $status0")

      val outSyncVal = 0x2
      val nodeIdVal = 0x1234
      writeBurst(c, controlAddr(0), Seq(BigInt(outSyncVal)))
      writeBurst(c, controlAddr(1), Seq(BigInt(nodeIdVal)))

      c.io.outSyncFlag.expect(outSyncVal.U)
      c.io.nodeId.expect(nodeIdVal.U)

      startRead(c, controlAddr(0), len = 0)
      val ctrl0 = collectReadBeats(c, 1).head
      assert(ctrl0 == outSyncVal, s"Control reg0 readback mismatch: $ctrl0")

      startRead(c, controlAddr(1), len = 0)
      val ctrl1 = collectReadBeats(c, 1).head
      assert(ctrl1 == nodeIdVal, s"Control reg1 readback mismatch: $ctrl1")
    }
  }

  it should "enqueue AXI writes into toCore queues and expose counts/fields" in {
    simulate(new SatelliteStationHarness) { c =>
      resetAndInit(c)
      val idx = 2
      val dstVal = 0x12
      val payload0 = BigInt("123456789ABC", 16) // 48 bits
      val payload1 = BigInt("0F0E0D0C0B0A", 16)
      val word0 = (BigInt(dstVal) << payloadWidth) | payload0
      val word1 = (BigInt(dstVal + 1) << payloadWidth) | payload1

      c.io.toCore(idx).ready.poke(false.B) // hold to observe count
      writeBurst(c, writeQueueAddr(idx), Seq(word0))
      writeBurst(c, writeQueueAddr(idx), Seq(word1))

      startRead(c, statusAddr(1 + idx), len = 0)
      val countVal = collectReadBeats(c, 1).head
      assert(countVal == 2, s"Expected count 2 for queue $idx, got $countVal")

      // Drain and check field split order
      expectToCorePacket(c, idx, dstVal, payload0)
      expectToCorePacket(c, idx, dstVal + 1, payload1)
    }
  }

  it should "accept fromCore packets and serve them via AXI read queues" in {
    simulate(new SatelliteStationHarness) { c =>
      resetAndInit(c)
      val idx = 1
      val dstVal = 0x55
      val payloadVal = BigInt("ABCDEF123456", 16)
      val combined = (BigInt(dstVal) << payloadWidth) | payloadVal

      enqueueFromCore(c, idx, dstVal, payloadVal)

      startRead(c, readQueueAddr(idx), len = 0)
      val beats = collectReadBeats(c, 1)
      assert(beats.head == combined, s"Read queue data mismatch: ${beats.head}")
    }
  }

  it should "raise and clear interrupt when toCore buffers become full/drain" in {
    simulate(new SatelliteStationHarness) { c =>
      resetAndInit(c)
      val idx = 0
      val dstVal = 1
      val payloadVal = BigInt("111111111111", 16)
      val word = (BigInt(dstVal) << payloadWidth) | payloadVal

      c.io.toCore(idx).ready.poke(false.B) // block draining
      // Fill buffer depth (default 4) with individual writes
      for (_ <- 0 until p.toCoreStateBusBufferDepth) {
        writeBurst(c, writeQueueAddr(idx), Seq(word))
      }

      c.io.fullIntr.expect(true.B)

      c.io.toCore(idx).ready.poke(true.B)
      // Drain all entries
      for (_ <- 0 until p.toCoreStateBusBufferDepth) {
        expectToCorePacket(c, idx, dstVal, payloadVal)
      }
      c.io.fullIntr.expect(false.B)
    }
  }
}
