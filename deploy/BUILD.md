# BUILD.md —— 构建 / 运行 / 打包 / 发布

命令级手册。工作区布局与被排除项的来源见 [`README.md`](README.md)。

> 前置：**JDK 21** 是硬需求（engine 的 toolchain 与 game 都是 21）。
> 所有命令都假定当前目录是工作区根下的 `mmtr\`，且 `env\workspace.env.*` 已就位 —— 脚本自己会去取，
> 不要依赖机器上的用户级 `JAVA_HOME`（实测本机用户级 `JAVA_HOME` 指向 JRE 8，Gradle 会直接拒绝启动）。

## 0. 命令速查

| 目的 | 命令 | 产物 |
|---|---|---|
| 编译引擎 | `cd engine && gradlew.bat shadowJar` | `engine\build\libs\Transport-Simulation-Core-1.0.0.jar` |
| 引擎 jar 同步进游戏 | `scripts\sync-engine.bat` | `game\libs\Transport-Simulation-Core-0.0.1.jar` |
| 引擎 jar 同步（带停机检查） | `scripts\deploy-engine.ps1 -Build` | 同上，**推荐** |
| 跑引擎单测 | `cd engine && gradlew.bat test` | 报告 `engine\build\reports\tests\` |
| 编译游戏（Fabric） | `cd game && gradlew.bat :fabric:build` | `game\fabric\build\libs\fabric-4.0.5.jar` |
| 编译游戏（Forge） | `cd game && gradlew.bat :forge:build` | `game\forge\build\libs\` |
| 起 dev 服务端 | `scripts\dev-server.ps1` | 引擎 Web 控制台 `http://127.0.0.1:8888/` |
| 起 dev 客户端 | `scripts\dev-client.ps1` | 游戏窗口 |
| 起专用服务端（简易） | `game\run-server.bat` | 同上 |
| 编译 Web 控制台 | `cd engine\website && npm ci && npm run build` | `engine\website\dist\website\browser\` |
| Web 控制台单测 | `cd engine\website && npm test` | — |
| 打包一台车 | `scripts\pack-vehicle.ps1 <配置.json> -Version 13` | 校验过的资源包 zip |
| 跑官方 MTR 作者端 | `apps\creator-studio\start-creator-studio.bat` | 游戏窗口 + Resource Pack Creator |
| 路径守卫（提交前必跑） | `pwsh -File scripts\check-paths.ps1` | 退出码 0 = 干净 |

## 1. 构建引擎（TSC fork）

```bat
cd engine
gradlew.bat shadowJar --console=plain
```

产出 `engine\build\libs\Transport-Simulation-Core-1.0.0.jar`（约 34 MB，含依赖的 fat jar）。
引擎是独立 Java 21 后端，可以单独跑（内嵌 Web 服务），也可以被游戏进程内嵌经消息队列驱动；
它自己的文档在 `engine\docs\`（`BUILD.md` / `RUNNING.md` / `API.md` / `ARCHITECTURE.md`）。

单测：

```bat
cd engine && gradlew.bat test
```

## 2. 把引擎 jar 同步进游戏

游戏端从 `game\libs\Transport-Simulation-Core-0.0.1.jar` **按需懒加载 class**，所以这个 jar 必须和引擎源码一致。

```powershell
.\scripts\deploy-engine.ps1 -Build     # 推荐：先 shadowJar，再检查服务端已停，然后拷贝
```

`scripts\sync-engine.bat` 是更粗暴的版本（直接取 `build\libs` 里最新的 `Transport-Simulation-Core-*.jar` 覆盖），
不做停机检查，适合确认服务端没在跑的时候用。

> ⚠️ **服务端在跑的时候绝对不要覆盖这个 jar。** Fabric 的类加载器正在从这个 zip 里按需读 class，
> 覆盖等于把正在被读的 zip 换掉一半，症状不是"崩了"而是：tick 持续抛
> `java.io.EOFException: Unexpected end of ZLIB input stream`、车辆全部消失（`vehicles=8 → 0`）、
> 指令通道完全不应答，而地图接口还在正常回 200。看起来像"引擎卡死"，实际是半个 jar。
> 唯一恢复办法是杀掉这一轮、用完整的 jar 重启。`deploy-engine.ps1` 会拒绝在服务端运行时拷贝
> （`-Force` 可强拷，之后必须立刻重启服务端）。

## 3. 构建游戏（MTR fork）

```bat
cd game
gradlew.bat :fabric:build --console=plain
```

产出 `game\fabric\build\libs\fabric-4.0.5.jar` —— **这就是可发布的模组**（remap 过的版本）。
同目录的 `fabric-4.0.5-all.jar` 是 remap 前的 shadowJar，`-sources.jar` / `-javadoc.jar` 不用发布。

Forge 侧同构：`gradlew.bat :forge:build`。
注意 `game\forge\src\main\resources\` 是**生成物**（由 `buildSrc` 从 fabric 侧生成，已 gitignore），不要手动编辑。

## 4. 跑起来

### dev 服务端（带引擎 Web 控制台）

```powershell
.\scripts\dev-server.ps1
```

- 走 Loom 的 `:fabric:runServer`，日志落 `game\fabric\run\dev-server.out.log`。
- 服务端起来后引擎在 **8888** 端口应答，Web 控制台：`http://127.0.0.1:8888/`。
- 脚本以"8888 到底有没有人在应答"为成功判据（比读日志硬），失败会重试。
- **前后端分离**：服务端默认从磁盘发前端（`engine\website\dist\website\browser`，
  经 `MMTR_WEB_ROOT` 传入）。改前端只要 `npm run build` 再刷新浏览器，不必重打 jar、不必重启服务端。
  想回退到 jar 内嵌的那份，把 `MMTR_WEB_ROOT` 置空即可。
