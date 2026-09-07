# MMTR 车辆移动与动态交路架构（M2-Core 重构）

> 状态：已确认方向（用户拍板选 B：大改底层）。本文是 M2-Core 的架构基线，
> 与 docs/03（M2 任务引擎+AI+连解挂）对齐；旧行为作为兼容基线回归（引擎 208 tests / 0 fail）。

## 1. 核心判断（用户提出的模型，作为设计约束）

1. **出生点只管出生**：股道/depot 是"刷车点"——提供 编组代码(template) + 时刻 + 地点，
   到点自生该编组即可。出生点不决定车辆一辈子怎么走。
2. **车辆位置独立判断**：车辆是一等公民实体（位置/速度/当前编组），**不伴随出生绑定一条固定路线**。
3. **路线由任务动态规划**：交路(route plan)不是出生时烤进车辆的长常量，而是 作业单/任务 按需生成的一段段
   leg（出段→站台→停站→换场→…→终到某股道），执行中可替换/续接。

这三点同时解决现在的三堵墙：车辆被"出生股道路径"绑死、无法驶入其它股道、无法"到达已有停场车处再连挂"。

## 2. 现状模型（为什么不能小改）

- Vehicle 构造时把完整 path（出段+全线+回段）写进 VehicleExtraData(totalDistance/path)，且
  `Vehicle.siding` 为 final（车辆永久绑定出生股道，靠它自己的 vehicleIdMap tick）。
- 单停场不变量：Siding.simulateVehicles 每 tick 清掉同股道第 2 辆停场车。
- COUPLE 因此退化成"发车前模板合并"（make-up at spawn），UNCOUPLE 只能"车场最终切分"。
- 跨股道移动不存在：每 siding 的 path caches 只含"本站台↔自己"。

## 3. 目标模型（M2-Core）

### 3.1 实体
- **SpawnPoint（刷车点）= 股道配置**：{股道, 编组代码, 时刻, 每日重复} —— 只负责到点把模板车辆自生出来并登记。
- **Consist/Vehicle（运行实体）**：拥有 位置(railProgress, 所在轨段)、速度/加减速、编组内容(cars，可被合并/拆分/换车)、
  当前 **MotionPlan**；不持有"出生 home"。
- **MotionPlan（动态交路）**：由 任务/作业单 生成的有序 legs，例如
  [出股道A→站台P1→停站SERVE→…→终到股道B]。每条 leg = 一段 PathData + 停站/停车意图；可被后续任务替换/续接。
- **ParkingSlot（停车点）**：股道/站台 只是"可停位置"，允许调度系统声明 **arrival window**（到达作业窗口）：
  停稳的车 + 正在入库的作业车在窗口内可同时存在（仅编组合并/拆分用），窗口结束只剩一台。

### 3.2 关键原语（分层）
- **R0 路径解耦**：Vehicle 不再在 spawn 时烘焙整条 path；simulate 按 MotionPlan 当前 leg 前进；
  leg 耗尽 → 请求任务系统给下一 leg（到站/到股道）。
- **R1 动态路线规划器（Router）**：输入 (当前位置轨段, 目标=任意平台/股道) 输出 leg 序列；
  基于 depot 路网可达性 + SidingPathFinder 逐步生成：出段→共享平台→目标股道回段；支持同网任意两股道。
- **R2 停场点 & 到达窗口**：股道 tick 尊重 scheduler 声明的窗口；窗口内允许第二台入库车停稳待合并。
- **R3 合并/拆分原语**：位置同一 + 停稳 → COUPLE = 编组合并（复用 rebuildParkedConsist / engine-spawn 路径，改为到达时执行）；
  UNCOUPLE = 拆分并登记尾车到窗口股道。
- **R4 出生点纯出生化 & 接线**：作业单 = [出生描述(SpawnPoint)] + [步骤序列(leg 意图)]；
  执行器只发"到X/停Y/挂Z/摘T"，Router 负责怎么走。

## 4. 分阶段落地（每阶段带合成+真实存档回归，保持 208 全绿基线）

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| L0 设计+探针 | 本架构落档；在引擎里加"可达性探针"(same-depot/跨 depot A→B 股道是否可达) | 真实存档 1/2 股道探针报告可达；回归绿 |
| L1 Router 骨架 | Depot 路网 reachability + 生成 leg：任意平台↔任意股道（复用 SidingPathFinder/PathData） | 合成双股道世界：A→B leg 可生成且车按 leg 到 B 停稳（暂以 respawn/relocate 承接） |
| L2 停场点/到达窗口 | Siding 支持 scheduler 声明的双停窗口；到达合并原语（复用 engine-spawn make-up 改到达时） | 合成世界：车A 入库到已停 车W 的股道 → 窗口内合并 1 台 → 拉走 |
| L3 Vehicle 路径解耦 | 把 path 从 VehicleExtraData 抽到 MotionPlan（最重、影响面最大；默认保持旧 path 行为以零回归切换） | 全量 208+ 绿；旧时刻表行为不变 |
| L4 全剧本 | 作业单：1刷动力→2刷挂车→动力到2合并→跑线→回库→换场/互换 全 AI | 真实存档双股道 e2e DONE |

