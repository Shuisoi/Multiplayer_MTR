# 35 - 信号灯显示：服务器权威通道（已交付 295/0/2 全绿，实机红灯验证通过）

> 背景：用户建 6 股道+多站台客运测试线并摆好 MTR 原版信号机；实机发现"车占轨但灯绿"。
> 根因排查（渲染链读透）：MTR 信号灯红/绿 = 灯前保护轨的 blocked 状态。本地机制（客户端
> VehicleExtension 每帧把"本地可见车"占用轨写 blockedRailIds）对 motion 镜像车不生效，且原版
> 机制依赖 rail 的 signalColors（reserveRail 只在 rail.signalColors.contains(color) 时登记占用，
> 测试轨从没设过信号色 → 本地/legacy 通道都无法触发）。解法 = 服务器权威通道（多人正确且与
> 客户端本地模拟无关）。

## 机制（读懂 MTR 原版信号链后采用）

- Rail 有 legacy 信号块状态机：preBlockedVehicleIds/currentlyBlockedVehicleIds（key=信号色、
  value=车辆 id），`isBlocked(id, BlockReservation)`（package）登记 CURRENTLY_RESERVE；reserveRail
  沿 **同色相连轨** 递归传播（一色=一个闭塞分区）。
- `Rail.tick1`（每 tick，车辆模拟前）：diff old/current → 附近 client 收到
  `client.update(this, needsUpdate)` → SignalBlockUpdate（rail blocked 色快照）→
  client.railIdToCurrentlyBlockedSignalColors 更新；随后 current→old、current 清空（车每 tick 重登记）。
- 客户端灯：getAspectState(灯) 找节点→保护轨（灯前方 90° 内从节点伸出的轨）→
  occupiedAspect = 保护轨 blocked（nodeBlocked 或 occupiedColors 非空，filter 空时恒真）→ 红灯。

## 改动（engine，3 文件）

- `Rail.mmtrSignalColor()`：每轨确定性保留色（0x40000000 | hex.hashCode()&0x3FFFFFFF，每轨唯一
  防 reserveRail 同色递归扩散到全图）；`Rail.mmtrEnsureSignalColor()` 幂等注入。
- `Simulator.mmtrEnsureSignalColors()`：rails-signature 门控给全部轨注入（轨增删自动补），
  tick 内与 mmtrEnsurePointDefaults 并排调用。
- `Vehicle`：服务器端 motion 车每 tick 把自己占用轨（tail..head 覆盖的 leg 段，同 footprint
  遍历）登记 `rail.isBlocked(id, CURRENTLY_RESERVE)` → 走标准 signal-block 通道 → 客户端灯红。
  顺带收益：legacy 车也能感知 motion 车占用（互操作 R3）。

## 测试与验证

- MmtrSignalDisplayTests（2 例）：每轨注入唯一保留色且不扩散/幂等；行驶 motion 车占用轨在
  currently-blocked 集合携带其 MMTR 色，相邻空轨干净。
- 全量 295/0/2 零新增失败（legacy 行为零回归）。
- 实机（dev server 六股道世界，MTR 原版 2/3/4Aspect 信号机）：车 1 停站 1 台轨、车 2 被挡于
  台轨入口前（S1 waiting）→ **保护台轨的信号灯变红**（用户目视确认）——首次全链路
  "MMTR 占用 → 服务器权威 → 游戏内红绿灯" 闭环。
- 部署注意（坑）：运行中替换 game/libs engine jar 会使服务器已打开的 jar 句柄失效
  （ZipException/8888 哑火）——必须先停服务器再 sync-engine 再启动。

## 剩余

- 灯"转绿"验证（车离开后 SignalBlockUpdate 空快照→绿）——尽头站场景车 1 无法前进（无折返），
  可删车/后续折返任务验证；
- 3-aspect 黄灯（pre-blocked/预占）语义、咽喉多灯布置规则文档、AWS/LZB 区灯位语义（S6 收口）。
