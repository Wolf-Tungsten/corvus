package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable.ArrayBuffer

class CtrlAXI4SlaveSpec extends AnyFlatSpec {
  private val dataBits = 64
  private val addrBits = 10
  private val nRS = 16
  private val nWS = 16
  private val nRQ = 16
  private val nWQ = 16
  private val wordBytes = dataBits / 8
  private val sizeVal = log2Ceil(wordBytes)
  private val fullStrbMask = (BigInt(1) << wordBytes) - 1

  /** Harness wiring queues for stream interfaces. */
  private class CtrlAXI4SlaveHarness extends Module {
    val io = IO(new Bundle {
      val axi = new CtrlAXI4IO(addrBits, dataBits)
      val controlOut = Output(Vec(nWS, UInt(dataBits.W)))
      val readQEnq = Vec(nRQ, Flipped(Decoupled(UInt(dataBits.W))))
      val writeQDeq = Vec(nWQ, Decoupled(UInt(dataBits.W)))
      val statusIn = Input(Vec(nRS, UInt(dataBits.W)))
    })
    val dut = Module(new CtrlAXI4Slave(addrBits, dataBits, nRS, nWS, nRQ, nWQ))

    val readQueues = Seq.fill(nRQ)(Module(new Queue(UInt(dataBits.W), 4)))
    val writeQueues = Seq.fill(nWQ)(Module(new Queue(UInt(dataBits.W), 4)))

    for (i <- 0 until nRQ) {
      readQueues(i).io.enq <> io.readQEnq(i)
      dut.io.readQueues(i) <> readQueues(i).io.deq
    }
    for (i <- 0 until nWQ) {
      dut.io.writeQueues(i) <> writeQueues(i).io.enq
      io.writeQDeq(i) <> writeQueues(i).io.deq
    }

    dut.io.status := io.statusIn
    io.controlOut := dut.io.control
    io.axi <> dut.io.axi
  }

  private def statusBase = 0
  private def writeBase = nRS * wordBytes
  private def readQBase = (nRS + nWS) * wordBytes
  private def writeQBase = (nRS + nWS + nRQ) * wordBytes

  private def statusAddr(idx: Int) = statusBase + idx * wordBytes
  private def controlAddr(idx: Int) = writeBase + idx * wordBytes
  private def readQueueAddr(idx: Int) = readQBase + idx * wordBytes
  private def writeQueueAddr(idx: Int) = writeQBase + idx * wordBytes

  private def resetAndInit(c: CtrlAXI4SlaveHarness): Unit = {
    c.io.axi.ar.valid.poke(false.B)
    c.io.axi.aw.valid.poke(false.B)
    c.io.axi.w.valid.poke(false.B)
    c.io.axi.r.ready.poke(false.B)
    c.io.axi.b.ready.poke(false.B)
    for (q <- c.io.readQEnq) q.valid.poke(false.B)
    for (q <- c.io.writeQDeq) q.ready.poke(false.B)
    c.reset.poke(true.B)
    c.clock.step()
    c.reset.poke(false.B)
    c.clock.step()
  }

