package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.util._
import CtrlAXI4Consts._
import CtrlAXI4SlaveUtils._

class WriteStatusCtrl(
    val addrBits: Int,
    val dataBits: Int,
    val regCount: Int
) extends Module {
  require(dataBits == 32 || dataBits == 64, "DBITS must be 32 or 64")
  require(regCount > 0 && isPow2(regCount), "N_WS must be power of 2 and > 0")

  private val wordBytesVal = wordBytes(dataBits)
  private val addrLsbVal = addrLsb(wordBytesVal)

  val io = IO(new Bundle {
    val axi = new CtrlAXI4IO(addrBits, dataBits)
    val control = Output(Vec(regCount, UInt(dataBits.W)))
  })

  private val controlRegs =
    RegInit(VecInit(Seq.fill(regCount)(0.U(dataBits.W))))
  io.control := controlRegs

  // Read channel
  private val readInFlight = RegInit(false.B)
  private val readLen = RegInit(0.U(8.W))
  private val readAddr = RegInit(0.U(addrBits.W))
  private val readLegal = RegInit(false.B)
  private val readBeat = RegInit(0.U(8.W))

  io.axi.ar.ready := !readInFlight
  when(io.axi.ar.fire) {
    readInFlight := true.B
    readLen := io.axi.ar.bits.len
    readAddr := io.axi.ar.bits.addr
    readLegal := io.axi.ar.bits.size === addrLsbVal.U && io.axi.ar.bits.burst === BURST_INCR
    readBeat := 0.U
  }

  private val readBeatAddr = readAddr + (readBeat << addrLsbVal).asUInt
  private val readData =
    Mux(readLegal, assembleReadData(controlRegs, readBeatAddr, wordBytesVal), 0.U)

  io.axi.r.valid := readInFlight
  io.axi.r.bits.data := readData
  io.axi.r.bits.resp := RESP_OKAY
  io.axi.r.bits.last := readBeat === readLen

  when(io.axi.r.fire) {
    when(io.axi.r.bits.last) {
      readInFlight := false.B
      readBeat := 0.U
    }.otherwise {
      readBeat := readBeat + 1.U
    }
  }

  // Write channel
  private val writeInFlight = RegInit(false.B)
  private val writeLen = RegInit(0.U(8.W))
  private val writeAddr = RegInit(0.U(addrBits.W))
  private val writeLegal = RegInit(false.B)
  private val writeBeat = RegInit(0.U(8.W))
  private val bValid = RegInit(false.B)

  io.axi.aw.ready := !writeInFlight && !bValid
  io.axi.w.ready := writeInFlight && !bValid
  io.axi.b.valid := bValid
  io.axi.b.bits.resp := RESP_OKAY

  when(io.axi.aw.fire) {
    writeInFlight := true.B
    writeLen := io.axi.aw.bits.len
    writeAddr := io.axi.aw.bits.addr
    writeLegal := io.axi.aw.bits.size === addrLsbVal.U && io.axi.aw.bits.burst === BURST_INCR
    writeBeat := 0.U
  }

  private val writeBeatAddr = writeAddr + (writeBeat << addrLsbVal).asUInt

  when(io.axi.w.fire && writeInFlight) {
    when(writeLegal) {
      val nextRegs = computeNextRegs(
        controlRegs,
        writeBeatAddr,
        io.axi.w.bits.data,
        io.axi.w.bits.strb,
        wordBytesVal
      )
      controlRegs := nextRegs
    }

    when(writeBeat === writeLen) {
      writeInFlight := false.B
      bValid := true.B
    }.otherwise {
      writeBeat := writeBeat + 1.U
    }
  }

  when(io.axi.b.fire) {
    bValid := false.B
    writeBeat := 0.U
  }
}
