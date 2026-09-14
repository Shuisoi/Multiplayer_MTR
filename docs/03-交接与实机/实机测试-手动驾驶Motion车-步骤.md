# 实机测试步骤 · 手动驾驶 Motion-Core 车（真实 dev 服务器）

> 目标形态（交接文档 §1）：生成一辆 manual 车，人上服务器用现有驾驶手动开、随便走 / 到岔口自己定。
> 本文件给「在真实 dev 存档上人工验证」的步骤；确定性自动化验证见
> MmtrVehicleLegsRunTests / DevWorldMotionWalkTests（引擎内已全绿）。
> 凡标注【运行时待确认】的项 = 代码面已确认存在、但本会话未实际跑过服务端，需按部署现场填。

## 0. 验收口径（做什么算过）

1. 一辆 manual（可手动）车停在真实股道（-96 岔口所在场区附近）。
2. 用现有驾驶命令（mmtr_drive / ControlState）把它往前开：railProgress 前进、mmtr-motion 快照的
   (segment,offset,head) 世界坐标沿真实轨变化。
3. 到 -96 岔口（节点 (-96,-60,76)，via 轨）时：未设岔 -> 车停在岔口（快照 offset≈via 全长，不再前进）；
   mmtr-point-op 搬 0 -> 车继续走直向真实轨；搬 1 -> 车跨上分叉真实轨；快照段端点/轨道随之改变。
4. 全程无需 depot 自动发车/烘焙：无人/无任务时车绝不自己动。

## 1. 前置环境

- 真实 dev 存档：mmtr/game/fabric/run/saves/新的世界/mtr（含 overworld 的 mmtr-consist-types.json 等）。
- JDK：env\jdk-21（PATH 前置）。
- 启动引擎/存档服务：以仓库现有运行方式为准
  （【运行时待确认】engine Main / game 侧启动脚本；SystemMapServlet 与 OperationProcessor 的
  HTTP/WS 入口地址与端口按实际部署填）。
- 存档内有 mmtr-jobs.json -> mmtrJobsMode=true（禁 depot 自动发车，正是我们要的）；
  任务调度需 mmtrAiJobStepsEnabled=true 才跑，手动测试期保持默认 false 即可。

## 2. 放置一辆 manual 车（两步）

1. 编写车辆生成表 mmtr-rolling-stock.json（<存档>/minecraft/overworld/ 下），声明目标场区
   （-96 岔口所在 depot 的某条 siding）的编组：
   - 【运行时待确认】depotId/sidingId 必须用存档里的真实 id：先调 mmtr-topology / mmtr-points 或
     存档 JSON 查 -96 附近 depot、siding、以及能通向 via 轨（sw.viaRailHex）的股道；
   - 车辆声明示例（字段以 MmtrManifestDepot/Siding 为准，参考 config-example / mmtr-consist-templates）。
2. 触发：action=mmtr-manifest-reset（SystemMapServlet）
   -> 返回 placedSidings>=1；该 siding 上应出现一辆 manual-allowed 停场车
   （Siding.mmtrManualSpawn=true，每 siding 一辆、绝不自动发车）。

> 若 -96 场区没有可用的、能开进 via 轨的股道，则退而求其次：
> 用 headless 引擎（同一存档、同一岔口）走确定性路径验证（引擎测试已覆盖），
> 实机只验证「车能被手动驱动 + 快照正确」，不强求恰好从 -96 侧股道出发。

## 3. 观察手段

- mmtr-motion 快照（SystemMapServlet action）：每个车输出当前运动表示，重点字段
  segStartX/Z・segEndX/Z・segmentReversed・segmentOffsetM・segmentLengthM・headX/headZ
  （MmtrMotionSnapshot.from(siding, vehicle)，无烘焙整条 path 依赖）。
- 服务端日志：驾驶时每 tick 打印
  [MMTR-DRV] mode=NOTCHED throttle=.. brake=.. speed=..->.. dist=.. prot=false —— 车在动的直接证据。
- mmtr-points：列出每个 (node, via) 岔的 branch0/branch1 真实轨 hex 与 operator 分支状态。
- 车辆列表/编组信息：mmtr-rolling-stock / 现有车辆 feed（确认生成的那辆车及其 vehicleId）。

## 4. 手动驾驶（核心步骤）

