# 03 MMTR —— 架构决策与里程碑（Multiplayer MTR）

> 本文件记录 2026-09 确认的产品决策，并给出 MMTR 的服务器架构与修订后的里程碑。
> 前置阅读：`docs/02-可行性分析与技术路线.md`、`docs/01-*.md`。
> 事实依据（实读源码）：TSC `Main.java` 既支持“独立进程启动”（picocli + Jetty Web + 10ms tick + 控制台输入），也支持“被 MTR 进程内嵌”（`manualTick + sendMessageC2S/processMessagesS2C`）。

---

## 1. 已确认决策

| 项 | 决策 | 备注 |
| --- | --- | --- |
| 模组名 | MMTR = Multiplayer MTR | 明确标注“衍生自 Minecraft Transit Railway (MIT)” |
| 平台 | Fabric（1.20.4） | 环境已就绪；Forge 二期再议 |
| MC 版本 | 锁定 1.20.4 | 基线 MTR 4.0.5 / 打包的 TSC 0.0.1；不追上游 |
| 玩法范围 | 只改列车操控与玩法层 | MTR 的方块/铁轨/乘客/景观/电子显示屏等全部保留 |
| 服务端 | 需要修改/加强服务端（数据量大处理不过来） | 第 3 节：引擎独立进程化（TSC 已有 standalone 基础） |
| 并发 | 32 人同时在线 | 作为容量与网络设计基准 |
| 授权 | MIT 继承派生 | 保留版权与许可声明，发布页注明派生 |

## 2. 命名与兼容策略

- 推荐：mod id = `mmtr`（fabric.mod.json），显示名 “MMTR (Multiplayer MTR)”。
- 资源命名空间与内部 Java 包 v1 **保留 `mtr` / `org.mtr.*`**：
  - 好处：MTR 全部资产/模型/贴图/schema 引用原样可用；**面向 MTR 的资源包（车辆/站牌/装饰）无需改写**；差异集中在列车操控与玩法代码，便于与上游比对。
  - 代价：与官方 MTR 不能同实例共存（替换式服务器模组，属预期）。
- 备选 A（全量改 `mmtr` 命名空间+包名）：资产引用与资源包兼容层全部重写，昂贵，近期不做。
- 备选 B（drop-in，连 mod id 也用 `mtr`）：第三方 Java addon 视其为同一 mod 直接加载；冲突/越权风险高，仅在确定要兼容第三方 Java addon 时考虑。
- 声明：README/发布页写明名称、源码链接、MIT、继承自 MTR/TSC。

## 3. 服务器架构：MMTR Engine（独立进程目标）+ MC 端桥

### 3.1 为什么值得“独立引擎进程”

- TSC 仿真不依赖 MC tick：`Main.main` 已能独立 JVM 运行（rootPath/webserverPort/threadedSimulation/threadedFileLoading/dimensions + Jetty + 控制台命令）。
- 独立引擎带来：仿真不被 MC 重启打断；任务/时刻/货运数据与 MC 世界解耦；多 MC 服务器共享路网/时刻表；Web 调度台/API 直连引擎；CPU 隔离与横向扩展可能。
- 说明：32 人在线未必“必须”外置（MTR 服务端用内嵌+线程化仿真也能承载更多）。但既然要长期服务化，第一天就把引擎当独立进程设计（模块边界现成），第一版是否外置见 3.4。

### 3.2 目标拓扑

```text
             +----------------------------------------------+
             |  MMTR Engine daemon (JVM)   <- fork TSC      |
             |   . Simulator(s) 按维度                        |
             |   . 任务引擎/TaskRunner、车辆物理/连解挂/货运(新增) |
             |   . 存档在引擎目录                              |
             |   . Jetty: 系统图 / 调度台 / API               |
             +---------^----------------+--------------------+
                桥接协议(新增)           (已有 HTTP/地图/OBA)
        +----------+--------------------+--------------------+
        |  MC Dedicated Server (Fabric 1.20.4, MMTR mod)       |
        |  方块/红石/乘客/渲染/实体/UI/网络包 -> 薄桥接层        |
        +-----------------------------------------------------+
                  ^ MC 客户端(MMTR 客户端 mod：司机台/任务UI/HUD)
```

### 3.3 桥接协议（新增工作量主体）

TSC 现有：`QueueObject(key, data)` + `OperationProcessor` 操作键 + schema 化序列化（JSON/MessagePack）。MMTR 需新增：

