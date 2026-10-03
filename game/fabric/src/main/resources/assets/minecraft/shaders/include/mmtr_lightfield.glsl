// MMTR 光场：按**世界坐标**取光 + 世界 AO（一份实现，两个消费者）。
//
// 消费者（都必须在 include 本文件**之前**定义下面三个宏，并声明对应类型的采样器 uniform）：
//   #define MMTR_LUT_SAMPLER Sampler3      // 我们自己的 program（MTR 的优化渲染器）
//   #define MMTR_ATLAS_SAMPLER Sampler4
//   #define MMTR_SOLID_SAMPLER Sampler5
//   或者注入进光影包时用自己起的名字（mmtrLut / mmtrAtlas / mmtrSolid），见 MmtrShaderPackLightField。
//
// 语义抄 Flywheel（backend/.../internal/light_lut.glsl）：**光属于世界，不属于 draw**。
// 采样在片元阶段按世界坐标做，所以一个 draw 里每个像素各自有自己的光照值。
//
// 数据（由 MmtrLightField 每帧准备好并上传，常量必须与 Java 侧一致）：
//   LUT   (R32UI)：绝对 section 格号 → 图集槽位（0 = 这个 section 没数据）；
//                  最后一列（x = 25）是元数据：行 0/1/2 = 相机方块坐标（整数），
//                  行 3/4/5 = 小数部分 × 65536（定点），行 6 = AO 强度 × 1000。
//   光图集(R8UI) ：每 section 一个 64×64 槽，槽内 u = x + (z & 3) * 16、v = y + (z >> 2) * 16；
//                  每字节 = 低 4 位方块光 + 高 4 位天空光。
//   实心位图(R8UI)：**一行一个 section**（行号 = 槽位 − 1，行宽 512 字节 = 4096 bit）；
//                  bit = x + (z << 4) + (y << 8)；0 = 不实心（不参与遮蔽）。
//
// ⚠️ 采样格点必须是**绝对世界方块**：调用方传进来的是"相机相对世界坐标"（= 世界 − 相机），
//    这里再加上 LUT 元数据里的**精确**相机位置折回绝对坐标。传整数会在 frac(相机位置) 上留一个
//    周期为 1 格的漂移（实测症状："边走边滑、走一格重置一次"）。

const ivec3 MMTR_GRID_DIM = ivec3(25, 8, 25);
const ivec3 MMTR_GRID_OFFSET = ivec3(12, 4, 12);
const int MMTR_ATLAS_TILES_X = 16;
const int MMTR_TILE = 64;
const int MMTR_LUT_META_COLUMN = 25;
/** 实心位图行宽（字节）= 4096 bit / 8。 */
const int MMTR_SOLID_ROW_BYTES = 512;
const float MMTR_AO_EPSILON = 1e-5;

/// LUT 元数据列里的第 row 行。
/// ⚠️ 不用 floatBitsToUint / intBitsToFloat：GLSL 1.50（MC 用 #version 150）里**不存在**这两个函数，
///    离线编译器直接报 "function is not known"。定点整数编码没有任何位转换，最稳。
float mmtrMetaValue(int row) {
    return float(int(texelFetch(MMTR_LUT_SAMPLER, ivec2(MMTR_LUT_META_COLUMN, row), 0).r));
}

/// 相机**精确**位置 = 整数部分 + 小数部分（定点）。
vec3 mmtrCameraPosition() {
    return vec3(mmtrMetaValue(0), mmtrMetaValue(1), mmtrMetaValue(2))
            + vec3(mmtrMetaValue(3), mmtrMetaValue(4), mmtrMetaValue(5)) * (1.0 / 65536.0);
}

/// AO 强度（0 = 关闭 ⇒ 连 27 次取位都不做，用来做 A/B 性能对照）。
float mmtrAoStrength() {
    return mmtrMetaValue(6) / 1000.0;
}

