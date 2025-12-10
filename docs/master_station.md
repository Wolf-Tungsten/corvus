# Master Station 主控站

## 现状
- master station 的全部功能已由 `SatelliteStation` 覆盖（控制寄存器、StateBus 队列、同步树标志等）。
- 不再单独实现 `MasterStation` 模块；master 核直接实例化/复用 `SatelliteStation`。
- 唯一区别是 master 核不需要 `stateBusBufferFullInterrupt`，通过轮询队列计数即可获知写队列是否满。