1. 传输通道：引擎加一对读写 servlet/websocket，把进程内 `sendMessageC2S/processMessagesS2C` 换成网络通道（TCP/WebSocket + MessagePack 压缩）。
2. 上行 C2S（MC→引擎）：轨道/节点/方块改动、信号与门事件、司机输入（档位/制动/降保升）、装卸请求、玩家上下车、任务申报。
3. 下行 S2C（引擎→MC）：车辆/乘客快照（按可见范围与 LOD 节流增量）、到发事件、信号状态、任务状态、货运结果。
4. 容量基准：32 人×可见车辆，做节流与增量；正式开发前跑基准（M0b）。
5. 数据归属：路网/车底/任务/货运 -> 引擎权威；方块外观/红石/乘客实体渲染 -> MC 权威但依赖引擎计算结果。

### 3.4 起步选项（需拍板）

- 选项 1（推荐）：MVP 期沿用“MC 服务端内嵌引擎”，但代码留 `EngineBridge` 接口（内嵌/网络两个实现），任务与货运全部实现在引擎层；引擎外置化放 M2 后。最快出可玩版，外置只是换实现类。
- 选项 2：一开始就外置引擎进程，先做桥接（约 +2–4 周前置），一切联调走网络。最贴近“服务端要改”，但 MVP 前移成本更高。

## 4. 范围红线（拒绝需求清单）

- 保留不做减法：MTR 方块/轨道网络/站台/乘客模拟/景观/电子显示屏(PIDS 等)/列车外观资源。
- 只新增/重做：任务驱动玩法、车底与牵引制动、货运与连解挂、司机与调度 UI/交互。
- 派生关系在文档与发布信息中永远声明（MIT）。

## 5. 修订里程碑（对齐决策）

| 阶段 | 内容 | 交付/验收 | 估期(单人) |
| --- | --- | --- | --- |
| M0 派生跑通 | monorepo(engine=game=mappings fork)；锁定 TSC 基线；composite 构建；`mmtr` id + 保留 `mtr` 命名空间；Fabric 1.20.4 单机+专用服务器跑通 | 进服能玩原 MTR 内容；改一行 schema 全链路生效 | 1–2 周 |
| M0b 桥接原型 | `EngineBridge` 接口+网络实现 spike；32 人量级快照带宽基准 | 内外嵌可切换 demo + 带宽数据 | 1–2 周 |
| M1 车底与物理 | ConsistType + DriveController(有级/无级/降保升) + 载重影响；JUnit 无头回归 | 两类车底、三种操控在服内可开 | 3–5 周 |
| M2 任务引擎+AI+连解挂 | Task 图/调度/持久化；Actor(Human/AI)；Coupler 可变编组(先 spike) | spike 通过；客运+调车连挂任务闭环 | 4–6 周 |
| M3 货运 | 货舱/货物/装卸终端/货运任务与报酬 | 机车+货车：装→运→卸→结算 | 4–6 周 |
| M4 UI/输入 | HUD(列车/任务/信号)、司机台、任务与调度 GUI、Web 调度台(可选) | 32 人在线 UI 不卡 | 4–6 周 |
| M5 运营化 | 权限/经济/存档迁移/备份/性能/文档/CI | 可公测 | 3–6 周 |

合计仍约 5–8 个月到可玩公测（多人团队可压缩；若一开始外置整体 +2–4 周）。

## 6. M0 即刻任务（确认后开工）

1. monorepo：`engine/ game/ mappings/ docs/` 目录与 gradle 结构。
2. 定 TSC 基线：以 MTR 4.0.5 打包的 `Transport-Simulation-Core-0.0.1.jar` 反查对应 TSC 提交（比对 schema/字节或按发布日附近的提交），锁定源码。
3. fabric.mod.json -> `mmtr` + 派生声明；保留 `mtr` 命名空间与 `org.mtr.*` 包。
4. `gradlew setupFiles -PminecraftVersion=1.20.4 && gradlew build` 产出 MMTR jar，单机回归（方块/列车/轨道完好）。
5. 建 `docs/04-域模型v0.md`：Task/ConsistType/Actor/Freight/Coupler/EngineBridge 概念与字段草案。

## 7. 遗留拍板点（回复即可开工）

1. 起步选 选项 1（先内嵌+桥接接口，推荐）还是 选项 2（一开始就外置进程）？
2. mod id 用 `mmtr`（推荐）还是 `mtr` drop-in？
3. 是否按上表直接进入 M0？
