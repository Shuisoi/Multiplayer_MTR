// MMTR 车灯：**注入进光影包片元着色器**的那一段（Java 侧 piece H）。
//
// ── 这一份解决什么（notes/345 §5 的第 3 条边界）──────────────────────────────────
// 光影包开着时，车厢与世界方块都由**包自己的程序**画，MTR 的 mmtr_vehicle_light 与 Sodium 的
// blocks/block_layer_opaque 都不参与 ⇒ 车灯在那条路上根本不存在。这里把车灯的**加性项**插进
// 包自己的 gbuffers 程序，锚点选在包算完光照的那一行之后。
//
// ── 为什么锚点是 DoLighting(...) 之后，而不是改光照贴图值 ─────────────────────────
// 包（Complementary）在 **gbuffers 阶段**就把光照算完了：`DoLighting(color, …, lmCoordM, …)`
// 把 `color` 从"反照率"变成"反照率 × 光照"，随后原样写进 colortex0。所以：
//   · 改 `lmCoordM`（notes/344 §17.24 那条路）只能表达**方块光/天空光两个标量**，
//     表达不了"锥形 × 方向 × 颜色"，而且会连包自己的光照曲线一起动；
//   · 在 DoLighting **之后**加一项，则是纯粹"再多一点光"，与 Sodium 那条路
//     （mmtr_terrain_headlight.fsh：`diffuseColor.rgb += mmtrLight;`）**逐字同构**。
// 灯是函数不是数据（见 mmtr_headlight.glsl 开头），所以两条路能共用同一份数学：
// 这一份与 Sodium 那一份都只是"把 mmtrHeadlightTerm 摆在合适的位置"，数学一个字都不重复。
//
// ── 为什么必须在 DoLighting **之前**捕获反照率 ──────────────────────────────────
// DoLighting 之后 `color` 已经被乘过光照 —— 隧道里那个值是全黑的，拿它当反照率再乘一次灯，
// 灯永远是黑的（Sodium 那条路踩过同一个坑，见 mmtr_terrain_headlight.fsh 第 47 行注释）。
// 所以 Java 侧会在锚点行**前面**插一行，把 DoLighting 之前的 color.rgb 存进一个局部变量
// （名字见下面 mmtrPackHeadlightAdd 的第三个参数），再把它当反照率传给车灯项。
//
// ── 三个坐标/法线从哪来（都是包已经算好的，白送）────────────────────────────────
//   · playerPos     —— 包片元 main() 里的"相机相对世界坐标"（= 世界 − 相机），与 held light 同一口径；
//   · worldGeoNormal—— 包算好的**世界**法线（`normalize(ViewToPlayer(geoNormal * 10000.0))`），
//                      正是 mmtrHeadlightTerm 要的那一种（不是漫反射用的视空间 normalM）；
//   · albedo        —— DoLighting 之前的 color.rgb。
// ⚠️ 口径必须与 MmtrHeadlights 上传的灯表一致：灯位是"世界 − **收集时**的相机"，上传时按
//    收集时→现在的相机位移整体搬一次（见 MmtrHeadlights.upload）。
//
// ⚠️ 本文件由 Java 侧把**独占一行的那条占位行**（形如 `@MMTR_HEADLIGHT_INCLUDE@`，见下面第 40 行）
//    整行换成共享数学 mmtr_headlight.glsl 的内容 —— 它要进的是包自己的源码，而包不认识
//    #moj_import / #import。内联后**不能**出现 `#version` / `#moj_import` / `#import` 开头的行
//    （那两条指令只能出现在文件最前面，Sodium 那份 include 也有同一条约束）。
//
// ⚠️ 上面这条注释**故意不写占位符的完整字样**：替换是"整行相等"匹配的。2026-09-29 实测踩过：
//    早先写的是全文本 String.replace，而注释里正好也写了那个完整字样 ⇒ 共享数学被内联了**两遍**
//    （一遍落进注释、一遍才是真的）⇒ 重复的 uniform/函数定义 ⇒ 包编译不过。见 notes/351。

// 强度系数：与 Sodium 那条路**共用同一个 uniform 名**（Java 侧只有一份上传代码）。
uniform float mmtrTerrainLuxScale;
// 1 = 不做兰伯特（法线不可信时的兜底档）。
uniform int mmtrTerrainFlat;
// 1 = 假色：被车灯照到的地方画成品红（不看贴图与环境光）。
uniform int mmtrTerrainDebug;

//@MMTR_HEADLIGHT_INCLUDE@

/// 把车灯的加性贡献叠到 color 上。
///
/// **必须在包的 `DoLighting(color, …)` 之后调用**，而且 `albedo` 必须是 DoLighting **之前**的
/// `color.rgb`（Java 侧就是这么插的）。
///
/// 与世界 AO、包的阴影的关系：这一项**不参与**它们（加法，落在包算完的光照之上）。
/// 这与 Sodium 那条路的边界一致（notes/345 §5 第 1 条：没有遮挡 = 光穿墙）。
void mmtrPackHeadlightAdd(inout vec4 color, vec3 playerPos, vec3 worldNormal, vec3 albedo) {
    if (mmtrHeadlightCount <= 0) {
        // 附近没有亮着的车头灯 ⇒ 一个字节的代价，包自己的画面完全不受影响
        return;
    }

    // 先算**纯光函数**（反照率当 1）：假色视图与强度系数都建在它上面，
    // 这样"灯到没到这儿"与贴图深浅、与兰伯特（地面只有 0.2 上下）都解耦。
    vec3 normal = mmtrTerrainFlat != 0 ? vec3(0.0) : worldNormal;
    vec3 lux = mmtrHeadlightTerm(playerPos, normal, vec3(1.0));

    if (mmtrTerrainDebug != 0) {
        // 假色：品红 = 被车灯照到，其余全黑。判据与 Sodium 那条路逐字相同：
        // 画面不变 = 注入没进去；全黑 + 品红光斑 = 注入生效了（见 notes/345 §7.20 的验收表）。
        float brightness = max(max(lux.r, lux.g), lux.b) * 4.0;
        color.rgb = vec3(1.0, 0.0, 1.0) * clamp(brightness, 0.0, 1.0);
        return;
    }

    // 加性叠加（不是替换）：包自己的光照那一份照旧，灯只是"再多一点"。
    color.rgb += lux * albedo * mmtrTerrainLuxScale;

    // 灯罩的颜色**不在这里**（2026-10-03）：那是 MTR 逐 draw 的顶点色，包自己的 gbuffers 会照常
    // 读顶点色 ⇒ 红尾灯在光影包下同样正确，这里一行都不用写（notes/374）。
}
