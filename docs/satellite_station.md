# Satellite Station 卫星站

面向仿真核的适配层，通过 `CtrlAXI4Slave` 暴露一段可读写的控制/队列地址空间，使仿真核能够：

- 读取同步树状态（SyncTree）。
- 读取/写入 StateBus 数据包，用于与 corvus 系统交换状态。

遵循 `docs/ctrl_axi4_slave.md` 描述的 AXI4 Slave 约束（ABITS=simCoreDBusAddrWidth，DBITS=simCoreDBusDataWidth，非缓存直映地址空间，INCR 突发，单方向单 outstanding）。

## 配置参数（`CorvusConfig`）
- `simCoreDBusAddrWidth`: 仿真核 AXI 地址宽度（bits）。
- `simCoreDBusDataWidth`: 仿真核 AXI 数据宽度（bits），`wordBytes = simCoreDBusDataWidth / 8`。
- `nStateBus`: 并行 StateBus 数量，`>0` 且 `2^k`。
- `toCoreStateBusBufferDepth`: 写入 corvus 方向队列深度。
- `fromCoreStateBusBufferDepth`: 从 corvus 读出方向队列深度。
- 约束：`stateBusConfig.dstWidth + stateBusConfig.payloadWidth = simCoreDBusDataWidth`，否则配置时报错。

## 顶层接口
- 仿真核侧
  - `ctrlAXI4Slave`: AXI4 Slave（ABITS/DBITS 如上）。
  - `stateBusBufferFullInterrupt`: Bool，任一写队列满时置高。
- corvus 系统侧
  - `inSyncFlag`: 输入，同步树标志位，宽度 `syncTreeConfig.flagWidth`。
  - `outSyncFlag`: 输出，同步树标志位，宽度 `syncTreeConfig.flagWidth`，复位 0，软件写后保持（由 CtrlAXI4Slave 寄存）。
  - `nodeId`: 输出，StateBus 节点 ID，宽度 `stateBusConfig.dstWidth`，复位 0，软件写后保持（由 CtrlAXI4Slave 寄存）。
  - `toCoreStateBusPort`: `Vec(nStateBus, Flipped(Decoupled(StateBusPacket)))`，来自仿真核写入的包，送往 corvus。
  - `fromCoreStateBusPort`: `Vec(nStateBus, Decoupled(StateBusPacket))`，来自 corvus 的包，供仿真核读取。

## 主要子模块与数据通路
- `CtrlAXI4Slave`: 参照 `src/main/scala/corvus/ctrl_axi4_slave/CtrlAXI4Slave.scala`，实例化 4 个子控制器并通过 Crossbar 拼接地址空间。
- `toCoreStateBusBuffers`: `nStateBus` 个 `Queue`，深度 `toCoreStateBusBufferDepth`，数据类型 `UInt(DBITS.W)`，AXI 写入；出队经拆字段后喂给 `toCoreStateBusPort`。
- `fromCoreStateBusBuffers`: `nStateBus` 个 `Queue`，深度 `fromCoreStateBusBufferDepth`，数据类型 `UInt(DBITS.W)`，从 `fromCoreStateBusPort` 入队；AXI 读取即出队。
- 字段映射：`UInt(DBITS.W) = Cat(packet.dst, packet.payload)`，高位为 `dst`。两个方向共用同一布局。

## 地址空间布局（低地址到高地址）
记 `N_RS = pow2ceil(1 + nStateBus)`，`N_WS = 2`（已为 2 的幂），`N_RQ = nStateBus`，`N_WQ = nStateBus`。各段大小均为 `数量 * wordBytes`。

| 段 | 数量 | 作用 | 地址范围（相对 base=0） |
| - | - | - | - |
| 读状态 | `N_RS` | 寄存器只读 | `[0, N_RS*wordBytes)` |
| 写状态 | `N_WS` | 寄存器可读写 | `[N_RS*wordBytes, (N_RS+N_WS)*wordBytes)` |
| 读队列 | `N_RQ` | 出队 1 个 `UInt(DBITS)` | `[(N_RS+N_WS)*wordBytes, (N_RS+N_WS+N_RQ)*wordBytes)` |
| 写队列 | `N_WQ` | 入队 1 个 `UInt(DBITS)` | `[(N_RS+N_WS+N_RQ)*wordBytes, (N_RS+N_WS+N_RQ+N_WQ)*wordBytes)` |

### 读状态寄存器映射（按寄存器索引）
0. `inSyncFlag`，零扩展到 `DBITS`。
1...`nStateBus`: `toCoreStateBusBuffer[i].count`，零扩展。
剩余补 0 直至 `N_RS` 个。

### 写状态寄存器映射
- 0: `outSyncFlag`（复位 0；读返回当前寄存器值，写遵循 AXI 写掩码，小端拼接，写后保持）。
- 1: `nodeId`（复位 0；读返回当前寄存器值，写遵循 AXI 写掩码，小端拼接，写后保持）。

### 队列访问语义
- 语义与 `CtrlAXI4Slave` 流式读/写队列一致（对齐要求、背压/弹队行为、越界/非对齐 OKAY 返回 0）。此处仅复用该子模块接口，不再重复展开。

## 中断
- `stateBusBufferFullInterrupt` 为电平信号，任一 `toCoreStateBusBuffer` 进入满状态置高；全部恢复非满后拉低。无额外屏蔽/粘滞/状态寄存器，保持设计简洁。

## StateBusPacket 转换与校验
- 写方向（仿真核→corvus）：AXI 写入的 `UInt(DBITS)` 拆为 `{dst, payload}` 后送 `toCoreStateBusPort`。`dstWidth` 与 `payloadWidth` 来自 `stateBusConfig`。
- 读方向（corvus→仿真核）：`fromCoreStateBusPort` 输出的 `StateBusPacket` 组合为 `UInt(DBITS)` 入队。
- 配置校验：`dstWidth + payloadWidth` 必须等于 `simCoreDBusDataWidth`；否则 elaboration 期报错。

## 时钟/复位域假设
- 默认 AXI 侧与 corvus 侧处于同一时钟/复位域，无额外 CDC。
- 如需跨域，在上层模块添加跨域 FIFO/同步逻辑；本模块保持单域实现。
