# 103 - 实机验收（游戏内目视 · 首次）：AWS 触发 → 确认 → 复位 / 未确认 → SPAD

> 承接 notes/93（AWS 确认键 H 补缺）、notes/94（提前确认被记住的缺陷修复）、notes/102（区间×道岔 ①②③④ 收口）。
> 本轮是**信号线第一次真正跑游戏内目视**：用户在真实 dev 服务端手动驾驶，引擎侧用日志与 feed 逐条对照。
> 验收对象 = `docs/03-交接与实机/实机测试-手动驾驶Motion车-步骤.md` §9 第 5、6 条。

## 1. 环境（本轮踩到并解决的坑）

| 项 | 情况 |
| --- | --- |
| dev 服务端 | 首次 `scripts\dev-server.ps1` **配置期失败**：`net.minecraftforge.gradle:6.+` 无法解析（game 的 settings 同时 include 了 `forge`，跑 `:fabric:runServer` 也会先配置 forge）。仓库与缓存都没问题（ForgeGradle 6.0.54 在 `~/.gradle` 缓存里、maven.minecraftforge.net 响应 200）。**判为瞬时解析失败，重试即通**；`--offline` 可复现成功，可作现场兜底 |
| 端口 | 25565（MC）、**8888（引擎 web/指令栏 `mmtr-command`）**、25575（RCON，本轮未用） |
| 客户端 | `scripts\dev-client.ps1`；`options.txt` 已存 `lastServer:127.0.0.1:25565` |
| 世界 | `saves` 之外运行中世界 `fabric/run/world`；**7 台车**（aassdd 1 / 987654 6）、8 架灯、134 轨 |
| 结论 | **后续所有实机验收都必须连 8888 的 `mmtr-command`**（`interlock` / `blocks` 等引擎侧对照全靠它），RCON 里没有这些指令 |

## 2. §9-5 AWS：非绿信号 → 触发 → 确认 → 复位 ✅ 通过

用户驾驶 `-7130062426401751518`（987654 车场，CAB_A，钥匙 CREW）沿 `x=-170` 长直轨（z −79→−478，**全线唯一一盏灯**在 z=−122）向北接近。

服务端日志（`game/fabric/run/dev-server.out.log`）原文：

```
[18:34:06] [MMTR-AWS] warning on ...FEB3-...FECE at 195.5m
           - signal RED on ...FE85-...FEB3 in 26.9m, boundary at 9.223372036854776E17m
[18:34:07] [MMTR-AWS] acknowledged
[18:34:10] [MMTR-AWS] warning cleared (band/driver left)
```

逐条对上设计（A3）：

| 设计条款 | 实机证据 |
| --- | --- |
| 触发绑定"本信号显示"，非固定距离 | 触发时车头距该灯 **26.9 m**（`MMTR_AWS_TRIGGER_LEAD_M=75 m` 触发带内），读出的是该轨进入节点的 aspect = **RED** |
| 确认键（默认 H）真的送到引擎 | 触发后 1 s 内 `acknowledged`（notes/93 补的按键链路，实机首次走通） |
| 绿灯/离开后复位 | 3 s 后 `warning cleared (band/driver left)` |
| 提前空按不计数（notes/94） | 本轮两次触发都**只在告警显示后被确认才生效**，无 stale 队列行为 |

## 3. §9-6 未确认 → SPAD 紧急制动 ✅ 通过

同一辆车再次向北经过同一盏灯，司机**故意不按 H**：

```
[18:36:30] [MMTR-AWS] warning on ... at 611.1m - signal RED ... in 26.9m, boundary at ...
[18:36:32] [MMTR-AWS] unacknowledged warning - SPAD emergency engaged
```

| 指标 | 触发前 | 触发后 |
| --- | ---: | ---: |
| 车速 | 14.66 km/h | **0.49 km/h**（≈20 s 内降到 creep 下限） |
| 车头 z | −308.78 | −312.48（**只多走 3.7 m**） |
| `mmtr-trains` 健康行 | `protections=0` | `protections=1`（18:36:33–18:36:48），18:36:53 回到 0 |

结论：2.5 s（`MMTR_AWS_ACK_WINDOW_MILLIS`）窗口到点即拉保护层、车自己停住；保护随后自动释放。
**A3 的"未确认 → 紧急制动直至停稳"在真实世界、真实存档上成立。**

## 4. 一个记录下来待判的现象（本轮未定性）

第一趟（同一根轨、同一盏灯、司机主动驾驶）：车从 z=−142 向南开 239 m，**全程无任何 `[MMTR-AWS]` 行**。

- 现象存在，且服务端日志可证（整份日志只有 5 行 `[MMTR-AWS]`，全在第 2、3 节那两趟里）。
- 但**不能断定是缺陷**：该灯 `angle=180`（朝向南），其保护方向与向南行驶相反；而 AWS 读的是
  `aspectFrom(下一轨, 进入节点)`，有方向性。要定性必须做**同一根轨、同一盏灯的反向（向南）复跑**，
  或在引擎侧补一条"方向 × aspect"用例。
- 处置：不阻塞本轮收口，作为 §9 复跑项列入下次实机的第一条。

## 5. 台账：§9 剩余项（本轮未覆盖，均需"有任务的车"或"第二列车占道"）

| # | 场景 | 状态 | 备注 |
| --- | --- | --- | --- |
| 5 | AWS 黄灯响 / 按 H 确认 / 绿灯复位 | ✅ 本轮通过（红灯光路） | **黄灯（单/双）光路本身尚未复跑**，同一条日志口径即可验 |
| 6 | 未确认 2.5 s → SPAD | ✅ 本轮通过 | — |
| 1 | 派任务后 `route{SET/MAIN}` + `interlock` 对照 | ⬜ 待跑 | 服务端侧 notes/83、84 已验证；游戏内目视未做 |
| 2 | 分岔处信号按进路放行 | ⬜ 待跑 | 需车跑在真实分岔上 |
| 3 | 人为锁岔 → 进路 PENDING、车不越岔、灯红 | ⬜ 待跑 | `mmtr-point-lock` + `interlock` |
| 4 | 解锁 → 回 SET、分段释放 | ⬜ 待跑 | 同上 |
| 7 | 双车咽喉排队（先 SET 持有、后者 PENDING） | ⬜ 待跑 | 引擎用例已绿（notes/82） |
| 8 | 双车同腿跟随 → S1 停在闭塞边界 | ⬜ 待跑 | notes/84 服务端侧已见 S1 停闭塞 |
| — | 人工目视"道岔被清空 / 岔区被占 → 灯变红" | ⬜ 待跑 | notes/102 §5 遗留 |

## 6. 复跑口径（下次照做）

1. 停服 → `scripts\sync-engine.bat`（**服务器运行时不要覆盖 `game/libs` jar**，notes/77 红线）→ 起 `dev-server.ps1`。
2. 起 `dev-client.ps1`，进 `127.0.0.1:25565`。
3. 让"有任务"的车成为观察对象：WEB（8888）指令栏或用 `mmtr-vehicle-task`/作业单派车，`interlock <车id>` 对照。
4. 每次目视前后各抓一次：`mmtr-trains`（进路/信号/占用）、`interlock <id>`、`blocks all`，以及
   `game/fabric/run/dev-server.out.log` 里的 `[MMTR-AWS]`/`[MMTR-DRV]`/`[MMTR-SIG]` 行。
