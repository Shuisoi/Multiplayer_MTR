// MMTR 光场：**注入进光影包顶点着色器**的那一段（Java 侧 piece V）。
//
// ── 这一版只做一件事：法线包装。取光整体搬到了**片元**阶段 ────────────────────────
//
// 用户实测（notes/344 §17.23）：「手持光源完全正常，固定光源不行」—— 这就是粒度问题，
// 而包自己已经给出了答案：**手持光是包在片元阶段逐像素算的**
// （`lib/lighting/heldLighting.glsl` 的 `GetHeldLighting(playerPos, …)`：
//   `heldLight = pow2(pow2(heldLight * 0.47 / length(playerPos + relativeEyePosition)))`，
//   最后 `blockLighting = sqrt(pow2(blockLighting) + heldLighting)`）。
// 而我们在顶点阶段改写光照属性：MTR 的车体是**大面片低模**，一个面只有几个角各采一次光、
// 中间线性过渡 ⇒ "取到的光是对的，但落在面上是错的"（相邻面对光的反应不同、明暗跟着面跳）。
//
// 所以取光现在写在 `mmtr_lightfield_pack_frag.glsl` 里，只需要把包片元 main() 里的
// `vec2 lmCoordM = lmCoord;` 换成 `vec2 lmCoordM = mmtrFragmentLmCoord(lmCoord, playerPos);`
// —— `playerPos` 就是包为 held light 算好的那个逐像素"相机相对世界坐标"。
//
// ── 为什么顶点阶段还留着三个恒等包装函数 ────────────────────────────────────────
// 包装是**类型保持**的（vec4→vec4、ivec2→ivec2），包源码一个字都不用改，也不认包的变量命名。
// 它们现在是恒等的：既不丢强制点亮的部件，也不改任何语义。留着它们是为了
//   · 万一某天要回退"逐顶点取光"，只改这里的函数体（而不是重做整套 token 改写）；
//   · 让"改写过的 token"始终是有效表达式，注入失败时包源码保持可编译。
//
// 法线包装仍然在这里，而且**必须**在这里：MTR 的 `Normal` 顶点属性是**模型局部空间**的
// （它自己的 shader 要 `mat3(ModelViewMat * ModelMat) * Normal` 才得到视空间法线），
// 而包按 vanilla 口径期待**视空间**法线（vanilla 实体法线在 CPU 侧就被相机旋转转过，
// 那个 draw 时刻 `iris_NormalMat ≈ 单位矩阵`）。不转换的症状是"车辆建模的不同面对光的反射不同"。

/// MTR 优化渲染器**每个 draw** 的模型矩阵（Java 侧在 `VertexAttributeState.apply()` 时按 draw 上传，
/// 每个 draw 画完再置成全零当哨兵）。
///
/// <p>为什么必须有它：MTR 的车体顶点坐标在 VBO 里是**模型局部坐标**，摆放完全靠这个矩阵
/// （MTR 自己的顶点格式里叫 `ModelMat`，占 location 6..9，每 draw 上传一次）。包的程序里
/// `gl_ModelViewMatrix` 被 Iris 改写成 `iris_ModelViewMat * _iris_internal_translate(iris_ChunkOffset)`
/// —— 那是**相机视图矩阵**，不含 MTR 的模型变换。少了它，法线的"模型局部 → 世界"这一步就是错的。</p>
///
/// <p>哨兵约定：**全零 = 这次绘制不是 MTR 优化渲染器**（原版实体/方块实体等也用同一个
/// `gbuffers_entities` 程序，它们没有 ModelMat，而且法线/光照本来就是对的）⇒ 原样返回。</p>
uniform mat4 mmtrModelMat;

/// 法线空间修正的**实时开关**（Java 侧从 `run/mmtr-lightfield.properties` 的 `normalFix=` 上传，2 秒内生效）。
/// 0 = 关（原样把 MTR 的模型局部法线交给包）；1 = 开（先转成包期待的视空间法线）。
/// 之所以做成开关：这件事的"哪个更好"只能用眼睛在大世界里判，重启一次 3 分钟的代价太高。
uniform int mmtrNormalFix;

/// **二分定位探针**（Java 侧从 properties 的 `probe=` 上传，2 秒内生效），顶点阶段只认 2/3：
///   2 = 法线恒定为 (0,1,0)（用来判断"面差异"是不是法线在驱动）
///   3 = 法线恒定 + 片元阶段光照恒定满亮
/// 其余取值见 mmtr_lightfield_pack_frag.glsl（0 = 正常，1 = 光照满亮，4 = 方块光取 max 兜底，5 = 整条路关闭）。
uniform int mmtrProbe;

/// **法线空间修正**：把 MTR 的"模型局部空间法线"换成包期待的"视空间法线"。
///
/// <p>哨兵与光照一致：`mmtrModelMat` 全零（不是 MTR 的绘制）就原样返回 —— 原版实体的法线本来就是
/// 视空间的，不能被再转一次。</p>
vec3 mmtrNormalFromPack(vec3 mmtrPackNormal) {
    if (mmtrProbe == 2 || mmtrProbe == 3) {
        // 诊断：法线钉死成"朝上"，用来判断"面差异"是不是法线在驱动
        return vec3(0.0, 1.0, 0.0);
    }
    if (mmtrNormalFix == 0 || mmtrModelMat[3][3] == 0.0) {
        return mmtrPackNormal;
    }
    // MTR 的 ModelMat 只有平移 + 绕 Y 旋转（无缩放），所以直接乘即可，不需要逆转置。
    return mat3(mmtrModelMat) * mmtrPackNormal;
}

/// 光照属性值的入口 —— **现在是恒等函数**，取光在片元阶段做（见文件头）。
///
/// <p>为什么保留这个函数而不是把 token 改写删掉：改写是"包无关"的唯一通道（Iris 1.7.2 先完整预处理、
/// 再 AST 改名，所以宏影子属性名结构性无效 —— 见 §17.9）。留着恒等包装，回退时只改函数体。</p>
ivec2 mmtrPickUv2(ivec2 mmtrOriginalUv2) {
    return mmtrOriginalUv2;
}

/// 旧式写法（Complementary 这类）：包用 `gl_MultiTexCoord1`，Iris 会把它换成
/// `vec4(iris_UV2, 0.0, 1.0)`。我们包一层 vec4→vec4，语义与原来完全一致。
vec4 mmtrLightFromPack(vec4 mmtrPackUv2) {
    return vec4(vec2(mmtrPickUv2(ivec2(mmtrPackUv2.xy))), 0.0, 1.0);
}

/// 现代写法：包自己声明 `vaUV2`（ivec2），Iris 的 `Root.rename` 会把它改名成 `iris_UV2`。
/// 局部变量名**绝不能**叫 `vaUV2`/`iris_UV2`，否则会被 Iris 的改名一起改掉。
ivec2 mmtrLightFromIvec2(ivec2 mmtrPackUv2) {
    return mmtrPickUv2(mmtrPackUv2);
}
