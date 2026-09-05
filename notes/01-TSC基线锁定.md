# notes/01-TSC 基线锁定（M0）

- 目标：MTR 4.0.5（2026-06-14 发布）打包的 libs/Transport-Simulation-Core-0.0.1.jar 对应的源码状态。
- 证据：MTR 4.0.5 发布日前最后一次 TSC 提交 = ce3a509082（2026-06-13 22:05 "Passenger logic part 4"）；
  TSC 版本号变更 "Bump version" 提交在 2026-06-17（发布之后）→ 基线取 ce3a509082。
- 注意：该提交 gradle.properties 内 version=1.0.0；libs jar 文件名 0.0.1 只是 MTR 侧固定别名，两者不冲突。
- 落地：mmtr/engine @ 分支 mmtr-baseline = ce3a509082（全历史克隆，可再演进）。
- 构建要求：TSC build.gradle.kts 声明 Java toolchain 21（CI 用 JDK21）；本地需安装 JDK21（tools/jdk-21*）。
