package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.util._
import CtrlAXI4Consts._
import CtrlAXI4SlaveUtils._

class WriteQueueCtrl(
    val addrBits: Int,
    val dataBits: Int,
    val queueCount: Int
) extends Module {
  require(dataBits == 32 || dataBits == 64, "DBITS must be 32 or 64")
  require(queueCount > 0 && isPow2(queueCount), "N_WQ must be power of 2 and > 0")

  private val wordBytesVal = wordBytes(dataBits)
  private val addrLsbVal = addrLsb(wordBytesVal)

  val io = IO(new Bundle {
    val axi = new CtrlAXI4IO(addrBits, dataBits)
    val queues = Vec(queueCount, Decoupled(UInt(dataBits.W)))
  })

  // Read path (illegal, return zero)
  private val readInFlight = RegInit(false.B)
  private val readLen = RegInit(0.U(8.W))
  private val readBeat = RegInit(0.U(8.W))

  io.axi.ar.ready := !readInFlight
  when(io.axi.ar.fire) {
    readInFlight := true.B
    readLen := io.axi.ar.bits.len
    readBeat := 0.U
  }

  io.axi.r.valid := readInFlight
  io.axi.r.bits.data := 0.U
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

  // Write path
  private val writeInFlight = RegInit(false.B)
  private val writeLen = RegInit(0.U(8.W))
  private val writeAddr = RegInit(0.U(addrBits.W))
  private val legalBurst = RegInit(false.B)
  private val writeBeat = RegInit(0.U(8.W))
  private val bValid = RegInit(false.B)

  io.axi.aw.ready := !writeInFlight && !bValid

  private val beatAddr = writeAddr + (writeBeat << addrLsbVal).asUInt
  private val queueIndex = beatAddr >> addrLsbVal
  private val aligned = isAligned(beatAddr, wordBytesVal)
  private val inRange = queueIndex < queueCount.U
  private val useQueue = writeInFlight && legalBurst && aligned && inRange

  private val queueReady = MuxLookup(queueIndex, false.B)(
    io.queues.zipWithIndex.map { case (q, idx) =>
      idx.U -> q.ready
    }
  )

  io.axi.w.ready := writeInFlight && !bValid && (!useQueue || queueReady)

  for ((q, idx) <- io.queues.zipWithIndex) {
    val targetBeat = useQueue && queueIndex === idx.U
    q.valid := targetBeat && writeInFlight && !bValid && io.axi.w.valid
    q.bits := io.axi.w.bits.data
  }

  io.axi.b.valid := bValid
  io.axi.b.bits.resp := RESP_OKAY

  when(io.axi.aw.fire) {
    writeInFlight := true.B
    writeLen := io.axi.aw.bits.len
    writeAddr := io.axi.aw.bits.addr
    legalBurst := io.axi.aw.bits.size === addrLsbVal.U && io.axi.aw.bits.burst === BURST_INCR
    writeBeat := 0.U
  }

  when(io.axi.w.fire && writeInFlight && !bValid) {
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
