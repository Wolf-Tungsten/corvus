# State Bus 状态总线

状态总线负责在仿真核和主控核之间传递需要同步的状态信息（主要是寄存器状态）。

状态总线采用环形总线结构，由首尾相连的 RingNode 节点组成。每个节点可连接一个仿真核或主控核。

## 数据包结构 StateBusPacket

src/main/scala/corvus/state_bus/StateBusPacket.scala

- dst: 目标节点 ID，宽度由配置参数 stateBusConfig.dstWidth 决定。
- payload: 负载数据，宽度由配置参数 stateBusConfig.payloadWidth 决定。

## 简单环形总线节点 RingNode

src/main/scala/corvus/state_bus/RingNode.scala

包含以下接口：

- fromPrev: 来自前一个节点的数据输入，Decoupled 接口，数据类型为 StateBusPacket。
- toNext: 发送到下一个节点的数据输出，Decoupled 接口，数据类型为 StateBusPacket。
- fromCore: 来自连接的核心的数据输入，Decoupled 接口，数据类型为 StateBusPacket。
- toCore: 发送到连接的核心的数据输出，Decoupled 接口，数据类型为 StateBusPacket。
- nodeId: 当前节点的 ID，UInt 类型，宽度等于 dstWidth。
- hasPayload: Bool 类型输出，由 payloadQueue.io.deq.valid 直接驱动，指示输出端是否有待发送数据包。

内部结构：

- payloadQueue：储存节点待发送数据包的队列，深度由配置参数 stateBusConfig.ringNodeQueueDepth 决定（要求深度 >= 2），输出端直接驱动 toNext；有负载时，hasPayload 信号为高。

处理逻辑，优先级递减：

1. 来自 fromPrev 的数据包，如果目标 ID 匹配当前节点 ID，则消费该包并通过 toCore 输出；若 toCore 无法接收则阻塞 fromPrev，不再向 toNext 转发。
2. 若来自 fromPrev 的目标 ID 不匹配且 payloadQueue 还有至少 1 个空位，则将 fromPrev 的数据包写入 payloadQueue；否则阻塞 fromPrev。
3. 来自 fromCore 的数据包只能在 payloadQueue 的空位数大于 1 且本周期 fromPrev 未占用入队时写入 payloadQueue（fromPrev 优先，留出至少 1 个空位避免死锁）；否则阻塞 fromCore，允许 fromCore 长期等待。
4. payloadQueue 的出队直接驱动 toNext；当 toNext 未就绪时，payloadQueue 出队阻塞，hasPayload 仍为高，并向后端施加背压直至 toNext 就绪。
