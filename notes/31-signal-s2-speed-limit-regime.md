# 31 - 信号 S2：行经段限速接线 + 制式服务（已交付 287/0/2 全绿）

> 前置：docs/01-设计/信号系统-AWS与LZB两制式-设计.md（v3 定案）+ notes/30（S1 占用底座）。
> S2 = 制式判据落地（轨限速 101 阈值）+ auto 行经段限速执行 + 真实存档限速摸底。

## 1. 制式判定（定案，已实现）

- `MmtrRegime`（org.mtr.core.mmtr）：AWS（≤100 km/h 轨）｜LZB（≥101 km/h 轨）；阈值 101 用户口径。
- `Vehicle.getMmtrCurrentSpeedLimitKmh()`：当前轨行进方向限速（方向 = walker 进入端→前方节点，
  rail.getSpeedLimitMetersPerMillisecond(startPosition) 方向版）；`Vehicle.getMmtrRegime()` 由此派生。
- 证据：MTR Rail 自带**双向独立限速**（speedLimit1/2，随轨持久化，游戏编辑轨即改）——代码事实见
  Rail.java L36-37/L292-301 与摸底结果。限速 0 = 该方向不可达，运行中不出现。

## 2. auto 行经段限速执行（motion 规划器）

simulateMmtrMotion drive 链重构（auto 与司机路径分离）：
- **auto（非空气制动车底）走确定性规划器**：巡航目标 = min(当前轨行向限速, 车底上限)；
  加速度/减速度取 ConsistType（traction 0.6 / service 0.9），无类型时回落 VED。
- **前方慢轨接近减速**（peek 预问下一轨限速 < 巡航上限）：以服务制动包络（needA=(v²−t²)/2r 精确律）
  在节点前减速，跨轨时 ≈ 新轨限速——绝不冲过限速轨入口；包络外正常巡航（判据只在包络内触发，
  修正 v1 误把"远处巡航"当残余超速强制减速的 bug）。
- 残余超速（刚跨入慢轨的瞬时越限）以 service 回落至新 cap。
- **司机路径不变**：手动不强制轨限速（AWS 语义，用户定案：警示层 S3 之后做；LZB 强制属 S4 曲线监督）。
- auto 车 ConsistType 惰性初始化（tryInitMmtrController 同司机路径）——v1 bug：auto 未 init type
  导致 accel 落 VED（ACCELERATION_DEFAULT=4e-6）龟速 1km/h。

## 3. 数据面

- mmtr-topology rails 增 `speedLimitKmh1/2`（沿 JSON 端点 x1→x2 方向/反向；换算结论：
  limit(from→to) = rail.getSpeedLimitKilometersPerHour(from.compareTo(to) > 0)，4 种端点序全验证）。
- 运营台限速图层/着色留给 S6（feed 已就绪）。

## 4. 测试证据（MmtrMotionSpeedLimitTests 4 例）

1. regimeThresholdBoundaryIsAwsBelow101AndLzbFrom101 —— 0/40/100 → AWS，101/160/300 → LZB；
2. autoCruiseHoldsTheSlowRailLimitAndStillStopsExactly —— 40 km/h 轨上 auto 全程采样 max ≤ 40+ε，
   两次精确停点（±0.05）+ 开门（回归 slice-4 在新规划器下）；
3. autoBracesBeforeBoardingASlowerRailAndRegimeFlipsAtTheNode —— 200(LZB) 轨巡航 80 → 40(AWS) 轨：
   A 上 max ≥75 km/h、跨轨速度 ∈ [35,41.5] km/h、B 上不越 40 轨限、regime 在节点翻转（A LZB limit200
   / B AWS limit40 逐 tick 断言）、停点精确；
4. manualDriverIsNotForcedToTheRailLimitOnAwsRails —— 手动司机在 40 轨上可跑 >45 km/h 不受引擎强制
   （AWS 不强制语义锁定；regime/limit 照常上报）。

## 5. 真实存档限速摸底（MmtrDevWorldSpeedSurveyTests，Assumptions 门控，保留可复用）

结果（dev 存档 49 根轨）：
- **AWS（双向 ≤100）：21 根**；**LZB（任一方向 ≥101）：28 根**；不对称轨 0。
- 限速档分布：**40 km/h ×15（股道/车场默认）、80 ×6（平台默认）、300 ×28（正线/咽喉默认）**。
- LZB 轨全部 300 km/h 双向，最长 108 m；即实机网络天然 = **车场/站台(AWS) ↔ 正线咽喉(LZB)** 结构。
- 启示：① 演示场景现成——yard(40 AWS) → 出库 300 LZB 加速 → 站台(80 AWS) 减速停靠，制式随轨切换；
  ② 咽喉道岔轨全部 300 与真实铁路"岔区侧向限速"不符——属铺设侧标定事项（游戏内轨编辑/后续运营台
  限速工具），机制不受影响（轨限速即权威数据）。

## 6. 门禁与文档

- cleanTest test = **287 tests / 0 fail / 2 skip**（S1 后 282 → +5 零新增失败；survey 在真档存在时计入）。
- 提交（见 git log）；设计文档 v3 状态行更新为 S1/S2 已交付。

## 7. 下一步（S3：AWS 车载单元）

- 警示状态机（WARN→ACK→超时 SPAD）：接近"限制/占用信号点或限速起点"（=块边界/轨限速变化点）触发；
  确认输入（ControlState 扩展）；TPWS 式超速兜底用现成 MmtrProtection；mirror 同步警示态；
- AWS 区轨限速"超速警示"（司机可超但系统提示+确认）语义落地（S2 只锁了"不强制"）。
