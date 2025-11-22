package corvus.state_bus

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import corvus.CorvusConfig

class RingNodeSpec extends AnyFlatSpec {
  implicit private val p: CorvusConfig = CorvusConfig()

  private val dstWidth = p.stateBusConfig.dstWidth

  private def initInputs(c: RingNode): Unit = {
    c.reset.poke(true.B)
    c.clock.step()
    c.reset.poke(false.B)

    c.io.fromPrev.valid.poke(false.B)
    c.io.fromPrev.bits.dst.poke(0.U)
    c.io.fromPrev.bits.payload.poke(0.U)
    c.io.fromCore.valid.poke(false.B)
    c.io.fromCore.bits.dst.poke(0.U)
    c.io.fromCore.bits.payload.poke(0.U)
    c.io.toCore.ready.poke(true.B)
    c.io.toNext.ready.poke(true.B)
    c.io.nodeId.poke(1.U(dstWidth.W))
  }

  behavior of "RingNode"

  it should "deliver matching fromPrev packet to core and not forward" in {
    simulate(new RingNode) { c =>
      initInputs(c)
      c.io.nodeId.poke(3.U)
      c.io.fromPrev.bits.dst.poke(3.U)
      c.io.fromPrev.bits.payload.poke(0x55.U)
      c.io.fromPrev.valid.poke(true.B)
      c.io.toCore.ready.poke(true.B)

      c.clock.step()

      c.io.toCore.valid.expect(true.B)
      c.io.toCore.bits.dst.expect(3.U)
      c.io.toCore.bits.payload.expect(0x55.U)
      c.io.fromPrev.ready.expect(true.B)
      c.io.hasPayload.expect(false.B) // no enqueue to payloadQueue
      c.io.toNext.valid.expect(false.B)
    }
  }

  it should "enqueue fromPrev when dst mismatches and block fromCore in that cycle" in {
    simulate(new RingNode) { c =>
      initInputs(c)
      c.io.nodeId.poke(1.U)
      c.io.toNext.ready.poke(false.B) // hold queue contents

      c.io.fromPrev.valid.poke(true.B)
      c.io.fromPrev.bits.dst.poke(2.U) // mismatch
      c.io.fromPrev.bits.payload.poke(0xAA.U)
      c.io.fromCore.valid.poke(true.B)
      c.io.fromCore.bits.dst.poke(4.U)
      c.io.fromCore.bits.payload.poke(0xCC.U)

      // fromPrev should have priority; fromCore cannot enqueue this cycle
      c.io.fromCore.ready.expect(false.B)

      c.clock.step() // enqueue fromPrev

      c.io.hasPayload.expect(true.B)
      c.io.toNext.valid.expect(true.B)
      c.io.toNext.bits.payload.expect(0xAA.U)
      c.io.toNext.bits.dst.expect(2.U)

      // allow dequeue and observe that fromCore can use freed space
      c.io.fromPrev.valid.poke(false.B)
      c.io.toNext.ready.poke(true.B)
      // with one item in queue and no fromPrev enqueue, fromCore should be allowed
      c.io.fromCore.ready.expect(true.B)
      c.clock.step() // dequeue prev and enqueue core in the same cycle

      c.io.toNext.valid.expect(true.B)
      // next element observed should be core's payload
      c.clock.step()
      c.io.toNext.bits.payload.expect(0xCC.U)
      c.io.toNext.bits.dst.expect(4.U)
    }
  }

  it should "accept fromCore when queue has more than one free slot and no fromPrev enqueue" in {
    simulate(new RingNode) { c =>
      initInputs(c)
      c.io.fromCore.valid.poke(true.B)
      c.io.fromCore.bits.dst.poke(5.U)
      c.io.fromCore.bits.payload.poke(0x77.U)
      c.io.fromPrev.valid.poke(false.B)
      c.io.toNext.ready.poke(false.B) // keep entry to observe hasPayload

      c.io.fromCore.ready.expect(true.B)
      c.clock.step() // enqueue fromCore

      c.io.hasPayload.expect(true.B)
      c.io.toNext.valid.expect(true.B)
      c.io.toNext.bits.payload.expect(0x77.U)
      c.io.toNext.bits.dst.expect(5.U)
    }
  }

  it should "block fromCore until queue frees at least two slots" in {
    simulate(new RingNode) { c =>
      initInputs(c)
      c.io.toNext.ready.poke(false.B) // keep queue occupied

      // Fill queue to depth-1 with fromPrev mismatches
      val payloads = Seq(0xA1, 0xB2, 0xC3)
      for (pVal <- payloads) {
        c.io.fromPrev.valid.poke(true.B)
        c.io.fromPrev.bits.dst.poke(2.U)
        c.io.fromPrev.bits.payload.poke(pVal.U)
        c.clock.step()
      }
      c.io.fromPrev.valid.poke(false.B)

      // With only one free slot left, fromCore must be blocked
      c.io.fromCore.valid.poke(true.B)
      c.io.fromCore.bits.dst.poke(7.U)
      c.io.fromCore.bits.payload.poke(0xD4.U)
      c.io.fromCore.ready.expect(false.B) // still blocked before draining

      // Start draining; first dequeue frees space so fromCore can proceed next cycle
      c.io.toNext.ready.poke(true.B)
      c.io.toNext.valid.expect(true.B)
      c.io.toNext.bits.payload.expect(payloads.head.U)
      c.clock.step()
      c.io.fromCore.ready.expect(true.B)
      c.io.toNext.bits.payload.expect(payloads(1).U) // head is now payloads(1)

      // Next dequeue allows fromCore to enqueue once while draining
      c.clock.step() // dequeue second payload and enqueue core in the same cycle
      c.io.fromCore.ready.expect(true.B)
      c.io.toNext.bits.payload.expect(payloads(2).U) // head becomes payloads(2)
      c.io.fromCore.valid.poke(false.B) // avoid additional enqueues

      // Drain remaining and observe ordering: remaining payload first, then core
      c.io.toNext.valid.expect(true.B)
      c.io.toNext.bits.payload.expect(payloads(2).U)
      c.clock.step()
      c.io.toNext.bits.payload.expect(0xD4.U)
      c.clock.step()
    }
  }

  it should "apply backpressure to fromPrev when toCore not ready on match" in {
    simulate(new RingNode) { c =>
      initInputs(c)
      c.io.nodeId.poke(9.U)
      c.io.fromPrev.valid.poke(true.B)
      c.io.fromPrev.bits.dst.poke(9.U) // match
      c.io.fromPrev.bits.payload.poke(0x11.U)
      c.io.toCore.ready.poke(false.B)
      c.io.toNext.ready.poke(true.B)

      c.io.fromPrev.ready.expect(false.B)
      c.io.toCore.valid.expect(true.B)
      c.io.hasPayload.expect(false.B)
      c.io.toNext.valid.expect(false.B)
    }
  }
}
