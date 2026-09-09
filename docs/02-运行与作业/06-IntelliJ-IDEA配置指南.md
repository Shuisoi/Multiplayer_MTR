# 06 IntelliJ IDEA 配置指南（MMTR 开发）

适用环境（本机实测）：IDEA Community **2025.2.6**（env/idea，自带 jbr）；Gradle：engine=9.5.1、game=8.14（各自 wrapper）；
JDK：本机另装 env/jdk-21（必需，engine 与 game 构建/运行都用 21）与 env/jdk-17。

mmtr 是两个独立的 Gradle 工程（engine 与 game 不是同一个 Gradle build），因此**分两个 IDEA 窗口/工程**使用。

## 0. 一次性准备（重要，否则打开即报错）

### 0.1 给 game 预生成源码（再打开 IDEA）
game 的大量源码是构建期生成的（Keys.java、org/mtr/mod/generated/**、fabric.mod.json 等）。
首次打开前请先在命令行跑一次（JDK21、注意 -P 值加引号）：

    cd mmtr\game
    call ..\..\env\workspace.env.bat    &rem 设置 JAVA_HOME=env\jdk-21
    gradlew.bat fabric:setupFiles fabric:compileJava '-PminecraftVersion=1.20.4'

（engine 的 generated 源码已随仓库存在，无需预跑；改 schema 后由 Gradle 的 generateSchemaClasses 自动再生。）

### 0.2 安装 Lombok 插件（engine 用 Lombok）
Settings(Ctrl+Alt+S) -> Plugins -> Marketplace -> 搜 “Lombok” -> Install -> 重启 IDE。

## 1. 打开工程（两种方式任选）
- 方式 A（推荐，Gradle 正规导入）：File -> Open -> 选目录，Open as Project：
  - 引擎：mmtr\engine（识别 build.gradle.kts）
  - 游戏：mmtr\game（识别 settings.gradle：fabric/forge 两个子模块）
  首次会弹 “Trust Project”，勾选信任；随后 IDEA 自动 Gradle Sync。
- 方式 B：双击工作区根目录的 `bin\打开-Engine-工程.bat` / `bin\打开-Game-工程.bat`（脚本已指向 env\idea）。

## 2. 关键设置（每个工程都要做一遍）

### 2.1 JDK 注册
File -> Project Structure(Ctrl+Alt+Shift+S) -> SDKs -> + -> Add JDK：
- 添加 <MC>\env\jdk-21（起名 JDK21）
- 建议同时添加 env\jdk-17（部分工具链需要）

### 2.2 Gradle JVM 指向 21
Settings -> Build, Execution, Deployment -> Build Tools -> Gradle：
- Gradle JVM：选 “JDK21”（不要用系统 Java 8 / 不要默认）
- Build and run using / Run tests using：均选 Gradle
- 取消 “Use Gradle from: wrapper” 之外的自动下载可不动；若提示下载 toolchain，指向本地 JDK21 即可
  （engine 在 build.gradle.kts 声明 toolchain 21；game 的 BuildTools 已按 21 编译）。

### 2.3 注解处理（Lombok，engine 必需）
Settings -> Build, Execution, Deployment -> Compiler -> Annotation Processors：
- 勾选 Enable annotation processing。
（game 模块的 buildSrc/engine 类含 Lombok 注解；不开启会导致一堆 “cannot find symbol log”。）

### 2.4 编码全部 UTF-8（否则中文注释/字符串乱码或编译报 GBK 错误）
Settings -> Editor -> File Encodings：
- Global Encoding / Project Encoding / Default encoding for properties files 全部 UTF-8；
- 右下角无 BOM。engine/game 的 gradle 已带 -Dfile.encoding=UTF-8，但 IDE 自身也要设 UTF-8。

### 2.5 内存（可选）
Help -> Change Memory Settings -> 建议 ≥ 4GB（两个 Gradle 工程 + loom 同步较吃内存）。

## 3. 运行/调试（示例）
- engine（独立引擎进程）：新建 Application 运行配置，Main class = org.mtr.core.Main，
  Program arguments 示例： -r "${MC_ROOT}/mmtr/notes/spike-data" -p 8899 test
  JRE：JDK21。启动后浏览器可开 http://127.0.0.1:8899/ 与 /mmtr/api/bridge/ping?dimension=0。
- game（Fabric 客户端）：Gradle 侧运行 task fabric:runClient（loom 已配置 runConfigs；需先 fabric:setupFiles）。
  服务端冒烟：fabric:runServer。
- game 单元测试：Gradle fabric:test，或直接运行 org/mtr/test 下测试类。

## 4. 常见问题
- 打开后一片红：先确认 0.1 已生成源码；执行 Gradle Sync（右侧 Gradle 面板刷新按钮）。
- Lombok 报错：装插件 + 开注解处理 + 重启 + 重新 Sync。
- 中文乱码/编译 “unmappable character”：见 2.4；命令行构建见 notes/03（-P 引号坑）。
- .idea 目录：engine/game 的 .gitignore 已忽略，不会污染 git；可放心让 IDEA 生成。
- 想在根目录同时管两个工程：分别开两个窗口即可（不要尝试在 mmtr 根做 Gradle 聚合，两个工程的 Gradle 版本不一致，前面已论证会失败）。

## 5. 参考
- 构建/版本说明：docs/00-历史/03-MMTR-架构决策与里程碑.md、notes/01-04
- EngineBridge/外置引擎：docs/01-设计/EngineBridge-设计.md

## 6. 常见错误：ClassNotFoundException: net.fabricmc.devlaunchinjector.Main（runClient 报错）

原因：工程的 .idea / *.iml 被“普通 idea 插件”或旧缓存生成，覆盖了 Loom 需要的模块类路径
（dev-launch-injector 其实已在 Gradle 缓存里，问题只在 IDE 侧类路径）。
修复（已验证流程）：
1. 关闭该 IDEA 工程窗口。
2. 删除 mmtr/game 下的 .idea 与 *.iml（root 与 fabric/、forge/ 子目录）。
3. 重新打开：File -> Open -> 选 mmtr/game/settings.gradle -> Open as Project -> Trust -> 等 Gradle Sync
   （此时 Loom 会重新生成正确的运行配置与模块依赖）。
4. 运行客户端推荐二选一：
   - Gradle 工具窗口：fabric -> Tasks -> runClient；
   - 或 Sync 完成后 Loom 生成的 “Minecraft Client (:fabric)” 运行配置（确认 ALTERNATIVE_JRE_PATH=21）。
终端兜底（不依赖 IDE 类路径）：
    cd mmtr/game && gradlew.bat fabric:runClient '-PminecraftVersion=1.20.4'   (JDK21)
