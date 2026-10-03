// MMTR 车灯（notes/345）：把模型里的 mmtr_light_<cab>_<n> 锚点当成**逐片元的光源**。
//
// ⚠️ 本文件有**第二份副本**：`assets/sodium/shaders/include/mmtr_headlight.glsl`。
//    两份必须**逐字一致**（改一处就 `Copy-Item` 过去）：mixin 供出去的是这一份
//    （{@code MmtrSodiumTerrain.SHARED_INCLUDE_PATH}），而那一份是 Sodium 的 `#import` 解析
//    真去查资源时**不至于崩**的兜底（notes/350：include 找不到会当场抛异常）。
//
// 为什么不是"往世界里放光"：方块光是烘进几何的（16 级整数场 + 顶点光），改它就等于把光做成"数据"，
// 粒度必然是一格、而且一移动就要重烘网格。灯要的是连续的锥形衰减与半影 —— 那只能把光做成**函数**。
//
// 这一份是**共享数学**，两条路都包含它（必须只有一份，否则数学会漂）：
//   · MTR 那条路（车厢 + 3D 钢轨）：assets/mtr/shaders/core/mmtr_vehicle_light.fsh
//     —— 经 mmtr_lightfield_mtr.glsl 以 #moj_import 引入（原版加载器）。
//   · 世界方块那条路（Sodium 的地形 program）：assets/mtr/shaders/sodium/mmtr_terrain_headlight.fsh
//     —— 以 #import <sodium:include/mmtr_headlight.glsl> 引入，由 MmtrSodiumTerrain 把**这一份文件的内容**
//     当作那个标识符的源码交给 Sodium（见 notes/345 §6）。
//
// 由 MmtrHeadlights 每帧上传一次（**全局**，不随 draw 变，所以一个 draw 里每个像素都吃同一份灯表）：
//   Pos   = xyz 相机相对世界位置, w = 1/射程（先算好，省一次除法）
//   Dir   = xyz 单位方向,        w = cos(外锥角)
//   Color = rgb 颜色×强度,       a = cos(内锥角)
//
// ⚠️ **灯罩自己的颜色不走这里**（2026-10-03 起）：那是 MTR 的**逐 draw 顶点色**
//    （{@code PartCondition.MMTR_LAMP} 那一组几何，颜色由 MmtrHeadlights.lampColor 按端 + 档位算）。
//    这里只负责"灯往世界里投的那一份光"，见 notes/374。
//
// ⚠️ 数组长度 8 **必须**与 MmtrHeadlights.MAX_LIGHTS 一致。
// ⚠️ 这里的坐标口径是"相机相对世界坐标"（MTR 那条路是 mmtrRelWorld，地形那条路是
//    _vert_position + u_RegionOffset + 16*chunkCoord —— 两者是同一个空间）。
// ⚠️ 本文件里**不能**出现原版那两条预处理指令（moj_import / version）：Sodium 的预处理器不认它们
//    （它只按自己的 #import 展开，见 MmtrSodiumTerrain）。

uniform int mmtrHeadlightCount;
uniform vec4 mmtrHeadlightPos[8];
uniform vec4 mmtrHeadlightDir[8];
uniform vec4 mmtrHeadlightColor[8];

