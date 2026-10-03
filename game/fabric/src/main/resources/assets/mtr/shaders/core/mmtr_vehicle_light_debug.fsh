#version 150

// 诊断用假色版本（run/mmtr-lightfield.properties 里 debug=true 时由 PatchingResourceProviderMixin 换上）：
//   红 = 方块光 / 15，绿 = 天空光 / 15，蓝 = AO（0.2 最暗 → 0，1.0 无遮挡 → 1），
//   灰 = 这个像素取不到光场数据，纯蓝 = MTR 强制点亮的部件。
// AO 关掉时（properties 里 ao=0）蓝色恒为 1 —— 这也正是 A/B 性能对照的开关。

#moj_import <fog.glsl>

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;

in float vertexDistance;
in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec4 normal;
in vec3 mmtrRelWorld;
in vec3 mmtrWorldNormal;
flat in ivec2 mmtrLightUv;

out vec4 fragColor;

#moj_import <mmtr_lightfield_mtr.glsl>

void main() {
    if (mmtrLightUv.x == 240 && (mmtrLightUv.y == 240 || mmtrLightUv.y == 176)) {
        fragColor = vec4(0.0, 0.0, 1.0, 1.0);
        return;
    }

    MmtrField field = mmtrSampleField(mmtrCameraPosition(), mmtrRelWorld, mmtrWorldNormal);
    if (!field.valid) {
        fragColor = vec4(0.25, 0.25, 0.25, 1.0);
        return;
    }

    // AO 归一到 0..1：0.2（最暗）→ 0，1.0（无遮挡）→ 1，好一眼看出遮蔽。
    float ao = clamp((field.ao - 0.2) / 0.8, 0.0, 1.0);
    fragColor = vec4(clamp(field.light.x / 15.0, 0.0, 1.0), clamp(field.light.y / 15.0, 0.0, 1.0), ao, 1.0);
}
