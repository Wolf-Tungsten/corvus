package corvus

import chisel3._
import _root_.circt.stage.ChiselStage

object Elaborate extends App {
  implicit private val p: CorvusConfig = CorvusConfig()

   ChiselStage.emitSystemVerilogFile(
    new Top,
    args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "-default-layer-specialization=enable")
  )
}