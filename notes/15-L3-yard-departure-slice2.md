# 15 - L3 Slice-2：yard 停场起步 + Siding 发车接缝（本会话）

> 承接 notes/14（slice-1：Vehicle live motion mode）。slice-2 把 motion mode 接到"真实车场"：
> 停在股道里的 manual 车（引擎每 tick 经 Siding.simulateVehicles 驱动）由司机用现有座舱控制开出车场，
> 出库后全程 Motion Core 逐 tick (segment+offset) 裁决——无任何预烘焙路径。

## 1. 改动（全部 additive）

- MmtrMotionWalker：新增 startAtOffset(data, rail, entryNode, initialOffsetM, branches, target) ——
  车头可从段中间起步（停场姿态），offset 从进向节点量起、不得越过远端节点；distanceM 同步初值。
  旧 start(...) 委托 initialOffset=0，行为不变。
- Siding：
  - mmtrMotionWalkerFromYard(rearEnd, branches, target)：基于 yard rail（defaultPathData，即真实
    股道 rail）构造停场 walker——车尾端 = 连接其它轨更少的端点（缓冲区/库尾；可显式传 rearEnd 覆盖），
    车头停场 offset = clamp((railLen+trainLen)/2, trainLen, railLen)，编组放不下返回 null；
    branches 缺省时取 Simulator.mmtrPointBranches（权威存储）。
  - spawnMmtrMotionVehicle(walker)：Motion-Core 发车接缝——空闲车场（无 on-route、至多一辆 parked）
    时替换停场车：VED 空 legs + engageMmtrMotion(walker)（车一出生即在 live motion mode，停场姿态），
    占住模板再播种槽位（mmtrManualSpawn+mmtrSessionSpawned），杜绝离场后车场再自生一辆。
- 引擎路径不变：Siding.simulateVehicles 每 tick 照常（area 空时清车表的既有语义未动——车场必须挂
  Depot area，与真实世界一致）。

## 2. 测试（MmtrYardMotionDepartureTests，2 例全绿，经 Siding.simulateVehicles 真实调用路径）

1. parkedMotionVehicleIdlesThenDepartsAcrossLiveFork：
   - 真实 Depot 车场（depot corners 含 yard rail；newSidingRail 股道 -32..-20 经 -20 节点接 rIn→岔口）；
   - 停场：车头几何在股道内（head≈-32+offset）、mmtr-motion 快照 = 停场段+offset、30 tick 无人驱动
     不动、车场恒只有这一辆车（无模板再播种）；
   - 司机上车 + MmtrDriveControl 油门 → 出股道、过 -20 节点（单续向无需权威）上 rIn、未设岔停在岔口
     （~32m，haltedAtAuthority）；
   - 活搬 0 → 同一辆车越岔上 straight 真实轨。
2. yardDepartureFlipsToDiverge：搬 1 → 上 diverging 真实轨。

## 3. 回归证据
- 定向：Yard(2) + MotionRun(5) + VehicleLegs(5) 等全绿。
- 全量：slice2-full.log（零新增失败，基线 231/0/2 → 预计 +2）。

## 4. 边界与下一步
- 边界：出库方向 = 库尾→咽喉（fewer-connections 端起步）启发式，需真实存档运行时确认
  （对应交接/实机文档【运行时待确认】项）；司机=ControlState；门/信号/停站仍后续；反向无动力。
- 下一步：
  a. 真实 dev 存档"manifest 停场车 → 实车出库 → -96 岔口活搬岔"端到端（需确认场区股道拓扑与
     sidingId，见实机文档 §7 待确认清单）；
  b. 停站/门/乘客（SERVE 语义上 motion mode）；c. 行经段信号/限速（M2）；d. 客户端渲染推送
     （增长式 legs）；e. 任务 MOVE_TO → live walker + targetRailHex（task 覆盖陈旧 operator 已就绪）。
