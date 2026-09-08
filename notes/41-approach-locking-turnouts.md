# 41 - 低速区行车控制 A1：接近式岔权（approach locking）

> 集成设计：docs/01-设计/行车控制-低速区信号道岔任务集成-设计.md（A1/A2/A3/A4 划分）。
> 现实基准：英铁 approach/route locking（NR/L2/SIG/30009 等，见参考-英铁AWS与TPWS机制.md）。

## 问题（现场）

任务出发时整条进路所有岔口一次性预占：后车提前占住前车将用的岔，前车到岔口反而
排队（(-155,-189) 折返岔"该扳没扳"观感的机制根因）；删车残留 holder 亦由此放大。

## 改动（engine）

- `MmtrRunPlanner.Plan.forkMeters`：每个岔口 op 的绝对 walker 距离（与 forkOps 平行同序，
  与 stopCumulativeM 同坐标系）；
- `Vehicle.armMmtrPointRun`：只把"尚未到达且距头 ≤120 m"的岔加入 pending 并请求；
  远处岔口**不发请求** → 后车不再预占前车岔口；
- 运行期 `replenishForkRequests`（每 mission tick）：车进入窗口 (0,120m] 的岔补入 pending
  并请求（幂等去重；已越过=drained 的岔因剩余距离 ≤0 不再补回 → 不会复活 holder）；
- 既有语义不变：请求刷新窗口、passed 过岔释放、FIFO 排队、operator>授权优先级、
  mission 终了 releaseAll。
- 常量 `MMTR_APPROACH_LOCK_METERS = 120.0`（覆盖低速带制动接近）。

## 效果（英铁式）

最接近的列车到信号/岔前才锁岔：前车先接近→先持有→过岔释放；后车在信号外排队等
grant，互不预占；配合占用闭塞不再出现"前等后/后等前"。

## 状态

- engine 全量 310/0/2 绿（既有 E2E 语义兼容：小网络全部岔口在窗口内=旧行为）。
- 待实机验证：dev server 重启后观察多车咽喉/折返岔 holder 序列与列车流动。
