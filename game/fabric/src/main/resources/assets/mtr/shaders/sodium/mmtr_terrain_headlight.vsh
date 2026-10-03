#version 330 core

// MMTR：这是 **Sodium 地形顶点着色器**的副本（notes/345 §6），加了车灯要用的两个 varying。
// 原始文件在 Sodium 的 jar 里（assets/sodium/shaders/blocks/block_layer_opaque.vsh），
// 由 MmtrSodiumTerrain 在 ShaderLoader 读源码时整体替换掉。
// 除了下面标了 MMTR 的三处，其余与 Sodium 0.5.8 逐字一致（含 #import 与宏的使用方式）。
//
// ⚠️ 必须保持 Sodium 的写法：_vert_init() / u_RegionOffset / _draw_id / VERT_* 宏 都是 Sodium 自己的契约，
//    改一处就等于自己发明了一套顶点格式。
// ⚠️ Sodium 的着色器预处理器只认它自己的 `#import <namespace:path>`，不认原版的 `#moj_import`。

#import <sodium:include/fog.glsl>
#import <sodium:include/chunk_vertex.glsl>
#import <sodium:include/chunk_matrices.glsl>
#import <sodium:include/chunk_material.glsl>

out vec4 v_Color;
out vec2 v_TexCoord;

out float v_MaterialMipBias;
#ifdef USE_FRAGMENT_DISCARD
out float v_MaterialAlphaCutoff;
#endif

#ifdef USE_FOG
out float v_FragDistance;
#endif

// MMTR 车灯（notes/345 §6）：片元里要按世界坐标现算光照，所以在顶点阶段把这两样带下去。
//   mmtrRelWorld     = _vert_position + u_RegionOffset + 16*chunkCoord
//                    = **相机相对世界坐标**（与 fog 的 getFragDistance 同一个口径；
//                      实测 u_RegionOffset = 区块原点 − 相机位置，见 DefaultChunkRenderer.setModelMatrixUniforms）
//   mmtrTerrainShade = _vert_color.rgb（方块 tint × AO，**乘光照贴图之前**）
//                    —— 车灯照亮的是材质本身；乘过光照贴图之后隧道里是全黑的，再乘一次永远是黑的。
out vec3 mmtrRelWorld;
out vec3 mmtrTerrainShade;

uniform int u_FogShape;
uniform vec3 u_RegionOffset;

uniform sampler2D u_LightTex; // The light map texture sampler

vec4 _sample_lightmap(sampler2D lightMap, ivec2 uv) {
    return texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
}

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    // Transform the chunk-local vertex position into world model space
    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

    // MMTR：相机相对世界坐标 + 材质反照率（乘光照贴图之前）传给片元
    mmtrRelWorld = position;
    mmtrTerrainShade = _vert_color.rgb;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(u_FogShape, position);
#endif

    // Transform the vertex position into model-view-projection space
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    // Add the light color to the vertex color, and pass the texture coordinates to the fragment shader
    v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);
    v_TexCoord = _vert_tex_diffuse_coord;

    v_MaterialMipBias = _material_mip_bias(_material_params);
#ifdef USE_FRAGMENT_DISCARD
    v_MaterialAlphaCutoff = _material_alpha_cutoff(_material_params);
#endif
}
