# 95 - 闭塞区间 B1：`MmtrBlockService`（纯数据，未接线）

> 拍板（2026-09-09）：①**按信号灯切，不管轨长**（不做长度虚拟切分）；②几何投影就是"节点绑定"的超集
> （灯贴节点时投影=节点），直接实现投影；③**区间不作为进路锁/调车授权的单位**。
> 设计：`docs/01-设计/闭塞区间细化-灯与虚拟边界-设计.md`。

## 1. 交付

`org.mtr.core.mmtr.signal.MmtrBlockService`（纯数据，**本片不接线**）：

| 件 | 内容 |
| --- | --- |
| 边界来源 | **轨两端节点**（必分）+ **登记表中 target 指向该轨的灯**（灯方块中心投影到轨曲线） |
| 投影 | 沿曲线 0.25 m 采样取最近点；超过 `SIGNAL_BIND_TOLERANCE_M = 8 m` 视为不在该轨；距端点 ≤ `NODE_SNAP_M = 2 m` 视为"贴在节点上"→ 不切分；同一位置多灯去重 |
| 无长度切分 | 300 m 无灯轨仍是 1 个区间（用例钉死） |
| 坐标空间 | **ordered-position-1 弧长**（与共享占用树同空间，投影占用=区间求交，调用点零换算） |
| 缓存 | rails 签名 + signals 签名（键与 target）双门控，`refresh()` 惰性重建 |
| 查询 | `blocksOf` / `blockAt(railHex, arc)` / `boundariesOf` / `blockCount` / `railCount`；未知轨/ null 安全 |
| 端点读取 | `Rail.getPosition1/2` 是 protected（data 包内），改用曲线采样端点 + `Position.compareTo` 判有序端，避免越包访问 |

## 2. 用例（`MmtrBlockServiceTests`，7 例）

无灯轨=1 段（含整段弧长与 `blockAt` 中段命中）；灯在轨中段→2 段且边界落在投影弧（±1.5 m）；**300 m 无灯轨=1 段**；
灯距轨 30 格→忽略；灯贴在端点→不切分；新登记灯后 `refresh()` 拾取（双签名）；两条轨互不影响。

## 3. 验证

- 全量引擎套件 **481/0/2**（上一轮 474/0/2，+7 例，零新增失败；本片**未接线**，既有行为不变）。
- 踩到并规避的坑：测试若用 `mmtrSignalOp` 会把灯持久化到存档目录，下一次跑测试再加载 → "无灯"断言失败；改用 `mmtrSignals.put` 直接登记（用例注释已写明）。

## 4. 下一步（B2）

S1 接线：`Vehicle.computeMmtrBlockStopM` 改读区间——本区间内按尾距跟车、**下一区间**被占则停在区间边界前
（不再等整根轨）。B2 会**改变 S1 的期望值**（后车能更贴近前车），按设计 §7 逐条核对
`MmtrMotionBlockingTests` / `MmtrMultiVehiclePerRailTests` / `MmtrShuntAuthorityVehicleTests` /
连挂 0.3 m 车钩用例 / `MmtrAwsWarningTests` / `MmtrLzbSupervisionTests`，变化原因写进 notes。