/// 车灯对这一个像素的**加性**贡献（已经乘上材质反照率）。
///
/// @param relativeWorld 相机相对世界坐标
/// @param worldNormal   世界空间法线（**世界**口径，与 AO 同一个；不是漫反射那个视空间法线）。
///                      传 0 向量 = "没有法线信息" ⇒ 不做兰伯特（各向同性照亮），
///                      这是地形那条路的 A/B 兜底档（有些顶点格式根本不带法线）。
/// @param albedo        材质自身颜色（**乘光照贴图之前**的 color）
vec3 mmtrHeadlightTerm(vec3 relativeWorld, vec3 worldNormal, vec3 albedo) {
    if (mmtrHeadlightCount <= 0) {
        return vec3(0.0);
    }

    vec3 normal = worldNormal;
    float normalLengthSquared = dot(normal, normal);
    bool hasNormal = normalLengthSquared > 1e-8;
    if (hasNormal) {
        normal *= inversesqrt(normalLengthSquared);
    }

    vec3 sum = vec3(0.0);
    for (int index = 0; index < 8; index++) {
        if (index >= mmtrHeadlightCount) {
            break;
        }

        vec3 offset = mmtrHeadlightPos[index].xyz - relativeWorld;
        float distance = length(offset);
        if (distance < 1e-4) {
            continue;
        }
        vec3 toLight = offset / distance;

        // 锥形：内锥里是 1、外锥外是 0，中间是半影（smoothstep 的连续过渡 —— 这就是"平滑"的来源）。
        // 灯朝外射，所以判据是"灯→片元"与朝向的夹角 = dot(-toLight, dir) = dot(toLight, -dir)。
        float cone = smoothstep(mmtrHeadlightDir[index].w, mmtrHeadlightColor[index].a, dot(toLight, -mmtrHeadlightDir[index].xyz));
        if (cone <= 0.0) {
            continue;
        }

        // 二次衰减、到射程正好归零：不发散、不会在远处留一片灰。
        float attenuation = clamp(1.0 - distance * mmtrHeadlightPos[index].w, 0.0, 1.0);
        attenuation *= attenuation;

        // 兰伯特：只照朝向灯的那一面。没有法线信息时退化成"各向同性"（见上面 @param worldNormal）。
        float lambert = hasNormal ? max(dot(normal, toLight), 0.0) : 1.0;

        sum += mmtrHeadlightColor[index].rgb * (cone * attenuation * lambert);
    }
    return albedo * sum;
}

// 【已删】mmtrMarkerGlow —— "在半径内把灯罩染成灯色"那条路（notes/373）。
//
// 为什么删（2026-10-03，notes/374）：它要同时凑齐一整套条件才生效 —— 着色器得是最新的一份、
// 当前 program 得认得那几个 uniform、这盏灯得挤进 8 个槽位、灯罩还得离相机在射程 + 64 m 之内，
// 于是现场表现就是"一会儿红一会儿不红"，只能靠猜。用户口径：「不用切，直接改颜色就行了」。
//
// 现在灯罩的颜色是 MTR 优化渲染器**逐 draw 的顶点色**（{@code PartCondition.MMTR_LAMP} 那一组几何，
// 颜色 = 端 + 档位，见 {@code MmtrHeadlights.lampColor}）⇒ 与"灯在不在 8 个槽位里""离相机多远"
// 全都无关，也不需要着色器参与。本文件只保留"灯往世界里投的那一份光"。

/// 从屏幕空间导数重建**世界法线**。
///
/// 为什么需要它：Sodium 的地形顶点格式里**没有法线**（原版地形的方向性明暗早就烘进顶点色了，
/// notes/345 §6 实测 chunk_vertex.glsl 只有 position/texcoord/lightcoord/color）。
/// 体素地形全是平面四边形，导数重建出来的就是这个面的**真法线**（不是近似）。
///
/// 符号不靠 gl_FrontFacing 判：导数重建法线的符号依赖 GL 的手性与绕序约定，而"朝向相机"没有歧义
/// —— 相机就在这个空间的原点。
vec3 mmtrGeometricNormal(vec3 relativeWorld) {
    vec3 geometric = cross(dFdx(relativeWorld), dFdy(relativeWorld));
    float lengthSquared = dot(geometric, geometric);
    if (lengthSquared < 1e-12) {
        // 退化（比如单个像素的岛）：给一个朝上的法线，总比 0 向量好。
        return vec3(0.0, 1.0, 0.0);
    }
    geometric *= inversesqrt(lengthSquared);
    return dot(geometric, relativeWorld) > 0.0 ? -geometric : geometric;
}
