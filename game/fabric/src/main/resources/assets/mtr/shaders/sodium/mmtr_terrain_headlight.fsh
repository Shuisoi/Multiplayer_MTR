#version 330 core

// MMTR：这是 **Sodium 地形片元着色器**的副本（notes/345 §6），加了车灯的加性项。
// 原始文件在 Sodium 的 jar 里（assets/sodium/shaders/blocks/block_layer_opaque.fsh），
// 由 MmtrSodiumTerrain 在 ShaderLoader 读源码时整体替换掉。
// 除标了 MMTR 的几处，其余与 Sodium 0.5.8 逐字一致。
//
// 覆盖范围 = **所有世界方块**（道床/隧道壁/站台/草/树叶/水…都走这一对 vsh/fsh，只是 defines 不同）。
// 这就是"车灯真的能照明"的那一半：车厢那条路（mmtr_vehicle_light）照不到地形。

#import <sodium:include/fog.glsl>
#import <sodium:include/mmtr_headlight.glsl>

in vec4 v_Color; // The interpolated vertex color
in vec2 v_TexCoord; // The interpolated block texture coordinates
in float v_FragDistance; // The fragment's distance from the camera

in float v_MaterialMipBias;
in float v_MaterialAlphaCutoff;

// MMTR：相机相对世界坐标 / 材质反照率（乘光照贴图之前）
in vec3 mmtrRelWorld;
in vec3 mmtrTerrainShade;

uniform sampler2D u_BlockTex; // The block texture

uniform vec4 u_FogColor; // The color of the shader fog
uniform float u_FogStart; // The starting position of the shader fog
uniform float u_FogEnd; // The ending position of the shader fog

// MMTR 车灯的三个调参口（由 MmtrHeadlights 每帧上传，改 properties 2 秒生效）
uniform float mmtrTerrainLuxScale; // 地形这一侧的强度系数（与车厢分开调）
uniform int mmtrTerrainFlat;       // 1 = 不做兰伯特（没有法线信息时的兜底档）
uniform int mmtrTerrainDebug;      // 1 = 假色：只看"车灯照到哪儿了"（不受贴图/环境光干扰）

out vec4 fragColor; // The output fragment for the color framebuffer

void main() {
    vec4 diffuseColor = texture(u_BlockTex, v_TexCoord, v_MaterialMipBias);

#ifdef USE_FRAGMENT_DISCARD
    if (diffuseColor.a < v_MaterialAlphaCutoff) {
        discard;
    }
#endif

    // MMTR 车灯：**反照率取在乘光照贴图之前**（乘过之后隧道里是全黑的，再乘一次永远是黑的）。
    vec3 mmtrAlbedo = diffuseColor.rgb * mmtrTerrainShade;
    // 法线：Sodium 的地形顶点格式没有法线，用屏幕空间导数重建面法线（见 mmtr_headlight.glsl）。
    vec3 mmtrNormal = mmtrTerrainFlat != 0 ? vec3(0.0) : mmtrGeometricNormal(mmtrRelWorld);
    // 先算**纯光函数**（反照率当 1），再乘材质：这样假色视图看到的是"光到没到这儿"，与贴图深浅无关。
    vec3 mmtrSum = mmtrHeadlightTerm(mmtrRelWorld, mmtrNormal, vec3(1.0));

    if (mmtrTerrainDebug != 0) {
        // 假色：被车灯照到的地方是品红，其余全黑。用它验证"光斑落在地上了没有"，
        // 不用挑夜晚/隧道、也不受贴图与环境光干扰。（地形**没换**成这一份时，画面看上去与平常一样
        // ⇒ "全黑 + 品红光斑" 本身就是"补丁生效了"的证据。）
        float mmtrBrightness = max(max(mmtrSum.r, mmtrSum.g), mmtrSum.b) * 4.0;
        fragColor = vec4(vec3(1.0, 0.0, 1.0) * clamp(mmtrBrightness, 0.0, 1.0), 1.0);
        return;
    }

    vec3 mmtrLight = mmtrSum * mmtrAlbedo * mmtrTerrainLuxScale;

    // Apply per-vertex color
    diffuseColor.rgb *= v_Color.rgb;

    // Apply ambient occlusion "shade"
    diffuseColor.rgb *= v_Color.a;

    // MMTR 车灯：**加性**叠加（加法而不是替换 —— 环境光那一份照旧，灯只是"再多一点"）
    diffuseColor.rgb += mmtrLight;

    fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);
}
