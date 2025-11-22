package corvus.state_bus

import chisel3._
import corvus.CorvusConfig

// State bus entry; widths are driven by CorvusConfig.
class StateBusPacket(implicit p: CorvusConfig) extends Bundle {
  val dst = UInt(p.stateBusConfig.dstWidth.W)
  val payload = UInt(p.stateBusConfig.payloadWidth.W)
}
