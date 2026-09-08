# 34 - 信号 S4：LZB 连续监督（手动强制，已交付 292/0/2 全绿）

> 前置：notes/30-33（S1 占用 / S2 限速与制式 / S3 AWS / HUD-1 镜像字段）。
> S4 = 德铁 LZB 语义在**手动驾驶**上的落地：高速带（轨限速 ≥101）连续速度监督——
> 与 AWS（警示不强制）相反，LZB ceiling **强制**；另产出驾驶室数据（ceiling/目标/距离）。

## 1. 语义（定稿，测试锁定）

- 只作用于手动 override、非空气制动车底、当前轨 LZB 带（`MmtrRegime.LZB`，≥101 km/h）；
  air-brake 车底保留 ConsistDynamics controller 路径（注记）。
- **Ceiling 强制**：司机牵引永远不能超过 min(当前轨行向限速, 车底 max)——超速以 service 减速度
  回落（Zwangsbremsung 温和版：先刹回曲线内，非紧急）；司机自己的制动（含 emergency）仍更强
  （braking 分支优先）。
- **前方慢轨包络强制**：下一轨限速更低 → 节点前按 service 包络降到其限速（与 auto 规划器同一
  精确律 + **一 tick 前瞻**判据）。
- AWS 段（进入后）司机恢复自由（S2 语义不变）——测试对照断言同油门在 B 上可再超 40。
- 驾驶室数据（getter，HUD-2 时接入镜像/快照）：
  - `getMmtrLzbCeilingKmh()`：监督 ceiling（0=无监督）；
  - `getMmtrLzbTargetKmh()`：目标速度（前方占用 → 0；否则 min(下一轨限速, ceiling)）；
  - `getMmtrLzbTargetDistanceM()`：到目标距离（占用停点或慢轨节点；无目标 -1）。

## 2. 实现

- Vehicle drive 链新增分支（auto 规划器之后、CD 司机之前）：
  `overridden && !airBrakeConsist && mmtrConsistType != null && regime==LZB`：
  braking（司机）→ 司机减速优先；前方慢轨 needA 包络（前瞻一 tick）→ 刹到 nextLimit；
  speed>ceiling → service 回落；wantPower → min(ceiling, +accel)；否则 coast。
- type 惰性初始化扩到 overridden（auto 已 init；司机进 LZB 分支前需要 ConsistType）。
- 离散度修正：慢轨包络判据分母改为 `toNode − speed·dt`（把本 tick 行程计入剩余）——
  巡航→制动切换的离散一步不再把节点速度抬高 ~1 tick 行程（44.5→42.2 km/h 残余属
  1 tick 服务减速粒度（0.9 m/s²×1 s），测试容差 +3 km/h 并注释）。

## 3. 测试（MmtrLzbSupervisionTests 2 例）

1. manualLzbDriverIsEnforcedToTheRailCeilingAndBracesIntoTheSlowerRail —— 120(LZB) 轨上司机
   全油门：max ≤ 120+ε 且 ≥115（被压在天花板）；ceiling 数据 120、目标变 40 且带距离；跨轨
   ≤43 ≥35；进入 40(AWS) 后同油门可再超 45（对照 AWS 自由）；离开 LZB 带 ceiling=0；
2. manualLzbDriverStopsAtAnOccupiedRailWithZeroCabTarget —— B 被占：目标 0+距离；S1 占用停
   于 A/B 节点（软停，无 SPAD）；blockHeld 镜像 true。

## 4. 门禁与文档

- cleanTest test = **292 / 0 / 2**（S3 后 290 → +2 零新增失败）。
- 提交（git log）；设计 v3 状态行更新（S1-S4 已交付）。

## 5. 剩余与下一步

- S5：联锁 route-lock 收编（道岔 P4：进路原子锁 + 信号联动；mission 双车过咽喉按 route 序）。
- S6/HUD-2：驾驶室渲染框架（用户已选"科技玻璃驾驶台"，HUD-1 镜像数据面就绪，S4 数据 getter
  待接入镜像字段）；AWS ack 键位（VehicleRidingMovement）。
- 注记：air-brake 车底在 LZB 区保持 controller 路径（无线性监督）；legacy 车不受 motion 监督。
