package corvus.sync_tree

import chisel3._
import chisel3.util._
import corvus.CorvusConfig

class MergesTree(implicit p: CorvusConfig) extends Module {
  val NUM_S_CORE = p.numSCore
  val FACTOR = p.syncTreeConfig.syncTreeFactor
  val WIDTH = p.syncTreeConfig.stateWidth
  val INVALID = 0.U(WIDTH.W)

  val io = IO(new Bundle {
    val in = Input(Vec(NUM_S_CORE, UInt(WIDTH.W)))
    val out = Output(UInt(WIDTH.W))
  })

  // LEAF_NUM 是最接近且不小于 NUM_S_CORE 的 FACTOR 的幂
  val LEAF_NUM = math
    .pow(FACTOR, math.ceil(math.log(NUM_S_CORE) / math.log(FACTOR)).toInt)
    .toInt
  val leafPadding = Wire(Vec(LEAF_NUM, UInt(WIDTH.W)))
  for (i <- 0 until LEAF_NUM) {
    if (i < NUM_S_CORE) {
      leafPadding(i) := io.in(i)
    } else {
      leafPadding(i) := INVALID
    }
  }
  // 节点树
  val NODE_NUM =
    if (FACTOR == 1) LEAF_NUM else (FACTOR * LEAF_NUM - 1) / (FACTOR - 1)
  val nodeTree = Wire(Vec(NODE_NUM, UInt(WIDTH.W)))
  for (i <- NODE_NUM - LEAF_NUM until NODE_NUM) {
    nodeTree(i) := leafPadding(i - (NODE_NUM - LEAF_NUM))
  }
  for (i <- 0 until NODE_NUM - LEAF_NUM) {
    val mergeNode = Module(new MergeNode())
    for (j <- 0 until FACTOR) {
      mergeNode.io.in(j) := nodeTree(FACTOR * i + 1 + j)
    }
    nodeTree(i) := mergeNode.io.out
  }
  io.out := nodeTree(0)
}
