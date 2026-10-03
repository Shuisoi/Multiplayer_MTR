#version 150

#moj_import <fog.glsl>

// Sampler0 = 车厢贴图，Sampler2 = 原版光照贴图（16×16，u=方块光 v=天空光）。
// 原版只在顶点着色器里声明 Sampler2，我们在这里也要用它把 (block, sky) 换算成颜色，所以这里再声明一次
// （同一个 program 里两个阶段声明同类型 uniform 是合法的）。
uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
// 灯罩**不吃雾**（properties: lampLensFog，由 MmtrHeadlights 每帧上传一次；查不到就是 0）。
uniform float mmtrLampFog;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec4 normal;
// 相机相对世界坐标 / 世界法线（光场按它们取光与 AO）
in vec3 mmtrRelWorld;
in vec3 mmtrWorldNormal;
flat in ivec2 mmtrLightUv;

out vec4 fragColor;

// 光场（Sampler3 = LUT、Sampler4 = 光图集、Sampler5 = 实心位图）+ 取光/AO 的共享实现。
#moj_import <mmtr_lightfield_mtr.glsl>

void main() {
    vec4 color = texture(Sampler0, texCoord0);
    if (color.a < 0.1) {
        discard;
    }
    color *= vertexColor * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    // 车灯要用**材质自身的颜色**（乘光照贴图之前）：灯照亮的是车漆/金属本身，
    // 而乘过光照贴图的颜色已经是"被环境照亮之后的样子"，再乘一次会变成自乘、暗部永远提不亮。
    vec3 mmtrAlbedo = color.rgb;
    // MTR 强制点亮的部件（车灯/显示屏 = getDefaultLight() = 0xF000F0；车内照明 = MAX_LIGHT_INTERIOR = 0xF000B0）。
    // 判据放在共享核心里（mmtrForcedBrightUv2），这样"无光影"和"有光影"两条路用的是同一套口径 ——
    // 之前这里写成了 (240,240)/(240,176)，(240,176) 其实是**交换过**的旧口径，永远不成立，
    // 所以车内照明的强制点亮一直没生效（见 notes/344 §17.15）。
    bool forcedBright = mmtrForcedBrightUv2(mmtrLightUv);
    color *= mmtrFinalLight(forcedBright, mmtrCameraPosition(), mmtrRelWorld, mmtrWorldNormal, lightMapColor);
    // MMTR 车灯（notes/345）：**加性**的一项，与光场（替换光照贴图）互不干扰 ——
    // 所以取不到光场数据的部件（远处、网格外）照样吃得到车灯。
    color.rgb += mmtrHeadlightTerm(mmtrRelWorld, mmtrWorldNormal, mmtrAlbedo);
    // 灯罩（notes/374）：颜色是**逐 draw 的顶点色**（Java 侧 MmtrHeadlights.lampColor 按端 + 档位算，
    // 已在上面 `color *= vertexColor` 里乘进来）。但"红玻璃"与"发光的红灯"是两回事：
    // 用户口径「红了，而且有光，但是灯本身不是发亮的」⇒ 灯罩要**自发光**：亮的直接是灯色，
    // 不吃环境光/漫反射/光场。判据是顶点色的 **alpha 标志位**（Java 侧 LENS_ALPHA = 250/255；
    // 其余部件一律 255）—— 用顶点色捎带标志，是为了让它**跟着几何走**（逐 draw 的 uniform 会留到
    // 同一个 program 的下一次 draw，原版实体就会误吃）。
    bool mmtrLampSurface = abs(vertexColor.a * 255.0 - 250.0) < 0.5;
    if (mmtrLampSurface) {
        // 留一点贴图明暗（0.55 + 0.45 × 反照率亮度）：灯罩不是一个纯色块。
        float mmtrLensLuma = clamp(dot(mmtrAlbedo, vec3(0.2126, 0.7152, 0.0722)) * 1.30, 0.0, 1.0);
        // ★ 这里**不再有"中心白心"**：2026-10-03 试过（按法线朝向判断"正对相机"），
        //   但灯罩是一块正对相机的平面 ⇒ 整块都满足 ⇒ 现场是"发粉"。想在 LDR 里更亮只有
        //   把颜色推向白（=发粉）或加光晕，用户口径最终选了**纯饱和红**（notes/374 §5）。
        color = vec4(vertexColor.rgb * (0.55 + 0.45 * mmtrLensLuma), 1.0);
    }
    // 灯罩**不吃雾**（properties: lampLensFog，默认开）：自发光的东西在夜里被雾拉暗是错的 ——
    // 真车的尾灯正是黑夜里隔几百米唯一看得见的东西。
    fragColor = mmtrLampSurface && mmtrLampFog > 0.5 ? color : linear_fog(color, vertexDistance, FogStart, FogEnd, FogColor);
}
