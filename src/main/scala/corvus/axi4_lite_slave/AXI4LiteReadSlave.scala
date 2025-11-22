package corvus.axi4_lite_slave

import chisel3._
import chisel3.util._

class AXI4LiteReadSlave(
    val wordBits: Int = 32,
    val nonBlockWordCount: Int = 2,
    val blockWordCount: Int = 4,
    val blockDefaultValue: Array[UInt] = Array(0.U)
) extends Module {
  require(wordBits % 8 == 0, "wordBits must be a multiple of 8")
  require(nonBlockWordCount > 0, "at least one nonBlockWord is required")
  require(
    blockDefaultValue.length == blockWordCount,
    "blockDefaultValue length must equal to blockWordCount"
  )
  require(
    blockDefaultValue.forall(_.getWidth == wordBits),
    "blockDefaultValue elements must match wordBits"
  )
  private val wordBytes = wordBits / 8
  require(isPow2(wordBytes), "wordBytes must be a power of 2")

  private val totalWords = nonBlockWordCount + blockWordCount
  private val rawIndexBits = if (totalWords <= 1) 0 else log2Ceil(totalWords)
  private val wordIndexWidth = (if (rawIndexBits == 0) 1 else rawIndexBits)
  private val blockIndexWidth =
    if (blockWordCount <= 1) 1 else log2Ceil(blockWordCount)
  private val addrLSB = log2Ceil(wordBytes)
  private val addrBits = (addrLSB + rawIndexBits) max 1

  val io = IO(new Bundle {
    val ar = Flipped(Decoupled(new AXI4LiteAR(addrBits)))
    val r = Decoupled(new AXI4LiteR(wordBits))
    val nonBlockData = Input(Vec(nonBlockWordCount, UInt(wordBits.W)))
    val blockChannel = Flipped(Vec(blockWordCount, Decoupled(UInt(wordBits.W))))
  })

  /** nonBlock 和 block
    *   - nonBlock 直接读取输入数据 nonBlockData，不会阻塞
    *   - block 读取的是放在 FIFO（queue）中的数据，当 FIFO 为空时返回 blockDefaultValue 以示阻塞
    */

  /** 地址空间排布
    *   - 先放置 non-block 区域，再放置 block 区域
    *   - non-block 区域大小为 nonBlockWordCount * (wordBits / 8) 字节
    *   - block 区域大小为 blockWordCount * (wordBits / 8)
    */

  private val blockQueues = Seq.tabulate(blockWordCount) { i =>
    val q =
      Module(new Queue(UInt(wordBits.W), 2, pipe = true, flow = false))
    q.io.enq <> io.blockChannel(i)
    q
  }

  private val wordIndexWire = Wire(UInt(wordIndexWidth.W))
  private val pendingValidReg = RegInit(false.B)
  // private val pendingAddrReg = Reg(UInt(addrBits.W))
  private val pendingWordIndexReg = Reg(UInt(wordIndexWidth.W))
  private val pendingIsBlockReg = Reg(Bool())
  private val pendingBlockIndexReg = Reg(UInt(wordIndexWidth.W))

  io.ar.ready := !pendingValidReg
  wordIndexWire := (io.ar.bits.addr >> addrLSB)(wordIndexWidth - 1, 0)
  when(io.ar.fire) {
    pendingValidReg := true.B
    // pendingAddrReg := io.ar.bits.addr
    pendingWordIndexReg := wordIndexWire
    pendingIsBlockReg := wordIndexWire >= nonBlockWordCount.U && wordIndexWire < totalWords.U
    pendingBlockIndexReg := wordIndexWire - nonBlockWordCount.U
  }
  when(io.r.fire) {
    pendingValidReg := false.B
  }

  private val wordDataWire = Wire(Vec(totalWords, UInt(wordBits.W)))
  for (i <- 0 until totalWords) {
    if (i < nonBlockWordCount) {
      wordDataWire(i) := io.nonBlockData(i)
    } else if (blockWordCount > 0) {
      when(blockQueues(i - nonBlockWordCount).io.deq.valid) {
        wordDataWire(i) := blockQueues(i - nonBlockWordCount).io.deq.bits
      }.otherwise {
        wordDataWire(i) := blockDefaultValue(i - nonBlockWordCount)
      }
    }
  }

  io.r.bits.data := wordDataWire(pendingWordIndexReg)
  io.r.bits.resp := wordIndexWire < totalWords.U
  io.r.valid := pendingValidReg

  for (i <- nonBlockWordCount until totalWords) {
    val q = blockQueues(i - nonBlockWordCount)
    q.io.deq.ready := false.B
    when(io.r.fire) {
      when(pendingIsBlockReg) {
        when(pendingBlockIndexReg === i.U) {
          q.io.deq.ready := true.B
        }
      }
    }
  }
}

class AXI4LiteAR(addrBits: Int) extends Bundle {
  val addr = UInt(addrBits.W)
  val prot = UInt(3.W)
}

class AXI4LiteR(wordBits: Int) extends Bundle {
  val data = UInt(wordBits.W)
  val resp = UInt(2.W)
}