/// 绝对 section 格号 → 图集槽位（0 = 这个 section 没数据）。
uint mmtrLightSlot(ivec3 cell) {
    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, MMTR_GRID_DIM))) {
        return 0u;
    }
    return texelFetch(MMTR_LUT_SAMPLER, ivec2(cell.x, cell.z * MMTR_GRID_DIM.y + cell.y), 0).r;
}

/// 取单个方块的光照值。第三个分量是"有效"标志：-1 = 这个 section 没有数据。
/// blockPos 必须是**绝对世界**方块坐标（不是相机相对坐标！）。
vec3 mmtrLightAt(ivec3 blockPos, ivec3 gridOrigin) {
    uint slot = mmtrLightSlot((blockPos >> 4) - gridOrigin);
    if (slot == 0u) {
        return vec3(0.0, 0.0, -1.0);
    }

    ivec3 local = blockPos & 15;
    uint slotIndex = slot - 1u;
    ivec2 tileOrigin = ivec2(int(slotIndex % uint(MMTR_ATLAS_TILES_X)), int(slotIndex / uint(MMTR_ATLAS_TILES_X))) * MMTR_TILE;

    // 槽内布局：u = x + (z & 3) * 16，v = y + (z >> 2) * 16
    int u = local.x + (local.z & 3) * 16;
    int v = local.y + (local.z >> 2) * 16;

    // 注意：不能叫 `packed` —— 那是 GLSL 保留字（layout 限定符），编译器会报 syntax error。
    uint packedLight = texelFetch(MMTR_ATLAS_SAMPLER, tileOrigin + ivec2(u, v), 0).r;
    return vec3(float(packedLight & 15u), float((packedLight >> 4u) & 15u), 1.0);
}

/// 这个方块实不实心（AO 用）。section 没数据时一律返回 0（= 不遮蔽）：
/// 宁可"少黑"也不要因为缺数据凭空变黑 —— 缺数据时调用方本来就会退回原来的光照。
uint mmtrSolidAt(ivec3 blockPos, ivec3 gridOrigin) {
    uint slot = mmtrLightSlot((blockPos >> 4) - gridOrigin);
    if (slot == 0u) {
        return 0u;
    }

    ivec3 local = blockPos & 15;
    int bitIndex = local.x + (local.z << 4) + (local.y << 8);
    uint word = texelFetch(MMTR_SOLID_SAMPLER, ivec2(bitIndex >> 3, int(slot) - 1), 0).r;
    return (word >> uint(bitIndex & 7)) & 1u;
}

/// 以 base 为中心的 3×3×3 实心位图（27 次取位，只做一次，之后所有方向/角都是纯 ALU）。
/// bit 序号沿用 Flywheel 的 `index3x3x3(x, y, z) = x + z * 3 + y * 9`，其中 (x,y,z) ∈ {0,1,2}
/// 对应偏移 (x-1, y-1, z-1)。
uint mmtrSolidRing(ivec3 base, ivec3 gridOrigin) {
    uint mask = 0u;
    for (int y = -1; y <= 1; y++) {
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 1; x++) {
                uint bit = mmtrSolidAt(base + ivec3(x, y, z), gridOrigin);
                mask |= bit << uint((x + 1) + (z + 1) * 3 + (y + 1) * 9);
            }
        }
    }
    return mask;
}

/// 一个"角"的 AO：该角周围 4 个方块里有几个实心 ⇒ AO = 1 − 0.2 × 实心数（0.2 .. 1.0）。
/// 与 Flywheel 的 `_flw_validCountToAo(validCount) = 1 − (4 − validCount) × 0.2` 等价。
float mmtrCornerAo(uint solidMask, uint base0, uint base1, uint base2, uint base3, uint corner) {
    uint solidCount = ((solidMask >> (base0 + corner)) & 1u)
            + ((solidMask >> (base1 + corner)) & 1u)
            + ((solidMask >> (base2 + corner)) & 1u)
            + ((solidMask >> (base3 + corner)) & 1u);
    return 1.0 - 0.2 * float(solidCount);
}

