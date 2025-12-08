package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util.log2Ceil
import org.scalatest.flatspec.AnyFlatSpec

class WriteStatusCtrlSpec extends AnyFlatSpec {
  private val dataBits = 32
  private val addrBits = 8
  private val nRegs = 8
  private val wordBytes = dataBits / 8
  private val sizeVal = log2Ceil(wordBytes)

  behavior of "WriteStatusCtrl"

  it should "write unaligned bytes with WSTRB and read back updated values" in {
    simulate(new WriteStatusCtrl(addrBits, dataBits, nRegs)) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      // Prepare write
      c.io.axi.aw.bits.addr.poke(1.U) // unaligned
      c.io.axi.aw.bits.len.poke(0.U)
      c.io.axi.aw.bits.size.poke(sizeVal.U)
      c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.aw.bits.prot.poke(0.U)
      c.io.axi.aw.valid.poke(true.B)
      c.io.axi.w.bits.data.poke("hAABBCCDD".U)
      c.io.axi.w.bits.strb.poke("b1111".U)
      c.io.axi.w.bits.last.poke(true.B)
      c.io.axi.w.valid.poke(true.B)
      c.io.axi.b.ready.poke(false.B) // hold B until observed

      c.clock.step() // AW fires, W not yet (w.ready low initially)
      c.io.axi.aw.valid.poke(false.B)
      // keep W until it fires
      var guard = 0
      while (!c.io.axi.w.ready.peek().litToBoolean && guard < 10) {
        guard += 1; c.clock.step()
      }
      assert(guard < 10, "W channel did not become ready")
      c.clock.step() // W fires here
      c.io.axi.w.valid.poke(false.B)

      var bGuard = 0
      while (!c.io.axi.b.valid.peek().litToBoolean && bGuard < 10) {
        bGuard += 1; c.clock.step()
      }
      assert(bGuard < 10, "B channel did not become valid")
      c.io.axi.b.valid.expect(true.B)

      // consume B
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()
      c.io.axi.b.valid.expect(false.B)

      // Read back reg0 and reg1
      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)

      c.io.axi.ar.bits.addr.poke(0.U)
      c.io.axi.ar.bits.len.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step()
      c.io.axi.ar.valid.poke(false.B)
      c.io.axi.r.valid.expect(true.B)
      c.io.control(0).expect("hBBCCDD00".U)
      c.io.axi.r.bits.data.expect("hBBCCDD00".U)
      c.io.axi.r.bits.last.expect(true.B)
      c.clock.step()

      c.io.axi.ar.bits.addr.poke(wordBytes.U) // reg1
      c.io.axi.ar.bits.len.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step()
      c.io.axi.ar.valid.poke(false.B)
      c.io.axi.r.valid.expect(true.B)
      c.io.control(1).expect("h000000AA".U)
      c.io.axi.r.bits.data.expect("h000000AA".U)
    }
  }

  it should "handle INCR burst writes across multiple registers" in {
    simulate(new WriteStatusCtrl(addrBits, dataBits, nRegs)) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      c.io.axi.b.ready.poke(false.B)

      // Burst of 3 beats starting at reg2 (addr = 2*wordBytes)
      val baseAddr = (2 * wordBytes).U
      val payloads = Seq("h11110000", "h22220000", "h33330000")

      c.io.axi.aw.bits.addr.poke(baseAddr)
      c.io.axi.aw.bits.len.poke(2.U) // 3 beats
      c.io.axi.aw.bits.size.poke(sizeVal.U)
      c.io.axi.aw.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.aw.bits.prot.poke(0.U)
      c.io.axi.aw.valid.poke(true.B)
      c.clock.step()
      c.io.axi.aw.valid.poke(false.B)

      for ((p, idx) <- payloads.zipWithIndex) {
        c.io.axi.w.bits.data.poke(p.U)
        c.io.axi.w.bits.strb.poke("b1111".U)
        c.io.axi.w.bits.last.poke(idx == payloads.size - 1)
        c.io.axi.w.valid.poke(true.B)
        var guard = 0
        while (!c.io.axi.w.ready.peek().litToBoolean && guard < 10) {
          guard += 1; c.clock.step()
        }
        assert(guard < 10, s"W not ready at beat $idx")
        c.clock.step()
        c.io.axi.w.valid.poke(false.B)
        c.clock.step()
      }

      var bGuard = 0
      while (!c.io.axi.b.valid.peek().litToBoolean && bGuard < 10) {
        bGuard += 1; c.clock.step()
      }
      assert(bGuard < 10, "B channel did not become valid")
      c.io.axi.b.valid.expect(true.B)
      c.io.axi.b.ready.poke(true.B)
      c.clock.step()

      // Read back regs 2,3,4
      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.bits.addr.poke(baseAddr)
      c.io.axi.ar.bits.len.poke(2.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step()
      c.io.axi.ar.valid.poke(false.B)

      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h11110000".U)
      c.io.axi.r.bits.last.expect(false.B)
      c.clock.step()
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h22220000".U)
      c.io.axi.r.bits.last.expect(false.B)
      c.clock.step()
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h33330000".U)
      c.io.axi.r.bits.last.expect(true.B)
    }
  }
}
