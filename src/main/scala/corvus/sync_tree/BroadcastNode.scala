package corvus.sync_tree

import chisel3._
import corvus.CorvusConfig

class BroadcastNode(implicit p: CorvusConfig) extends Module {

  val FACTOR: Int = p.syncTreeConfig.syncTreeFactor
  require((FACTOR & (FACTOR - 1)) == 0, "syncTreeFactor must be a power of 2")
  require(FACTOR >= 2, "syncTreeFactor must be at least 2")
  val WIDTH: Int = p.syncTreeConfig.stateWidth

  val io = IO(new Bundle {
    val out = Output(Vec(FACTOR, UInt(WIDTH.W)))
    val in = Input(UInt(WIDTH.W))
  })

  val INVALID = 0.U(WIDTH.W)
  val outReg = RegInit(INVALID)
  outReg := io.in
  for (i <- 0 until FACTOR) {
    io.out(i) := outReg
  }

}