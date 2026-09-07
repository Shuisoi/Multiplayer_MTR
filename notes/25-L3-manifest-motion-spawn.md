# 25 - 实机修复：manifest 停场车改以 Motion-Core 形态出生（本会话）

> 现场（真实 dedicated server，world "world"，depot 112330）用户反馈"开车开不了"。
> 取证结论：manifest 播种的 legacy 停场车（VED=defaultPathData 单股道 35m）停车点≈旅程终点
> （defaultPosition≈totalDistance≈25m），车一起步就触发"到终点→回库→再起步"原地循环；
> 日志全程零 [MMTR-DRV] mode= 运动输出、offset 钉在 25.000、速度恒为 startUp bump（0.014 km/h）。
> 即 legacy 形态的车**物理上开不出车场**——与按键（R0/T0 等）无关。

## 修复（engine, additive）
Siding.simulateVehicles 模板播种块：mmtrManualSpawn（rolling-stock manifest/manual 车场）时
改走 spawnMmtrMotionVehicle(mmtrMotionWalkerFromYard)（live Motion-Core、yard 停场姿态、无终点、
岔口实时裁决），失败时回退 legacy 播种并告警。auto 车场行为不变。

## 测试（MmtrManifestMotionSpawnTests，1 例）
manifest(真实 depot/siding id) → mmtrResetAndApplyRollingStock=1 → 首个 tick 播种 → 断言
isMmtrMotion && 停场 → 司机 ControlState(T3,R1) → 开出 yard 上正线（progress>yardLen+5、
railHex==正线、onRoute；日志逐 tick motion 推进至线路尽头）。

## 证据
- 全量 247/0/2 → 248/0/2 零新增失败。
- 部署：cd mmtr && powershell -File scripts/sync-engine.ps1（engine shadowJar →
  game/libs/Transport-Simulation-Core-0.0.1.jar）→ 重启服务端/客户端 → 启动/重置即生效
  （mmtr-manifest-reset 或服务器重启自动 staged）。

## 实机驾驶备忘（客户端键位已接线，勿再踩）
- ↑/↓ = 油门挡 ±（上升沿，每按一次一挡）；←/→ = 换向 ±（**必须 R≥1 才有动力**）；
  ; / ' = 制动施加/缓解；R = 门。无 HUD 挡位显示，用 mmtr-motion feed 验证。
- 车现在是 motion 形态：出车场后岔口未设会停车等待；mmtr-point-op（web :8888）搬岔即走。
