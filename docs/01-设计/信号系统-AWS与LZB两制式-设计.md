# 信号系统 · 设计（v3：两制式分级 AWS/LZB · 定案）

> 状态：**设计定案（2026-09-08 需求确认）**，按 S1→S6 分片实施，每片提交+测试+notes。
> **S1（占用与防追尾底座）已交付**（notes/30，commit 5e5cf40，282/0/2）；**S2（行经段限速接线 + 制式
> 服务 + 实机摸底）已交付**（notes/31，287/0/2；MmtrRegime、auto 限速规划器、mmtr-topology
> speedLimitKmh；实机摸底：49 轨 = AWS 21（40×15+80×6）/ LZB 28（300 全档））。
> 待交付：S3 AWS 车载单元、S4 LZB 车载监督、S5 联锁收编、S6 文档实机。
> v1 统一红黄绿显示 → v2 两制式分级 → **v3 制式判据定案：轨自带限速 ≤100 → AWS / ≥101 → LZB（方向性）**；
> 双层限速模型确认（轨=基础+制式源；动态约束=独立对象）。代码事实核对自 14fef6c。
> 前置：运动系统-脱离MTR-MotionCore-设计.md（M2 行经段限速）、道岔系统…-设计.md（P4 联锁，收编为本 S5）。

## 1. 需求（用户口径，已确认）

按轨限速装两套列车防护/信息制式：
- **低速轨（限速 ≤ 100 km/h）→ 英国 AWS**：点式自动警告 + 司机确认（未确认超时制动）；超轨限速
  **不强制**（英国语义：警示为主）；冒进/接近超速由 TPWS 式防护兜底（引擎已有同机制）；
- **高速轨（限速 ≥ 101 km/h）→ 德铁 LZB**：连续速度监督曲线（目标距离-目标速度，逐 tick），
  驾驶室指示；**超曲线强制制动**（先刹回曲线内，严重才紧急）；LZB 自动驾驶 = 按曲线自动运行（收编 auto step-run）。

### 1.1 制式判据（定案，v3）

- 判据直接读 **MTR Rail 自带限速**（`Rail.speedLimit1/2`，km/h，随轨持久化，轨编辑即改）：
  **行进方向限速 ≥ 101 km/h → LZB 轨；≤ 100 → AWS 轨**（0 = 该方向不可达，不算区段）；
- 方向性：同一条轨两个方向各自判定（下行 160 / 上行 40 的现实不对称区段天然支持）；
- 车载单元按**当前所在轨**切换制式；切换发生在轨边界（= 节点/块边界），无需额外区段对象。

### 1.2 双层限速模型（定案，回应「限速主体不能绑铁轨」）

| 层 | 主体 | 内容 | 来源/生命周期 |
|---|---|---|---|
| 基础限速 | **Rail 自带数据** | 每方向设计限速；制式判据；LZB 曲线/AWS 警示的静态分量 | 随存档持久化，轨编辑即改；引擎只读 |
| 动态约束 | **独立对象（不绑轨）** | 临时限速 TSR（发布/撤销/过期）、信号降级（红灯=目标速度 0）、未来岔区临时约束 | operator 发布；rails 变更按锚点重锚（rails-signature 先例） |

运行时允许速度永远是 `min(轨限速, 动态约束, 目标距离制动曲线)`——信号配合发生在 min 链，不在轨属性。

## 2. 事实基础（代码核对）

- `Rail.java` L36-37 方向性双限速；`getSpeedLimitKilometersPerHour(reversed)` L300；0 方向 = 不可达
  （runway 只认 >0，Data.java L73-86）；`PathData.getRailSpeed` 逐段继承当前方向限速（PathData L191）；
- **legacy 车每 tick `speedTarget = 当前段轨限速`**（Vehicle L1394）——行经段限速旧分支本来就有；
  **motion 分支（simulateMmtrMotion）至今未接**（L3 遗留 M2 项）＝S2 的接线点；
- 占用：motion 车写共享 `vehiclePositions` footprint（Vehicle L1557-1561）但**自己不查前障**；
  legacy 有 `railBlockedDistance` 前视（L1361-1366）＝S1 要补的查询；
- SPAD/TPWS 式执行现成：`MmtrProtection.requiresProtection` + `evaluateMmtrProtection`（L1307）+ 10s 锁
  + 客户端镜像；精确停点 `setMmtrMotionStopTarget`（可停轨中任意里程）；auto step-run（slice-5）。
