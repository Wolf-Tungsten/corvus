package corvus.sync_tree

import chisel3._
import chisel3.util._
import corvus.CorvusConfig

class BroadcastTree(implicit p: CorvusConfig) extends Module {
  val NUM_S_CORE = p.numSCore
  val FACTOR = p.syncTreeConfig.syncTreeFactor
  val WIDTH = p.syncTreeConfig.flagWidth
  val INVALID = 0.U(WIDTH.W)

  val io = IO(new Bundle {
    val out = Output(Vec(NUM_S_CORE, UInt(WIDTH.W)))
    val in = Input(UInt(WIDTH.W))
  })

  // LEAF_NUM 是最接近且不小于 NUM_S_CORE 的 FACTOR 的幂
  val LEAF_NUM = math
    .pow(FACTOR, math.ceil(math.log(NUM_S_CORE) / math.log(FACTOR)).toInt)
    .toInt
  val leafPadding = Wire(Vec(LEAF_NUM, UInt(WIDTH.W)))
  for (i <- 0 until NUM_S_CORE) {
    io.out(i) := leafPadding(i)
  }
  // 节点树
  val NODE_NUM =
    if (FACTOR == 1) LEAF_NUM else (FACTOR * LEAF_NUM - 1) / (FACTOR - 1)
  val nodeTree = Wire(Vec(NODE_NUM, UInt(WIDTH.W)))
  nodeTree(0) := io.in
  for (i <- 0 until NODE_NUM - LEAF_NUM) {
    val broadcastNode = Module(new BroadcastNode())
    broadcastNode.io.in := nodeTree(i)
    for (j <- 0 until FACTOR) {
      nodeTree(FACTOR * i + 1 + j) := broadcastNode.io.out(j)
    }
  }
  for (i <- NODE_NUM - LEAF_NUM until NODE_NUM) {
    leafPadding(i - (NODE_NUM - LEAF_NUM)) := nodeTree(i)
  }
}