/// **一个方向**上的 AO，按 8 个角三线性插值（角 = 方块格点，与光照场同一个 cell）。
/// 三个轴各取一次再按法线分量平方加权 ⇒ 曲面（斜面/弧面）上不会出现"主轴切换处一条硬缝"。
float mmtrAoForDirection(uint solidMask, vec3 interpolant, uint base0, uint base1, uint base2, uint base3) {
    // 角的位序号偏移（index3x3x3 里 cx + 2*cz + 4*cy 的展开）
    float corners[8];
    corners[0] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 0u);
    corners[1] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 1u);
    corners[2] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 3u);
    corners[3] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 4u);
    corners[4] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 9u);
    corners[5] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 10u);
    corners[6] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 12u);
    corners[7] = mmtrCornerAo(solidMask, base0, base1, base2, base3, 13u);

    float ao00 = mix(corners[0], corners[1], interpolant.x);
    float ao01 = mix(corners[2], corners[3], interpolant.x);
    float ao10 = mix(corners[4], corners[5], interpolant.x);
    float ao11 = mix(corners[6], corners[7], interpolant.x);
    return mix(mix(ao00, ao01, interpolant.z), mix(ao10, ao11, interpolant.z), interpolant.y);
}

/// 世界 AO：法线的三个轴各算一次方向 AO，按 n² 加权。
///
/// ⚠️ 这里要的是**世界空间**法线。法线口径别弄反：漫反射要的是**视空间**法线
///    （Light0/1_Direction 是视空间的），AO 要的是世界空间法线（遮蔽是世界的属性）。
float mmtrAmbientOcclusion(ivec3 base, vec3 interpolant, vec3 worldNormal, ivec3 gridOrigin) {
    float strength = mmtrAoStrength();
    if (strength <= 0.0) {
        return 1.0;
    }

    uint solidMask = mmtrSolidRing(base, gridOrigin);
    if (solidMask == 0x7FFFFFFu) {
        // 周围 27 格全实心（片元整个埋在几何里）：不必再算，直接最暗——Flywheel 同样的短路。
        return mix(1.0, 0.2, min(strength, 1.0));
    }

    vec3 n = worldNormal;
    float lengthSquared = dot(n, n);
    if (lengthSquared < 1e-8) {
        n = vec3(0.0, 1.0, 0.0);
    } else {
        n *= inversesqrt(lengthSquared);
    }
    vec3 weight = n * n;

    float ao = 0.0;
    if (n.x > MMTR_AO_EPSILON) {
        ao += weight.x * mmtrAoForDirection(solidMask, interpolant, 1u, 4u, 10u, 13u);
    } else if (n.x < -MMTR_AO_EPSILON) {
        ao += weight.x * mmtrAoForDirection(solidMask, interpolant, 0u, 3u, 9u, 12u);
    }
    if (n.y > MMTR_AO_EPSILON) {
        ao += weight.y * mmtrAoForDirection(solidMask, interpolant, 9u, 12u, 10u, 13u);
    } else if (n.y < -MMTR_AO_EPSILON) {
        ao += weight.y * mmtrAoForDirection(solidMask, interpolant, 0u, 3u, 1u, 4u);
    }
    if (n.z > MMTR_AO_EPSILON) {
        ao += weight.z * mmtrAoForDirection(solidMask, interpolant, 3u, 12u, 4u, 13u);
    } else if (n.z < -MMTR_AO_EPSILON) {
        ao += weight.z * mmtrAoForDirection(solidMask, interpolant, 0u, 9u, 1u, 10u);
    }

    return mix(1.0, ao, min(strength, 1.0));
}

/// 一次光场采样：插值后的 (方块光, 天空光) + 世界 AO + 有没有取到数据。
struct MmtrField {
    vec2 light;
    float ao;
    bool valid;
};

