# Satellite Station 卫星站

面向仿真核的适配层，通过 `CtrlAXI4Slave` 暴露一段可读写的控制/队列地址空间，使仿真核能够：

- 读取/写入同步树状态（SyncTree）。
- 读取/写入 StateBus 数据包，用于与 corvus 系统交换状态。

遵循 `docs/ctrl_axi4_slave.md` 描述的 AXI4 Slave 约束（ABITS=simCoreDBusAddrWidth，DBITS=simCoreDBusDataWidth，非缓存直映地址空间，INCR 突发，单方向单 outstanding）。

## 配置参数（`CorvusConfig`）
- `nMBus`: MBus 条数，必须 `>0` 且 `2^k`。
- `nSBus`: SBus 条数，必须 `>0`。
- `nStateBus = nMBus + nSBus`: satellite station 可见的 StateBus 总数，必须 `>0` 且 `2^k`。
- `simCoreDBusAddrWidth`: 仿真核 AXI 地址宽度（bits）。
- `simCoreDBusDataWidth`: 仿真核 AXI 数据宽度（bits），`wordBytes = simCoreDBusDataWidth / 8`。
- `fromCoreStateBusBufferDepth`: 写入 corvus 方向队列深度。
- `toCoreStateBusBufferDepth`: 从 corvus 读出方向队列深度。
- `writeQueueWStallTimeoutCycles`（模块内常量，默认 32，`SatelliteStation` 中定义）：流式写队列在 W 通道被背压时的超时门限（周期）；设为 0 可关闭，超时会在超时周期拉高 WREADY 消耗当前 beat，随后在 B 通道返回 SLVERR 结束该笔突发。
- 约束：`stateBusConfig.dstWidth + stateBusConfig.payloadWidth = simCoreDBusDataWidth`，否则配置时报错。

`SatelliteStation` 还带一个构造参数 `localStateBusCount`，表示该站点本地可见的 StateBus 条数：

- master station 实例化为 `SatelliteStation(p.nMBus)`，因此只暴露 MBus 队列。
- satellite station 实例化为 `SatelliteStation(p.nStateBus)`，因此同时暴露 MBus 和 SBus 队列。

master station 和 satellite station 的本地控制地址空间大小不同，这是预期行为。

## 顶层接口
- 仿真核侧
  - `ctrlAXI4Slave`: AXI4 Slave（ABITS/DBITS 如上）。
  - `stateBusBufferFullInterrupt`: Bool，任一读队列满时置高。

- corvus 系统侧
  - `inSyncFlag`: 输入，同步树标志位，宽度 `syncTreeConfig.flagWidth`。
  - `outSyncFlag`: 输出，同步树标志位，宽度 `syncTreeConfig.flagWidth`，复位 0，软件写后保持（由 CtrlAXI4Slave 寄存）。
  - `nodeId`: 输出，StateBus 节点 ID，宽度 `stateBusConfig.dstWidth`，复位 0，软件写后保持（由 CtrlAXI4Slave 寄存）。
  - `fromCoreStateBusPort`: `Vec(localStateBusCount, Decoupled(StateBusPacket))`，来自仿真核写入的包，送往 corvus。
  - `toCoreStateBusPort`: `Vec(localStateBusCount, Flipped(Decoupled(StateBusPacket)))`，来自 corvus 的包，供仿真核读取。
  - `toCoreStateBusBufferNonEmpty`: Bool，任一 `toCoreStateBusBuffer` 非空时置高，提示仿真核有待读数据。

## 本地通道编号
- 对 master station：本地通道 `0 ..< nMBus` 全部对应 `MBus`。
- 对 satellite station：本地通道 `0 ..< nMBus` 对应 `MBus`，本地通道 `nMBus ..< nStateBus` 对应 `SBus`。

