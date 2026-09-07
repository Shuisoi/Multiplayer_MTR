# 26 - L3 收尾：motion 车客户端镜像推送（slice 10，本会话）

> 现场（真实服务器）发现：motion 车在引擎里正常（web mmtr-motion 快照可见、可自动跑），但游戏内
> "看不见车/上不去" —— motion 车从不推送 VehicleUpdate（legacy writer 才推），且镜像端若收到也会因
> VED.totalDistance=0 误判"到终点→回库"。本 slice 补上 motion 车→客户端 的完整同步语义。

## 改动（engine）
1. schema（buildSrc/.../schema/data/vehicle.json + 重新生成 VehicleSchema/TS）：
   - mmtrMotionMirror(boolean)、mmtrRunTotalDistance(number)、mmtrRunStopTarget(number)。
2. VehicleExtraData：mmtrSetSyncPath(legs)（VED.path ← 影子 legs，client copy 用）、mmtrMarkSyncDirty()。
3. Vehicle（服务端）：
   - refreshMmtrMotionLegs：同步 VED.path、mmtrRunTotalDistance=影子末端、stoppingPoint 置大
     （copy 尾部过滤放行全影子）、标记 dirty；
   - engage/simulateMmtrMotion：置 mmtrMotionMirror；engage(null)/任务终态/出发/武装停点处维护
     mmtrRunStopTarget 与 dirty（停点/终态/续行变化即刻推送）；
   - writeMmtrMotionVehiclePositions：追加与 legacy writer 相同的客户端推送（半径/riding 判定、
     needsUpdate 或 1s 周期强制推送，pathUpdateIndex=0 全影子）。
4. Vehicle（镜像端 isClientside）：镜像车辆不回库（end-of-route 分支跳过 mmtrMotionMirror）；
   在停点（runStopTarget）与影子末端（runTotal，岔口等待/线路尽头）处夹住 speed=0，直到下一包
   延伸/重武装。

## 测试
- MmtrManifestMotionSpawnTests：加断言——车辆 JSON 含 mmtrMotionMirror=true、mmtrRunTotalDistance>0，
  VED.copy(0) 的 path = motion 影子（>0 legs）。（真实客户端视觉验证依赖部署后人工确认。）

## 证据
- 定向：ManifestMotion/MotionRun/Yard/Mission 全绿；全量：mirror-full.log（基线 248/0/2 → +0 用例，
  仅断言扩展，零新增失败）。
- 部署：powershell scripts/sync-engine.ps1 → 重启服务端/客户端。

## 边界/下一步（人工验证后补）
- 镜像端中间态以 1s 周期推送纠正（等待岔口/停点时最多 ~1s 视觉误差）；真机视觉/手感验证后按需
  加密推送或加"镜像软制动包络"；客户端渲染几何（mmtrLegs PathData 与 legacy 同构，预计可用）。
