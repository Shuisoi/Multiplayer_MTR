# Motion Core ↔ Vehicle 集成 · 交接文档（给新会话）

> 用途：开新会话做「让引擎 Vehicle 本体跑 Motion Core」这项核心改造时，先读本文档即可接手，无需回顾冗长历史。
> 仓库：本机 mmtr/ 是独立 git 仓库（引擎在 mmtr/engine，Minecraft 模组在 mmtr/game）。
> ⚠ 状态标注（重要）：本文正文写于 78e6610（16:30，T3/T3b 时刻）；其后进展远超前文，**正文仅作历史/锚点参考**，当前状态一律以本文下方「L3 主线进展（新会话先读）」+ notes/14–21 + git log 为准。
>
> ### L3 主线进展（新会话先读，2026-09 会话收口）
> 1. 09-07 17:07–18:16：删除 MTR depot 自动路径烘焙/时刻表自动发车（§3 的 T4 前置删除链，全量转绿）→ 1222962。
> 2. 本会话 L3 slice-1..7（每片提交+测试+notes，全量从 227/0/2 推进到 **243/0/2 全绿，零新增失败**）：
>    - slice-1（b6cbf6e，notes/14）：**Vehicle live motion mode**——Vehicle 内嵌 MmtrMotionWalker，(segment+offset) 逐 tick、岔口按当前道岔态实时裁决、未设岔停车等待、搬岔即换向；增长影子 legs（渲染/占用沿用）、mmtr-motion 快照由 walker 直出；含真实 -96 岔口活搬岔用例（MmtrVehicleMotionRunTests 5 例）。
>    - slice-2（cdbf6a9，notes/15）：yard 停场起步（startAtOffset）+ **Siding.spawnMmtrMotionVehicle** 发车接缝（MmtrYardMotionDepartureTests 2 例）。
>    - slice-3（3d6a19c，notes/16）：**真实 dev 存档端到端**——seam 停场车出库→真实咽喉到 -96 未设停车→活搬岔跨被选真实轨（DevYardMotionE2ETests，0 skip）。
>    - slice-4（6ac7a06，notes/17）：**精确停点**——制动包络恒减速精确落点（无过冲）、按请求开门、新令续行、运行中再武装；含 slice-1 无政策回落单位修正（MmtrMotionStopTargetTests 2 例）。
>    - slice-5（462c664，notes/18）：**无人自动运行 auto step-run**——武装停点即自动跑/停/开门，任务再武装自动续行；未设岔自动等、搬岔自动续（MmtrMotionAutoRunTests 2 例）。
>    - slice-6（a937365，notes/19）：**MmtrRunPlanner 进路规划服务**——BFS 进路+沿途岔口预置（与 walker 同 cos 规则）+ walker 空间停点换算，MOVE_TO 底座（MmtrRunPlannerTests 3 例）。
>    - slice-7（1956115，notes/20）：**任务驱动 motion 车闭环**——MmtrMissionControl AUTOPILOT 派发→planner+岔口预置进权威 store+auto 武装→平台轨精确停稳 AT_TARGET 门开→终态交回 idle（MmtrMotionMissionTests 2 例）。
>    - slice-8（444d728，notes/22）：**运行中任务目标重定向**——walker live retarget，任务目标覆盖陈旧 operator / 未设岔上任务即权威；登轨停车、清除续行（MmtrMotionTaskTargetTests 2 例）。
>    - slice-9（4f6ce8a，notes/23）：**任务自武装**——任何来源给 motion 车挂 AUTOPILOT 任务即自行 planner+auto 执行，不可行目标任务 FAILED+原因（MmtrMotionMissionTests 4 例）。
> 3. **T4 收尾评估**：notes/21——旧烘焙自动生成面已全部删除（legacy create() 全仓 0 调用）；残余=存档兼容(writePathCache)/停场模板(defaultPathData)/归档接缝(spawnMmtrManualWithLegs，仅测试)，均有主、无需再删。
> 4. **验收证据映射**：notes/24——目标条款→提交/测试/文档逐条对应，全量 227→**247/0/2** 零新增失败。
> 5. **剩余（另立项，不阻塞 L3/T4 验收）**：作业宏（consist-jobs 多步+COUPLE/UNCOUPLE）的 motion 版编排与作业车 motion 出生；行经段信号/限速（M2）；平台停点对齐；客户端渲染/镜像；实机人工确认清单（03 实机测试文档 §7）。验证纪律：每片全量零新增失败（现 247/0/2）。
> 6. **信号 × 道岔 × 任务集成（2026-09-09，notes/78–82）**：进路对象 `MmtrRoute`/`MmtrRouteRegistry`（SET/PENDING 由道岔权威每 tick 派生、分段释放、feed 每列车 `route` + 顶层 `routes[]`）；A2 信号=进路×闭塞（`MmtrSignalAspect` 单一真源，引擎与游戏内同规则，客户端镜像 `PacketMmtrRoutes`）；A3 AWS 绑定信号显示（黄灯也响、绿灯复位、确认窗口 2.5 s）；S5 冲突进路互斥排队 + 咽喉双车用例。全量 **467/0/2**。**剩余：实机验收（03 文档 §9 清单）+ S6/P5 文档收口。**
> 7. **闭塞区间 × 道岔 ①②③④ 收口（2026-09-10，notes/95–102）**：区间按灯切分（几何投影、无长度切分）+ 每区间一个信号色 + 区间入口停车 + 岔区清限（10 m 侧面防护）+ 占用锁闭收尾（车尾清岔）+ 显示层（道岔/岔区未清 → 灯显危险，引擎与客户端同规则）。全量 **495/0/2**。
> 8. **实机验收首次跑通（2026-09-10，notes/103）**：真实 dev 服务端手动驾驶，§9 第 5、6 条**通过**——车头距红灯 26.9 m 触发 AWS、1 s 内按 H 确认、3 s 后复位；故意不确认时 2.5 s 到点 `SPAD emergency engaged`（14.66 → 0.49 km/h，只多走 3.7 m，`protections=1` 后自动释放）。**剩余目视项**：§9 第 1–4、7、8 条（进路 SET/PENDING 压红、分岔按进路放行、双车咽喉/跟随）+ 人工目视"岔区被占/道岔被清空 → 灯变红"（notes/102 §5）+ 两条**布置侧**待处理（`-163,-60,-189` 哑绑定灯；x=−170 走廊 6 盏灯全朝南、向南无信号，双向跑车需补灯）。~~"同一根轨反向无 AWS"~~ 已定性为**正确**（notes/103 §4）。**引擎侧对照一律走 8888 指令栏**（`interlock <id>` / `blocks all`），RCON 没有这些指令。
> 9. **闭塞区间 v2：灯到灯的跨轨有向区间（2026-09-10，notes/104–111）**：v1 的模型（"一根轨内的弧长区间 + 节点必分"）在真实世界**一盏灯都没参与划分**（`blocks all` 曾报 `134 轨 = 134 区间 / 被灯切分的轨 0 根`），因为真灯贴在节点上、而 v1 把"落在轨端的灯"跳过了。v2 改成：**区间 = 一盏灯保护的那一段（沿灯朝向走到下一盏灯，跨轨、带方向），只有灯产生边界**。S1 数据、S2 占用投影+`blocks-v2`、S3 S1 停车、S4 引擎显示（含**岔口多腿 + 有进路收窄**，用户拍板）+ 客户端镜像（引擎按灯推结论，渲染器用方块坐标查表）**全部交付并实测**；全量 **513/0/2** + fabric 22/0。实测：`-170` 走廊被 30 段 / 601 m 的区间覆盖、8 盏灯各有自己的区间。
>     **剩余（S5，需用户在游戏内）**：① `z=-122` 那 4 盏重复灯改站咽喉朝南（现在 4 盏保护同一根 43 m 存车线，且其远端节点是尽头）；② `-163,-60,-189` 哑绑定**重新绑定**（绑定工具已与区间模型同源 notes/111，但模型优先显式 target，不会自动纠正）；③ x=−170 走廊补一盏朝北的灯。**"被灯切分的轨不再是 0"这一条取决于此**——v2 已支持轨中段的灯（notes/109），只是该世界没有。**动手前先读 `docs/01-设计/闭塞区间-灯到灯跨轨有向模型-设计.md`（v2）§9。**
> 语言：文档中文；代码/提交信息英文。