- `server restart` 由**启动器**接力（引擎不能重启自己）：引擎在 run 目录写 `mmtr-restart.request` 标记，
  脚本跑完一轮 gradle 后看到标记就再起一轮；`server stop` 写 `mmtr-stop.request`，用于区分"用户主动停机"与"没起来"。

### dev 客户端

```powershell
.\scripts\dev-client.ps1        # Loom runClient，日志 game\fabric\run\dev-client.out.log
```

### 简易专用服务端

```bat
game\run-server.bat             rem 保持 stdin 打开，避免 gradle runServer 提前退出
```

### 官方 MTR 作者端（建模 / 车门动画 / 导出资源包）

```bat
apps\creator-studio\start-creator-studio.bat
```

只跑官方未改动的 MTR 4.0.5，与 mmtr fork 完全隔离（不受引擎 jar 更换影响）。
进单人世界后日志/聊天会打印 `Open the Resource Pack Creator at http://localhost:8888/creator/`，
浏览器打开（**带末尾斜杠**）。默认端口 8888，若 mmtr 客户端也在跑会占用，以本实例日志打印的地址为准。

## 5. Web 控制台（engine/website）

```bat
cd engine\website
npm ci
npm run build      rem vue-tsc --noEmit && vite build
```

两个硬约定（改 `vite.config.ts` 前先看那里的注释）：

1. `base: "/"` —— 引擎把 `index.html` 挂在 `/`，资源必须从根路径解析。
2. `build.outDir = "dist/website/browser"` —— 引擎的 Gradle 任务 `WebserverSetup` 固定从这个目录
   递归读文件、生成 `WebserverResources.java` 嵌进 jar。**输出目录改了，jar 里就没有前端。**

本地前端热调试：`npm run dev`（5173，`/mtr/api` 代理到 `127.0.0.1:8888`）。
单测：`npm test`。

## 6. 车辆资源包（Blender → OBJ → 资源包 zip）

```powershell
.\scripts\pack-vehicle.ps1 .\tools\obj-mtr-packager\example\vehicle.hst_h.json -Version 13
```

旧的 `tools\obj-mtr-packager\pack.bat` 现在只是转发到这个校验过的流程
（配置检查 → 打包 → zip 校验 → 锚点 → 命名）。

- 配置里用 `${MC_ROOT}/assets/models/blender/...` 占位符（由 `tools\obj-mtr-packager\paths.js` 解析），
  所以 `assets\` 可以整体移动位置。
- 产出默认写 `game\fabric\run\resourcepacks`（游戏要读）；验收通过后把 zip 复制到 `artifacts\packs\` 存档。
- 创作源在 `assets\models\blender\`（**不可再生，不入 git，需单独备份**）；
  流程规范见 `docs\02-运行与作业\MMTR-OBJ车辆资源包-标准化工作流.md`。

## 7. 发布 / 部署到真实服务器

1. `cd engine && gradlew.bat shadowJar`
2. `scripts\deploy-engine.ps1`（确认服务端已停）
3. `cd game && gradlew.bat :fabric:build`
4. 取 `game\fabric\build\libs\fabric-4.0.5.jar`
5. 服务端：**Minecraft 1.20.4 + Fabric Loader**，`mods\` 里放
   `fabric-4.0.5.jar` + `fabric-api-0.97.3+1.20.4.jar`（API 版本见 `vendor\README.md`）
6. 客户端：同一份 `fabric-4.0.5.jar` + 同样的 fabric-api（模组是双端的，不需要单独客户端包）
7. 首次启动服务端会生成配置；模板见 `config-example\`

引擎的独立运行方式（不嵌在游戏里）见 `engine\docs\RUNNING.md`。

## 8. 提交前自检

```powershell
pwsh -File scripts\check-paths.ps1     # 退出码 0 = 没有机器相关绝对路径
```

另外建议至少跑一次 `cd engine && gradlew.bat test` 与 `cd engine\website && npm test`。