/// 三线性插值取光（与 Flywheel 的 TRI_LINEAR 同构）的实现。
///
/// <p>{@code withAo = false} 时**完全跳过**那 27 次实心取位与 AO 的算术。这一条是给
/// **片元阶段**（注入进光影包的 `mmtrFragmentLmCoord`）用的：那里每个像素都要采一次，
/// 27 次实心取位 + 8 次图集取位 = 35 次取位/像素在 Complementary 这种重包上是不可接受的开销，
/// 而 AO 目前只有"没有光影包"那条路消费（见 notes/344 §17.24）。</p>
///
/// @param camPos       **精确**相机世界坐标（LUT 元数据列里就读得到）
/// @param relativeWorld 相机相对世界坐标（= 世界 − 相机）
/// @param withAo       要不要算世界 AO
/// @param worldNormal  世界空间法线（只在 withAo 时有意义）
MmtrField mmtrSampleFieldCore(vec3 camPos, vec3 relativeWorld, bool withAo, vec3 worldNormal) {
    MmtrField field;
    field.light = vec2(0.0);
    field.ao = 1.0;
    field.valid = false;

    ivec3 gridOrigin = (ivec3(floor(camPos)) >> 4) - MMTR_GRID_OFFSET;
    vec3 exact = relativeWorld + camPos - 0.5;
    ivec3 base = ivec3(floor(exact));
    vec3 interpolant = fract(exact);

    vec3 c000 = mmtrLightAt(base, gridOrigin);
    if (c000.z < 0.0) {
        // 自己所在的 section 都没数据 ⇒ 整个退回调用方原本的光照
        return field;
    }

    vec3 c100 = mmtrLightAt(base + ivec3(1, 0, 0), gridOrigin);
    vec3 c001 = mmtrLightAt(base + ivec3(0, 0, 1), gridOrigin);
    vec3 c101 = mmtrLightAt(base + ivec3(1, 0, 1), gridOrigin);
    vec3 c010 = mmtrLightAt(base + ivec3(0, 1, 0), gridOrigin);
    vec3 c110 = mmtrLightAt(base + ivec3(1, 1, 0), gridOrigin);
    vec3 c011 = mmtrLightAt(base + ivec3(0, 1, 1), gridOrigin);
    vec3 c111 = mmtrLightAt(base + ivec3(1, 1, 1), gridOrigin);

    // 网格边缘会出现取不到的 tap（车在可视范围边上时）。此时退回最近邻、AO 不参与，
    // 行为与"没有 AO 之前"逐位一致 —— 宁可退回可预测的旧行为。
    if (c100.z < 0.0 || c001.z < 0.0 || c101.z < 0.0 || c010.z < 0.0 || c110.z < 0.0 || c011.z < 0.0 || c111.z < 0.0) {
        field.light = c000.xy;
        field.valid = true;
        return field;
    }

    vec2 light00 = mix(c000.xy, c001.xy, interpolant.z);
    vec2 light01 = mix(c010.xy, c011.xy, interpolant.z);
    vec2 light10 = mix(c100.xy, c101.xy, interpolant.z);
    vec2 light11 = mix(c110.xy, c111.xy, interpolant.z);

    vec2 light0 = mix(light00, light01, interpolant.y);
    vec2 light1 = mix(light10, light11, interpolant.y);

    field.light = mix(light0, light1, interpolant.x);
    field.ao = withAo ? mmtrAmbientOcclusion(base, interpolant, worldNormal, gridOrigin) : 1.0;
    field.valid = true;
    return field;
}

/// 三线性取光 + 世界 AO（**没有光影包**那条路用的：MTR 自己的 program，一个像素一次，承担得起）。
///
/// @param worldNormal 世界空间法线（AO 要的是世界口径，不是视空间 —— 见 mmtrAmbientOcclusion）
MmtrField mmtrSampleField(vec3 camPos, vec3 relativeWorld, vec3 worldNormal) {
    return mmtrSampleFieldCore(camPos, relativeWorld, true, worldNormal);
}

