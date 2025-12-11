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

  private val PENDING = 0.U(WIDTH.W)
  private val DANGLING = Fill(WIDTH, 1.U(1.W))

  private val nonDangling = io.in.map(_ =/= DANGLING)
  private val anyNonDangling = nonDangling.reduce(_ || _)
  private val firstState = PriorityMux(
    io.in.zip(nonDangling).map { case (state, valid) => valid -> state } :+ (true.B -> DANGLING)
  )
  private val hasDisagree = io.in
    .zip(nonDangling)
    .map { case (state, valid) => valid && state =/= firstState }
    .reduce(_ || _)

  private val mergedState =
    Mux(hasDisagree, PENDING, Mux(anyNonDangling, firstState, DANGLING))

  val outReg = RegInit(PENDING)
  outReg := mergedState
  io.out := outReg

}