---

## 1. 新会话目标（一句话）
把 MTR 派生的引擎 Vehicle 的运动，从「spawn 时烤死整条 vehicleExtraData.immutablePath（预烘焙 path）」改成由
Motion Core 驱动：车的运行状态 = (当前轨道段 + 段内偏移)，到节点按「当前道岔状态 / 任务目标」选下一段（道岔权威换向），
由现有驾驶控制（ControlState，见 §5.4）开。MTR 只作世界轨道图 / 几何来源。目标形态：生成一辆 manual 车，人上服务器用现有驾驶手动开、能随便走 / 到岔口自己定。

---

## 2. 已完成且已验证（Motion Core 独立运动系统 —— 不要再重做）
全部在真实轨 / 真实 dev 存档验证，compileJava/TestJava + 对应测试绿。

### 2.1 运动系统代码（mmtr/engine/src/main/java/org/mtr/core/mmtr/）
- segment/MmtrSegmentStep.java：纯 (railHex, offsetM, lengthM, reversed) 段状态（advance/remaining/atEnd/overshoot）。
- segment/MmtrNodeRouter.java：道岔权威节点决策 elect(...)：单续向直行；task 命中优先；operator 0=straight(branch0)/1=diverge(branch1)；无权威真岔返回 null（绝不 auto）。
- segment/MmtrLiveRouter.java：route(...) 沿真实 positionsToRail 逐节点权威路由出轨序；integrate(...) 按距离推进返回 (segment,offset)。
- segment/MmtrMotionWalker.java：可续逐 tick (segment+offset) 引擎；advance(delta) 跨节点按权威选段；未设岔停在岔口（等操作者，非终态，可续）；buildLegs() 输出它走过的有序、累计距离、Vehicle 可跑的 PathData 轨序。
- segment/MmtrMotionDriver.java：纵向驱动（巡航/手动）。applyControl(ControlState, dt, accel, decel, max) = 用现有驾驶 ControlState 驱动；manualTick = 手控油门/刹车。到无权威岔口/端点/目标制动停。
- point/MmtrPointRegistry.java（含 BranchStore）：真实岔 (节点, 进向轨)->branch0/1 自动发现 + operator 0/1 持久化。point/MmtrSwitch.java：岔模型。
- MmtrMotionSnapshot.java：from(Siding,Vehicle)（现有车辆表示）与 ofWalker(MmtrMotionWalker)（Motion Core 驱动车 -> segment+offset+车头世界坐标，可渲染，无烘焙 path）。

