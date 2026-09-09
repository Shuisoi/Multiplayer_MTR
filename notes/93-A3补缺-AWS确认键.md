# 93 - A3 补缺：AWS 确认键（默认 H）

> 触发：用户问「AWS 按什么键确认」。查代码发现——**之前根本没有这个键**。

## 1. 缺口（实机验收才能暴露的那类）

| 层 | 状态 |
| --- | --- |
| 引擎 | ✅ `ControlState.acknowledge` + `MmtrDriveControl` 协议字段 + 状态机消费（`Vehicle.tickMmtrAwsWarning` 的 `mmtrAwsAckQueued`）+ 用例 |
| HUD | ✅ `MmtrCabHudRenderer` 显示"警示-需确认"/"已确认" |
| **客户端输入** | ❌ **没有任何按键把它送出去**：`KeyBindings` 里没有 AWS 确认绑定，`PacketDriveControl` 也不带该字段 |

后果：实机手动驾驶时 AWS 一响就**无法确认**，2.5 s 后必然 SPAD 紧急制动——A3 的"确认"路径在游戏里根本走不通。

## 2. 修复

| 件 | 改动 |
| --- | --- |
| `KeyBindings` | 新增 `MMTR_AWS_ACK`，默认 **H**（`key.mmtr.aws_ack`，可在 选项→控制 改键） |
| `PacketDriveControl` | 增加 `acknowledge` 字段（读/写/构造 + `ControlState.setAcknowledge`）；保留旧 5 参构造重载 |
| `VehicleRidingMovement` | 对 H 做**上升沿**检测；按下时单独发一条驾驶指令（即使档位没变），并在发送前确保引擎已把本客户端登记为司机 |
| `MmtrCabHudRenderer` | 未确认时灯注改为"警示-按 H 确认" |

## 3. 验证

- `:fabric:compileJava` SUCCESS（引擎侧协议字段与状态机此前已有用例覆盖：`MmtrAwsWarningTests` 5 例，
  其中 `unacknowledgedOccupancyWarningTriggersSpadWhileStillMoving` / `greenSignalClearsTheAcknowledgedWarning`
  正是确认/未确认两条路径）。
- 引擎 Java 未改动 → 全量 **473/0/2** 不变量。
- 实机路径：接近非绿信号 → AWS 灯闪 → 按 H → `[MMTR-AWS] acknowledged` → 灯"已确认"；不按 → 2.5 s 后 SPAD。

## 4. 说明

确认键的**默认值**是我定的（H，取自"确认/喇叭"的常见习惯），键位可改；如果你想要别的默认键（例如 B 或空格），
说一声即可一行改掉。
