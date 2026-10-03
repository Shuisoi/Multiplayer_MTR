// MMTR 光场：**注入进光影包片元着色器**的那一段（Java 侧 piece F）。
//
// ── 为什么取光必须在片元阶段（notes/344 §17.24）────────────────────────────────
//
// 用户实测：**手持光源**在车厢上完全正常，**固定光源**"不对"。原因是包自己已经把答案写好了：
// 手持光是包在片元阶段逐像素算的（`lib/lighting/heldLighting.glsl`），
// 而我们的固定光源只能从**顶点属性**（光照贴图 UV2）进去 —— MTR 的车体是大面片低模，
// 一个面几个角各采一次、中间线性过渡 ⇒ 面与面对光的反应不同、明暗跟着面跳。
// 这一版把取光搬到与 held light **同一个地方**、用**同一个坐标**：包片元 main() 里的 `playerPos`。
//
// ── 包自己已经把"逐像素世界坐标"算好了，白送 ───────────────────────────────────
// `program/gbuffers_entities.glsl` 的片元 main() 开头：
//     vec3 screenPos = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z);
//     vec3 viewPos   = ScreenToView(screenPos);        // 只反投影，**不读深度纹理**
//     vec3 playerPos = ViewToPlayer(viewPos);          // = 世界坐标 − 相机（与 held light 同口径）
//     …
//     vec2 lmCoordM = lmCoord;                         // ← 我们只改这一行
// 所以不需要新加 varying、不需要深度纹理、不需要 sampler2D 回读 —— Iris 的 `ScreenToView` 是
// `gbufferProjectionInverse` 直接反投影 gl_FragCoord.z，逐像素精确。
//
// ── 值的口径：包把光照贴图值换算成了"等级/15"，我们照抄 ────────────────────────
// 顶点段（`vec2 GetLightMapCoordinates()`）：
//     lmCoord = (iris_LightmapTextureMatrix * vec4(uv2, 0, 1)).xy   // ≈ (block*16+8)/256
//     return clamp((lmCoord − 0.03125) * 1.06667, 0, 1);            // = 等级/15，正好
// 然后顶点段还有一行包自己的约定：`lmCoord.x = min(lmCoord.x, 0.9)`（"降低最大方块光"，包的原话）。
// ⇒ 片元里 `lmCoord * 15.0` 就是等级，反过来 `/15.0` 就回到包的空间，**完全不需要知道
//    iris_LightmapTextureMatrix 的内容**（这正是"与包无关"的关键：不猜矩阵，只按包自己的口径换算）。

uniform usampler2D mmtrLut;
uniform usampler2D mmtrAtlas;
uniform usampler2D mmtrSolid;

/// MTR 这个 draw 的模型矩阵。片元阶段只用它的哨兵（全零 = 这次绘制不是 MTR 优化渲染器 ⇒ 原样返回）。
uniform mat4 mmtrModelMat;

/// **二分定位探针**（Java 侧从 properties 的 `probe=` 上传，2 秒内生效）：
///   0 = 正常
///   1 = 注入的光照**恒定为满亮**（包的口径下 (0.9, 1.0)）—— 面差异若仍在，就与我们的光照无关
///   3 = 光照满亮 + 法线恒定（顶点段认这个值）
///   4 = 方块光**取 max(包发的值, 光场值)**（= §17.23 那个"绝不丢光源"的兜底版；用来 A/B）
///   5 = 整条路**关闭**（原样返回包自己的值，= 只有 per-draw 光）
uniform int mmtrProbe;

/// **光场占比**（0..1，Java 侧从 properties 的 `mix=` 上传，2 秒内生效、不用重启）。
/// 0 = 完全用包/MTR 的 per-draw 光；1 = 完全用光场。两个值分别作用于方块光与天空光。
uniform float mmtrFieldMix;

#define MMTR_LUT_SAMPLER mmtrLut
#define MMTR_ATLAS_SAMPLER mmtrAtlas
#define MMTR_SOLID_SAMPLER mmtrSolid

//@MMTR_CORE_INCLUDE@

/// 逐片元修正光照贴图值：光属于世界，不属于 draw（Flywheel 的语义），所以在**这个像素自己的
/// 世界位置上**取光，而不是在某个顶点上取完再插值。
///
/// @param mmtrLm       包算好的本像素光照贴图值（= 等级/15，x 已被包夹到 0.9）
/// @param mmtrPlayerPos 包算好的本像素"相机相对世界坐标"（= 世界 − 相机，与 held light 同一个量）
vec2 mmtrFragmentLmCoord(vec2 mmtrLm, vec3 mmtrPlayerPos) {
    if (mmtrProbe == 5) {
        // A/B：整条路关闭 ⇒ 只有 per-draw 光（这条路上固定光源能亮，但是"整车一个值"）
        return mmtrLm;
    }
    if (mmtrProbe == 1 || mmtrProbe == 3) {
        // 诊断：光照钉死成"满亮"（包的口径：x 是它自己夹过的 0.9，y 是 1.0）
        return vec2(0.9, 1.0);
    }

    // 哨兵：全零矩阵 = 这次绘制不是 MTR 优化渲染器（原版实体/物品/生物等）⇒ 原样返回。
    // 它们的 per-draw 光本来就是对的，而且它们没有 ModelMat。
    if (mmtrModelMat[3][3] == 0.0) {
        return mmtrLm;
    }

    // 只要光、不要 AO：这里每像素都要跑，AO 的 27 次实心取位在光影包这条路上目前没人消费。
    MmtrField field = mmtrSampleFieldLightOnly(mmtrCameraPosition(), mmtrPlayerPos);
    if (!field.valid) {
        // 这个像素不在光场覆盖范围内（网格外 / 所属 section 没数据）⇒ 原样，行为与没有光场时一致
        return mmtrLm;
    }

    // 包发下来的值：方块光被它夹到 0.9 ⇒ 最高只能表达 13.5 级；天空光是 0..1。
    float mmtrOriginalBlock = mmtrLm.x * 15.0;
    float mmtrOriginalSky = mmtrLm.y * 15.0;

    float mmtrBlock = mix(mmtrOriginalBlock, field.light.x, mmtrFieldMix);
    if (mmtrProbe == 4) {
        // 兜底版（§17.23）：方块光"绝不丢光源"。代价是 MTR 的 per-draw 值会变成整车的地板值。
        mmtrBlock = max(mmtrOriginalBlock, field.light.x);
    }
    float mmtrSky = mix(mmtrOriginalSky, field.light.y, mmtrFieldMix);

    // 回到包的空间：÷15 之后再照抄包自己的 0.9 夹取（x）。
    return vec2(min(mmtrBlock / 15.0, 0.9), clamp(mmtrSky / 15.0, 0.0, 1.0));
}