对目标车发驾驶命令（OperationProcessor key=mmtr_drive，即 MmtrDriveControl JSON）：
{ "vehicleId": <该车 id>, "throttleNotch": 3, "brakeNotch": 0, "reverser": 1,
  "throttleAxis":0,"brakeAxis":0,"emergency":false, "driverUuid": ""|司机 }
- 空 driverUuid：无司机身份路径（测试/工具可用）；带司机时引擎要求其占用该车司机席
  （occupation lock，MmtrDriveAccess.canControl）。

1) 停车态：不发任何命令 -> 车保持停场（railProgress 不变、无 [MMTR-DRV] 前进行）。
2) 油门：throttle=3 -> [MMTR-DRV] dist 逐 tick 增大；mmtr-motion 的 segmentOffsetM 前进、
   headX/headZ 沿股道变化。到 -96 岔口前若岔口未设 -> offset 停在 via 全长、dist 停增
   （等在岔口，非终态）。
3) 设岔走直：mmtr-point-op { x:-96, y:-60, z:76, via:<viaHex>, branch:0 }
   -> 同一辆车继续前进，跨上 branch0（直向）真实轨；快照段变为 branch0Hex 段、head 沿该轨走。
4) 换岔走分叉：再发 branch:1（或重新开一辆车在未设岔口停下后搬 1）
   -> 车跨上 branch1（分叉）真实轨；mmtr-points 里该岔 operator 分支显示 1。
5) 停目标/制动：brakeNotch>0 或 emergency=true -> dist 停止增长、速度回落到 ~0；
   不设岔的车在岔口停下后可随时由司机继续（自由开语义，非终态）。

## 5. 关键观察点（每条都要核对）

| # | 操作 | 期望证据 |
|---|------|---------|
| 1 | 停着不发车 | 无 [MMTR-DRV] dist 行；mmtr-motion offset 不变 |
| 2 | throttle=3 开向岔口 | dist 递增；offset 沿 via 轨前进 |
| 3 | 未设岔到 -96 | offset≈via 全长且停增（等权威，不自动选向） |
| 4 | point-op branch=0 | 车续走，快照段=straight 真实轨 |
| 5 | 换车/重试 branch=1 | 车续走，快照段=diverging 真实轨 |
| 6 | brake | dist 停增、速度≈0 |
| 7 | 全程 | depot 不自动发车；快照无「烘焙整条 path」依赖 |

## 6. 回归底线

- 动手前/后各跑一次：engine 全量 gradlew test 应 227 completed / 0 failed / 2 skipped
  （含 MmtrVehicleLegsRunTests 5 例：真实 Vehicle 跑 Motion legs、操作层驱动、-96 跨岔）。
- 本方案不触碰 mmtr-job 调度器（mmtrAiJobStepsEnabled 保持 false），与任务重建正交。

## 7. 【运行时待确认】清单（部署现场补）

1. 引擎/存档服务的启动命令与 SystemMapServlet/OperationProcessor 的实际 URL/端口；
2. -96 场区中能开向 via 轨的 siding 及其 depotId/sidingId（写 rolling-stock.json 用）；
3. 该 dev 存档里 mmtr-rolling-stock.json / mmtr-consist-templates.json 是否已存在（避免覆盖）；
4. 真实 Minecraft 客户端「上车 + 键位映射到 mmtr_drive」的接线是否已实现（客户端侧不在本引擎测试面）。

## 8. 快速失败判定

- 车不在 -96 停住而是自动绕路/自动进站 -> 还有 auto 残留（应已删净，属回归）。
- 设岔后车不续走 -> 检查 branch 值/ via hex 是否与 mmtr-points 一致；或看 [MMTR-DRV] 是否还在打印。
- mmtr-motion 无该车 -> 车未生成（manifest reset 未命中 siding）或 vehicleId 看错。

---

## 9. 信号 × 道岔 × 任务集成（A2/A3/S5）实机验收清单

> 代码面已交付并有引擎用例：notes/78（进路对象）、79（信号=进路×闭塞）、80（游戏内镜像）、
> 81（AWS 绑信号）、82（冲突进路互斥 + 分段释放）。本节是**实机目视**部分。
> **第 5、6 条已于 2026-09-10 实机通过（notes/103）**；其余项待跑，逐条状态见下表。

