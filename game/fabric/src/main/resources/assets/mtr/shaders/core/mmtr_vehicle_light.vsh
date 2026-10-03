#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

// MTR 优化渲染器每个 draw 传进来的"模型矩阵"（顶点格式里的 ModelMat 属性，见
// ShaderManager.MINECRAFT_VERTEX_FORMAT_BLOCK）。它已经把相机位置减掉了，所以它乘出来的就是
// **相机相对世界坐标** —— 光场正是按这个坐标取光。
in mat4 ModelMat;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

// MTR 在 setupShaderBatchState 里把 ModelViewMat 设成 RenderSystem 的 modelView（相机旋转），
// 平移部分由上面的 ModelMat 承担，所以 ModelViewMat * ModelMat * Position = 相机相对世界坐标。
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform mat3 IViewRotMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

// 光场取光用的"相机相对世界坐标"（片元里用）。
// 片元会再加上"相机所在方块坐标"（从 LUT 元数据列读）折回**绝对**世界坐标 —— 格点必须绝对，
// 否则图集里的局部下标会随相机位置的小数部分错位（实测症状："玩家走动时光照图案在车身上滑动"）。
out vec3 mmtrRelWorld;

// 光场 AO 用的"相机相对世界法线"（= 世界法线，只差一个相机平移，方向口径不受影响）。
//
// ⚠️ 与上面漫反射的 relativeNormal **不是**同一个口径，别混：
//   · 漫反射要**视空间**法线（Light0/1_Direction 本来就是视空间的）；
//   · AO 要**世界空间**法线（遮蔽是世界方块的属性，跟镜头朝向无关）。
//   把 AO 也写成视空间法线的话，镜头一转车身遮蔽就会变 —— 与当时把漫反射改成世界法线的错误同型。
out vec3 mmtrWorldNormal;

// MTR 每个 draw 塞进来的光照常量（未除以 16 的原始 UV2）。
// 片元要用它区分"这个部件是不是被 MTR 强制点亮的"（车内 CUTOUT_BRIGHT=0xF000F0、MAX_LIGHT_INTERIOR=0xF000B0）。
flat out ivec2 mmtrLightUv;

void main() {
    // 视空间坐标：这一行必须与 MTR 打补丁后的原版逐字等价
    // （原版是 (MODELVIEWMAT * ModelMat * vec4(Position, 1.0)).xyz，其中 MODELVIEWMAT 就是 ModelViewMat uniform）。
    vec4 mmtrViewSpace = ModelViewMat * ModelMat * vec4(Position, 1.0);

    // 世界相对坐标（相机相对）：**必须再乘一次 IViewRotMat**。
    //
    // ⚠️ 这里有两次踩坑记录，别再改回去（2026-09-28 实测）：
    //   实测 1：mat3(ModelViewMat) 在这个 draw 时刻**是单位矩阵**（日志"本窗口 mat3(ModelViewMat) = 1 0 0 | 0 1 0 | 0 0 1"）。
    //   实测 2：把 ModelMat 的平移按两种口径各自解释成世界坐标，与真实地形比对：
    //            A（只加相机位置）  → y≈99，天上什么都没有
    //            B（再乘 IViewRotMat）→ y≈68，正好是轨道/车体高度
    //          ⇒ 相机旋转在 **ModelMat** 里（MTR 的 StoredMatrixTransformations 是在已经压入视图矩阵的
    //            pose 上做 translate 的），ModelViewMat 只是单位矩阵，所以它不做这个抵消。
    //   所以：viewSpace = ModelViewMat * ModelMat * Position 已经是"视空间"，乘 IViewRotMat 才回到世界相对坐标。
    //   中间我曾因为"ModelViewMat 是单位矩阵"就以为不用乘 IViewRotMat —— 那是错的，症状是光场跟着视角旋转。
    mmtrRelWorld = IViewRotMat * mmtrViewSpace.xyz;
    mmtrLightUv = UV2;

    gl_Position = ProjMat * mmtrViewSpace;

    // 雾保持与 MTR 打补丁后的原版完全一致（球状雾只用长度，所以那个 IViewRotMat 不影响结果）。
    vertexDistance = fog_distance(mat4(1.0), IViewRotMat * mmtrViewSpace.xyz, FogShape);

    // 漫反射用**视空间**法线 —— 这是对的，别再"修"成世界法线（2026-09-28 实测踩坑）。
    //
    // 原版链条（反编译 bytecode，net.minecraft.client.render.DiffuseLighting）：
    //   WorldRenderer → DiffuseLighting.enableForLevel(mat4f)            // mat4f = 相机旋转
    //     → RenderSystem.setupLevelDiffuseLighting(dir0, dir1, mat4f)    // 把两个光照方向按相机旋转转进视空间
    //       → RenderSystem.setShaderLights(viewSpaceDir0, viewSpaceDir1)
    //   ⇒ Light0_Direction / Light1_Direction 是**视空间**的；
    //     原版实体着色器里那个未变换的 `Normal` 属性本身也是视空间（WorldRenderer.renderEntity
    //     把相机旋转压进了模型矩阵）。
    //
    // 我一度以为它们是世界方向，于是写成 `worldNormal = IViewRotMat * relativeNormal` ——
    // 结果是"视空间光照方向 · 世界法线"，**镜头一转整车亮度就变**（恰好就是用户报的那个症状）。
    // MTR 补丁的 mat3(ModelViewMat * ModelMat) 把模型法线带到视空间，与上面的方向口径一致，保持原样。
    vec3 relativeNormal = normalize(mat3(ModelViewMat * ModelMat) * Normal);
    // 世界空间法线：IViewRotMat 是相机旋转的逆矩阵（正交），乘上去就把视空间方向转回世界方向。
    // mat3(ModelViewMat * ModelMat) 里若含非等比缩放，这里严格来说该用逆转置 —— MTR 的 ModelMat
    // 只有平移+绕 Y 旋转（实测 mat3(ModelViewMat) = 单位矩阵），所以直接乘就够；将来若出现缩放需回看这里。
    mmtrWorldNormal = IViewRotMat * relativeNormal;
    // MTR 强制点亮的部件（{@code ALWAYS_ON_LIGHT} = 车灯灯罩 / 显示屏，UV2 = 0xF000F0 = (240,240)）**不吃漫反射**：
    // 它们是**自发光**的 —— 灯罩的颜色是"这块玻璃什么颜色"，不该随太阳方向和车头朝向忽明忽暗
    // （2026-10-03 改口径时加的：灯罩现在由逐 draw 的顶点色染色，若再乘一个 0.4~1.0 的漫反射，
    //  红尾灯会在背光时暗成一个暗红块。见 notes/374）。
    // 灯罩还有第二个判据：**顶点色的 alpha 标志位**（Java 侧 MmtrHeadlights.LENS_ALPHA = 250/255）——
    // 灯罩走哪个 renderStage 都可能变（EXTERIOR / ALWAYS_ON_LIGHT），但"这是灯罩"这个事实
    // 必须让漫反射让路，否则灯色会被太阳方向与车头朝向调暗。
    // 非灯罩的 (240,176) 是 CUTOUT_BRIGHT（车内照明），那是**被照亮**的内装，照旧吃漫反射。
    bool mmtrLampLens = abs(Color.a * 255.0 - 250.0) < 0.5;
    vertexColor = mmtrLampLens || (UV2.x == 240 && UV2.y == 240)
        ? Color
        : minecraft_mix_light(Light0_Direction, Light1_Direction, relativeNormal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    normal = ProjMat * vec4(relativeNormal, 0.0);
}
