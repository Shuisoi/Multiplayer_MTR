# MTR Creator Studio(原版 MTR 4.0.5 作者端)

用途:只跑官方未改动的 MTR-fabric-4.0.5+1.20.4,用来打开内置网页版 Resource Pack Creator,
建模/做车门动画/导出资源包。与 mmtr fork(引擎二次开发)完全隔离,不受 engine jar 更换影响。

## 运行
    gradlew.bat runClient
(需要 JDK 21;`start-creator-studio.bat` 会自动 call `env\workspace.env.bat` 设好 JAVA_HOME=env\jdk-21)
或从工作区根双击 `bin\打开-Creator作者端.bat`。

本工程依赖原版 MTR jar（`vendor\mods\MTR-fabric-4.0.5+1.20.4.jar`），路径由 `build.gradle` 按
`rootDir/../../..` 推算，可用 `-PmcRoot=<工作区根>` 覆盖。

启动后:单人游戏 -> 进入/新建一个世界(网页服务随存档启动) -> 游戏日志/聊天会打印:
    Open the Resource Pack Creator at http://localhost:8888/creator/
用浏览器打开(带末尾斜杠)。

注意:默认端口 8888,若 mmtr 客户端也在运行会占用,此时官方 MTR 会自动换一个空闲端口,
以本实例日志打印的地址为准。建议使用时先关闭 mmtr 客户端。

## 产物
Creator 里 Export 导出的资源包 zip 会写入本工程 run/resourcepacks(游戏资源包目录),
也可直接放到 mmtr 世界的 resourcepacks 使用(格式完全兼容:assets/mtr/...)。