前置：停服后同步引擎 jar（`mmtr\scripts\sync-engine.bat`；**服务器运行时不要覆盖 `game/libs` 的 jar**，
notes/77 红线的崩溃就是这么来的），再启动 dev 服务端。
**引擎侧对照一律走 8888 的指令栏**（`POST /mtr/api/map/mmtr-command`，如 `interlock <id>`、`blocks all`）——
RCON 里没有这些指令。部署现场踩过的坑（Forge 插件瞬时解析失败）见 notes/103 §1。

| # | 场景 | 期望证据 | 状态 |
|---|------|---------|------|
| 1 | 给一列车派任务（`mmtr-vehicle-task` / 作业单），观察 WEB `mmtr-trains` | 该车出现 `route{state:"SET",kind:"MAIN",entryRail,targetRail,rails[]}`；`routes[]` 顶层也有它；**同时**在网页指令栏发 `interlock <该车id>` 得到引擎侧对照（进路/道岔持有/每条轨显示/客户端收窄） | ⬜ 待跑（服务端侧见 notes/83/84） |
| 2 | 该车经过分岔 | 分岔处信号灯按**进路放行**（不再因另一支被占而保守显黄/红）；`[MMTR-DRV] motion seg=` 沿进路推进 | ⬜ 待跑 |
| 3 | 人为 `mmtr-point-lock` 该进路的一个道岔（或让另一列车先持有） | 该车进路变 `state:"PENDING"`，`stateReason` 点名道岔与 `lock=true`/`holder=v…`；**车不越过该道岔**，信号显红 | ⬜ 待跑 |
| 4 | 解锁 / 对方释放 | 进路自动回到 `SET`，车继续（分段释放：越过第一个道岔后进路仍为 `SET`） | ⬜ 待跑 |
| 5 | 黄灯区段手动开车 | 接近**非绿**信号时 `[MMTR-AWS] warning on … signal SINGLE_YELLOW/DOUBLE_YELLOW/RED …`；驾驶室 AWS 灯闪"警示-按 H 确认"，按 **H**（默认键，可在 选项→控制 改）后 `[MMTR-AWS] acknowledged`、灯转"已确认"；信号转绿时 `[MMTR-AWS] warning cleared` | ✅ **红灯光路通过**（notes/103：26.9 m 触发 → 1 s 确认 → 3 s 复位）；黄灯光路待复跑 |
| 6 | 未确认并继续行驶 | 约 2.5 s 后 `[MMTR-AWS] unacknowledged warning - SPAD emergency engaged`，车紧急制动停稳 | ✅ **通过**（notes/103：14.66 → 0.49 km/h，只多走 3.7 m；`protections=1` → 自动释放） |
| 7 | 双车咽喉（两条股道同抢一个岔，不同腿） | WEB `routes[]`：先 SET 者持有；后者 `PENDING` 且车留在自己股道；前者越岔后后者自动 SET 并通过 | ⬜ 待跑 |
| 8 | 双车同腿跟随 | 后车进路点被独占而等待；点释放后跟进，最终被 **S1 占用**停在闭塞边界（不撞前车） | ⬜ 待跑 |
| 9 | 同一根轨、同一盏灯**反向**再跑一次 | 向北经过该灯有 AWS（notes/103 §2），向南却全程无 `[MMTR-AWS]` | ✅ **已定性为正确**（notes/103 §4：`blocks all` 证明该走廊 6 盏灯全部朝南**只保护北行**，向南无灯可读 → 不响是对的）；**改为补灯决策项**：x=−170 走廊若要双向跑车需补一盏朝北的灯 |

> 客户端要看到信号灯变化，客户端 JVM 需与服务器同一 jar 版本；进路镜像包只在**变化时**推送
> （`[MMTR-CL] routes mirror: N locked rail(s)`，需 `-Dmmtr.trace=true` 才打印）。

---

## 10. T1–T5 任务联锁实机验收清单（notes/116–129 的引擎侧交付）

