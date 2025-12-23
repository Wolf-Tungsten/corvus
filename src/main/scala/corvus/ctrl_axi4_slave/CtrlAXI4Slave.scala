package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.util._
import CtrlAXI4Consts._
import CtrlAXI4SlaveUtils._

class CtrlAXI4Slave(
    val addrBits: Int,
    val dataBits: Int,
    val nRS: Int,
    val nWS: Int,
    val nRQ: Int,
    val nWQ: Int,
    val writeQueueWStallTimeoutCycles: Int = 32
) extends Module {
  require(dataBits == 32 || dataBits == 64, "DBITS must be 32 or 64")
  require(nRS > 0 && isPow2(nRS), "N_RS must be power of 2 and > 0")
  require(nWS > 0 && isPow2(nWS), "N_WS must be power of 2 and > 0")
  require(nRQ > 0 && isPow2(nRQ), "N_RQ must be power of 2 and > 0")
  require(nWQ > 0 && isPow2(nWQ), "N_WQ must be power of 2 and > 0")

  private val wordBytesVal = wordBytes(dataBits)
  private val addrLsbVal = addrLsb(wordBytesVal)
  private val targetWidth = 3
  private val targetStatus = 0.U(targetWidth.W)
  private val targetWriteCtrl = 1.U(targetWidth.W)
  private val targetReadQueue = 2.U(targetWidth.W)
  private val targetWriteQueue = 3.U(targetWidth.W)
  private val targetInvalid = 4.U(targetWidth.W)

  private val totalWords = nRS + nWS + nRQ + nWQ
  private val wordIndexWidth = (addrBits - addrLsbVal) max 1
  require(addrBits >= addrLsbVal, "addrBits must cover word alignment bits")
  require(
    (BigInt(1) << wordIndexWidth) >= totalWords,
    "address bits are insufficient for mapped space"
  )

  val io = IO(new Bundle {
    val axi = new CtrlAXI4IO(addrBits, dataBits)
    val status = Input(Vec(nRS, UInt(dataBits.W)))
    val control = Output(Vec(nWS, UInt(dataBits.W)))
    val readQueues = Flipped(Vec(nRQ, Decoupled(UInt(dataBits.W))))
    val writeQueues = Vec(nWQ, Decoupled(UInt(dataBits.W)))
  })

  private def selectTarget(wordIndex: UInt): UInt = {
    val idx = WireDefault(targetInvalid)
    val region1 = nRS.U(wordIndexWidth.W)
    val region2 = (nRS + nWS).U(wordIndexWidth.W)
    val region3 = (nRS + nWS + nRQ).U(wordIndexWidth.W)
    val region4 = totalWords.U(wordIndexWidth.W)
    when(wordIndex < region1) {
      idx := targetStatus
    }.elsewhen(wordIndex < region2) {
      idx := targetWriteCtrl
    }.elsewhen(wordIndex < region3) {
      idx := targetReadQueue
    }.elsewhen(wordIndex < region4) {
      idx := targetWriteQueue
    }
    idx
  }

  private val readStatusCtrl = Module(new ReadStatusCtrl(addrBits, dataBits, nRS))
  private val writeStatusCtrl =
    Module(new WriteStatusCtrl(addrBits, dataBits, nWS))
  private val readQueueCtrl =
    Module(new ReadQueueCtrl(addrBits, dataBits, nRQ))
  private val writeQueueCtrl =
    Module(new WriteQueueCtrl(addrBits, dataBits, nWQ, writeQueueWStallTimeoutCycles))

  readStatusCtrl.io.status := io.status
  io.control := writeStatusCtrl.io.control
  readQueueCtrl.io.queues <> io.readQueues
  writeQueueCtrl.io.queues <> io.writeQueues

  // Default connections
  val subReadIOs = Seq(
    readStatusCtrl.io.axi,
    writeStatusCtrl.io.axi,
    readQueueCtrl.io.axi,
    writeQueueCtrl.io.axi
  )
  private val regionBasesBytes = Seq(
    0,
    nRS * wordBytesVal,
    (nRS + nWS) * wordBytesVal,
    (nRS + nWS + nRQ) * wordBytesVal
  )
  for (sub <- subReadIOs) {
    sub.ar.valid := false.B
    sub.ar.bits := io.axi.ar.bits
    sub.r.ready := false.B
  }
  val subWriteIOs = subReadIOs
  for ((sub, base) <- subWriteIOs.zip(regionBasesBytes)) {
    sub.aw.valid := false.B
    sub.aw.bits := io.axi.aw.bits
    sub.aw.bits.addr := io.axi.aw.bits.addr - base.U(addrBits.W)
    sub.ar.bits.addr := io.axi.ar.bits.addr - base.U(addrBits.W)
    sub.w.valid := false.B
    sub.w.bits := io.axi.w.bits
    sub.b.ready := false.B
  }

  // Read path
  private val readInFlight = RegInit(false.B)
  private val readTarget = RegInit(targetInvalid)
  private val invalidReadLen = RegInit(0.U(8.W))
  private val invalidReadBeat = RegInit(0.U(8.W))

  private val arWordIndex =
    (io.axi.ar.bits.addr >> addrLsbVal)(wordIndexWidth - 1, 0)
  private val arTarget = selectTarget(arWordIndex)
  private val selectedArReady = MuxLookup(arTarget, true.B)(
    Seq(
      targetStatus -> readStatusCtrl.io.axi.ar.ready,
      targetWriteCtrl -> writeStatusCtrl.io.axi.ar.ready,
      targetReadQueue -> readQueueCtrl.io.axi.ar.ready,
      targetWriteQueue -> writeQueueCtrl.io.axi.ar.ready,
      targetInvalid -> true.B
    )
  )

  io.axi.ar.ready := !readInFlight && selectedArReady

  readStatusCtrl.io.axi.ar.valid := io.axi.ar.valid && !readInFlight && arTarget === targetStatus
  writeStatusCtrl.io.axi.ar.valid := io.axi.ar.valid && !readInFlight && arTarget === targetWriteCtrl
  readQueueCtrl.io.axi.ar.valid := io.axi.ar.valid && !readInFlight && arTarget === targetReadQueue
  writeQueueCtrl.io.axi.ar.valid := io.axi.ar.valid && !readInFlight && arTarget === targetWriteQueue

  when(io.axi.ar.fire) {
    readInFlight := true.B
    readTarget := arTarget
    when(arTarget === targetInvalid) {
      invalidReadLen := io.axi.ar.bits.len
      invalidReadBeat := 0.U
    }
  }

  readStatusCtrl.io.axi.r.ready := io.axi.r.ready && readInFlight && readTarget === targetStatus
  writeStatusCtrl.io.axi.r.ready := io.axi.r.ready && readInFlight && readTarget === targetWriteCtrl
  readQueueCtrl.io.axi.r.ready := io.axi.r.ready && readInFlight && readTarget === targetReadQueue
  writeQueueCtrl.io.axi.r.ready := io.axi.r.ready && readInFlight && readTarget === targetWriteQueue

  private val invalidRValid =
    readInFlight && readTarget === targetInvalid
  private val invalidRLast = invalidReadBeat === invalidReadLen

  private val selectedRValid = MuxLookup(readTarget, invalidRValid)(
    Seq(
      targetStatus -> readStatusCtrl.io.axi.r.valid,
      targetWriteCtrl -> writeStatusCtrl.io.axi.r.valid,
      targetReadQueue -> readQueueCtrl.io.axi.r.valid,
      targetWriteQueue -> writeQueueCtrl.io.axi.r.valid
    )
  )

  private val selectedRBits = Wire(io.axi.r.bits.cloneType)
  selectedRBits := 0.U.asTypeOf(io.axi.r.bits.cloneType)
  selectedRBits.data := 0.U
  selectedRBits.resp := RESP_OKAY
  selectedRBits.last := invalidRLast

  when(readTarget === targetStatus) {
    selectedRBits := readStatusCtrl.io.axi.r.bits
  }.elsewhen(readTarget === targetWriteCtrl) {
    selectedRBits := writeStatusCtrl.io.axi.r.bits
  }.elsewhen(readTarget === targetReadQueue) {
    selectedRBits := readQueueCtrl.io.axi.r.bits
  }.elsewhen(readTarget === targetWriteQueue) {
    selectedRBits := writeQueueCtrl.io.axi.r.bits
  }

  io.axi.r.valid := readInFlight && selectedRValid
  io.axi.r.bits := selectedRBits

  when(io.axi.r.fire) {
    when(io.axi.r.bits.last) {
      readInFlight := false.B
      invalidReadBeat := 0.U
    }.elsewhen(readTarget === targetInvalid) {
      invalidReadBeat := invalidReadBeat + 1.U
    }
  }

  // Write path
  private val writeInFlight = RegInit(false.B)
  private val writeTarget = RegInit(targetInvalid)
  private val invalidWriteLen = RegInit(0.U(8.W))
  private val invalidWriteBeat = RegInit(0.U(8.W))
  private val invalidBValid = RegInit(false.B)

  private val awWordIndex =
    (io.axi.aw.bits.addr >> addrLsbVal)(wordIndexWidth - 1, 0)
  private val awTarget = selectTarget(awWordIndex)
  private val selectedAwReady = MuxLookup(awTarget, true.B)(
    Seq(
      targetStatus -> readStatusCtrl.io.axi.aw.ready,
      targetWriteCtrl -> writeStatusCtrl.io.axi.aw.ready,
      targetReadQueue -> readQueueCtrl.io.axi.aw.ready,
      targetWriteQueue -> writeQueueCtrl.io.axi.aw.ready,
      targetInvalid -> true.B
    )
  )

  io.axi.aw.ready := !writeInFlight && selectedAwReady

  readStatusCtrl.io.axi.aw.valid := io.axi.aw.valid && !writeInFlight && awTarget === targetStatus
  writeStatusCtrl.io.axi.aw.valid := io.axi.aw.valid && !writeInFlight && awTarget === targetWriteCtrl
  readQueueCtrl.io.axi.aw.valid := io.axi.aw.valid && !writeInFlight && awTarget === targetReadQueue
  writeQueueCtrl.io.axi.aw.valid := io.axi.aw.valid && !writeInFlight && awTarget === targetWriteQueue

  when(io.axi.aw.fire) {
    writeInFlight := true.B
    writeTarget := awTarget
    when(awTarget === targetInvalid) {
      invalidWriteLen := io.axi.aw.bits.len
      invalidWriteBeat := 0.U
      invalidBValid := false.B
    }
  }

  private val selectedWReady = MuxLookup(writeTarget, false.B)(
    Seq(
      targetStatus -> readStatusCtrl.io.axi.w.ready,
      targetWriteCtrl -> writeStatusCtrl.io.axi.w.ready,
      targetReadQueue -> readQueueCtrl.io.axi.w.ready,
      targetWriteQueue -> writeQueueCtrl.io.axi.w.ready
    )
  )
  private val invalidWReady = writeInFlight && writeTarget === targetInvalid && !invalidBValid
  io.axi.w.ready := writeInFlight && Mux(writeTarget === targetInvalid, invalidWReady, selectedWReady)

  readStatusCtrl.io.axi.w.valid := io.axi.w.valid && writeInFlight && writeTarget === targetStatus
  writeStatusCtrl.io.axi.w.valid := io.axi.w.valid && writeInFlight && writeTarget === targetWriteCtrl
  readQueueCtrl.io.axi.w.valid := io.axi.w.valid && writeInFlight && writeTarget === targetReadQueue
  writeQueueCtrl.io.axi.w.valid := io.axi.w.valid && writeInFlight && writeTarget === targetWriteQueue

  readStatusCtrl.io.axi.w.bits := io.axi.w.bits
  writeStatusCtrl.io.axi.w.bits := io.axi.w.bits
  readQueueCtrl.io.axi.w.bits := io.axi.w.bits
  writeQueueCtrl.io.axi.w.bits := io.axi.w.bits

  readStatusCtrl.io.axi.b.ready := io.axi.b.ready && writeInFlight && writeTarget === targetStatus
  writeStatusCtrl.io.axi.b.ready := io.axi.b.ready && writeInFlight && writeTarget === targetWriteCtrl
  readQueueCtrl.io.axi.b.ready := io.axi.b.ready && writeInFlight && writeTarget === targetReadQueue
  writeQueueCtrl.io.axi.b.ready := io.axi.b.ready && writeInFlight && writeTarget === targetWriteQueue

  private val selectedBValid = MuxLookup(writeTarget, invalidBValid)(
    Seq(
      targetStatus -> readStatusCtrl.io.axi.b.valid,
      targetWriteCtrl -> writeStatusCtrl.io.axi.b.valid,
      targetReadQueue -> readQueueCtrl.io.axi.b.valid,
      targetWriteQueue -> writeQueueCtrl.io.axi.b.valid
    )
  )

  private val selectedBBits = Wire(io.axi.b.bits.cloneType)
  selectedBBits := 0.U.asTypeOf(io.axi.b.bits.cloneType)
  selectedBBits.resp := RESP_OKAY
  when(writeTarget === targetStatus) {
    selectedBBits := readStatusCtrl.io.axi.b.bits
  }.elsewhen(writeTarget === targetWriteCtrl) {
    selectedBBits := writeStatusCtrl.io.axi.b.bits
  }.elsewhen(writeTarget === targetReadQueue) {
    selectedBBits := readQueueCtrl.io.axi.b.bits
  }.elsewhen(writeTarget === targetWriteQueue) {
    selectedBBits := writeQueueCtrl.io.axi.b.bits
  }

  io.axi.b.valid := writeInFlight && selectedBValid
  io.axi.b.bits := selectedBBits

  when(io.axi.w.fire && writeInFlight && writeTarget === targetInvalid && !invalidBValid) {
    when(invalidWriteBeat === invalidWriteLen) {
      invalidBValid := true.B
    }.otherwise {
      invalidWriteBeat := invalidWriteBeat + 1.U
    }
  }

  when(io.axi.b.fire) {
    writeInFlight := false.B
    when(writeTarget === targetInvalid) {
      invalidBValid := false.B
      invalidWriteBeat := 0.U
    }
  }
}