## 主要子模块与数据通路
- `CtrlAXI4Slave`: 参照 `src/main/scala/corvus/ctrl_axi4_slave/CtrlAXI4Slave.scala`，实例化 4 个子控制器并通过 Crossbar 拼接地址空间。
- `fromCoreStateBusBuffers`: `localStateBusCount` 个 `Queue`，深度 `fromCoreStateBusBufferDepth`，数据类型 `UInt(DBITS.W)`，AXI 写入；出队经拆字段后喂给 `fromCoreStateBusPort`。
- `toCoreStateBusBuffers`: `localStateBusCount` 个 `Queue`，深度 `toCoreStateBusBufferDepth`，数据类型 `UInt(DBITS.W)`，从 `toCoreStateBusPort` 入队；AXI 读取即出队。
- 字段映射：`UInt(DBITS.W) = Cat(packet.dst, packet.payload)`，高位为 `dst`。两个方向共用同一布局。

## 地址空间布局（低地址到高地址）
记 `N_RS = pow2ceil(1 + 2 + 2*localStateBusCount)`，`N_WS = 2`（已为 2 的幂），`N_RQ = localStateBusCount`，`N_WQ = localStateBusCount`。各段大小均为 `数量 * wordBytes`。

| 段 | 数量 | 作用 | 地址范围（相对 base=0） |
| - | - | - | - |
| 读状态 | `N_RS` | 只读控制寄存器 | `[0, N_RS*wordBytes)` |
| 写状态 | `N_WS` | 读写控制寄存器 | `[N_RS*wordBytes, (N_RS+N_WS)*wordBytes)` |
| 收队列 | `N_RQ` | 出队 1 个 `UInt(DBITS)` | `[(N_RS+N_WS)*wordBytes, (N_RS+N_WS+N_RQ)*wordBytes)` |
| 发队列 | `N_WQ` | 入队 1 个 `UInt(DBITS)` | `[(N_RS+N_WS+N_RQ)*wordBytes, (N_RS+N_WS+N_RQ+N_WQ)*wordBytes)` |

### 只读控制寄存器映射
- 0: `inSyncFlag`,零扩展到 `DBITS`。
- 1: 当前站点可用的`MBus`数量，零扩展。
- 2: 当前站点可用的`SBus`数量，零扩展。
- 3...`3 + localStateBusCount - 1`: `toCoreStateBusBuffer[i].count`，零扩展。
- `3+localStateBusCount`...`3 + 2*localStateBusCount - 1`: `fromCoreStateBusBuffer[i].count`，零扩展。
- ... `N_RS`:  固定0，填充用

### 读写控制寄存器映射
- 0: `outSyncFlag`（复位 全1；读返回当前寄存器值，写遵循 AXI 写掩码，小端拼接，写后保持）。
- 1: `nodeId`（复位 全1；读返回当前寄存器值，写遵循 AXI 写掩码，小端拼接，写后保持）。

### 收/发队列
每个寄存器均对应一条本地可见的 `StateBus`。对 master station 只会出现 `MBus`；对 satellite station 则是先 `MBus`、后 `SBus`。

## 中断
- `stateBusBufferFullInterrupt` 为电平信号，任一 `toCoreStateBusBuffer` 进入满状态置高；全部恢复非满后拉低。无额外屏蔽/粘滞/状态寄存器，保持设计简洁。
- `toCoreStateBusBufferNonEmpty` 为电平指示，任一 `toCoreStateBusBuffer` 非空即置高；全部为空后拉低，便于仿真核轮询/响应有数据可读。

## StateBusPacket 转换与校验
- 写方向（仿真核→corvus）：AXI 写入的 `UInt(DBITS)` 拆为 `{dst, payload}` 后送 `fromCoreStateBusPort`。`dstWidth` 与 `payloadWidth` 来自 `stateBusConfig`。
- 读方向（corvus→仿真核）：`toCoreStateBusPort` 输出的 `StateBusPacket` 组合为 `UInt(DBITS)` 入队。
- 配置校验：`dstWidth + payloadWidth` 必须等于 `simCoreDBusDataWidth`；否则 elaboration 期报错。

## 时钟/复位域假设
- 默认 AXI 侧与 corvus 侧处于同一时钟/复位域，无额外 CDC。
- 如需跨域，在上层模块添加跨域 FIFO/同步逻辑；本模块保持单域实现。
