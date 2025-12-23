package corvus.ctrl_axi4_slave

import chisel3._
import chisel3.util._

class CtrlAXI4Address(val addrBits: Int) extends Bundle {
  val addr = UInt(addrBits.W)
  val len = UInt(8.W)
  val size = UInt(3.W)
  val burst = UInt(2.W)
  val prot = UInt(3.W)
}

class CtrlAXI4W(val dataBits: Int) extends Bundle {
  val data = UInt(dataBits.W)
  val strb = UInt((dataBits / 8).W)
  val last = Bool()
}

class CtrlAXI4B extends Bundle {
  val resp = UInt(2.W)
}

class CtrlAXI4R(val dataBits: Int) extends Bundle {
  val data = UInt(dataBits.W)
  val resp = UInt(2.W)
  val last = Bool()
}

class CtrlAXI4IO(val addrBits: Int, val dataBits: Int) extends Bundle {
  val ar = Flipped(Decoupled(new CtrlAXI4Address(addrBits)))
  val r = Decoupled(new CtrlAXI4R(dataBits))
  val aw = Flipped(Decoupled(new CtrlAXI4Address(addrBits)))
  val w = Flipped(Decoupled(new CtrlAXI4W(dataBits)))
  val b = Decoupled(new CtrlAXI4B)
}

object CtrlAXI4Consts {
  val RESP_OKAY = 0.U(2.W)
  val RESP_SLVERR = 2.U(2.W)
  val BURST_INCR = 1.U(2.W)
}

object CtrlAXI4SlaveUtils {
  def wordBytes(dataBits: Int): Int = dataBits / 8

  def addrLsb(wordBytes: Int): Int = log2Ceil(wordBytes)

  def isAligned(addr: UInt, wordBytes: Int): Bool = {
    val mask = (wordBytes - 1).U(addr.getWidth.W)
    (addr & mask) === 0.U
  }

  def assembleReadData(regs: Vec[UInt], baseAddr: UInt, wordBytes: Int): UInt = {
    val addrLsbVal = addrLsb(wordBytes)
    val bytes = Wire(Vec(wordBytes, UInt(8.W)))
    for (i <- 0 until wordBytes) {
      val byteAddr = baseAddr + i.U
      val regIndex = byteAddr >> addrLsbVal
      val byteOffset = byteAddr(addrLsbVal - 1, 0)
      val inRange = regIndex < regs.length.U
      val regByteVecs = regs.map(_.asTypeOf(Vec(wordBytes, UInt(8.W))))
      val selectedByte =
        MuxLookup(regIndex, 0.U(8.W))(regByteVecs.zipWithIndex.map {
          case (vec, idx) => idx.U -> vec(byteOffset)
        })
      bytes(i) := Mux(inRange, selectedByte, 0.U)
    }
    Cat(bytes.reverse)
  }

  def computeNextRegs(
      regs: Vec[UInt],
      baseAddr: UInt,
      data: UInt,
      strb: UInt,
      wordBytes: Int
  ): Vec[UInt] = {
    val addrLsbVal = addrLsb(wordBytes)
    val next = Wire(Vec(regs.length, UInt(regs.head.getWidth.W)))
    for (regIdx <- regs.indices) {
      val byteVec =
        WireInit(regs(regIdx).asTypeOf(Vec(wordBytes, UInt(8.W))))
      for (byte <- 0 until wordBytes) {
        val byteAddr = baseAddr + byte.U
        val targetReg = byteAddr >> addrLsbVal
        val byteOffset = byteAddr(addrLsbVal - 1, 0)
        val inRange = targetReg < regs.length.U
        val incomingByte = data(byte * 8 + 7, byte * 8)
        when(inRange && targetReg === regIdx.U && strb(byte)) {
          byteVec(byteOffset) := incomingByte
        }
      }
      next(regIdx) := byteVec.asUInt
    }
    next
  }
}