### 2.2 接缝（已落地，直接复用）
- data/VehicleExtraData.createWithLegs(depotId, sidingId, railLength, vehicleCars, legs, accel, decel, isManualAllowed, maxManualSpeed, manualToAutomaticTime)：把 Motion Core 的 buildLegs() 轨序直接做成 Vehicle 的运行路径载体（immutablePath），不拼烘焙缓存。（注：Siding.simulateVehicles 现在用 VehicleExtraData.create(...) 生成车，见 §4。）

### 2.3 测试（新会话先跑它们确认基线绿）
- engine/src/test/.../mmtr/MmtrSegmentMotionTests.java
- engine/src/test/.../mmtr/point/MmtrTurnoutRoutingTests.java
- engine/src/test/.../mmtr/segment/MmtrLiveRouterTests.java（含 walk / driver / manual / existing-control 用例）
- engine/src/test/.../mmtr/point/DevWorldTurnoutFlipTests.java（真实 -96 岔口 搬0走直/搬1走岔，决策层）
- engine/src/test/.../mmtr/point/DevWorldMotionWalkTests.java（真实 -96：Motion Core 驱动车沿 via 轨开进岔口、branch0/1 跨上不同真实轨、ofWalker / buildLegs）
运行：cd mmtr/engine，JAVA_HOME 用 env\jdk-21（见 §6），./gradlew.bat test --tests 类名。

---