## 5. 风险与兼容
- L3 是核心重构，逐项拆：先让 Vehicle 支持"运行时替换 MotionPlan"（不影响默认 path），再做完全解耦。
- 单停场→窗口化默认关闭，仅作业单模式(mmtrJobsMode)开启，老 depot 时刻表零变化。
- 每次合并/换道都先合成世界确定性测试，再真实存档冒烟。

## 6. 与里程碑关系
- 属于 docs/03 的 M2（任务引擎+AI+连解挂/Coupler）的移动内核；
- 货运(接驳/装卸/报酬)M3 依赖此移动内核。
## 7. 车辆位置通信协议（SimRail / Stepford 启发）
- 原则：服务端权威；给客户端的不是"烤好的路线"，而是**逐帧可插值的轨道段位置**。
- 引擎已新增 `mmtr-motion` feed（SystemMapServlet），每辆车输出 `MmtrMotionSnapshot`：
  vehicleId/sidingId/sidingName、formation cars、headX/Z、
  **段表示**：segStartX/Z・segEndX/Z（轨道段两端点）+ segmentReversed + segmentOffsetM + segmentLengthM
  （客户端沿段按 offset 插值），外加 speedKmh/moving/onRoute/doorsOpen/platformId/mission。
- 优点：客户端不需要任何 depot/route 知识即可插值渲染；换段/换场不改变协议；未来车头/尾双点只加字段。
- 服务端现有权威仍来自 MTR rail progress；本协议是"解耦后的表示层"，为 L3（Vehicle 路径解耦、
  MotionPlan 驱动）留好同一接口——L3 完成后同一 snapshot 字段继续使用。

---
## 20xx M2-Core 修正（用户拍板）：解耦运动层 + 道岔权威

> 时间线：在完成 rolling-stock manifest + vehicle-id 操作 + 人工自由驾驶 + AI 作业停用之后，
> 用户指出：**只要车仍走 MTR 的 pathData（发车前烤死整条轨段+到站+信号停点），就永远“被生成路线束缚”，
> 达不到自由开**；并明确道岔是**权威对象**，不是车自带走向的附属。

### 约束 / 判定（设计原则）
1. **道岔 = 轨道交界处（节点）的一个可操作对象**：在某进向轨到达节点时，可选择的两个出向（branch0/branch1）。
   以 (节点, 进向轨) 为键；四向交会等退化为多张（每进向一张）。状态由操作者/任务显式 0/1 设定，**绝不自动**。
2. **运动层与 MTR pathData 解耦**：车的运行状态 = “当前轨道段 + 段内偏移(speed/门/任务)”，
   走完当前段到达节点时，**读该道岔当前状态(或任务目标)决定接下一段**；不是发车时把整条路烤死。
3. **任务驱动（人工与 AI 平级）**：任务判定“该往哪走”→ 直接操作相关道岔（搬向那支）+ 作为执行者对车给牵引/制动；
   车只是被“当前道岔+当前段”引导，不带预设整段路线。人工可随时介入，AI/任务用同一套接口。
4. **MTR 只作为逻辑基础**：用于世界编辑/轨道图/信号/区段预订等参考，不作为“车如何走”的裁决。

### 现状（本修正落地前）
- 生成表 mmtr-rolling-stock.json：重启 reset → 每股道 1 辆(可手动)停场，会话内不自动补。已实现+提交。
- 车辆按唯一 ID 操作(delete / clear-all / manifest-reset)。已实现+提交。
- 道岔自动发现 (节点,进向)->branch0/1 + 0/1 持久化 + PathFinder 认岔(仅人工设过的约束)。已提交(M1/地基)。
- 问题：车仍被 MTR pathData 绑定（出库腿需生成），且人工只能在“已生成路径内”开 → 非自由。

### 下一步实现切片（本文件之后）
A. 段级运动模型（segment+offset 解耦）：车不再持整条 PathData，改持 (当前段, 偏移)。
B. 节点决策：到节点由“道岔状态/任务”选下一段（替换预烘焙续行）。
C. 用 -95 等真实岔口做“搬 A 走 A、搬 B 走 B”的引擎内翻转验证。
D. 自由驾驶 + 信号/限速按行经段判定；任务接口(人工/AI)平行。
