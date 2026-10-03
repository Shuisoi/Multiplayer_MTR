// MMTR 光场：**我们自己的 program**（MTR 优化渲染器）这一侧的薄封装。
//
// 只做三件 MTR 特有的事，取光/AO 的全部数学都在共享核心 mmtr_lightfield.glsl 里：
//   1. 把 Sampler3/4/5 声明出来并喂给核心（核心靠 MMTR_*_SAMPLER 宏取名字）；
//   2. (方块光, 天空光) → 光照颜色：用原版光照贴图（带双线性过滤）换算，与 MTR 原来的 per-draw 光同色；
//   3. MTR 的两种"强制点亮"特例与"没有光场数据就退回 per-draw 光"。
//
// ⚠️ 采样器单元 3/4/5 是 MMTR 约定的（MTR 的 ShaderManager 会把 Sampler0..7 从 RenderSystem 取出来绑上，
//    见 notes/344）。光影包开着时这条链路整体不可用（MTR 会改用包自己的 program），那条路走注入，见
//    MmtrShaderPackLightField。

uniform usampler2D Sampler3;
uniform usampler2D Sampler4;
uniform usampler2D Sampler5;

#define MMTR_LUT_SAMPLER Sampler3
#define MMTR_ATLAS_SAMPLER Sampler4
#define MMTR_SOLID_SAMPLER Sampler5

#moj_import <mmtr_lightfield.glsl>

/// (方块光, 天空光) → 光照颜色。
vec3 mmtrFieldColor(vec2 light) {
    return texture(Sampler2, clamp(light / 16.0, 0.5 / 16.0, 15.5 / 16.0)).rgb;
}

/// 这一像素最终用哪个光照：**取到光场就用光场**（Flywheel 的语义：光属于世界，不属于 draw）。
///
/// @param forcedBright  MTR 主动强制点亮的部件（车内 CUTOUT_BRIGHT=0xF000F0 → UV2=(240,240)、
///                      MAX_LIGHT_INTERIOR=0xF000B0 → UV2=(240,176)）：保持原样、也不做 AO
/// @param fallback      per-draw 光照颜色（MTR 原来给的那个 = lightMapColor）
vec4 mmtrFinalLight(bool forcedBright, vec3 camPos, vec3 relativeWorld, vec3 worldNormal, vec4 fallback) {
    if (forcedBright) {
        return fallback;
    }

    MmtrField field = mmtrSampleField(camPos, relativeWorld, worldNormal);
    if (!field.valid) {
        return fallback;
    }

    vec4 fieldColor = vec4(mmtrFieldColor(field.light) * field.ao, 1.0);
    /*
     * **光场只许把车厢照亮，不许凭一个"落在几何里面"的采样点把它压黑**（notes/371）。
     *
     * 采样格点 = floor(世界坐标 − 0.5)，所以车厢贴着站台/道床时，车体的一部分像素落在**实心方块内部**，
     * 那一格的（方块光, 天空光）本来就是 (0, 0) —— 光场没有错，但拿"地底下的光"去画车厢侧面就是纯黑。
     * 判据只取最窄的那一种：**光场说"这一点光都没有"**（两个分量都 0）时，与 MTR 自己给的
     * per-draw 光取较亮的一侧。真正的暗处（隧道里、夜里）MTR 的 per-draw 光本来就是暗的，
     * max() 不会把它提亮；而"数据还没到手 / 采样点埋在方块里"这两种情形下，
     * 结果都不会比"光场不介入"更黑 —— 这正是我们要的不变量。
     */
    if (field.light.x + field.light.y <= 0.0) {
        return max(fieldColor, fallback);
    }
    return fieldColor;
}

// ——————————————————————————————————————————————————————————————————————————————
// MMTR 车灯（notes/345）：灯表的声明与 mmtrHeadlightTerm 全在**共享 include** mmtr_headlight.glsl 里。
//
// 车厢这条路（这个 program）与地形那条路（Sodium 的地形 program，notes/345 §6）**共用同一份数学** ——
// 只有一份实现，所以"给车厢调好的观感"与"给地形调好的观感"不会各自漂开。
// ——————————————————————————————————————————————————————————————————————————————

#moj_import <mmtr_headlight.glsl>