## 3. 现在要做的核心改造（新会话主体）
把引擎里真实运行的车接到 Motion Core，使其手动驾驶不再被 spawn 时烤死的整条 path 限制：
1. 手动停场车被驱动/发车时，其「向前该走哪」由 Motion Core 在每个节点实时决定（当前道岔态/任务），而不是沿它 spawn 时那份 default/出库/整条 path 走到底。
2. 运动的坐标/渲染/占用仍由真实 Rail 几何提供（MTR 只作轨道图），MmtrMotionSnapshot.ofWalker 已是 (segment+offset+head)。
3. 驾驶输入 = 现有 ControlState（MmtrDriveControl.apply -> Vehicle.applyMmtrControl），不需要再造一套控制。
4. 收尾删除旧烘焙机制（Depot/Siding.generateRoute 生成的三份缓存拼装 path）。

## 4. 现有引擎代码锚点（先读这些再动手）
- data/Vehicle.java（1292 行）：simulate/simulateMoving/simulateStopped/simulateInDepot/startUp/getRailProgress/writeVehiclePositions 全部沿 vehicleExtraData.immutablePath（PathData 列表 + 累计 railProgress）；siding final 绑出生股道。手动驾驶：rider/driver + powerLevel/applyMmtrControl(ControlState) 进 mmtrActiveControl，在 simulateMoving 的 MMTR 分支由 ConsistDynamics 算速度并沿 path 推进 railProgress。
- data/VehicleExtraData.java：immutablePath；create(...) -> createPathData(...) 用 pathSidingToMainRoute + pathMainRoute + pathMainRouteToSiding（+ defaultPathData）拼出整条；createWithLegs(...) 已可换源。
- data/Siding.java：simulateVehicles 生成车（VehicleExtraData.create(areaId,id,railLength,vehicleCars,pathSidingToMainRoute,pathMainRoute,pathMainRouteToSiding,defaultPathData,repeatInfinitely,accel,decel,(getIsManual()||mmtrManualSpawn),maxManualSpeed,manualToAutomaticTime) 在 Siding.java:124/364/407）；generateRoute 生成三份 path 缓存。
- data/Depot.java：generateRoute / writePathCache（出库腿来源）；Simulator.java:514 用到 hasPathToMainRoute/hasReturnFromMainRoute。
- operation/MmtrDriveControl.java：现有驾驶命令 ControlState（throttle/brake/emergency/reverser + driverUuid）-> vehicle.applyMmtrControl。

> 状态更新（commit f04ff20）：T3 / T3b 已完成并验证（见 §5b），T4 尚未删烘焙（前置见 §5b 说明）。

## 5. 建议落地切片（每步 compile + 对应测试可验，再提交）
- T3（推荐先做，可测）：在发车/生成 manual 车处用 createWithLegs + Motion Core buildLegs() 生成一辆「路线由 Motion Core 决定」的真实 Vehicle，并让它在合成场区/真实存档里跑起来（railProgress 前进 + 跨岔换向断言）。这一步把 Motion Core 选轨真正送进一辆活的车。
- T3b：让那辆 manual 车能被现有 ControlState（MmtrDriveControl / rider 驾驶）驱动，运动走 Motion Core；验证人用现有键位/命令能把它往前开、到岔口设岔换向、停目标。
- T4：删旧烘焙（Depot.generateRoute / Siding.generateRoute 三缓存拼装、Simulator.hasPath*），改为 Motion Core 取径。
> T4 阻塞证据（goal round，全量 256 绿集内）：当前 8 个绿测试类都靠 Depot.generateDepots -> "SUCCESSFUL"
> （= auto/scheduled 路径烘焙）才能发车跑车：MiniWorldLineTests、MiniWorldDeterministicDriveTests、
> MmtrMissionDriveTests、MmtrPeriodicTaskTests、MmtrTrainsServletTests、DepotDepartureTests、MiniWorldMissionTests、
> MmtrMultiplayerFoundationTests（后者的 VehicleExtraData.create 亦走三缓存）。Motion Core 自身的 buildLegs()
> 也调 SidingPathFinder.generatePathDataDistances。=> 直接删 generateRoute/三缓存会破坏整个 auto 服务 + 这 8 类绿测试，
> 违反"不新增失败"。要让烘焙可删，前置 = 把全部 Vehicle（含 auto/scheduled）运动切到 Motion Core 逐 tick
> (segment+offset)（Vehicle.simulate*/bogies/signal/停站/turnback 的 L3 大改，见 notes/12）。故 T4 应作为单独、
> 多轮、分片的工作立项，而不是一次低风险删除。



