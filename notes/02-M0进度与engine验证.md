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
3. M0b：EngineBridge 接口 + MC<->engine 网络桥（草案放 docs/05-EngineBridge-设计.md）与带宽基准。
