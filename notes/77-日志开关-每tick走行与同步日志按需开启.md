# 77 - 日志开关：每 tick 走行/同步日志按需开启

> 触发（2026-09-09 20:41，实机）：用户手动开 3 节编组时，`run/logs/latest.log` 最近 2000 行里
> **851 行 `[MMTR-SYNC] push vehicle`、771 行 `[MMTR-DRV] motion seg=`、304 行 `[MMTR-CL]`**，
> 真正的状态消息（mission / 连挂闸门 / 信号占用 / 道岔等待）被淹在里面。这些行都是**每 tick 一列**打出来的。

## 口径

| 类别 | 例子 | 处理 |
| --- | --- | --- |
| 每 tick 的走行/同步量 | `[MMTR-DRV] motion seg=… offset=… dist=… speed=…`、`[MMTR-DRV] mode=… throttle=…`、`[MMTR-SYNC] push vehicle … dirty=…`、客户端 `[MMTR-CL] mirror created …` / `vehicles_lifts packet …` | **关掉，按需开** |
| 状态变化 | mission 自arm/失败、`[MMTR-COUP] 连挂完成`、`[MMTR-SIG] motion stopped at occupancy block`、`[MMTR-DRV] flip 换端`、`[MMTR-RUN] no plan`、道岔等待 | 保持常开（每次状态变化才打） |

## 实现

| 件 | 内容 |
| --- | --- |
| `MmtrTrace`（引擎 `mmtr/MmtrTrace`） | 静态开关，默认 `Boolean.getBoolean("mmtr.trace")`；`log(msg)` 仅在开时打印；`isEnabled()` / `setEnabled()` |
| 引擎接线 | `Vehicle` 的三处每 tick 打印改走 `MmtrTrace.log` |
| 客户端接线 | `VehicleExtension` 的 `mirror created`、`PacketUpdateVehiclesLifts` 的 `vehicles_lifts packet` 改走 `MmtrTrace.log`（同 jar，同一开关） |
| 运行时开关 | OP 指令 `trace on` / `trace off` / `trace status`（`MmtrCommandExecutor`），结果回写指令日志 |

开启方式：服务器启动加 `-Dmmtr.trace=true`，或在 Web 指令栏发 `trace on`（立即生效，不用重启）。
客户端要开就走启动参数（客户端 JVM 的 `-Dmmtr.trace=true`）。

## 操作红线（本次踩到的）

**服务器/客户端运行时不要覆盖 `game/libs/Transport-Simulation-Core-0.0.1.jar`**：本次在服务器运行时同步 jar，
服务器随后加载 Web API 的 Jetty 类失败（`java.util.zip.ZipException: ZipFile invalid LOC header (bad signature)`）并崩溃。
同步 jar 必须先停服务器；客户端同理（它也在用同一个 jar）。

## 用例与验证（引擎）

- `MmtrTraceTests.perTickTracesStaySilentUntilSwitchedOn`：关时零输出、开时恰好一行、用例结束恢复关闭。
- 全量引擎套件 **442/0/2**（上一轮 441/0/2）。