> 引擎侧全部交付、用例全绿（见 `任务联锁T系列-验收证据.md`），但**没有一条在真游戏里逐条目视过** ——
> 本节就是那一步的清单。每条都写成"怎么摆 → 看什么"，且**每条都配一条服务端侧对照命令**：
> 眼前看到的与 8888 指令栏读到的必须一致，不一致就是缺陷（这条纪律来自 notes/114/115 的两次教训）。
>
> 前置：停服 → `mmtr\scripts\deploy-engine.ps1 -Build` → `mmtr\scripts\dev-server.ps1`；
> 客户端与服务端必须同一 jar。世界基线：车场 **987654** 六条股道各 1 台 `saf101`，人工锁 0 把。

| # | 片 | 场景怎么摆 | 期望看到什么（游戏内） | 服务端侧对照 |
|---|---|---|---|---|
| 1 | T3 | 给一辆车派任务，进路上先人为锁住一个道岔（`point lock <x> <y> <z> --via=<轨hex>`） | 车**停在出发信号前**不动，信号红；HUD 的许可（`authority`）给的是"红 + 距离" | `interlock <车id>`：进路 `PENDING`，理由点名道岔与 `lock=true`；`point locks` 看得到那把锁 |
| 2 | T2 | 同上，用 `signal why <x> <y> <z>` 读该信号的四显示 | HUD 里的许可与该灯色**逐条一致**（红=停车义务+距离、黄=注意、绿=无） | feed 每车 `authority{}` 与 `signals[]` 同值 |
| 3 | T3 | 解锁（`point unlock --all`） | 进路转 `SET`，信号由红转绿，车**自动续行**（不用再给指令） | `interlock <车id>`：`state:"SET"` |
| 4 | T1 | 给两列车派任务，让它们的进路要**互斥的道岔位置**（咽喉的两条腿） | 先到的那列走；另一列**停在自己股道上**（不是先开进咽喉再等），它的出发信号红 | `interlock all`：一条 `SET`、一条 `PENDING` 且理由写"物理道岔被 v… 按在位置 1/0" |
| 5 | T1 | 接上一条：让先到的车**开过岔口** | 后车的进路**自动**转 `SET`（分段释放：先到者越岔后进路仍保持 `SET` 到前方） | `interlock` 里先到者的进路仍 `SET`；后车转 `SET` |
| 6 | T1b | 三列车抢咽喉（两条进路各要两处道岔、顺序相反） | **不会**出现两列车各按着一处、互相干等；失败者在岔前等，且**一处持有都没有** | `point why <岔>`：等待队列 FIFO；`interlock` 里失败方 PENDING 且无持有 |
| 7 | T5 | 两条**敌对**进路（同一条单线对开）同时派任务 | 只有优先级高的那条得到 `SET`，另一条 PENDING 且理由写"敌对进路：与 v… ；对方优先" | feed 顶层 `conflicts[]` 列出这条冲突；`interlock` 两条都在 |
| 8 | T4 | 用**玩家执行**派一趟任务（`mmtr-vehicle-task … `，executor=PLAYER），自己开车 | 进路与道岔**照样被设置**、信号按进路放行，但**油门完全在自己手里**（引擎不接管）；道岔扳动在出发前就位 | `interlock <车id>`：`kind` 来自任务类型（`MAIN`/`SHUNT`）；`point list` 显示该车持有 |
| 9 | T4 | 开**没有任务**的车（策略开关打开时） | 拒绝操纵（与"必须有任务"一致）；关掉开关时照旧能开 | 开关：`Simulator.mmtrRequireTaskToDrive`（真服务器打开） |
| 10 | T1（本轮修复） | 让一列车"先按了某个道岔位置、又需要另一位"（例如调车换端后重新规划） | 车**不再卡死**在出发信号前：日志出现 `[MMTR-PT] 重新规划：放掉旧计划在道岔 … 的位置`，随后进路 `SET`、车继续 | `point why <岔>`：持有者是它自己时，位置跟着它的新需要走 |
| 11 | 岔区净空（本轮） | 让一列车**骑在岔上**停住，另一条进路申请要另一位（或自动同步要扳岔） | 道岔**不动**（不许从车下抽走）；车出清后自动扳到位 | `point why <岔>`：位置未变、等待队列里有点名；车走后位置到位 |

> 记录方式照旧：截图 + `logs/2026-09/` 下落一份服务端侧文本（`interlock` / `point why` / `blocks all`），
> 并在本表把 ⬜ 改成 ✅ 或 ❌（❌ 要写清现象与当时的坐标/车 id）。
