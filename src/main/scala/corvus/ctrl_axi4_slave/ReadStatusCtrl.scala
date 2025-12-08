package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.util._
import CtrlAXI4Consts._
import CtrlAXI4SlaveUtils._

class ReadStatusCtrl(
    val addrBits: Int,
    val dataBits: Int,
    val regCount: Int
) extends Module {
  require(dataBits == 32 || dataBits == 64, "DBITS must be 32 or 64")
  require(regCount > 0 && isPow2(regCount), "N_RS must be power of 2 and > 0")

  private val wordBytesVal = wordBytes(dataBits)
  private val addrLsbVal = addrLsb(wordBytesVal)

  val io = IO(new Bundle {
    val axi = new CtrlAXI4IO(addrBits, dataBits)
    val status = Input(Vec(regCount, UInt(dataBits.W)))
  })

  // Read channel
  private val readInFlight = RegInit(false.B)
  private val pendingLen = RegInit(0.U(8.W))
  private val pendingAddr = RegInit(0.U(addrBits.W))
  private val pendingLegal = RegInit(false.B)
  private val readBeat = RegInit(0.U(8.W))

  io.axi.ar.ready := !readInFlight
  when(io.axi.ar.fire) {
    readInFlight := true.B
    pendingLen := io.axi.ar.bits.len
    pendingAddr := io.axi.ar.bits.addr
    pendingLegal := io.axi.ar.bits.size === addrLsbVal.U && io.axi.ar.bits.burst === BURST_INCR
    readBeat := 0.U
  }

  private val beatAddr =
    pendingAddr + (readBeat << addrLsbVal).asUInt
  private val readData =
    Mux(pendingLegal, assembleReadData(io.status, beatAddr, wordBytesVal), 0.U)

  io.axi.r.valid := readInFlight
  io.axi.r.bits.data := readData
  io.axi.r.bits.resp := RESP_OKAY
  io.axi.r.bits.last := readBeat === pendingLen

  when(io.axi.r.fire) {
    when(io.axi.r.bits.last) {
      readInFlight := false.B
      readBeat := 0.U
    }.otherwise {
      readBeat := readBeat + 1.U
    }
  }

  // Write channel (writes are ignored but handshake is honored)
  private val writeInFlight = RegInit(false.B)
  private val writeLen = RegInit(0.U(8.W))
  private val writeBeat = RegInit(0.U(8.W))
  private val bValid = RegInit(false.B)

  io.axi.aw.ready := !writeInFlight && !bValid
  io.axi.w.ready := writeInFlight && !bValid
  io.axi.b.valid := bValid
  io.axi.b.bits.resp := RESP_OKAY

  when(io.axi.aw.fire) {
    writeInFlight := true.B
    writeLen := io.axi.aw.bits.len
    writeBeat := 0.U
  }

  when(io.axi.w.fire && writeInFlight) {
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
