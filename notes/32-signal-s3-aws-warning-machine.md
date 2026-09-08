# 32 - 信号 S3：AWS 车载警示状态机（已交付 29x/0/2 全绿）

> 前置：notes/31（S2 限速/制式）+ 设计 v3。S3 = 英国 AWS 制式的点式警示内核
> （磁体触发→WARN→司机确认→ACKED 指示保持 / 未确认超时→SPAD），TPWS 式执行复用现有防护通道。

## 1. 语义（定稿，测试锁定）

- 只作用于**人工驾驶（override）**且**当前轨 AWS 带（≤100 km/h）**的车；auto 自控车永不警示
  （引擎自己按目标/占用/限速走，无司机语义）。
- "受限边界" = ① 前方占用（S1 blockStop）② 下一轨限速更低（S2 将要求减速，手动不强制但警示）。
- 触发：车运行中进入距边界 `MMTR_AWS_TRIGGER_LEAD_M=75 m`（现实 AWS 磁体 ~200 yd≈183 m，
  常量可调，短网测试友好）→ WARN。
- 确认：`ControlState.acknowledge` 一次点按（上升沿，经 MmtrDriveControl 协议字段）→ ACKED；
  黄黑指示保持至限制解除（退出 AWS 带/限制消失 → NONE）。
- 超时：WARN 后 `MMTR_AWS_ACK_WINDOW_MILLIS=3000`（按车辆 tick 时间累计，测试免时钟）未确认
  **且车仍在动** → SPAD：`mmtrProtection=true` + 10 s 锁（现有通道）；已停稳（S1 占用等待）只保
  持警示不惩罚（不再计时）。
- 配套：motion 模式补齐 SPAD 执行（此前只在 legacy 分支评估）——保护期紧急制动覆盖牵引，
  displayPower 显示紧急位（LEGACY_EMERGENCY_POWER_LEVEL=-8）。

## 2. 实现

- ControlState：`acknowledge` 字段（setter/getter/copy）；MmtrDriveControl（网络 op）增
  `acknowledge`（构造拷贝 + updateData/serializeData，旧载荷默认 false 向后兼容）；
  applyMmtrControl：ack 点按入队并清存态（无重复语义）。
- Vehicle：AWS 状态机（NONE/WARN/ACKED + tick 累计窗口 + ack 队列）；engage 重置；
  getter：`isMmtrAwsWarningPending()` / `isMmtrAwsWarningAcknowledged()`（未来 HUD/mirror 消费）；
  日志 [MMTR-AWS]。

## 3. 测试（MmtrAwsWarningTests 3 例，全 AWS 40 km/h 网 + 注入占用 B）

1. unacknowledgedOccupancyWarningTriggersSpadWhileStillMoving —— WARN 于 AWS 带运行中触发
   （限速 40 报告正确）→ ≥3 tick 未确认 → SPAD；railHex 未入被占轨；
2. acknowledgedWarningStopsAtTheBoundaryWithoutSpad —— 运行中 ack → 无 SPAD；S1 占用停在
   A/B 节点；限制持续期间 ACKED 保持、不再计时不再 SPAD；
3. autoRunNeverSeesDriverWarnings —— auto 车全程无警示/无确认/无 SPAD，soft 停边界。

坑记录：① WARN 置位 tick 不得累计窗口（置位后 return，否则窗口 3s 变成 2 tick）；② ack 必须走
MmtrDriveControl 协议字段（此前车端只收到重建的 notch/brake state，ack 意图丢失）；③ SPAD
判定须含"仍在动"（已停的占用等待车不惩罚）。

## 4. 门禁

- cleanTest test：**290 tests / 0 fail / 2 skip**（S2 后 287 → +3 零新增失败）。
- 提交（git log）；设计 v3 状态行更新（S1-S3 已交付）。

## 5. 下一步

- S4（LZB 车载监督）：连续允许速度曲线（手动车超轨限速强制——LZB 区）与 LZB 驾驶室字段
  （目标速度/目标距离）——本轮 S2 已留 auto 版曲线（规划器），S4 = 手动侧强制 + 数据面。
- HUD/驾驶室显示重构（用户提出）：现行 MTR DrivingGuiRenderer（游戏内仪表，静态代码绘制）
  换新框架 + 动画，并把 S3 警示灯/S4 LZB 指示接进驾驶室——需先定视觉方向（另文档）。
- mirror 同步警示态（客户端镜像字段）与 HUD 一起做。
