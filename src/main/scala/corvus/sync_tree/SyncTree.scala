package corvus.sync_tree

import chisel3._
import chisel3.util._
import corvus.CorvusConfig

class SyncTree(implicit p: CorvusConfig) extends Module {
  val NUM_S_CORE = p.numSCore
  val WIDTH = p.syncTreeConfig.flagWidth

  val io = IO(new Bundle {
    val masterIn = Input(UInt(WIDTH.W))
    val masterOut = Output(UInt(WIDTH.W))
    val slaveIn = Input(Vec(NUM_S_CORE, UInt(WIDTH.W)))
    val slaveOut = Output(Vec(NUM_S_CORE, UInt(WIDTH.W)))
  })

  val broadcastTree = Module(new BroadcastTree())
  val mergesTree = Module(new MergesTree())
  broadcastTree.io.in := io.masterIn
  io.slaveOut := broadcastTree.io.out
  mergesTree.io.in := io.slaveIn
  io.masterOut := mergesTree.io.out
}
