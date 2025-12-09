package corvus.sync_tree

import chisel3._
import chisel3.util._
import corvus.CorvusConfig

class MergeNode(implicit p: CorvusConfig) extends Module {

  val FACTOR = p.syncTreeConfig.syncTreeFactor
  require((FACTOR & (FACTOR - 1)) == 0, "syncTreeFactor must be a power of 2")
  require(FACTOR >= 2, "syncTreeFactor must be at least 2")
  val WIDTH = p.syncTreeConfig.flagWidth

  val io = IO(new Bundle {
    val in = Input(Vec(FACTOR, UInt(WIDTH.W)))
    val out = Output(UInt(WIDTH.W))
  })

  // 输出全 0 表示无效状态
  val INVALID = 0.U(WIDTH.W)
  val FLOATING = Fill(WIDTH, 1.U(1.W)) //
  // 输入全 1 表示端口悬空

  // 处理悬空状态
  val handleFloating = Wire(Vec(FACTOR, UInt(WIDTH.W)))
  for (i <- 0 until FACTOR) {
    when(io.in(i) === FLOATING) {
      handleFloating(i) := io.in(0) // 第一个输入不可能是悬空的
    }.otherwise {
      handleFloating(i) := io.in(i)
    }
  }

  // 投票汇总，用二叉树结构
  // p.syncTreeConfig.syncTreeFactor 必须为 2 的幂次方
  val treeInternalNode = Wire(Vec(FACTOR * 2 - 1, UInt(WIDTH.W)))
  for (i <- 0 until FACTOR) {
    treeInternalNode(i + FACTOR - 1) := handleFloating(i)
  }
  // 1.全部都相等，输出该状态
  // 2.不一致，输出无效
  for (i <- (0 until FACTOR - 1)) {
    when(treeInternalNode(i * 2 + 1) === treeInternalNode(i * 2 + 2)) {
      treeInternalNode(i) := treeInternalNode(i * 2 + 1)
    }.otherwise {
      treeInternalNode(i) := INVALID
    }
  }
  val outReg = RegInit(INVALID)
  outReg := treeInternalNode(0)
  io.out := outReg

}
