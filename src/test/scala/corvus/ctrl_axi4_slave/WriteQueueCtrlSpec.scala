package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec

class WriteQueueCtrlSpec extends AnyFlatSpec {
  private val dataBits = 32
  private val addrBits = 8
  private val nQueues = 8
  private val wordBytes = dataBits / 8
  private val sizeVal = log2Ceil(wordBytes)

  /** Harness that exposes deq side of sink queues receiving writes. */
  private class WriteQueueHarness(timeoutCycles: Int = 0) extends Module {
    val io = IO(new Bundle {
      val axi = new CtrlAXI4IO(addrBits, dataBits)
      val deq = Vec(nQueues, Decoupled(UInt(dataBits.W)))
    })
    val dut =
      Module(new WriteQueueCtrl(addrBits, dataBits, nQueues, timeoutCycles))
    val sinks = Seq.fill(nQueues)(Module(new Queue(UInt(dataBits.W), 2)))
    for (i <- 0 until nQueues) {
      dut.io.queues(i) <> sinks(i).io.enq
      io.deq(i) <> sinks(i).io.deq
    }
    io.axi <> dut.io.axi
  }

  behavior of "WriteQueueCtrl"

  it should "enqueue aligned writes and produce write response" in {
    simulate(new WriteQueueHarness) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      c.io.deq.foreach(_.ready.poke(false.B)) // hold to observe enqueue
      c.io.axi.b.ready.poke(false.B) // hold B to observe

      c.io.axi.aw.bits.addr.poke(0.U)
      c.io.axi.aw.bits.len.poke(0.U)
      c.io.axi.aw.bits.size.poke(sizeVal.U)
      c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.aw.bits.prot.poke(0.U)
      c.io.axi.aw.valid.poke(true.B)
      c.io.axi.w.bits.data.poke("hDEADBEEF".U)
      c.io.axi.w.bits.strb.poke("b1111".U)
      c.io.axi.w.bits.last.poke(true.B)
      c.io.axi.w.valid.poke(false.B)

      c.clock.step() // AW fires
      c.io.axi.aw.valid.poke(false.B)

      // Drive W once ready
      c.io.axi.w.valid.poke(true.B)
      var guard1 = 0
      while (!c.io.axi.w.ready.peek().litToBoolean && guard1 < 10) {
        guard1 += 1; c.clock.step()
      }
      assert(guard1 < 10, "W channel did not become ready")
      c.clock.step() // W fires
      c.io.axi.w.valid.poke(false.B)

      // Observe B and queue data before consuming
      c.clock.step()
      c.io.axi.b.valid.expect(true.B)
      c.io.deq(0).valid.expect(true.B)
      c.io.deq(0).bits.expect("hDEADBEEF".U)

      // Now allow dequeue and consume B
      c.io.deq.foreach(_.ready.poke(true.B))
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
      c.io.axi.b.valid.expect(false.B)
    }
  }

  it should "ignore misaligned writes and leave queues untouched" in {
    simulate(new WriteQueueHarness) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)
      c.io.deq.foreach(_.ready.poke(false.B))
      c.io.axi.b.ready.poke(false.B)

      c.io.axi.aw.bits.addr.poke(1.U) // misaligned
      c.io.axi.aw.bits.len.poke(0.U)
      c.io.axi.aw.bits.size.poke(sizeVal.U)
      c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.aw.bits.prot.poke(0.U)
      c.io.axi.aw.valid.poke(true.B)
      c.io.axi.w.bits.data.poke("h01020304".U)
      c.io.axi.w.bits.strb.poke("b1111".U)
      c.io.axi.w.bits.last.poke(true.B)
      c.io.axi.w.valid.poke(false.B)

      c.clock.step() // AW fires
      c.io.axi.aw.valid.poke(false.B)
      c.io.axi.w.valid.poke(true.B)
      var guard2 = 0
      while (!c.io.axi.w.ready.peek().litToBoolean && guard2 < 10) {
        guard2 += 1; c.clock.step()
      }
      assert(guard2 < 10, "W channel did not become ready (misaligned case)")
      c.clock.step() // W fires
      c.io.axi.w.valid.poke(false.B)

      c.clock.step()
      c.io.axi.b.valid.expect(true.B)
      c.io.deq(0).valid.expect(false.B)
      for (i <- 1 until nQueues) { c.io.deq(i).valid.expect(false.B) }

      // allow dequeue and consume B
      c.io.deq.foreach(_.ready.poke(true.B))
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
      c.io.axi.b.valid.expect(false.B)
    }
  }

  it should "accept burst writes across consecutive queues" in {
    simulate(new WriteQueueHarness) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)
      c.io.deq.foreach(_.ready.poke(false.B))
      c.io.axi.b.ready.poke(false.B)

      // Burst of 3 beats targeting queues 0,1,2
      c.io.axi.aw.bits.addr.poke(0.U)
      c.io.axi.aw.bits.len.poke(2.U) // 3 beats
      c.io.axi.aw.bits.size.poke(sizeVal.U)
      c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.aw.bits.prot.poke(0.U)
      c.io.axi.aw.valid.poke(true.B)
      c.io.axi.w.bits.strb.poke("b1111".U)
      c.io.axi.w.bits.last.poke(false.B)

      c.clock.step() // AW fires
      c.io.axi.aw.valid.poke(false.B)

      val payloads = Seq("hAAAA0000", "hBBBB0000", "hCCCC0000")
      for ((p, idx) <- payloads.zipWithIndex) {
        c.io.axi.w.valid.poke(true.B)
        c.io.axi.w.bits.data.poke(p.U)
        c.io.axi.w.bits.last.poke(idx == payloads.size - 1)
        var guard = 0
        while (!c.io.axi.w.ready.peek().litToBoolean && guard < 10) {
          guard += 1; c.clock.step()
        }
        assert(guard < 10, s"W channel not ready for beat $idx")
        c.clock.step()
        c.io.axi.w.valid.poke(false.B)
        c.clock.step() // allow internal state update between beats
      }

      c.io.axi.b.valid.expect(true.B)
      for (i <- payloads.indices) {
        c.io.deq(i).valid.expect(true.B)
        c.io.deq(i).bits.expect(payloads(i).U)
      }

      // consume
      c.io.deq.foreach(_.ready.poke(true.B))
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
    }
  }

  it should "timeout stalled W channel and return SLVERR" in {
    val timeoutCycles = 3
    simulate(new WriteQueueHarness(timeoutCycles)) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)
      c.io.deq.foreach(_.ready.poke(false.B)) // keep queues from draining
      c.io.axi.b.ready.poke(false.B)

      val payloads = Seq("hAAAA0000", "hBBBB0000", "hCCCC0000")

      def singleBeatWrite(data: String): Unit = {
        c.io.axi.aw.bits.addr.poke(0.U) // queue 0
        c.io.axi.aw.bits.len.poke(0.U)
        c.io.axi.aw.bits.size.poke(sizeVal.U)
        c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
        c.io.axi.aw.bits.prot.poke(0.U)
        c.io.axi.aw.valid.poke(true.B)
        c.clock.step()
        c.io.axi.aw.valid.poke(false.B)

        c.io.axi.w.valid.poke(true.B)
        c.io.axi.w.bits.data.poke(data.U)
        c.io.axi.w.bits.strb.poke("b1111".U)
        c.io.axi.w.bits.last.poke(true.B)
        var guard = 0
        while (!c.io.axi.w.ready.peek().litToBoolean && guard < 10) {
          guard += 1; c.clock.step()
        }
        assert(guard < 10, "W channel did not become ready for single-beat write")
        c.clock.step() // fire W
        c.io.axi.w.valid.poke(false.B)

        // finish the B for this beat so next AW can start
        c.io.axi.b.ready.poke(true.B)
        var bGuard = 0
        while (!c.io.axi.b.valid.peek().litToBoolean && bGuard < 10) {
          bGuard += 1; c.clock.step()
        }
        assert(bGuard < 10, "B channel did not return for single-beat write")
        c.clock.step()
        c.io.axi.b.ready.poke(false.B)
      }

      // Prefill queue 0 to full (depth 2)
      singleBeatWrite(payloads(0))
      singleBeatWrite(payloads(1))
      c.io.deq(0).valid.expect(true.B) // queue holds data but ready is low, so nothing dequeued
      c.io.deq(0).bits.expect(payloads(0).U)

      // Third write stalls immediately because queue 0 is full; expect timeout -> SLVERR
      c.io.axi.aw.bits.addr.poke(0.U)
      c.io.axi.aw.bits.len.poke(0.U)
      c.io.axi.aw.bits.size.poke(sizeVal.U)
      c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.aw.bits.prot.poke(0.U)
      c.io.axi.aw.valid.poke(true.B)
      c.clock.step()
      c.io.axi.aw.valid.poke(false.B)

      c.io.axi.w.valid.poke(true.B)
      c.io.axi.w.bits.data.poke(payloads(2).U)
      c.io.axi.w.bits.strb.poke("b1111".U)
      c.io.axi.w.bits.last.poke(true.B)
      var stallCycles = 0
      var sawReadyRelease = false
      while (!c.io.axi.b.valid.peek().litToBoolean && stallCycles < timeoutCycles + 2) {
        if (stallCycles < timeoutCycles - 1) {
          c.io.axi.w.ready.expect(false.B)
        } else {
          c.io.axi.w.ready.expect(true.B)
          sawReadyRelease = true
        }
        stallCycles += 1
        c.clock.step()
      }
      assert(sawReadyRelease, "WREADY did not rise to consume timed-out beat")
      assert(stallCycles == timeoutCycles, s"Expected timeout after $timeoutCycles stall cycles, stalled $stallCycles")
      c.io.axi.b.bits.resp.expect(CtrlAXI4Consts.RESP_SLVERR)
      c.io.deq(0).valid.expect(true.B)
      c.io.deq(0).bits.expect(payloads(0).U)
      c.io.axi.w.valid.poke(false.B)

      // Consume B then drain the queued beats (only first two should be present)
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
      c.io.axi.b.valid.expect(false.B)

      c.io.deq.foreach(_.ready.poke(true.B))
      c.io.deq(0).valid.expect(true.B)
      c.io.deq(0).bits.expect(payloads(0).U)
      c.clock.step()
      c.io.deq(0).valid.expect(true.B)
      c.io.deq(0).bits.expect(payloads(1).U)
      c.clock.step()
      c.io.deq(0).valid.expect(false.B)
    }
  }
}
