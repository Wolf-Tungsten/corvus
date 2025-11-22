package corvus

case class CorvusSyncTreeConfig() {
  val syncTreeFactor: Int = 4
  val stateWidth: Int = 2
}
case class CorvusStateBusConfig() {
  val dstWidth: Int = 4
  val payloadWidth: Int = 32
  val ringNodeQueueDepth: Int = 4
}
case class CorvusConfig() {
  // 在这里定义你的配置参数
  val numSCore: Int = 16
  val syncTreeConfig = CorvusSyncTreeConfig()
  val stateBusConfig = CorvusStateBusConfig()
}