  private def startRead(
      c: CtrlAXI4SlaveHarness,
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
      c: CtrlAXI4SlaveHarness,
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
      c: CtrlAXI4SlaveHarness,
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

  private def preloadReadQueue(
      c: CtrlAXI4SlaveHarness,
      idx: Int,
      values: Seq[BigInt]
  ): Unit = {
    for (v <- values) {
      c.io.readQEnq(idx).bits.poke(v.U)
      c.io.readQEnq(idx).valid.poke(true.B)
      while (!c.io.readQEnq(idx).ready.peek().litToBoolean) { c.clock.step() }
      c.clock.step()
      c.io.readQEnq(idx).valid.poke(false.B)
    }
  }

  private def expectWriteQueue(
      c: CtrlAXI4SlaveHarness,
      idx: Int,
      expected: BigInt
  ): Unit = {
    c.io.writeQDeq(idx).ready.poke(true.B)
    var guard = 0
    while (!c.io.writeQDeq(idx).valid.peek().litToBoolean && guard < 40) {
      guard += 1; c.clock.step()
    }
    assert(guard < 40, s"Timed out waiting for write queue $idx")
    c.io.writeQDeq(idx).bits.expect(expected.U)
    c.clock.step()
    c.io.writeQDeq(idx).ready.poke(false.B)
  }

  behavior of "CtrlAXI4Slave"

  it should "route reads/writes to correct regions and return defaults out-of-range" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      c.io.statusIn(0).poke("h1111222233334444".U)
      c.io.statusIn(1).poke("h5555666677778888".U)

      startRead(c, statusAddr(0), len = 0)
      val statusBeat = collectReadBeats(c, 1).head
      assert(statusBeat == BigInt("1111222233334444", 16))

      writeBurst(c, controlAddr(0), Seq(BigInt("AAAABBBBCCCCDDDD", 16)))
      c.io.controlOut(0).expect("hAAAABBBBCCCCDDDD".U)

      startRead(c, writeQBase + nWQ * wordBytes + 16, len = 0)
      val outOfRange = collectReadBeats(c, 1).head
      assert(outOfRange == 0)
    }
  }

  it should "bridge stream read/write queues correctly" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      preloadReadQueue(c, 5, Seq(BigInt("DEADBEEFCAFEBABE", 16)))
      preloadReadQueue(c, 6, Seq(BigInt("0F0E0D0C0B0A0908", 16)))

      startRead(c, readQueueAddr(5), len = 1)
      val readQueueData = collectReadBeats(c, 2)
      assert(
        readQueueData == Seq(
          BigInt("DEADBEEFCAFEBABE", 16),
          BigInt("0F0E0D0C0B0A0908", 16)
        )
      )

      val writeData = Seq(
        BigInt("1111222233334444", 16),
        BigInt("5555666677778888", 16),
        BigInt("9999AAAABBBBCCCC", 16)
      )
      writeBurst(c, writeQueueAddr(4), writeData)
      expectWriteQueue(c, 4, writeData(0))
      expectWriteQueue(c, 5, writeData(1))
      expectWriteQueue(c, 6, writeData(2))
    }
  }

  it should "allow concurrent read/write transactions on different regions" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      c.io.statusIn(9).poke("h0A0B0C0D0E0F1011".U)

      startRead(c, statusAddr(9), len = 0)
      val writePayload = Seq(
        BigInt("DADADADA00000001", 16),
        BigInt("DADADADA00000002", 16)
      )
      writeBurst(c, writeQueueAddr(12), writePayload, waitForB = false)

      val readBeat = collectReadBeats(c, 1).head
      assert(readBeat == BigInt("0A0B0C0D0E0F1011", 16))

      expectWriteQueue(c, 12, writePayload.head)
      expectWriteQueue(c, 13, writePayload(1))

      var guard = 0
      while (!c.io.axi.b.valid.peek().litToBoolean && guard < 40) {
        guard += 1; c.clock.step()
      }
      assert(guard < 40, "B channel did not become valid")
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
      c.io.axi.b.ready.poke(false.B)
    }
  }

  it should "treat bursts crossing region boundary as subcontroller-local and return zeros when out-of-range" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      c.io.statusIn(14).poke("h1414141414141414".U)
      c.io.statusIn(15).poke("hF0F0F0F0F0F0F0F0".U)

      startRead(c, statusAddr(15), len = 2)
      val beats = collectReadBeats(c, 3)
      assert(
        beats == Seq(
          BigInt("F0F0F0F0F0F0F0F0", 16),
          BigInt(0),
          BigInt(0)
        )
      )
    }
  }

  it should "serve long aligned bursts across status registers" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      val statusVals = Seq(
        BigInt("1111111111111111", 16),
        BigInt("2222222222222222", 16),
        BigInt("3333333333333333", 16),
        BigInt("4444444444444444", 16)
      )
      for (i <- statusVals.indices) {
        c.io.statusIn(8 + i).poke(statusVals(i).U)
      }

      startRead(c, statusAddr(8), len = 3)
      val beats = collectReadBeats(c, 4)
      assert(beats == statusVals)
    }
  }

  it should "respect WSTRB when updating control registers" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      val baseVal = BigInt("0123456789ABCDEF", 16)
      val upperUpdate = BigInt("DEADBEEFCAFEBABE", 16)
      writeBurst(c, controlAddr(2), Seq(baseVal))
      writeBurst(
        c,
        controlAddr(2),
        Seq(upperUpdate),
        strb = Seq(BigInt("f0", 16))
      )

      startRead(c, controlAddr(2), len = 0)
      val updated = collectReadBeats(c, 1).head
      assert(updated == BigInt("DEADBEEF89ABCDEF", 16))
    }
  }

  it should "distribute write queue bursts across consecutive queues" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      val payload = Seq(
        BigInt("1111111122222222", 16),
        BigInt("3333333344444444", 16),
        BigInt("5555555566666666", 16),
        BigInt("7777777788888888", 16)
      )
      writeBurst(c, writeQueueAddr(10), payload)
      expectWriteQueue(c, 10, payload(0))
      expectWriteQueue(c, 11, payload(1))
      expectWriteQueue(c, 12, payload(2))
      expectWriteQueue(c, 13, payload(3))
    }
  }

  it should "drain multiple read queues in order during bursts" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      preloadReadQueue(c, 2, Seq(BigInt("0101010101010101", 16)))
      preloadReadQueue(c, 3, Seq(BigInt("0202020202020202", 16)))
      preloadReadQueue(c, 4, Seq(BigInt("0303030303030303", 16)))

      startRead(c, readQueueAddr(2), len = 2)
      val beats = collectReadBeats(c, 3)
      assert(
        beats == Seq(
          BigInt("0101010101010101", 16),
          BigInt("0202020202020202", 16),
          BigInt("0303030303030303", 16)
        )
      )
    }
  }

  it should "wait on empty read queues and deliver once data becomes available" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)
      startRead(c, readQueueAddr(7), len = 0)
      c.io.axi.r.ready.poke(true.B)

      for (_ <- 0 until 3) {
        assert(!c.io.axi.r.valid.peek().litToBoolean)
        c.clock.step()
      }

      c.io.readQEnq(7).bits.poke("h1111222233334444".U)
      c.io.readQEnq(7).valid.poke(true.B)
      while (!c.io.readQEnq(7).ready.peek().litToBoolean) { c.clock.step() }
      c.clock.step()
      c.io.readQEnq(7).valid.poke(false.B)

      var guard = 0
      while (!c.io.axi.r.valid.peek().litToBoolean && guard < 40) {
        guard += 1; c.clock.step()
      }
      assert(guard < 40, "Read response did not arrive after queue fill")
      c.io.axi.r.bits.data.expect("h1111222233334444".U)
      c.io.axi.r.bits.last.expect(true.B)
      c.clock.step()
      c.io.axi.r.ready.poke(false.B)
    }
  }

  it should "ignore mis-sized control writes and still accept legal writes" in {
    simulate(new CtrlAXI4SlaveHarness) { c =>
      resetAndInit(c)

      writeBurst(
        c,
        controlAddr(5),
        Seq(BigInt("FFFFEEEEDDDDCCCC", 16)),
        size = sizeVal - 1
      )
      startRead(c, controlAddr(5), len = 0)
      val ignored = collectReadBeats(c, 1).head
      assert(ignored == 0)

      val legal = BigInt("1122334455667788", 16)
      writeBurst(c, controlAddr(5), Seq(legal))
      startRead(c, controlAddr(5), len = 0)
      val applied = collectReadBeats(c, 1).head
      assert(applied == legal)
    }
  }
}
