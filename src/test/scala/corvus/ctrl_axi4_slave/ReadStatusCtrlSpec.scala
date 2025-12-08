package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util.log2Ceil
import org.scalatest.flatspec.AnyFlatSpec

class ReadStatusCtrlSpec extends AnyFlatSpec {
  private val dataBits = 32
  private val addrBits = 8
  private val nRegs = 8
  private val wordBytes = dataBits / 8
  private val sizeVal = log2Ceil(wordBytes)

  behavior of "ReadStatusCtrl"

  it should "return aligned register content" in {
    simulate(new ReadStatusCtrl(addrBits, dataBits, nRegs)) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      c.io.status(0).poke("h12345678".U)
      c.io.status(1).poke("hCAFEBABE".U)

      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.addr.poke(0.U)
      c.io.axi.ar.bits.len.poke(0.U)
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)

      c.clock.step() // accept AR
      c.io.axi.ar.valid.poke(false.B)

      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h12345678".U)
      c.io.axi.r.bits.last.expect(true.B)
      c.io.axi.r.bits.resp.expect(CtrlAXI4Consts.RESP_OKAY)
      c.clock.step()
      c.io.axi.r.valid.expect(false.B)
    }
  }

  it should "assemble unaligned bytes across registers" in {
    simulate(new ReadStatusCtrl(addrBits, dataBits, nRegs)) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      c.io.status(0).poke("h44332211".U)
      c.io.status(1).poke("h88776655".U)

      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.addr.poke(1.U) // unaligned
      c.io.axi.ar.bits.len.poke(0.U)
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)

      c.clock.step() // accept AR
      c.io.axi.ar.valid.poke(false.B)

      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h55443322".U) // bytes [22 33 44 55] little-endian
      c.io.axi.r.bits.last.expect(true.B)
      c.clock.step()
      c.io.axi.r.valid.expect(false.B)
    }
  }

  it should "serve INCR burst sequentially" in {
    simulate(new ReadStatusCtrl(addrBits, dataBits, nRegs)) { c =>
      c.reset.poke(true.B); c.clock.step(); c.reset.poke(false.B)

      val regVals = Seq("h11111111", "h22222222", "h33333333", "h44444444")
      for ((v, idx) <- regVals.zipWithIndex) {
        c.io.status(idx).poke(v.U)
      }

      c.io.axi.r.ready.poke(true.B)
      c.io.axi.ar.bits.addr.poke(wordBytes.U) // start at reg1
      c.io.axi.ar.bits.len.poke(2.U) // 3 beats: reg1, reg2, reg3
      c.io.axi.ar.bits.size.poke(sizeVal.U)
      c.io.axi.ar.bits.burst.poke(CtrlAXI4Consts.BURST_INCR)
      c.io.axi.ar.bits.prot.poke(0.U)
      c.io.axi.ar.valid.poke(true.B)
      c.clock.step() // accept AR
      c.io.axi.ar.valid.poke(false.B)

      // beat 0
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h22222222".U)
      c.io.axi.r.bits.last.expect(false.B)
      c.io.axi.r.bits.resp.expect(CtrlAXI4Consts.RESP_OKAY)
      c.clock.step()

      // beat 1
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h33333333".U)
      c.io.axi.r.bits.last.expect(false.B)
      c.clock.step()

      // beat 2
      c.io.axi.r.valid.expect(true.B)
      c.io.axi.r.bits.data.expect("h44444444".U)
      c.io.axi.r.bits.last.expect(true.B)
      c.clock.step()
      c.io.axi.r.valid.expect(false.B)
    }
  }
}
