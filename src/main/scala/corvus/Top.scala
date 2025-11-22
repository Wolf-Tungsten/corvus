package corvus

import chisel3._
import chisel3.util._


class Top(implicit p:CorvusConfig) extends Module {
  val io = IO(new Bundle {
    val led = Output(UInt(8.W))
  })

  // 直接实例化SCore
  printf("CorvusTop: NUM_S_CORE = %d\n", p.numSCore.U)
  io.led := 0.U
}