- 既有红线：道岔 authority「绝不动车」；信号只投影不裁决；全量回归零新增失败（当前 273/0/2）。

## 3. 分片计划（S1→S6，每片：编译+新测试绿+全量零新增+notes）

| 片 | 内容 | 验收 |
|---|---|---|
| **S1** | **占用与防追尾底座**（两制式公共安全网）：MmtrBlockService 静态分块（节点必分+长轨虚拟边界，rails-signature 缓存）+ 精确占用区间（head/tail）；motion 车前方占用 → stoppingPoint min 链；auto 舒适制动停块前；手动 wait-not-terminal；同块胎生占用声明 | 两车同轨跟跑：前停→后精确停块前不侵入；前走→后自动续；对向禁入占用块；胎生同块不重叠 |
| S2 | **行经段限速接线 + 制式服务**：motion 分支接轨限速钳制（legacy L1394 语义重接）；MmtrRegimeService（当前轨限速→AWS/LZB）；mmtr-topology 带 speedLimit 做实机全图摸底 | 合成限速段巡航不越限；制式随轨切换正确；实机摸底清单 |
| S3 | **AWS 车载单元**：警示状态机（接近限制信号/限速起点→WARN；ControlState 确认→ACKED；超时→SPAD）；TPWS 兜底验证；mirror 同步警示态 | 确认前不制动/超时制动/确认后仍超速→TPWS 制动；镜像一致 |
| S4 | **LZB 车载监督**：连续允许速度曲线钳制（auto 按曲线巡航=slice-5 推广；手动超曲线强制制动）；lzb 驾驶室字段入 snapshot/feed；目标=占用块尾/限速/停点 | 高速段跟跑自动减速到占用块尾精确停；手动超曲线制动；驾驶室指示正确 |
| S5 | **联锁收编**（原道岔 P4 route-lock：进路原子锁+信号联动） | 冲突进路互斥排队；mission 双车过咽喉按 route 序 |
| S6 | 文档收口 + 实机验收清单（AWS 确认键位、LZB 驾驶室数据、运营台两级状态、限速图层） | 03 实机文档同步 |

## 4. 接口与数据草案

```
底座   MmtrBlockService（rails-signature 派生分块 + 占用区间；前视查询=下一边界+占用者/方向）
制式   MmtrRegimeService：行进方向轨限速 → AWS/LZB + 切换边界（S2）
限速   （基础）直接读 Rail；（动态 S3+）MmtrSpeedConstraint 独立对象 + servlet
AWS    车载警示状态机（WARN→ACK→超时制动/TPWS）+ aspect 投影 + mmtr-signals feed
LZB    车载监督：每 tick 允许曲线钳制 + 驾驶室字段入 snapshot/feed
servlet：mmtr-signals / mmtr-speed-op(动态约束) / mmtr-topology 增 speedLimit / (S5) mmtr-route-req/-rel
网站：运营台：限速图层、AWS 警示点与 aspect、LZB 目标/限速投影（按片推进）
```

## 5. 风险与开放问题

- R1（低）性能：前视 O(1)（当前块+下一块），无全图扫。
- R2（中）停点与块错位：平台停在轨中段 → 后车停块首距离保守；虚拟边界加密缓解；LZB 区短块自然。
- R3（中）legacy/motion 互操作：共享占用索引+既有回归；混合场景合成测试。
- R4（中）对向死锁：占用块双向禁入互等；v1 人工/任务可解，自动化解锁属调度片。
- R5（低）确定性：tick 序固定（先版占用后读）+ 时钟注入 + 合成世界。
- O1 已关闭（制式=轨限速 101 阈值，数据随轨）。O2 司机终端：**引擎先行，键位后续**（已选）。
- O3 装备等级（可选后续）：全装双制式起步；"未装 LZB 禁入高速轨"留玩法深度。
- O4 实机网规模小且轨限速未知：S2 摸底后定演示场景；并发/高速以合成网验收为主。

## 6. 结论

定案模型下可行性高且实现路径清晰：S1 底座（占用+前视 min 链）→ S2 轨限速接线与制式 → S3 AWS（警示状态机，
制动全复用）→ S4 LZB（曲线钳制，收编 auto）→ S5 联锁收编 → S6 文档实机。每片全量零新增失败门禁不变。
