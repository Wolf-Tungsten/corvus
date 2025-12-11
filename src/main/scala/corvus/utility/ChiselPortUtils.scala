package chisel3.corvus_utils

import chisel3.experimental.BaseModule
import chisel3.Data

object ChiselPortUtils {
  implicit class GetChiselPorts(m: BaseModule) {
    def getChiselPorts: Seq[(String, Data)] = m.getChiselPorts
  }
}
