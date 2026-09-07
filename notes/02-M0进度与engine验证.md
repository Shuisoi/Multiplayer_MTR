# notes/02-M0 进度与 engine standalone 验证（2026-09）

- mmtr monorepo 初始化并提交（5094a65）：engine/ = TSC ce3a509082（分支已压平为普通目录，上游历史在 GitHub）；
  game/ = MTR 4.0.5 全树；fabric.mod 模板已改 id=mmtr + 派生声明；NOTICE/.gitignore/README 就绪。
- JDK 21 已装：tools/jdk-21.0.12.1+1（TSC toolchain 要求 21；Game/Loom 同样使用 21 与 CI 一致）。
- engine 编译通过：cd mmtr/engine && gradlew.bat classes（JDK21）。
- engine 独立可执行包：build/libs/Transport-Simulation-Core-1.0.0.jar（shadow fat jar，Main-Class=org.mtr.core.Main）。
- standalone 冒烟（关键，支撑“外置引擎=选项2”）：
    java -jar Transport-Simulation-Core-1.0.0.jar --help
  Usage: -p/--webserver-port(默认8888,0禁用) -r/--root-path <dimension>...
  按维度各起一个 Simulator；threaded-simulation/file-loading 默认 true。
- 注：jar 名版本沿用 TSC 源码 1.0.0（MTR libs 里的 0.0.1 只是它固定的别名），无实际影响。

## 下一步（M0 剩余 + M0b）
1. game composite：在 game/settings.gradle 用 includeBuild(../engine) + dependencySubstitution 把
   org.mtr:Transport-Simulation-Core 换为 engine 源码；删 game/libs/Transport-Simulation-Core-0.0.1.jar 引用（保留 Build-Tools jar）。
2. game 构建验证：gradlew.bat fabric:setupFiles fabric:build -PminecraftVersion=1.20.4（JDK21；首次会拉 loom/yarn/MC，较重）。
3. M0b：EngineBridge 接口 + MC<->engine 网络桥（草案放 docs/01-设计/EngineBridge-设计.md）与带宽基准。
## M0 验证完成（补记 2026-09）
- engine：TSC 基线 ce3a509082 在 JDK21 下 classes 编译通过；shadowJar(33MB) 生成；standalone --help OK。
- game：fabric:build BUILD SUCCESSFUL（loom 1.10.5, Gradle 8.14, JDK21 运行）；
  产物 game/fabric/build/libs/fabric-4.0.5.jar；fabric.mod.json 实测 id=mmtr / name "MMTR (Multiplayer MTR)"，
  资源命名空间 mtr/mtrsteamloco 与 org.mtr.* 包保留。
- 关键坑（已解决并固化到构建流程）：
  1) pwsh 调用 gradlew 的 -P 值必须加引号（'-PminecraftVersion=1.20.4'），否则点号被吞成 "1"；
  2) javadoc 需显式 UTF-8（根 build.gradle Javadoc options.encoding，及 gradle.properties jvmargs -Dfile.encoding=UTF-8）；
  3) TSC 仓库(现代,Java21) 与 MTR 4.0.5 打包的 TSC jar(旧,Java8/瘦jar) 不一致：M0 期 game 使用与原版一致的原配 jar
     (sources 副本恢复)，现代 engine fork 作为独立工程（外置引擎 M0b 目标）使用；二者 API 差异
     (如 org.mtr.core.operation.VehicleLiftResponse) 已记录，作为后续“引擎升级对齐”任务。
- 集成方式：scripts/sync-engine.ps1 把 engine shadowJar 同步为 game/libs/Transport-Simulation-Core-0.0.1.jar（上游式）。

## 下一步（M0b）
EngineBridge 设计文档已建（docs/01-设计/EngineBridge-设计.md）。实现顺序建议：
  1) 引擎侧加 bridge 占位 op/echo + 配置文件；
  2) MC 侧 EngineBridge 接口与 NetworkEngineBridge(连接/心跳/重连)；
  3) 最小 end-to-end：空世界+最小线路，外部引擎驱动一辆列车，MC 端能看到并渲染；
  4) 32 人量级带宽基准记录 notes。
