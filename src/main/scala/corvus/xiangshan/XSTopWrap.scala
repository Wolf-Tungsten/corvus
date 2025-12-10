package corvus.xiangshan

import chisel3._
import difftest.DifftestModule
import freechips.rocketchip.diplomacy.{DisableMonitors, LazyModule}
import top.{ArgParser, Generator, XSTop}
import utility.{ChiselDB, Constantin, FileRegisters}
import xiangshan.DebugOptionsKey
import chisel3.experimental.noPrefix
import chisel3.experimental.CloneModuleAsRecord

object XSTopWrap {
  val args: Array[String] = Array(
    "--target-dir", "build/rtl", "--config", "DefaultConfig", "--issue", "E.b",
    "--num-cores", "1", "--target", "systemverilog", "--firtool-opt", "-O=release",
    "--firtool-opt", "--disable-annotation-unknown --ignore-read-enable-mem",
    "--firtool-opt", "--lowering-options=explicitBitcast,disallowLocalVariables,disallowPortDeclSharing,locationInfoStyle=none",
    "--split-verilog", "--dump-fir",  "--fpga-platform", "--disable-all", "--remove-assert", "--reset-gen"
  )

  val (config, firrtlOpts, firtoolOpts) = ArgParser.parse(args)

  // tools: init to close dpi-c when in fpga
  val envInFPGA = config(DebugOptionsKey).FPGAPlatform
  val enableDifftest = config(DebugOptionsKey).EnableDifftest || config(DebugOptionsKey).AlwaysBasicDiff
  val enableChiselDB = config(DebugOptionsKey).EnableChiselDB
  val enableConstantin = config(DebugOptionsKey).EnableConstantin
}

class XSTopWrap extends RawModule {
  import chisel3.corvus_utils.ChiselPortUtils._
  import XSTopWrap._

  Constantin.init(enableConstantin && !envInFPGA)
  ChiselDB.init(enableChiselDB && !envInFPGA)

  val soc = DisableMonitors(p => LazyModule(new XSTop()(p)))(config)
  val xstop = Module(soc.module)

  val ioRecord = xstop.getChiselPorts.map { case (name, data) =>
    val io = IO(chiselTypeOf(data)).suggestName(name)
    io <> data
    (name, io)
  }
}

object Elaborate extends App {
  import XSTopWrap._
  Generator.execute(firrtlOpts, new XSTopWrap, firtoolOpts)

  // generate difftest bundles (w/o DifftestTopIO)
  if (enableDifftest) {
    DifftestModule.collect("XiangShan")
  }

  FileRegisters.write(fileDir = "./build", filePrefix = "XSTop.")

}