## 5b. T3/T3b 已完成（commit f04ff20，全量回归零新增失败）
- 交付物：真实 Vehicle 的运行路径来源换成 Motion Core 轨序（createWithLegs(legs)，legs=MmtrMotionWalker.buildLegs()），
  用现有座舱控制（applyMmtrControl / ControlState，即 MmtrDriveControl 送进 Vehicle 的那个对象）驱动；到真实岔口按道岔
  选轨跨上不同真实轨。引擎内真实 Vehicle 达成，非仅 DTO 断言。
- 代码：
  - main: Siding.spawnMmtrManualWithLegs(ObjectArrayList<PathData> legs)（Siding.java，rebuildParkedConsist 之后，additive）。
  - test: MmtrVehicleLegsRunTests（4 用例全绿）：synthetic 真岔 branch0->straight / branch1->diverge 两辆真 Vehicle 越过
    岔口；裸 Siding 经 spawnMmtrManualWithLegs 产出 manual 车并可开；真实 dev 存档 -96 岔口真 Vehicle 越过叉点落到
    branch0Hex / branch1Hex 真实轨（10.4 验收）。
- 回归：定向 63/0；全量 256 completed / 13 failed（全为既有 mmtr-job 红：MmtrJobSchedulerTests 12 + DevWorldJobSmokeTests 1）/ 2 skipped。
- T4（删烘焙）为何未在本切片删：三份缓存（pathSidingToMainRoute/pathMainRoute/pathMainRouteToSiding）仍被 auto/
  scheduled 车 + 时刻表/平台停站 + Depot 出库 + 众多既有测试（MiniWorldDeterministicDrive/DepotDeparture/Siding 等）
  使用。安全删它们 = 先把全部 Vehicle（含 auto）运动切到 Motion Core（Vehicle.simulate* 目前按 immutablePath 单调累计
  前进 + stoppingIndex/dwell/signal/turnback，属 notes/12 标注的 L3 级大改）。故 T4 另立切片，前置是"运行中的车由 Motion
  Core (segment+offset) 逐 tick 驱动"（MmtrMotionSnapshot.ofWalker 已给可渲染表示）。

## 6. 构建 / 验证纪律（新会话务必照做）
- JDK：JAVA_HOME=env\jdk-21，PATH 前置其 bin。引擎工作目录 mmtr/engine。
- 编译：./gradlew.bat compileJava compileTestJava
- 跑指定测试：./gradlew.bat test --tests 类名（见 §2.3）。
- 回归底线：改 Vehicle 前先跑全量 ./gradlew.bat test，记下失败集（现状：约 13 个失败全是既有的 mmtr-job 子系统 / DevWorldJobSmokeTests，与 Motion Core 无关，最初基线就红）。你的改动不得新增失败（Motion Core + 确定性测试必须保持绿）。
- 真实 dev 存档可加载：Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, ${MC_ROOT}/mmtr/game/fabric/run/saves/新的世界/mtr, false)。

## 7. 文档地图（按需加载）
- 本文档（新会话先读）：目标 / 已完成 / 剩余 / 锚点 / 验证。
- docs/01-设计/运动系统-脱离MTR-MotionCore-设计.md：完整架构 / 解耦边界 / 删除与迁移策略 / 验收 / §10 T1–T4 规约。
- notes/12-段级运动-slice-A地基.md：按轮次的过程记录（含各提交点）与全量回归证据。
- 提交锚点（历史）：652507e(决策+段) 3e3e18a(图层岔) c9ec451(路由) a434e84(integrate) 8c8c379(设计doc) f6cec64(真实-96决策) 1179d47(Walker) 0ab03da(Driver) 4e47449(真实-96驱动) 36f4d83(buildLegs/T1) e4cb07f(createWithLegs/T2) e0b6193(自由开续走) bc2ffdb(手动开) 78e6610(applyControl/现有控制驱动)。