/// **只要光、不要 AO** —— 光影包注入的片元阶段用（每像素一次，必须便宜）。
/// AO 的输入（27 次实心取位）在那条路上目前没人消费，所以整段跳掉。
MmtrField mmtrSampleFieldLightOnly(vec3 camPos, vec3 relativeWorld) {
    return mmtrSampleFieldCore(camPos, relativeWorld, false, vec3(0.0, 1.0, 0.0));
}

/// MTR / 原版**强制点亮**的部件：这些顶点的光照属性是"约定常量"而不是世界光照，
/// 必须原样保留，否则车灯、显示屏、车内照明会被世界光压暗 —— 看起来就是"该发光的不发光"。
///
/// 属性口径是 vanilla 的 `(block*16, sky*16)`（见 notes/344 §17.14）：
///   `GraphicsHolder.getDefaultLight()` = `0xF000F0` = `pack(15, 15)` → **(240, 240)**
///      —— `RenderStage.ALWAYS_ON_LIGHT`（车头灯、目的地显示屏、标志灯等"常亮"部件），
///         也是原版"全亮实体"用的同一个值
///   `IGui.MAX_LIGHT_INTERIOR` = `0xF000B0` = `pack(11, 15)` → **(176, 240)**
///      —— `RenderStage.INTERIOR` / `INTERIOR_TRANSLUCENT`（车灯开着时的车内照明；
///         用 11 而不是 15 是 MTR 故意的，注释见 IGui.java:34）
bool mmtrForcedBrightUv2(ivec2 uv2) {
    return (uv2.x == 240 && uv2.y == 240) || (uv2.x == 176 && uv2.y == 240);
}

/// 这个 draw 是不是 **MTR 的"常亮"部件**（`(240, 240)`，即 `0xF000F0`）—— 也就是**车灯玻璃罩那一组几何**
/// （车底 properties 里 `renderStage: ALWAYS_ON_LIGHT` 的部件：车头灯/尾灯灯罩、目的地显示屏等）。
///
/// <p>为什么需要它（notes/373）：尾灯的"红玻璃罩"必须**只染灯罩那几片几何**，车体上一点红晕都不能留
/// （用户口径 2026-10-03：「玻璃罩为白色，其实仅需要红色玻璃罩就行了。不需要红晕」）。
/// 位置/半径都做不到这么准（车灯是嵌在平的车头面上，罩子周围就是车体，同一个距离），
/// 而"这个 draw 是常亮部件"是**逐 draw 的几何事实** —— 灯罩是独立的一组面（实测 saf420 的
/// `headlights` 组 = 两个 0.3465 × 0.1449 × 0.035 m 的小盒子，24 个三角面）。</p>
///
/// <p>与 {@link #mmtrForcedBrightUv2} 的区别：那个把车内照明 `(176, 240)` 也算进来（它同样必须原样保留），
/// 这里**只要车灯/显示屏那一种** —— 车内面板不该被尾灯染红。</p>
bool mmtrAlwaysOnLightUv2(ivec2 uv2) {
    return uv2.x == 240 && uv2.y == 240;
}

/// 这个"相机相对世界坐标"所在的 section 在光场里**有没有数据**。
///
/// <p>只查槽位，不做插值、不碰 AO —— 便宜的覆盖判定，专门给"逐 draw 一致性"用：
/// 一个 draw（部件）的顶点如果一半落在跟踪到的 section、一半落在没跟踪的，就会出现
/// "一半取光场、一半退回 per-draw 常量"的**混用**，看过去就是同一辆车按面硬跳。
/// 在 draw 的**原点**上先判一次，就能让一个部件整体走同一条路。</p>
bool mmtrSectionTracked(vec3 camPos, vec3 relativeWorld) {
    ivec3 gridOrigin = (ivec3(floor(camPos)) >> 4) - MMTR_GRID_OFFSET;
    vec3 exact = relativeWorld + camPos - 0.5;
    return mmtrLightSlot((ivec3(floor(exact)) >> 4) - gridOrigin) != 0u;
}
