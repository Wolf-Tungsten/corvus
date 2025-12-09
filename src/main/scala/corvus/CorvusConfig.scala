package corvus

case class CorvusSyncTreeConfig() {
  val syncTreeFactor: Int = 4
  val flagWidth: Int = 2
}
case class CorvusStateBusConfig() {
  val dstWidth: Int = 16
  val payloadWidth: Int = 48
  val ringNodeQueueDepth: Int = 4
}
case class CorvusConfig() {
  // 在这里定义你的配置参数
  val numSCore: Int = 16
  val simCoreDBusAddrWidth: Int = 32
  val simCoreDBusDataWidth: Int = 64
  val nStateBus: Int = 4
  val toCoreStateBusBufferDepth: Int = 4
  val fromCoreStateBusBufferDepth: Int = 4
  val syncTreeConfig = CorvusSyncTreeConfig()
  val stateBusConfig = CorvusStateBusConfig()
}
