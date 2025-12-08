package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util._
import org.scalatest.flatspec.AnyFlatSpec

class ReadQueueCtrlSpec extends AnyFlatSpec {
  private val dataBits = 32
  private val addrBits = 8
  private val nQueues = 2
  private val wordBytes = dataBits / 8
  private val sizeVal = log2Ceil(wordBytes)

  /** Harness that exposes enq side of internal queues for testing. */
  private class ReadQueueHarness extends Module {
    val io = IO(new Bundle {
      val axi = new CtrlAXI4IO(addrBits, dataBits)
      val enq = Vec(nQueues, Flipped(Decoupled(UInt(dataBits.W))))
      val qValid = Output(Vec(nQueues, Bool()))
      val qBits = Output(Vec(nQueues, UInt(dataBits.W)))
    })
    val dut = Module(new ReadQueueCtrl(addrBits, dataBits, nQueues))
    val qs = Seq.fill(nQueues)(Module(new Queue(UInt(dataBits.W), 2)))
    for (i <- 0 until nQueues) {
      qs(i).io.enq <> io.enq(i)
      dut.io.queues(i) <> qs(i).io.deq
      io.qValid(i) := qs(i).io.deq.valid
      io.qBits(i) := qs(i).io.deq.bits
    }
    io.axi <> dut.io.axi
  }

  behavior of "ReadQueueCtrl"

  it should "wait for data when queue empty and deliver once available" in {
    simulate(new ReadQueueHarness) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.addr.poke(0.U)
      c.io.axi.ar.bits.len.poke(0.U)
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step() // accept AR
      c.io.axi.ar.valid.poke(false.B)

      c.io.axi.r.valid.expect(false.B) // queue empty, hold until data arrives

      // Enqueue data now
      c.io.enq(0).valid.poke(true.B)
      c.io.enq(0).bits.poke("hA1A2A3A4".U)
      c.clock.step()
      c.io.enq(0).valid.poke(false.B)

      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("hA1A2A3A4".U)
      c.io.axi.r.bits.last.expect(true.B)
      c.clock.step()
      c.io.axi.r.valid.expect(false.B)
    }
  }

  it should "return zero on misaligned access without consuming queue" in {
    simulate(new ReadQueueHarness) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      // Preload queue 0
      c.io.enq(0).valid.poke(true.B)
      c.io.enq(0).bits.poke("hDEADBEEF".U)
      c.clock.step()
      c.io.enq(0).valid.poke(false.B)
      c.io.qValid(0).expect(true.B) // still available

      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.addr.poke(1.U) // misaligned
      c.io.axi.ar.bits.len.poke(0.U)
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step()
      c.io.axi.ar.valid.poke(false.B)

      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect(0.U)
      c.io.qValid(0).expect(true.B) // queue element not consumed
    }
  }

  it should "support INCR burst across queues" in {
    simulate(new ReadQueueHarness) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      // Preload two queues
      c.io.enq(0).valid.poke(true.B)
      c.io.enq(0).bits.poke("h11111111".U)
      c.clock.step()
      c.io.enq(0).valid.poke(false.B)

      c.io.enq(1).valid.poke(true.B)
      c.io.enq(1).bits.poke("h22222222".U)
      c.clock.step()
      c.io.enq(1).valid.poke(false.B)

      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.addr.poke(0.U) // first beat -> queue0, next beat -> queue1
      c.io.axi.ar.bits.len.poke(1.U) // two beats
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step()
      c.io.axi.ar.valid.poke(false.B)

      // beat 0
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h11111111".U)
      c.io.axi.r.bits.last.expect(false.B)
      c.clock.step()

      // beat 1
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h22222222".U)
      c.io.axi.r.bits.last.expect(true.B)
      c.clock.step()
      c.io.axi.r.valid.expect(false.B)
    }
  }
}
