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
  private class WriteQueueHarness extends Module {
    val io = IO(new Bundle {
      val axi = new CtrlAXI4IO(addrBits, dataBits)
      val deq = Vec(nQueues, Decoupled(UInt(dataBits.W)))
    })
    val dut = Module(new WriteQueueCtrl(addrBits, dataBits, nQueues))
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
}
