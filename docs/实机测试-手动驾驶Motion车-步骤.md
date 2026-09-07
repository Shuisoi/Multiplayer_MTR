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
- JDK：C:\Users\30354\.jdks\jdk-21.0.12.1+1（PATH 前置）。
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
