# docs/05-EngineBridge 设计（M0b 草案，MMTR 选项2：外置引擎进程）

> 目标：MC 专用服务器（Fabric，MMTR mod）不再内嵌 TSC，而是通过 EngineBridge 连接独立运行的
> MMTR Engine 进程（fork TSC standalone，`Main.main` 已支持）。仿真权威在引擎侧。

## 1. 职责边界（谁做什么）

| 侧 | 职责 | 数据权威 |
| --- | --- | --- |
| MMTR Engine daemon | 仿真推进(每维度独立线程,默认10ms)、任务引擎/TaskRunner、车辆物理/连解挂/货运、路网/时刻/存档、系统图与 Web API(Jetty) | 路网/车辆/任务/货运/时刻 |
| MC Server (MMTR mod) | 世界里的方块/红石/玩家/乘客实体外观与交互、本地渲染同步、把玩家动作(上车/司机操作/装卸请求/建轨)上报引擎 | 方块外观/红石/实体渲染 |
| MC Client (MMTR mod) | HUD/司机台/任务与调度 UI、输入；与 Server 走原版网络包 | — |

核心原则：**玩法状态全部在引擎；MC 只做“世界的游戏表现层 + 输入层”。**

## 2. EngineBridge 接口（Java，双实现）

```java
public interface EngineBridge {
    // 生命周期
    void start(EngineConfig cfg);           // 内嵌=启动进程内 Main；网络=连接远端 daemon
    void stop();
    boolean isConnected();
    // MC 主线程每 tick 调用（20Hz）
    void tick();
    // 上行：MC -> 引擎（复用 TSC OperationProcessor 的 key+QueueObject 语义）
    void sendToEngine(String opKey, SerializedDataBase payload, Consumer<SerializedDataBase> onResponse);
    // 下行：引擎 -> MC（快照/事件），由桥在 MC 线程上回调
    void setEventHandler(EngineEventHandler handler); // onVehicleSnapshot/onEvent/onTaskUpdate...
}

public final class EmbeddedEngineBridge implements EngineBridge { /* 现状：manualTick + 进程内队列 */ }
public final class NetworkEngineBridge  implements EngineBridge { /* TCP/WebSocket + MessagePack */ }
```

- 内嵌实现暂保留（回归/测试/单机便捷），生产默认走网络实现；游戏逻辑只依赖接口，不感知实现。

## 3. 网络协议 v0（草案）

- 传输：TCP（本机/内网先）或 WebSocket（跨主机/浏览器复用），消息体用引擎既有 `MessagePackWriter/Reader`（schema 化对象）。
- 消息信封：`{op, id, worldId(维度索引), payload, replyTo?, ts}`，按 OperationProcessor 既有 key 体系扩展。
- 上行 C2S ops（新增命名空间 mmtr.*）：`mmtr.task.assign/cancel/ack`、`mmtr.drive.input(档位/制动/降保升)`、`mmtr.couple/uncouple.request`、`mmtr.freight.load/unload`、`mmtr.player.claim/release(席位)`；
  沿用既有：轨道编辑、信号/门、客车乘客上下、建站改线等(引擎已定义的那些 op)。
- 下行 S2C：
  - 低频事件：到发/门/任务状态/装卸结果/信号变化（事件流）；
  - 高频快照：可见车辆与乘客（`VehicleUpdate/DynamicDataResponse` 之类已有结构）按玩家分拣 + LOD 节流 + 增量；
- 心跳/超时：1s 心跳；断线 => MC 侧本地提示“引擎未连接”，引擎侧按任务规则自动 AI 接管(玩家掉线语义复用)。
- 重连与对账：MC 重连后拉一次全量路网/任务轻量快照，之后走增量；引擎持久化不受 MC 重启影响。
- 时钟：引擎世界时间权威，MC 侧只显示（现有 SetTime 机制反向即可保留/简化）。

## 4. 带宽与基准（M0b 验收）

- 场景：32 人在线、单维度、~40 列列车/编组、视距内可见车辆子集。
- 估算口径：每次快照单列(8 节)压缩后 ~1–3KB 量级、10–20Hz/可见列 => 单玩家几百 KB/s 内；需实测 MessagePack 后真实值。
- 基准工具：引擎侧脚本批量生成车辆并跑 N 维仿真；桥打印 Hz/pps/KB/s/延迟 p50/p99；记录到 `notes/03-带宽基准结果.md`。
- 验收线（初步目标）：32 人基准下 MC tick 平均 < 8ms 引擎相关开销、快照丢包重传 < 0.1%、峰值带宽在局域网/云内网可承受（若不足则降频/增量）。

## 5. 配置与部署形态（草图）

- 引擎：`mmtr-engine -r <data> -p <port> -P<维度>`（沿用现有 standalone 参数 + MMTR 新增配置：`--tasks-file/--bridge-port`）。
- MC：`config/mmtr.toml`：`engine.host=127.0.0.1 engine.port=8888 bridge=network|embedded`。
- 进程拓扑（开发=同一台机双进程；生产=可同机或分机，仅引擎暴露管理口）。
- 保留原版“引擎内嵌”开关用于单人/开发快速验证与自动化测试。

## 6. M0b 验收清单

1. 内嵌与网络两种 EngineBridge 实现编译并可通过同一个冒烟测试。
2. 起一个空维度 standalone 引擎 + MC 连上 => MC 内能读到引擎系统图/车站(最小 echo op)。
3. 双向 echo/任务占位 op 的 RTT 测量与带宽粗测记录在 notes。
4. 断线重连测试：杀引擎→MC 不崩并提示→重启引擎→自动重连恢复。

> 状态：设计草案 v0，待 M0(game 构建)收尾后按此实现 spike。

## 附录：实证记录（2026-09 round2 spike）
- standalone 端点清单与 /mmtr/api/bridge/* 原型（BridgeServlet，engine fork 已提交）；
- ping/vehicles 200 OK；localhost 往返 ~15ms（20 次采样）。
- 缺口清单（实现顺序）：op(C2S) 桥 → 车辆快照字段增强 → MC 侧 EngineBridge 双实现 → 端到端 → 带宽基准。

## 附录2：原型进度（round3）
- engine BridgeServlet 增加 `echo`（双向相关校验）；端点清单见 notes/03。
- game 新增 `org.mtr.mod.bridge`：EngineConfig/EngineBridge/NetworkEngineBridge（JDK HttpClient + relocated gson），fabric:compileJava 通过。
- 跨进程实测 echo p50≈15.3ms / p90≈16ms / ~194B；带宽基线段记录 notes/04。

## 附录3：round4 结果与收口
- /mmtr/api/bridge/bench 合成负载端点；vehicles 负载曲线见 notes/04。
- 类级 E2E（BridgeRunner + NetworkEngineBridge）：started/connected/echo/vehicles 全通。
- 后续（新里程碑）：Init/InitClient 接入 external 模式开关、真实世界数据生成器、增量快照协议。
