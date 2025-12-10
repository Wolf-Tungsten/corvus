package corvus.axi4_network

import circt.stage.ChiselStage
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters

import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.diplomacy._
import corvus.{CorvusConfig, Top}

// The address window served by a memory controller.
case class AXI4ControllerRegion(
  name: String,
  address: AddressSet
)

object AXI4ControllerRegion {
  def apply(name: String, base: BigInt, sizeBytes: BigInt): AXI4ControllerRegion = {
    AXI4ControllerRegion(name, AddressSet(base, sizeBytes - 1))
  }
}

case class AXI4NetworkParams(
  numCores: Int,
  controllers: Seq[AXI4ControllerRegion],
  core0Base: BigInt,
  perCoreMemoryBytes: BigInt,
  bundleParams: AXI4BundleParameters,
  coreRequestIdBits: Int,
  maxTransferBytes: Int,
  insertMasterBuffers: Boolean = true,
  insertSlaveBuffers: Boolean = true,
  coreNamePrefix: String = "core",
  controllerNamePrefix: String = "memctrl"
) {
  val numControllers: Int = controllers.length
  val beatBytes: Int = bundleParams.dataBits / 8
  val prefixWidth = bundleParams.idBits - coreRequestIdBits

  require((numCores - 1).U.getWidth <= prefixWidth,
    s"Not enough ID bits (${bundleParams.idBits}) to support $numCores cores with $coreRequestIdBits request ID bits each")

  val coreAddressSets: Seq[AddressSet] =
    (0 until numCores).map { idx =>
      AddressSet(core0Base + perCoreMemoryBytes * idx, perCoreMemoryBytes - 1)
    }
  coreAddressSets.zipWithIndex.foreach { case (range, idx) =>
    require(controllers.exists(_.address.overlaps(range)),
      s"Address window for core $idx (${range.base.toString(16)}-${range.max.toString(16)}) is not covered by any controller")
  }

  val idBasePerCore: Seq[Int] = Seq.tabulate(numCores)(_ << coreRequestIdBits)
  val idsPerCore: Int = 1 << coreRequestIdBits

  val coreBundleParams: AXI4BundleParameters = bundleParams.copy(idBits = coreRequestIdBits)

  def masterIdRange(idx: Int): IdRange = {
    val start = idBasePerCore(idx)
    IdRange(start, start + idsPerCore)
  }
}

class AXI4NetworkIO(params: AXI4NetworkParams) extends Bundle {
  val cores = Vec(params.numCores, Flipped(new AXI4Bundle(params.coreBundleParams)))
  val controllers = Vec(params.numControllers, new AXI4Bundle(params.bundleParams))
}

class AXI4NetworkLazy(params: AXI4NetworkParams)(implicit p: Parameters) extends LazyModule {
  val xbar = AXI4Xbar()

  val masterNodes = (0 until params.numCores).map { idx =>
    val node = AXI4MasterNode(Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = s"${params.coreNamePrefix}_$idx",
        id = params.masterIdRange(idx)
      ))
    )))
    if (params.insertMasterBuffers) {
      xbar := AXI4Buffer() := node
    } else {
      xbar := node
    }
    node
  }

  val slaveNodes = params.controllers.zipWithIndex.map { case (region, idx) =>
    val ctrlName = s"${params.controllerNamePrefix}_${region.name}".replaceAll("[^A-Za-z0-9_]", "_")
    val device = new SimpleDevice(ctrlName, Seq("corvus,axi4-memory"))
    val slave = AXI4SlaveNode(Seq(AXI4SlavePortParameters(
      slaves = Seq(AXI4SlaveParameters(
        address = Seq(region.address),
        resources = device.reg("mem"),
        regionType = RegionType.UNCACHED,
        executable = true,
        device = Some(device),
        supportsRead = TransferSizes(1, params.maxTransferBytes),
        supportsWrite = TransferSizes(1, params.maxTransferBytes)
      )),
      beatBytes = params.beatBytes
    )))
    if (params.insertSlaveBuffers) {
      slave := AXI4Buffer() := xbar
    } else {
      slave := xbar
    }
    slave
  }

  class AXI4NetworkLazyImp extends LazyModuleImp(this) {
    val masterIOs = masterNodes.zipWithIndex.map { case (node, idx) => node.makeIOs()(ValName(s"masterIO_$idx")) }
    val slaveIOs = slaveNodes.zipWithIndex.map { case (node, idx) => node.makeIOs()(ValName(s"slaveIO_$idx")) }
  }

  lazy val module: AXI4NetworkLazyImp = new AXI4NetworkLazyImp
}

class AXI4Network(params: AXI4NetworkParams) extends Module {
  val io = IO(new AXI4NetworkIO(params))

  private val inner = Module {
    val lm = LazyModule(new AXI4NetworkLazy(params)(Parameters.empty))
    lm.module
  }

  private val masterPorts = inner.masterIOs.map(_.head)
  private val slavePorts = inner.slaveIOs.map(_.head)

  (slavePorts zip io.controllers).foreach { case (slavePort, ctrlIO) =>
    slavePort <> ctrlIO
  }

  (masterPorts zip io.cores).zipWithIndex.foreach { case ((masterPort, coreIO), idx) =>
    val idBase = params.idBasePerCore(idx).U(params.bundleParams.idBits.W)
    val coreIdExtAw = Cat(0.U((params.bundleParams.idBits - params.coreRequestIdBits).W), coreIO.aw.bits.id)
    val coreIdExtAr = Cat(0.U((params.bundleParams.idBits - params.coreRequestIdBits).W), coreIO.ar.bits.id)

    masterPort.aw.valid := coreIO.aw.valid
    masterPort.aw.bits := coreIO.aw.bits
    masterPort.aw.bits.id := coreIdExtAw | idBase
    coreIO.aw.ready := masterPort.aw.ready

    masterPort.ar.valid := coreIO.ar.valid
    masterPort.ar.bits := coreIO.ar.bits
    masterPort.ar.bits.id := coreIdExtAr | idBase
    coreIO.ar.ready := masterPort.ar.ready

    masterPort.w <> coreIO.w

    coreIO.r.valid := masterPort.r.valid
    coreIO.r.bits := masterPort.r.bits
    coreIO.r.bits.id := masterPort.r.bits.id(params.coreRequestIdBits-1, 0)
    masterPort.r.ready := coreIO.r.ready

    coreIO.b.valid := masterPort.b.valid
    coreIO.b.bits := masterPort.b.bits
    coreIO.b.bits.id := masterPort.b.bits.id(params.coreRequestIdBits-1, 0)
    masterPort.b.ready := coreIO.b.ready
  }
}

object Elaborate extends App {
  implicit val p: CorvusConfig = CorvusConfig()

  ChiselStage.emitSystemVerilogFile(
    new AXI4Network(
      AXI4NetworkParams(
        numCores = p.numSCore,
        controllers = Seq(
          AXI4ControllerRegion("mem0", 0x80000000L, p.numSCore * 0x10000000L / 2),
          AXI4ControllerRegion("mem1", 0x80000000L + p.numSCore * 0x10000000L / 2, p.numSCore * 0x10000000L / 2)
        ),
        core0Base = 0x80000000L,
        perCoreMemoryBytes = 0x10000000L,
        bundleParams = AXI4BundleParameters(
          addrBits = 48,
          dataBits = 256,
          idBits = 14
        ),
        coreRequestIdBits = 8,
        maxTransferBytes = 64
      )
    ),
    args,
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
}
