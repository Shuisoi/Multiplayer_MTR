package org.mtr.mod.render.light;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import org.lwjgl.BufferUtils;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.render.tool.Utilities;
import org.mtr.mod.Init;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * MMTR 光场 —— 复刻 Flywheel「把世界光照搬到 GPU，按世界坐标在着色器里取光」的方案（车辆子集）。
 *
 * <p>背景：MMTR 的优化渲染器（{@code org.mtr.mapping.render.*}）把车厢几何放进静态 VBO，光照值是
 * **每个 draw 一个常量顶点属性**（{@code VertexAttributeState.lightmapUV} → {@code glVertexAttribI2i}），
 * 而整辆车共用 {@code PositionAndRotation.getLight()} 在**车中心**采到的那一个值 ⇒ 车头/车尾亮度一样。
 * 这不是"一 draw 一光值"的物理上限，而是"光跟着 draw 走"的必然结果。</p>
 *
 * <p>本类的做法与 Flywheel 一致：光属于**世界**，不属于模型。把车辆所在 section 的光照数据
 * （16³ 方块，1 字节/方块：低 4 位方块光 + 高 4 位天空光）采集、上传成一张 R8UI 图集，
 * 再用一张 R32UI 的 LUT 把"相机相对 section 坐标 → 图集槽位"映射出来；着色器在**片元**阶段
 * 用插值后的世界坐标取光，与 draw 数完全无关。</p>
 *
 * <p>坐标口径（与着色器里的常量必须一致）：着色器拿到的顶点坐标是**相机相对世界坐标**
 * （{@code ModelViewMat * ModelMat * Position}，其中 {@code ModelMat} 是 MTR 每个 draw 传的矩阵，
 * 由 {@code StoredMatrixTransformations} 减掉相机位置得到）。所以 section 也按"相机相对"来算，
 * 相机位置的小数部分会让一个世界 section 落到 1~2 个相机相对 section 里，LUT 因此按轴向最多写 8 格，
 * 并且**每帧重建**（486 个 int，可忽略）。</p>
 *
 * <p>优雅降级：LUT 里没有数据的格子取到 0，着色器就退回原来的 per-draw 光照值（{@code max()}），
 * 也就是"没接上光场 = 和现在一模一样"。光影包（Iris/OptiFine）启用时 MTR 会走回原生 RenderLayer 路径，
 * 我们的 shader 根本不会被加载，此时这里直接不做任何事。</p>
 */
public final class MmtrLightField {

	/**
	 * 总开关：{@code -Dmmtr.lightfield.disabled=true} 或 run 目录 properties 里的 {@code enabled=false}
	 * 都会让光场彻底不插手（着色器不换、数据不传），用来做"有/无光场"的对照。
	 *
	 * <p>properties 那一份是**运行时**的：它是"不增加 draw call"这条要求的 A/B 开关 ——
	 * 同一机位、同一帧下切 开/关，再比 {@code draws/frame}、{@code batches/frame} 与帧时。
	 * 注意切它需要一次资源重载（MTR 只在重载时重新请求着色器），这个自动做（见 {@link #maybeCaptureScreenshot}）。</p>
	 */
	public static boolean isEnabled() {
		return !Boolean.getBoolean("mmtr.lightfield.disabled") && diagnosticsEnabled;
	}

	private static boolean diagnosticsEnabled = true;

	/**
	 * 现在是不是"光影包在跑"（MTR 会改用包自己的程序）。
	 *
	 * <p>这个状态下：① 我们那份 core shader 不会被 MTR 用；② 车厢的光由注入进**包片元着色器**的代码负责
	 * （{@link MmtrShaderPackLightField}，逐片元按世界坐标取光 —— 与包的 held light 同一条路）；③ 我们的三个采样器**不能**走
	 * {@code setShaderTexture(3/4/5)}（那是 Iris 分给 colortex/shadowtex 的单元），改由
	 * {@link #bindPackSamplers} 指到程序里没人用的空闲单元。</p>
	 */
	public static boolean isUnderShaderpack() {
		return !Utilities.canUseCustomShader();
	}

	// —— 车灯开关的读口（notes/345）。全部来自 refreshDiagnostics 那份 properties ——

	/** 车灯总开关。 */
	public static boolean headlightsEnabled() {
		return diagnosticsHeadlights;
	}

	public static float headlightLux() {
		return (float) diagnosticsHeadlightLux;
	}

	public static float headlightTailLux() {
		return (float) diagnosticsHeadlightTailLux;
	}


	public static double headlightRangeM() {
		return diagnosticsHeadlightRangeM;
	}

	public static double headlightConeDeg() {
		return diagnosticsHeadlightConeDeg;
	}

	public static double headlightPenumbraDeg() {
		return diagnosticsHeadlightPenumbraDeg;
	}

	public static String headlightMode() {
		return diagnosticsHeadlightMode;
	}

	/**
	 * 近光档的白灯强度是远光档的多少倍（properties: {@code headlightDayRatio}，默认 0.45）。
	 *
	 * <p>键名保持 {@code headlightDayRatio}（不改名 ⇒ 老 properties 文件继续有效），
	 * 但 2026-10-03 起这两档的名字就是灯的**种类**：<b>近光 = 白天那一档（较暗）、远光 = 夜间那一档（较亮）</b>。
	 * 绝对量仍是 {@code headlightLux}（= 远光那一档），这里只管**两档之比** ——
	 * 一个调"有多亮"，一个调"近/远差多少"，改哪一个都不会把另一个带跑。</p>
	 */
	public static float headlightDayRatio() {
		return (float) diagnosticsHeadlightDayRatio;
	}

	// —— 地形（世界方块）那一侧的车灯开关（notes/345 §6）：Sodium 的地形 program 也吃同一份灯表 ——

	/**
	 * 灯罩自发光的**亮度倍数**（properties: {@code lampLensGain}，默认 1.5；0 = 关掉自发光那一项）。
	 *
	 * <p>灯罩的颜色由 {@code MmtrHeadlights.lampColor} 按端 + 档位算出来，再**逐个 draw** 交给着色器
	 * （{@code mmtrLampTint}，见 {@code MmtrDrawColor}）：着了色的灯罩不再吃环境光，亮的直接是灯色。
	 * 这个倍数就是"有多亮"：1.0 = 就是玻璃本身的颜色（看起来像一块红玻璃），
	 * 1.5 = 明显在发光（默认），2.5 = 刺眼。**改一行 2 秒生效、不用重启**。</p>
	 */
	public static float lampLensGain() {
		return (float) diagnosticsLampLensGain;
	}


	/** 灯罩吃不吃雾（properties: {@code lampLensFog}，默认 true = 不吃）。自发光的东西不该被雾拉暗。 */
	public static boolean lampLensFog() {
		return diagnosticsLampLensFog;
	}

	/** 地形吃不吃车灯。关掉 = 上传时把 {@code mmtrTerrainLuxScale} 压成 0（着色器不用重编，2 秒生效）。 */
	public static boolean terrainHeadlightsEnabled() {
		return diagnosticsTerrainHeadlights;
	}

	/** 地形这一侧的强度系数（与车厢分开调：车厢 1.0 好看不代表地面 1.0 好看）。 */
	public static double terrainLuxScale() {
		return diagnosticsTerrainLuxScale;
	}

	/** 假色诊断：被车灯照到的地形画成品红、其余全黑（验证"光斑落在地上了没有"，不受贴图/环境光干扰）。 */
	public static boolean terrainDebug() {
		return diagnosticsTerrainDebug;
	}

	/** true = 地形不做兰伯特（没有法线信息时的兜底档，见 {@code mmtr_headlight.glsl}）。 */
	public static boolean terrainNormalFlat() {
		return diagnosticsTerrainNormalFlat;
	}

	/** 读一个 double 属性，越界夹紧、写坏回默认（一行写错不该让整个读口失效）。 */
	private static double propertyDouble(Properties properties, String key, double fallback, double minimum, double maximum) {
		try {
			return Math.max(minimum, Math.min(maximum, Double.parseDouble(properties.getProperty(key, String.valueOf(fallback)).trim())));
		} catch (Exception ignored) {
			return fallback;
		}
	}

	/**
	 * 诊断开关写在 <b>run 目录</b>的 {@code mmtr-lightfield.properties} 里：
	 * <pre>
	 *   debug=true    # 换上假色片元着色器（红=方块光 绿=天空光 灰=没数据 蓝=MTR 强制亮）；改完按 F3+T 重载资源
	 *   screenshot=6  # 自动截 N 张（每 2 秒一张）到 run/screenshots/
	 * </pre>
	 * 不用 {@code -D} 的原因：dev 客户端是 gradle **daemon** 派生出来的，
	 * 早先启动的 daemon 不会继承新设的 JAVA_TOOL_OPTIONS，参数会被静默吞掉（实测 14:12 那次）。
	 */
	private static final String DIAGNOSTICS_FILE = "mmtr-lightfield.properties";
	private static final long DIAGNOSTICS_INTERVAL_MILLIS = 2000L;

	private static long diagnosticsReadMillis;
	private static boolean diagnosticsDebug;
	private static int diagnosticsScreenshotCount;
	/**
	 * AO 强度 × 1000（0 = 关闭，1000 = 全强度），来自 properties 的 {@code ao=}。
	 * 它被写进 LUT 元数据列第 6 行，着色器读它决定要不要做那 27 次实心取位
	 * —— 于是"开/关 AO 的性能对照"不需要重启客户端、也不需要两份着色器。
	 */
	private static int diagnosticsAoStrengthPermille = 1000;
	/** 光影包里"法线空间修正"的实时开关（properties: normalFix）。上传成 uniform，改完 2 秒内生效、不用重启。 */
	private static boolean diagnosticsNormalFix;
	/** 二分定位探针（properties: probe）。0=正常 1=光照恒亮 2=法线恒定 3=两者恒定。 */
	private static int diagnosticsProbe;
	/** 光场占比（properties: mix，0..1）。0 = MTR 原样 per-draw 光，1 = 纯光场（逐片元）。 */
	private static float diagnosticsFieldMix = 1.0F;

	// —— MMTR 车灯（notes/345）：锚点 → 逐片元光源。开关与光场共用这一份 properties 读口 ——
	/** 车灯总开关（properties: headlights）。关掉 = 着色器里那一项不生效（光照本身仍在）。 */
	private static boolean diagnosticsHeadlights = true;
	private static double diagnosticsHeadlightLux = 1.0;
	private static double diagnosticsHeadlightRangeM = 40.0;
	private static double diagnosticsHeadlightConeDeg = 14.0;
	private static double diagnosticsHeadlightPenumbraDeg = 26.0;
	private static double diagnosticsHeadlightTailLux = 1.2;

	/**
	 * auto = 按**灯光开关 + 端 + 换向器**判每一盏灯（notes/352）；all = 全当白前照灯（看灯本身长什么样）；
	 * off = 全灭。
	 */
	private static String diagnosticsHeadlightMode = "auto";
	/** 日间档相对夜间档的强度比（properties: headlightDayRatio）。 */
	private static double diagnosticsHeadlightDayRatio = 0.45;
	/** 废弃键的点名只做一次（refreshDiagnostics 每 2 秒跑一次）。 */
	private static boolean headlightAmbiguousWarned;
	// —— 地形（世界方块）那一侧（notes/345 §6）：车厢那条 program 照不到道床/隧道壁/站台 ——
	/** 地形吃不吃车灯（properties: terrainHeadlights）。 */
	private static boolean diagnosticsTerrainHeadlights = true;
	private static double diagnosticsTerrainLuxScale = 1.0;
	private static boolean diagnosticsTerrainDebug;
	/** true = 地形不做兰伯特（properties: terrainNormal=flat；默认 derivative = 屏幕空间导数重建面法线）。 */
	private static boolean diagnosticsTerrainNormalFlat;
	/** 灯罩自发光的亮度倍数（properties: lampLensGain，默认 1.0 = 就是灯色本身；调大 = 整块推向白）。 */
	private static double diagnosticsLampLensGain = 1.0;

	/** 灯罩吃不吃雾（properties: lampLensFog，默认 true = 不吃雾）。 */
	private static boolean diagnosticsLampLensFog = true;
	/** 诊断用：改这个值（任意新字符串）就会触发一次资源重载 —— 让"切换假色 shader"不用人来按 F3+T。 */
	private static String diagnosticsReloadNonce = "";

	/** 假色诊断模式（由 PatchingResourceProviderMixin 在加载着色器时读取）。 */
	public static boolean isDebugDiagnostics() {
		refreshDiagnostics();
		return diagnosticsDebug;
	}

	private static void refreshDiagnostics() {
		final long now = System.currentTimeMillis();
		if (now - diagnosticsReadMillis < DIAGNOSTICS_INTERVAL_MILLIS) {
			return;
		}
		diagnosticsReadMillis = now;

		try {
			final File file = new File(MinecraftClient.getInstance().runDirectory, DIAGNOSTICS_FILE);
			if (!file.isFile()) {
				return;
			}
			final Properties properties = new Properties();
			try (InputStream inputStream = new FileInputStream(file)) {
				properties.load(inputStream);
			}
			diagnosticsDebug = Boolean.parseBoolean(properties.getProperty("debug", "false").trim());
			diagnosticsScreenshotCount = Integer.parseInt(properties.getProperty("screenshot", "0").trim());
			diagnosticsReloadNonce = properties.getProperty("reload", "").trim();
			final double aoStrength = Math.max(0, Math.min(1, Double.parseDouble(properties.getProperty("ao", "1.0").trim())));
			diagnosticsAoStrengthPermille = (int) Math.round(aoStrength * 1000);
			diagnosticsEnabled = Boolean.parseBoolean(properties.getProperty("enabled", "true").trim());
			// 光影包里"法线空间修正"的实时开关（normalFix=1 打开）。见 notes/344 §17.19。
			diagnosticsNormalFix = Boolean.parseBoolean(properties.getProperty("normalFix", "false").trim());
			// 二分定位探针（probe=0..5，见 MmtrShaderPackLightField 与注入里的 mmtrFragmentLmCoord）。见 notes/344 §17.21。
			try {
				diagnosticsProbe = Integer.parseInt(properties.getProperty("probe", "0").trim());
			} catch (Exception ignored) {
				diagnosticsProbe = 0;
			}
			// 光场占比旋钮（mix=0..1，逐片元）。见 notes/344 §17.22 / §17.24。
			try {
				diagnosticsFieldMix = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(properties.getProperty("mix", "1.0").trim())));
			} catch (Exception ignored) {
				diagnosticsFieldMix = 1.0F;
			}

			// 车灯（notes/345）。每一个都单独兜底：写坏一行只该让那一项回到默认，不该让整个读口失效。
			diagnosticsHeadlights = Boolean.parseBoolean(properties.getProperty("headlights", "true").trim());
			diagnosticsHeadlightLux = propertyDouble(properties, "headlightLux", 1.0, 0.0, 10.0);
			diagnosticsHeadlightRangeM = propertyDouble(properties, "headlightRangeM", 40.0, 1.0, 400.0);
			diagnosticsHeadlightConeDeg = propertyDouble(properties, "headlightConeDeg", 14.0, 0.5, 89.0);
			diagnosticsHeadlightPenumbraDeg = propertyDouble(properties, "headlightPenumbraDeg", 26.0, 1.0, 89.0);
			diagnosticsHeadlightTailLux = propertyDouble(properties, "headlightTailLux", 1.2, 0.0, 10.0);

			// notes/352：**日间档比夜间档暗**（用户口径"日间较低亮度、夜间较高亮度"）。
			diagnosticsHeadlightDayRatio = propertyDouble(properties, "headlightDayRatio", 0.45, 0.0, 1.0);
			final String headlightMode = properties.getProperty("headlightMode", "auto").trim();
			diagnosticsHeadlightMode = headlightMode.equals("all") || headlightMode.equals("off") ? headlightMode : "auto";
			/*
			 * `headlightAmbiguous` 已废弃（notes/352）：判据从"按行进方向猜哪一端朝前"换成了
			 * **灯光开关 + 端 + 换向器**（引擎权威、镜像下发），停着不动时不再有"判不出朝向"这回事。
			 * 写了就点名一次 —— 留着一个读者看不懂的旧键比报错更贵。
			 */
			if (!headlightAmbiguousWarned && properties.containsKey("headlightAmbiguous")) {
				headlightAmbiguousWarned = true;
				Init.LOGGER.info("[MMTR-LIGHT] mmtr-lightfield.properties 里的 headlightAmbiguous 已废弃"
					+ "（灯光开关接管了它，见 notes/352）—— 删掉即可，写在这里不会读");
			}
			// 半影必须比主锥宽，否则 smoothstep 的上下界反了 → 光斑变成一个硬边圆盘。
			if (diagnosticsHeadlightPenumbraDeg <= diagnosticsHeadlightConeDeg) {
				diagnosticsHeadlightPenumbraDeg = diagnosticsHeadlightConeDeg + 6.0;
			}

			// 地形（世界方块）那一侧（notes/345 §6）。**改这几个不用重启、也不用重载资源**：
			// 着色器永远是那一份，开关只是 uniform（车灯总开关同一套做法）。
			diagnosticsTerrainHeadlights = Boolean.parseBoolean(properties.getProperty("terrainHeadlights", "true").trim());
			// 默认比车厢那侧（1.0）高一点：地面离灯远、灯又几乎平射 ⇒ 兰伯特只有 0.2 上下，1.0 会偏暗。
			diagnosticsTerrainLuxScale = propertyDouble(properties, "terrainLuxScale", 1.5, 0.0, 10.0);
			diagnosticsTerrainDebug = Boolean.parseBoolean(properties.getProperty("terrainDebug", "false").trim());
			diagnosticsTerrainNormalFlat = "flat".equalsIgnoreCase(properties.getProperty("terrainNormal", "derivative").trim());
			// 灯罩自发光（notes/374）：0 = 关掉（灯罩就是块彩色玻璃），1.5 = 默认"明显在发光"。
			diagnosticsLampLensGain = propertyDouble(properties, "lampLensGain", 1.0, 0.0, 4.0);

			diagnosticsLampLensFog = Boolean.parseBoolean(properties.getProperty("lampLensFog", "true").trim());
		} catch (Exception exception) {
			// 诊断文件坏掉绝不该影响渲染
		}
	}

	private static final MmtrLightField INSTANCE = new MmtrLightField();

	public static MmtrLightField getInstance() {
		return INSTANCE;
	}

	/**
	 * 本帧的帧号（每帧 {@link #beginFrame} 自增一次）。
	 *
	 * <p>给需要"每帧只做一次"的消费者当窗口 id 用（车灯收集就是这么用的，见 notes/345）：
	 * 收集发生在 draw 之前、而光场的 {@code beginFrame} 在 draw 之后，两者靠这个号对齐同一帧。</p>
	 */
	public static int frameId() {
		return INSTANCE.frame;
	}

	// ---- section 网格（必须与片元着色器里的 MMTR_GRID_* 常量一致） ----
	// 25×8×25 个 section = ±192 格 XZ、±64 格 Y。为什么不能小：编组列车在站里能横跨十几个 section
	// （实测一列车登记了 79 个 section），网格太小 = 大部分 section 落在网格外、LUT 全零、
	// 着色器一路退回 per-draw 光（症状就是"整车一起变亮变暗"）。
	//
	// ⚠️ 网格的锚点是**相机所在 section**（不是"相机相对坐标"）：格点必须是绝对的，
	//    否则 CPU 与着色器会各自按"相机相对坐标"取整，差出相机位置的小数部分 —— 见 §10。
	public static final int GRID_X = 25;
	public static final int GRID_Y = 8;
	public static final int GRID_Z = 25;
	public static final int OFFSET_X = 12;
	public static final int OFFSET_Y = 4;
	public static final int OFFSET_Z = 12;
	/** LUT 纹理宽度 = 网格 X + 1 列：最后一列放元数据（相机**精确**位置），见 {@link #rebuildLut}。 */
	private static final int LUT_W = GRID_X + 1;
	private static final int LUT_H = GRID_Y * GRID_Z;

	// ---- 图集：256 个槽位，每槽 64×64 纹素 = 16³ = 4096 字节，整图 1024×1024 ----
	private static final int MAX_SECTIONS = 256;
	private static final int TILE = 64;
	private static final int ATLAS_TILES_X = 16;
	private static final int ATLAS_SIZE = ATLAS_TILES_X * TILE;
	private static final int SECTION_BYTES = 16 * 16 * 16;

	/**
	 * 实心位图（AO 用）：**1 bit / 方块**，一个 section 512 字节，一行一个 section（行号 = 槽位 − 1），
	 * 整图 512×256 = 128 KB（对比光图集 4 MB，可以忽略）。
	 *
	 * <p>为什么单独一张纹理、而且用 bit 而不是 byte：片元里每个像素要查 <b>27</b> 个方块（3×3×3），
	 * 若按 1 字节/方块存就要读 27 个纹素、而且会白白占掉 256 倍显存；按 bit 存后每个纹素管 8 个方块，
	 * 打位也很便宜（见 {@link #collectSlice}）。</p>
	 *
	 * <p>位序（必须与 GLSL 的 {@code mmtrSolidAt} 逐字一致）：{@code bit = x + (z << 4) + (y << 8)}，
	 * 即：一个 (y,z) 行 = 2 字节，低字节是 x=0..7、高字节是 x=8..15，字节内 bit 0 是最低位。</p>
	 */
	private static final int SOLID_BYTES = SECTION_BYTES / 8;
	private static final int SOLID_TEX_WIDTH = SOLID_BYTES;

	/**
	 * 每帧采集的 z 层数。<b>= 16 表示"一帧采完一整个 section"</b>：采集成本是 4096 次
	 * {@code getLightLevel}（两次数组索引），实测可忽略，但换来的是"新出现的 section 下一帧就有数据"。
	 *
	 * <p>为什么必须这么快：早先设成 4（一个 section 要 4 帧），而编组列车一次能登记 56~79 个 section，
	 * 排队要几十秒才轮到近处的车 —— 假色诊断截图里那些**全黑的钢轨/车体**就是这个：
	 * 槽位已分配（slot≠0）、图块还是零（(0,0) = 方块光 0 + 天空光 0），不是"没数据"而是"还没采到"。</p>
	 */
	private static final int COLLECT_LAYERS_PER_FRAME = 16;
	/** 连续这么多帧没被请求的 section 释放槽位。 */
	private static final int EXPIRE_FRAMES = 240;
	/** 采样器单元：0=方块图集 1=overlay 2=原版光照贴图，3/4/5 留给光场。 */
	private static final int SAMPLER_LUT = 3;
	private static final int SAMPLER_ATLAS = 4;
	/** 实心位图（AO 用）。 */
	private static final int SAMPLER_SOLID = 5;
	/** 空槽位的哨兵值（不能拿 0 当哨兵：世界原点那个 section 的 key 就是 0）。 */
	private static final long EMPTY = Long.MIN_VALUE;

	private final Map<Long, Integer> sectionToSlot = new HashMap<>();
	private final long[] slotToSection = new long[MAX_SECTIONS + 1];
	private final int[] slotLastFrame = new int[MAX_SECTIONS + 1];
	private final BitSet slotInUse = new BitSet(MAX_SECTIONS + 1);
	private final BitSet slotDirty = new BitSet(MAX_SECTIONS + 1);
	/**
	 * 这个槽位的图块**采满过一次**没有。位号 = 槽位（不是槽位 − 1，与 {@link #slotInUse} 一致）。
	 *
	 * <p><b>为什么必须有这一位</b>（notes/367）：LUT 里"槽位 ≠ 0"是着色器唯一的判据 ——
	 * 它意味着"这个 section 有数据，用光场的值"。可是"槽位已分配、图块还是零"的窗口里，
	 * 光场的值就是 (方块光 0, 天空光 0) = 光照贴图最暗那一格 = **纯黑**，着色器分辨不出
	 * "还没采到"和"那里真的没光"。实测 5 秒诊断里那批
	 * {@code block 0..0 avg 0.00 / sky 0..0 avg 0.00} 就是这个窗口（也有"槽位被回收、
	 * 图块还留着上一节的光"的同族形态）。
	 * ⇒ 只有采满的槽位才准进 LUT；没采满的格子写 0，着色器自动退回 MTR 的 per-draw 光
	 * （= 光场介入之前的样子，可预测）。</p>
	 */
	private final BitSet slotReady = new BitSet(MAX_SECTIONS + 1);

	/**
	 * **采到"纯空气 + 零光"的 section 不算数据**（2026-10-03 实机：玩家传送落点附近的车厢整节发黑）。
	 *
	 * <p>{@code slotReady} 只回答"采满过没有"，而**区块还没到手时也能采满**：客户端在传送之后会先放一个
	 * <b>空区块占位</b>，{@code ClientWorld.isChunkLoaded} 对它返回 true，于是
	 * {@link #isSectionLoaded} 那道闸门形同虚设 —— {@code getBlockState} 全是空气、
	 * {@code getLightLevel} 全是 0，录下来就是"合法但全 0"的一节（诊断行的
	 * {@code block 0..0 avg 0.00 / sky 0..0 avg 0.00 / 实心 0.0%}）。着色器分辨不出
	 * "还没到手"和"那里真的没光"，只能照最暗那一格画 ⇒ <b>车厢纯黑</b>。
	 * 而且 {@link #markChunkDirty} 目前**没有调用点**（"光照变了就重采"这条整条是死的），
	 * 所以这一节会一直黑着（用户口径："我当前所在位置列车发黑，光照问题仍未解决"）。</p>
	 *
	 * <p>判据：{@code blockMax == 0 && skyMax == 0 && solidCount == 0} = 整个 16³ 里**一个实心方块都没有、
	 * 一点天空光都没有**。真实世界里只有"完全封闭的洞穴"能长成这样（少见），而那种地方退回 per-draw 光
	 * 也远好过把车厢画黑 —— 与着色器里"宁可少黑也不要因为缺数据凭空变黑"同一条口径。</p>
	 */
	private final int[] slotZeroRefusals = new int[MAX_SECTIONS + 1];

	/** 每个槽位**上一次采满**的时刻（滚动重采的节拍，见 {@link #ensureSlot}）。 */
	private final long[] slotCollectedMillis = new long[MAX_SECTIONS + 1];

	/** 本窗口里"拒绝发布的零光 section"次数（打在 slots 行里，现场一眼看得出还在不在发生）。 */
	private int zeroRefusals;

	/** 采到零光时**多久后重试**（等空区块占位被真区块换掉）。 */
	private static final long ZERO_RETRY_MILLIS = 1000;
	/**
	 * 采满的 section **多久重采一次**（滚动刷新）。
	 *
	 * <p>为什么必须有：光场是"世界的光"的快照，而它会变 —— 区块后到手、玩家点亮灯、日夜交替、
	 * 列车开进隧道。{@link #markChunkDirty} 是为这件事写的，但它**没有调用点**，
	 * 于是"光照变了就重采"这条整条不存在：只要某节车进过一次光场，它就永远按那一刻的光画。
	 * 这里把它落成滚动重采，范围只有"有车经过的 section"（{@code ensureSlot} 只被 {@code requestCar} 调到），
	 * 代价封在既有的采集预算里（每帧 4 层 × 16×16）。</p>
	 */
	private static final long REFRESH_MILLIS = 3000;
	/** 本帧被请求的 section（由渲染循环登记）。 */
	private final Set<Long> requested = new HashSet<>();
	/** 已排入采集队列、尚未采完的 section。 */
	private final Set<Long> queued = new HashSet<>();
	private final ArrayDeque<CollectTask> collectQueue = new ArrayDeque<>();
	private final BlockPos.Mutable cursor = new BlockPos.Mutable();

	private final ByteBuffer atlasCpu = BufferUtils.createByteBuffer(MAX_SECTIONS * SECTION_BYTES);
	private final ByteBuffer solidCpu = BufferUtils.createByteBuffer(MAX_SECTIONS * SOLID_BYTES);
	private final IntBuffer lutCpu = BufferUtils.createIntBuffer(LUT_W * LUT_H);

	private ClientWorld world;
	private ClientWorld boundWorld;
	private int atlasTex;
	private int lutTex;
	/** 实心位图纹理（R8UI，512×256，一行一个 section）。 */
	private int solidTex;
	private final BitSet solidDirty = new BitSet(MAX_SECTIONS + 1);
	/** 全零 LUT：绑给"不参与光场"的批次（钢轨），使查表一律无效、自动退回 per-draw 光。 */
	private int dummyLutTex;
	private int railBatchCount;
	private int vehicleBatchCount;
	private int railTextureLogs;
	private static final int MAX_RAIL_TEXTURE_LOGS = 6;
	private int frame;
	private boolean lutDirty;
	private double lastCamX = Double.NaN;
	private double lastCamY = Double.NaN;
	private double lastCamZ = Double.NaN;
	/** 相机所在**方块**坐标（整数，= floor(camera)）；LUT 的元数据列就是它。 */
	private int cameraBlockX;
	private int cameraBlockY;
	private int cameraBlockZ;
	/** 网格格号 0 对应的**绝对** section 坐标（= 相机所在 section − OFFSET）。 */
	private int gridOriginX;
	private int gridOriginY;
	private int gridOriginZ;
	private int lastCamBlockX = Integer.MIN_VALUE;
	private int lastCamBlockY = Integer.MIN_VALUE;
	private int lastCamBlockZ = Integer.MIN_VALUE;
	/** 诊断：MTR 的渲染偏移与真相机的距离（= 光场要是用错基准就会差这么多）。 */
	private double lastMtrOffsetLag;
	private double maxMtrOffsetLag;
	private int collectedSections;
	private int uploadedTiles;
	/**
	 * 本窗口"有 section 因为**客户端手上没有这个区块**而被裁掉"的**车次**（notes/367）。
	 *
	 * <p>这一条是**判断"远处的车厢为什么走 per-draw 光"的唯一现场读数**：它非零就说明
	 * 车的包围盒伸进了服务端 view-distance 之外（本服 160 格），而那部分车厢按设计退回 per-draw 光
	 * （＝光场介入之前的样子，不是纯黑）。</p>
	 */
	private int unloadedSkipped;
	private long lastStatsMillis;
	private int statsFrames;
	private int screenshotsTaken;
	private boolean matricesVerified;
	private boolean gpuTexturesVerified;
	private boolean drawTimeMatricesLogged;
	private long lastScreenshotMillis;
	private String lastSectionSummary = "-";

	// ---- 诊断状态（只是计数与一次性日志，不在渲染热路径上做任何分配） ----
	/** 每个 draw 都读一次 ModelMat 太贵，只记前 N 个。 */
	private static final int MAX_DRAW_STATE_LOGS = 24;
	private final FloatBuffer matrixFloats = BufferUtils.createFloatBuffer(16);

	/** 上传给注入进包的程序的那份 MTR 模型矩阵 / 哨兵（全零）。见 {@link #uploadPackModelMatrix}。 */
	private final FloatBuffer packModelMatrixFloats = BufferUtils.createFloatBuffer(16);
	/** 一次性回读探针，只打第一条（证明 uniform 真的写进去了）。 */
	private boolean packModelMatrixProbeLogged;
	/** 上一次上传的开关值（变了就打一条"上传值 + 回读值"，用来排除"开关没生效"）。 */
	private int lastProbeState = Integer.MIN_VALUE;
	/** 上一次上传的逐顶点强度（变了就打一条"上传值 + 回读值"）。 */
	private float lastFieldMixState = Float.NaN;
	private int lastNormalFixState = Integer.MIN_VALUE;
	private int drawStatesLogged;
	/** apply() 时 matrix4f 为 null 的次数（走非优化路径的那些 draw）。 */
	private int drawStatesWithoutMatrix;
	private double lastDrawX = Double.NaN;
	private double lastDrawY = Double.NaN;
	private double lastDrawZ = Double.NaN;
	private int lastLutTrackedSections;
	private int lastLutCellsWritten;
	/** 写进 LUT 元数据列的 AO 强度（着色器读它）——变了就要重传 LUT。 */
	private int aoStrengthPermille = -1;
	/** 帧时统计（性能对照用）：本窗口累计的帧间隔。 */
	private long lastFrameNanos;
	private double frameTimeSumMillis;
	private int frameTimeSamples;
	/** GPU 回读到的 LUT 非零纹素数（-1 = 还没回读过）。 */
	private int lastGpuLutNonZero = -1;
	private int gpuReadbackAttempts;
	/** 离相机最近的那辆被登记的车（诊断基准；见 {@link #requestCar}），用来在日志里做"这辆车的位置上，光场取到什么光"。 */
	private String lastCarSummary = "-";
	private double lastCarX = Double.NaN;
	private double lastCarY = Double.NaN;
	private double lastCarZ = Double.NaN;
	private double lastCarDistanceSquared = Double.MAX_VALUE;
	private String lastReloadNonce = "";
	/** 诊断用自动截图的"上次请求张数"：一变就从 0 重新记数（否则第一轮用完就再也截不到）。 */
	private int lastScreenshotRequest = -1;
	/** 上一帧 {@code Utilities.canUseCustomShader()}（null = 还没初始化）。用来发现"光影包被关掉"。 */
	private Boolean lastCanUseCustomShaderState;
	/** 上一帧 {@code enabled}（null = 还没初始化）。 */
	private Boolean lastEnabledState;
	/** 光影包路径的采样器绑定：按 GL program id 缓存（uniform 位置 + 挑好的空闲纹理单元）。 */
	private final Map<Integer, PackSamplerBinding> packSamplerBindings = new HashMap<>();
	private int packSamplerLogs;
	private static final int MAX_PACK_SAMPLER_LOGS = 4;
	/** 进入"光影包模式"时打一次说明（每次启动一次）。 */
	private boolean packShaderpackModeLog = true;

	private MmtrLightField() {
	}

	// ------------------------------------------------------------------ 外部登记

	/** 渲染循环登记"这一帧这辆车存在"：把它可能占用的 section 记下来。 */
	public void requestCar(double centerX, double centerY, double centerZ, double yaw, double halfLength, double halfWidth) {
		if (world == null) {
			return;
		}

		/*
		 * 诊断基准取**离相机最近**的那辆车，不取"最后登记的那辆"：MTR 的优化渲染器不做距离剔除，
		 * 一帧会登记几十辆车（几百格外也算），"最后登记"于是在离相机几百格的车上 —— 现场想看的是
		 * "我眼前这节车取到什么光"，那个读数必须落在他看的那辆车上（2026-10-03 实测：日志里的
		 * 最近登记车在 460 格外，而眼前那节车一行读数都没有）。
		 */
		final double toCameraX = centerX - lastCamX;
		final double toCameraY = centerY - lastCamY;
		final double toCameraZ = centerZ - lastCamZ;
		final double distanceSquared = Double.isNaN(lastCamX) ? 0
				: toCameraX * toCameraX + toCameraY * toCameraY + toCameraZ * toCameraZ;
		if (Double.isNaN(lastCarX) || distanceSquared < lastCarDistanceSquared) {
			lastCarDistanceSquared = distanceSquared;
			lastCarX = centerX;
			lastCarY = centerY;
			lastCarZ = centerZ;
		}

		final double sin = Math.abs(Math.sin(yaw));
		final double cos = Math.abs(Math.cos(yaw));
		// 车辆长度沿模型 Z 轴（notes/338：车头朝 -Z），绕 Y 旋转 yaw 后取轴对齐包围盒
		final double dx = halfWidth * cos + halfLength * sin + 2;
		final double dz = halfWidth * sin + halfLength * cos + 2;
		final double dyLow = 2;
		final double dyHigh = 5;

		final int minX = floorToSection(centerX - dx);
		final int maxX = floorToSection(centerX + dx);
		final int minY = floorToSection(centerY - dyLow);
		final int maxY = floorToSection(centerY + dyHigh);
		final int minZ = floorToSection(centerZ - dz);
		final int maxZ = floorToSection(centerZ + dz);

		boolean skippedUnloaded = false;

		for (int sx = minX; sx <= maxX; sx++) {
			for (int sy = minY; sy <= maxY; sy++) {
				for (int sz = minZ; sz <= maxZ; sz++) {
					// 只在**网格范围内**登记。MTR 的优化渲染器不做距离剔除，几百格外的车厢也会走到这里
					// （实测：tracked=64 里没有一个是网格内的，cells=0 —— 256 个图集槽位全被远处的车占满，
					//  玩家身边那节车反而被挤掉、取不到光）。远处的车本来也不需要光场：它们要么在雾里，
					// 要么根本不在视锥内。
					// ★ 还必须**客户端手上有这个区块**（notes/367，2026-10-03 实测抓到的根因）：
					//   网格是"相机 ±12 section（±192 格）"，而真正到手多少区块由**服务端 view-distance**
					//   决定（本服 10 = 160 格）。两者之间那条 160~192 格的窄带里，MTR 照样画车厢，
					//   但 `getBlockState` 全是 air、方块光/天空光全是 0 ⇒ 采出来是"全黑但有效"，
					//   车厢整节画成纯黑（诊断行里 `sky 0..0 avg 0.00 / 实心 0.0%` 就是这一批）。
					//   不登记 ⇒ 该 section 的 LUT 格子是 0 ⇒ 着色器退回 MTR 的 per-draw 光（介入前的样子）。
					if (isSectionInsideGrid(sx, sy, sz)) {
						if (isSectionLoaded(sx, sz)) {
							requested.add(sectionKey(sx, sy, sz));
						} else {
							skippedUnloaded = true;
						}
					}
				}
			}
		}

		// 诊断读数按"**车次**"计，不按 section 计 —— 一列车一帧能撞上好几个没到手的 section，
		// 按 section 计会变成每窗口几万，读不出"到底有没有车被裁"。见 notes/367 §5。
		if (skippedUnloaded) {
			unloadedSkipped++;
		}
	}

	/**
	 * 这个 section 所在的那一列区块，**客户端手上有没有**（notes/367）。
	 *
	 * <p>区块坐标 = section 的 X/Z —— section 本来就是 chunk 对齐的 16³，不需要再除 16。</p>
	 *
	 * <p>没有它的后果不是"少一点光"，而是"纯黑"：{@code World.getBlockState} 对没有的区块返回空气、
	 * {@code getLightLevel} 返回 0，于是采出来的是"合法但全 0"的一节，着色器只会当它是"那里没光"。
	 * 所以判据必须放在**登记**这一层：没到手就不登记。</p>
	 *
	 * <p>判不出来时（没有 world、API 变了、抛异常）一律当作**已加载** —— 宁可维持现状，
	 * 也不要在某台机器上把整条光场凭空关掉。</p>
	 */
	private boolean isSectionLoaded(int sectionX, int sectionZ) {
		final ClientWorld currentWorld = world;
		if (currentWorld == null) {
			return false;
		}
		try {
			return currentWorld.isChunkLoaded(sectionX, sectionZ);
		} catch (Throwable ignored) {
			return true;
		}
	}

	/**
	 * 这个 section 在不在光场网格里（见 {@link #requestCar} 的说明）。<b>绝对</b> section 直接和
	 * 网格原点比 —— 不再涉及相机位置的小数部分（§10）。
	 */
	private boolean isSectionInsideGrid(int sectionX, int sectionY, int sectionZ) {
		if (Double.isNaN(lastCamX)) {
			return true;
		}

		final int cellX = sectionX - gridOriginX;
		final int cellY = sectionY - gridOriginY;
		final int cellZ = sectionZ - gridOriginZ;

		return cellX >= 0 && cellX < GRID_X && cellY >= 0 && cellY < GRID_Y && cellZ >= 0 && cellZ < GRID_Z;
	}

	/** 收到光照更新包：该区块列里我们跟踪的 section 需要重新采集。 */
	public void markChunkDirty(int chunkX, int chunkZ) {
		if (world == null) {
			return;
		}
		final List<Long> hits = new ArrayList<>();
		for (final Map.Entry<Long, Integer> entry : sectionToSlot.entrySet()) {
			final long key = entry.getKey();
			// ⚠️ 判据是 section X/Z **等于** chunk X/Z（section 本来就是 chunk 对齐的 16³）。
			//    早先这里写成 `keyX(key) >> 4`（把 section 当方块又除了一次 16）⇒ 除世界原点附近
			//    那一小圈以外**永远不命中** ⇒ "光照变了就重采"这条整条是死的（notes/367）。
			if (keyX(key) == chunkX && keyZ(key) == chunkZ) {
				hits.add(key);
			}
		}
		for (final long key : hits) {
			enqueueCollect(key);
		}
	}

	// ------------------------------------------------------------------ 每帧入口

	/**
	 * 每帧调用一次，位置必须在优化渲染器真正 draw 之前（见 MainRenderer）。
	 *
	 * <p><b>相机基准必须是"真正的相机位置"，不能直接用传进来的 {@code mtrOffset}。</b>
	 * 原因（2026-09-28 实测 + 读源码）：MTR 的 {@code mtrOffset} 是那个"渲染用假实体"
	 * （{@code EntityRendering}）的插值位置 —— 它每 tick 只在"离相机 >1 格"时才被传送到相机处
	 * （{@code position.squaredDistanceTo(getPos2()) > 1}），因此永远比真相机**滞后 0~1 格**。
	 * 而顶点的 ModelMat 是建在 vanilla 实体姿势上的，那一层用的是 {@code camera.getPos()}（真相机）。
	 * 两者不一致 ⇒ 采样点整体被平移 {@code mtrOffset − 真相机}
	 * ⇒ 实测症状"**走动时光照朝走动方向柔和滑动，停下后柔和回到正确位置**"。
	 * （早先 A/B 诊断里那个"y/z 严丝合缝、只有 x 差 0.68 格"其实就是这个滞后，我当时当成了转向架偏移。）</p>
	 *
	 * @param mtrOffset MTR 传给 {@code StoredMatrixTransformations.transform} 的偏移（只用于诊断对照）
	 */
	public void beginFrame(Vector3d mtrOffset) {
		final MinecraftClient client = MinecraftClient.getInstance();
		final ClientWorld currentWorld = client.world;

		// 每 2 秒重读一次 run/mmtr-lightfield.properties（内部节流）。放在这里是因为本方法每帧都跑，
		// 而车灯那几个开关要在**收集之前**就是新的（收集发生在 RenderVehicles，见 MmtrHeadlights）。
		refreshDiagnostics();

		if (currentWorld == null) {
			requested.clear();
			return;
		}

		// 光影包开着时数据**照旧上传**（那条路上的车厢光由注入进包的顶点着色器负责取），
		// 只是采样器不能走 Sampler3/4/5（见 isUnderShaderpack）。
		final boolean underShaderpack = isUnderShaderpack();

		if (currentWorld != boundWorld) {
			reset(currentWorld);
		}

		frame++;

		// 帧时统计（性能对照要用**客观数字**，不能靠"感觉卡不卡"）：
		// 累计本窗口每帧的间隔（超过 1 秒的间隔是暂停/加载，不计入），5 秒窗口一起打出来。
		final long frameNanos = System.nanoTime();
		if (lastFrameNanos != 0) {
			final double frameDeltaMillis = (frameNanos - lastFrameNanos) / 1.0e6;
			if (frameDeltaMillis < 1000.0) {
				frameTimeSumMillis += frameDeltaMillis;
				frameTimeSamples++;
			}
		}
		lastFrameNanos = frameNanos;

		// 真相机（vanilla 实体姿势用的就是它）；mtrOffset 只用来量"滞后了多少"。
		// 注意这里用的是原版 MinecraftClient（不是 MTR 的 holder），所以走原版字段/方法。
		final Vec3d cameraPosition = client.gameRenderer.getCamera().getPos();
		final double camX = cameraPosition.x;
		final double camY = cameraPosition.y;
		final double camZ = cameraPosition.z;
		lastMtrOffsetLag = Math.sqrt(
				square(mtrOffset.getXMapped() - camX)
						+ square(mtrOffset.getYMapped() - camY)
						+ square(mtrOffset.getZMapped() - camZ));
		if (lastMtrOffsetLag > maxMtrOffsetLag) {
			maxMtrOffsetLag = lastMtrOffsetLag;
		}

		// 相机所在方块 + 网格原点（**绝对** section）。
		// 这两样是"CPU 与着色器格点一致"的全部依据：着色器拿到的是相机相对坐标，
		// 要折回绝对方块/section 只能靠相机所在方块坐标（LUT 元数据列传给它的就是这两个值）。
		cameraBlockX = (int) Math.floor(camX);
		cameraBlockY = (int) Math.floor(camY);
		cameraBlockZ = (int) Math.floor(camZ);
		gridOriginX = (cameraBlockX >> 4) - OFFSET_X;
		gridOriginY = (cameraBlockY >> 4) - OFFSET_Y;
		gridOriginZ = (cameraBlockZ >> 4) - OFFSET_Z;

		if (!ensureTextures()) {
			requested.clear();
			return;
		}

		// 1) 本帧请求的 section → 分配槽位（新槽位会排队采集）
		for (final long key : requested) {
			final int slot = ensureSlot(key);
			slotLastFrame[slot] = frame;
		}

		// 2) 采集（每帧一批 z 层，把 CPU 成本摊平）
		collectSlice();

		// 3) 槽位回收（低频）
		if ((frame & 63) == 0) {
			expireSlots();
		}

		// 4) LUT：
		//    · 槽位分配/释放会让 LUT 变脏（**那时相机可能一动不动** —— 漏了这条 = 站着不动时 LUT 全零）；
		//    · 相机位置一变就要刷新元数据列（着色器靠它把相机相对坐标折回**绝对**世界坐标）。
		//    ⚠️ 这里必须比较**精确**的相机位置（不是所在方块）：着色器用的就是精确值，
		//       漏更新会让格点差出 frac(相机位置) —— 实测"边走边滑、走一格重置一次"就是这个（§10.3）。
		//
		//    AO 强度也走这条：它在 LUT 元数据列第 6 行，改 properties 里的 ao= 会让 LUT 变脏、
		//    下一帧就重传 —— 于是"开/关 AO"是个不用重启、不用换着色器的开关（性能 A/B 就靠它）。
		if (diagnosticsAoStrengthPermille != aoStrengthPermille) {
			aoStrengthPermille = diagnosticsAoStrengthPermille;
			lutDirty = true;
		}
		if (lutDirty
				|| (float) camX != (float) lastCamX
				|| (float) camY != (float) lastCamY
				|| (float) camZ != (float) lastCamZ) {
			lastCamX = camX;
			lastCamY = camY;
			lastCamZ = camZ;
			rebuildLut(camX, camY, camZ);
			uploadLut();
		}

		// 5) 上传脏图块
		uploadDirtyTiles();

		// 6) 采样器绑定。**两种模式完全不同**（见 §15 的架构说明）：
		//    · 无光影包：MTR 用我们那份 program，它会把 Sampler0..7 从 RenderSystem 取出来绑上（Sampler3/4/5 是光场）。
		//    · 有光影包：车厢由**包自己的 program**画，光照在包源码里被注入的代码按顶点取（见 MmtrShaderPackLightField），
		//      我们的三个采样器由 bindPackSamplers 指到"程序里没人用的空闲单元" —— 绝不能碰 3/4/5，
		//      那是 Iris 分给 colortex/shadowtex 的单元，抢了会让整个画面的 pass 采样到我们的 LUT（实测整屏黑）。
		if (!underShaderpack) {
			RenderSystem.setShaderTexture(SAMPLER_LUT, lutTex);
			RenderSystem.setShaderTexture(SAMPLER_ATLAS, atlasTex);
			RenderSystem.setShaderTexture(SAMPLER_SOLID, solidTex);
		} else if (packShaderpackModeLog) {
			packShaderpackModeLog = false;
			Init.LOGGER.info("[MMTR-LIGHT] 光影包在跑：光场数据照旧上传，取样改由注入进**包片元着色器**的代码负责"
					+ "（逐片元，与包的 held light 同一条路）；{}", MmtrShaderPackLightField.getInstance().describe());
		}

		requested.clear();
		statsFrames++;

		verifyMatricesOnce(camX, camY, camZ);
		verifyGpuTexturesOnce();

		final long now = System.currentTimeMillis();
		if (lastStatsMillis == 0) {
			lastStatsMillis = now;
			Init.LOGGER.info("[MMTR-LIGHT] light field online: atlasTex={} lutTex={} solidTex={} samplers={}/{}/{}",
					atlasTex, lutTex, solidTex, SAMPLER_LUT, SAMPLER_ATLAS, SAMPLER_SOLID);
		} else if (now - lastStatsMillis > 5000) {
			Init.LOGGER.info("[MMTR-LIGHT] slots={}/{} collecting={} collected={} tiles={} frames={} cam=({}, {}, {}) 未加载跳过(车次)={} 零光重采={} | {}",
					sectionToSlot.size(), MAX_SECTIONS, collectQueue.size(), collectedSections, uploadedTiles, statsFrames,
					String.format("%.1f", camX), String.format("%.1f", camY), String.format("%.1f", camZ), unloadedSkipped, zeroRefusals, lastSectionSummary);
			MmtrOptimizerStats.logAndReset(statsFrames);
			// 车灯：同一窗口里"采到几盏、传了几盏、判定成什么"（notes/345）。
			MmtrHeadlights.getInstance().logAndReset();
			// 诊断：本窗口内"MTR 渲染偏移 vs 真相机"的最大滞后。光场若用错基准，采样点就会整体差这么多。
			Init.LOGGER.info("[MMTR-LIGHT] 相机基准滞后：本窗口最大 |mtrOffset − 真相机| = {} 格（当前 {}）| 批次：钢轨={}（不参与光场）车辆={}",
					round(maxMtrOffsetLag), round(lastMtrOffsetLag), railBatchCount, vehicleBatchCount);
			// 性能对照：平均帧时是客观数字（draw call 的 A/B 看下一行的 draws/frame、batches/frame）。
			final double averageFrameMillis = frameTimeSamples == 0 ? 0 : frameTimeSumMillis / frameTimeSamples;
			Init.LOGGER.info("[MMTR-LIGHT] 性能：平均帧时={} ms（≈{} fps，{} 帧）| AO 强度={}（properties: ao=）",
					String.format("%.2f", averageFrameMillis),
					String.format("%.1f", averageFrameMillis <= 0 ? 0 : 1000.0 / averageFrameMillis),
					frameTimeSamples, String.format("%.2f", aoStrengthPermille / 1000.0));
			frameTimeSumMillis = 0;
			frameTimeSamples = 0;
			maxMtrOffsetLag = 0;
			railBatchCount = 0;
			vehicleBatchCount = 0;
			// 每个 5 秒窗口重新给一轮 draw 日志配额（否则开局那 24 条用完之后再也看不到车的坐标）
			drawStatesLogged = 0;
			drawStatesWithoutMatrix = 0;
			lastDrawX = Double.NaN;
			lastDrawY = Double.NaN;
			lastDrawZ = Double.NaN;
			Init.LOGGER.info("[MMTR-LIGHT] LUT: cells={} tracked={}（网格 {}x{}x{}，**绝对** section；GPU 读回非零={}）| 最近登记车=({}, {}, {}) 车心光场采样={} 真实光照={}",
					lastLutCellsWritten, lastLutTrackedSections, GRID_X, GRID_Y, GRID_Z, lastGpuLutNonZero,
					round(lastCarX), round(lastCarY), round(lastCarZ), sampleCpu(lastCarX, lastCarY, lastCarZ), worldLightAt(lastCarX, lastCarY, lastCarZ));
			// AO 的 CPU 镜像探针（口径：世界法线 +Y、格点 floor(世界坐标 − 0.5)）。
			// 车心是空气 ⇒ 期望 AO≈1；车心下方 3 格多半在道床/结构里 ⇒ 期望明显 <1。
			// 这两个数在假色截图里可以逐个对照（蓝通道 = AO）—— CPU 与 GPU 对不上就是位序/行号那类编码错误。
			Init.LOGGER.info("[MMTR-LIGHT] AO 探针（世界法线 +Y）：车心 {} | 车心下方 3 格 {} | 生效强度={}",
					aoProbeCpu(lastCarX, lastCarY, lastCarZ),
					aoProbeCpu(lastCarX, lastCarY - 3, lastCarZ),
					String.format("%.2f", aoStrengthPermille / 1000.0));
			lastStatsMillis = now;
			statsFrames = 0;
			collectedSections = 0;
			uploadedTiles = 0;
			unloadedSkipped = 0;
			zeroRefusals = 0;
		}

		/*
		 * "离相机最近的车"的基准在这里清 —— **不能放在 beginFrame 开头**：诊断行就在本方法里，
		 * 而登记（requestCar）发生在这一帧的 draw 期间，开头的清零会把上一帧选好的车抹成 NaN
		 * （实测：`最近登记车=(NaN, NaN, NaN)`，探针整条失去意义）。放在末尾 = 本次诊断用上一帧
		 * 选出的车（一帧的差别），清完之后本帧的 draw 重新挑。
		 */
		lastCarX = Double.NaN;
		lastCarY = Double.NaN;
		lastCarZ = Double.NaN;
		lastCarDistanceSquared = Double.MAX_VALUE;
	}

	/**
	 * 一次性验证"着色器里的坐标到底是不是相机相对世界坐标"。
	 *
	 * <p>着色器算的是 {@code mmtrRelWorld = IViewRotMat * (ModelViewMat * ModelMat * Position)}。
	 * 这个式子只有在 {@code IViewRotMat * mat3(ModelViewMat) == 单位矩阵} 时才等于
	 * "世界坐标 − 相机位置"。这里把三个矩阵都打出来，一眼就能看出关系（单位矩阵 / 转置 / 别的）。
	 * 实测症状"光场跟着玩家和视角走"就是这一步不对的典型表现。</p>
	 */
	private void verifyMatricesOnce(double camX, double camY, double camZ) {
		if (matricesVerified) {
			return;
		}
		matricesVerified = true;

		try {
			final Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
			final Matrix3f inverseView = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
			final Matrix3f product = new Matrix3f(inverseView).mul(new Matrix3f(modelView));
			Init.LOGGER.info("[MMTR-LIGHT] camera=({}, {}, {})", String.format("%.3f", camX), String.format("%.3f", camY), String.format("%.3f", camZ));
			Init.LOGGER.info("[MMTR-LIGHT] ModelViewMat = {}", rowMajor(modelView));
			Init.LOGGER.info("[MMTR-LIGHT] IViewRotMat  = {}", rowMajor(inverseView));
			Init.LOGGER.info("[MMTR-LIGHT] IViewRotMat * mat3(ModelViewMat) = {}", rowMajor(product));
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] matrix diagnostics failed", exception);
		}
	}

	private static String rowMajor(Matrix4fc matrix) {
		final StringBuilder builder = new StringBuilder();
		for (int row = 0; row < 4; row++) {
			builder.append(row == 0 ? "" : " | ");
			for (int column = 0; column < 4; column++) {
				builder.append(String.format("%7.3f ", matrix.get(column, row)));
			}
		}
		return builder.toString();
	}

	private static String rowMajor(Matrix3fc matrix) {
		final StringBuilder builder = new StringBuilder();
		for (int row = 0; row < 3; row++) {
			builder.append(row == 0 ? "" : " | ");
			for (int column = 0; column < 3; column++) {
				builder.append(String.format("%7.3f ", matrix.get(column, row)));
			}
		}
		return builder.toString();
	}

	/**
	 * 在**真正 draw 的那一刻**（{@code ShaderManager.setupShaderBatchState} —— MTR 就是在这里把
	 * {@code ModelViewMat} / {@code IViewRotMat} 塞进 uniform 的）记一次矩阵。
	 *
	 * <p>为什么不能在 {@code beginFrame} 里读：那是世界渲染的另一个阶段，{@code RenderSystem} 的矩阵未必
	 * 等于 draw 时的值 —— 实测 {@code beginFrame} 里读到的 {@code ModelViewMat} 是**单位矩阵**。
	 * 着色器里 {@code mmtrRelWorld = IViewRotMat * (ModelViewMat * ModelMat * Position)} 对不对，
	 * 完全取决于这两个矩阵此刻的值：若 {@code IViewRotMat * mat3(ModelViewMat)} 不是单位矩阵，
	 * 那算出来的就不是世界坐标 —— 症状正是"光场跟着玩家/视角移动"。</p>
	 */
	public void logDrawTimeMatricesOnce() {
		if (drawTimeMatricesLogged) {
			return;
		}
		drawTimeMatricesLogged = true;

		try {
			final Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
			final Matrix3f inverseView = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
			final Matrix3f product = new Matrix3f(inverseView).mul(new Matrix3f(modelView));
			Init.LOGGER.info("[MMTR-LIGHT] @draw ModelViewMat = {}", rowMajor(modelView));
			Init.LOGGER.info("[MMTR-LIGHT] @draw IViewRotMat  = {}", rowMajor(inverseView));
			Init.LOGGER.info("[MMTR-LIGHT] @draw IViewRotMat * mat3(ModelViewMat) = {}", rowMajor(product));
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] draw-time matrix diagnostics failed", exception);
		}
	}

	/**
	 * 每个 draw（{@code VertexAttributeState.apply()}）都会把 MTR 的 per-draw 常量传进来：
	 * 光照 UV 和 **ModelMat**（顶点属性 {@code MATRIX_MODEL} 的数据源 —— 见
	 * {@code VertexAttributeState.apply()} 里的 {@code Utilities.store(matrix4f, ...)}）。
	 *
	 * <p>这条日志是"坐标口径对不对"的判决性证据。ModelMat 的平移量只可能有两种：
	 * <ul>
	 *   <li>{@code 车世界坐标 − 相机位置}（MTR 的 {@code StoredMatrixTransformations.transform} 减掉了 offset）
	 *       ⇒ 我们的 LUT（也是按世界 − 相机建的）口径一致，采样应当命中；</li>
	 *   <li>绝对世界坐标（数值几千/几万）⇒ 它减掉的偏移和 {@link #beginFrame} 收到的 {@code offset}
	 *       不是同一个东西，LUT 永远对不上，症状正是"光跟着视角/玩家走"。</li>
	 * </ul>
	 * 同时把"按这个口径解释出来的世界坐标"上，光场采样值与真实世界光照值并排打出来 ——
	 * 一眼就能分辨"口径错"还是"数据没进 LUT"。</p>
	 */
	/**
	 * 光影包模式下：把 MTR **这个 draw** 的模型矩阵写进当前 program 的 {@code mmtrModelMat}。
	 *
	 * <p>为什么必须每 draw 传：MTR 的车体顶点在 VBO 里是**模型局部坐标**，摆放全靠这个矩阵
	 * （MTR 自己的顶点格式里叫 {@code ModelMat}，占 location 6..9）。包的程序里
	 * {@code gl_ModelViewMatrix} 被 Iris 改写成相机视图矩阵，**不含**它；少了它，注入代码算出来的
	 * "世界坐标"退化成模型局部坐标，采到的光与车体真实位置无关 —— 实测症状就是**车身明暗无逻辑**。
	 * 完整推导见 notes/344 §17.16。</p>
	 */
	private void uploadPackModelMatrix(org.mtr.mapping.holder.Matrix4f modelMatrix) {
		if (!isEnabled() || !isUnderShaderpack()) {
			return;
		}
		final int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		final PackSamplerBinding binding = packSamplerBindings.get(programId);
		if (binding == null || binding.modelMatLocation < 0) {
			// 还没建缓存（= 这个 program 不是我们注入的），或者注入文本里没有这条 uniform
			return;
		}
		try {
			packModelMatrixFloats.clear();
			Utilities.store(modelMatrix, packModelMatrixFloats);
			GL20.glUniformMatrix4fv(binding.modelMatLocation, false, packModelMatrixFloats);
			if (binding.normalFixLocation >= 0) {
				// 法线空间修正开关：每 draw 传一次（改 properties 后 2 秒内生效，不用重启）。
				GL20.glUniform1i(binding.normalFixLocation, diagnosticsNormalFix ? 1 : 0);
			}
			if (binding.probeLocation >= 0) {
				GL20.glUniform1i(binding.probeLocation, diagnosticsProbe);
			}
			if (binding.fieldMixLocation >= 0) {
				GL20.glUniform1f(binding.fieldMixLocation, diagnosticsFieldMix);
			}
			if (Math.abs(lastFieldMixState - diagnosticsFieldMix) > 0.001F) {
				// 光场占比也要能证明"真的到着色器了"（上传值 + 回读值）。
				lastFieldMixState = diagnosticsFieldMix;
				String mixReadback = "无";
				if (binding.fieldMixLocation >= 0) {
					final FloatBuffer readback = BufferUtils.createFloatBuffer(1);
					GL20.glGetUniformfv(programId, binding.fieldMixLocation, readback);
					mixReadback = String.format("%.3f", readback.get(0));
				}
				Init.LOGGER.info("[MMTR-LIGHT] 光场占比 mix={}（回读 {}）program {}", diagnosticsFieldMix, mixReadback, programId);
			}
			if (lastProbeState != diagnosticsProbe || lastNormalFixState != (diagnosticsNormalFix ? 1 : 0)) {
				// 开关真的到着色器了没有？把"上传值 + 回读值"一起打出来（每次变化只打一条）。
				lastProbeState = diagnosticsProbe;
				lastNormalFixState = diagnosticsNormalFix ? 1 : 0;
				final IntBuffer readback = BufferUtils.createIntBuffer(1);
				String fixReadback = "无";
				String probeReadback = "无";
				if (binding.normalFixLocation >= 0) {
					GL20.glGetUniformiv(programId, binding.normalFixLocation, readback);
					fixReadback = String.valueOf(readback.get(0));
				}
				if (binding.probeLocation >= 0) {
					readback.clear();
					GL20.glGetUniformiv(programId, binding.probeLocation, readback);
					probeReadback = String.valueOf(readback.get(0));
				}
				Init.LOGGER.info("[MMTR-LIGHT] 注入开关生效：program {} normalFix={}（回读 {}）probe={}（回读 {}）",
						programId, lastNormalFixState, fixReadback, lastProbeState, probeReadback);
			}
			if (!packModelMatrixProbeLogged) {
				// 一次性回读：证明这个 uniform 真的写进去了（否则着色器会走"哨兵 ⇒ 退回 per-draw 光"分支，
				// 看起来也会"稳定"，但那就不是逐顶点了 —— 见 notes/344 §17.19）。
				packModelMatrixProbeLogged = true;
				final FloatBuffer readback = BufferUtils.createFloatBuffer(16);
				GL20.glGetUniformfv(programId, binding.modelMatLocation, readback);
				Init.LOGGER.info("[MMTR-LIGHT] mmtrModelMat 回读（program {}）：平移=({}, {}, {}) [3][3]={} normalFix={}（写入的平移=({}, {}, {})）",
						programId,
						round(readback.get(12)), round(readback.get(13)), round(readback.get(14)), round(readback.get(15)),
						diagnosticsNormalFix ? 1 : 0,
						round(packModelMatrixFloats.get(12)), round(packModelMatrixFloats.get(13)), round(packModelMatrixFloats.get(14)));
			}
		} catch (Exception exception) {
			// 静默：上传失败只是退回 per-draw 光，不该刷日志
		}
	}

	/**
	 * MTR 的一次 draw 结束：把 {@code mmtrModelMat} 置成**全零哨兵**。
	 *
	 * <p>包的那个程序是 MTR 和**原版实体共用的**（{@code gbuffers_entities}）。原版实体没有 MTR 的
	 * ModelMat，而且它们的光照属性本来就是逐顶点正确的 —— 所以 MTR 画完必须把哨兵放回去，
	 * 否则原版实体会误用上一个 MTR draw 的矩阵（症状：附近的生物/物品光照跟着车厢跑）。</p>
	 *
	 * <p>调用点：{@code BatchManager$RenderCall.draw()} 的 RETURN（MTR 优化渲染器每次真正 draw 的出口）。</p>
	 */
	public void clearPackModelMatrix() {
		if (!isEnabled() || !isUnderShaderpack()) {
			return;
		}
		final int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		final PackSamplerBinding binding = packSamplerBindings.get(programId);
		if (binding == null || binding.modelMatLocation < 0) {
			return;
		}
		packModelMatrixFloats.clear();
		for (int i = 0; i < 16; i++) {
			packModelMatrixFloats.put(0.0F);
		}
		packModelMatrixFloats.clear();
		GL20.glUniformMatrix4fv(binding.modelMatLocation, false, packModelMatrixFloats);
	}

	public void onDrawState(org.mtr.mapping.holder.Matrix4f modelMatrix, Integer lightmapUV) {
		// 光影包模式：每个 draw 都要把模型矩阵传下去（不能只在下面那段诊断里传 —— 诊断有配额）。
		if (modelMatrix != null) {
			uploadPackModelMatrix(modelMatrix);
		}

		// 车灯：一层全局 uniform（不随 draw 变），每个 (帧, program) 只传一次。
		// **必须在下面那个日志配额的提前返回之前**，否则开局 24 条日志用完之后车灯就再也不上传了。
		MmtrHeadlights.getInstance().uploadForCurrentProgram();

		if (drawStatesLogged >= MAX_DRAW_STATE_LOGS) {
			return;
		}
		if (modelMatrix == null) {
			drawStatesWithoutMatrix++;
			return;
		}

		try {
			matrixFloats.clear();
			Utilities.store(modelMatrix, matrixFloats);
			final double translateX = matrixFloats.get(12);
			final double translateY = matrixFloats.get(13);
			final double translateZ = matrixFloats.get(14);

			// 去重：同一个对象一帧会被画十几个材质组（矩阵一模一样），不去重的话配额全被它吃掉
			// —— 14:58 那次 24 条日志全给了几百格外的一件东西，车一根都没记到。
			if (Math.abs(translateX - lastDrawX) < 0.01 && Math.abs(translateY - lastDrawY) < 0.01 && Math.abs(translateZ - lastDrawZ) < 0.01) {
				return;
			}
			lastDrawX = translateX;
			lastDrawY = translateY;
			lastDrawZ = translateZ;
			drawStatesLogged++;

			// 两种口径同时算出来，配合"真实世界光照"一眼定胜负：
			//   A = 平移直接加相机位置（ModelViewMat 里没有相机旋转）
			//   B = 先把平移按 IViewRotMat 转回来（ModelViewMat 里有相机旋转 —— 即 shader 现在用的通用式）
			final double ax = translateX + lastCamX;
			final double ay = translateY + lastCamY;
			final double az = translateZ + lastCamZ;

			final Matrix3f inverseView = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
			final Vector3f rotated = new Vector3f((float) translateX, (float) translateY, (float) translateZ).mul(inverseView);
			final double bx = rotated.x + lastCamX;
			final double by = rotated.y + lastCamY;
			final double bz = rotated.z + lastCamZ;

			Init.LOGGER.info("[MMTR-LIGHT] draw #{}: 平移=({}, {}, {}) |T|={} light={} || A 口径世界=({}, {}, {}) 光={} || B 口径世界=({}, {}, {}) 光={} (光场采样 {} / {})",
					drawStatesLogged, round(translateX), round(translateY), round(translateZ),
					round(Math.sqrt(translateX * translateX + translateY * translateY + translateZ * translateZ)), lightmapUV,
					round(ax), round(ay), round(az), worldLightAt(ax, ay, az),
					round(bx), round(by), round(bz), worldLightAt(bx, by, bz),
					sampleCpu(ax, ay, az), sampleCpu(bx, by, bz));

			if (drawStatesLogged == 1) {
				// 每个 draw 这两个矩阵都一样，只在窗口第一条里各记一次：
				// 关键就是看 ModelViewMat 的旋转部分**是不是单位矩阵** —— 这就是 A/B 两种口径的分水岭。
				Init.LOGGER.info("[MMTR-LIGHT] 本窗口 mat3(ModelViewMat) = {}", rowMajor(new Matrix3f(new Matrix4f(RenderSystem.getModelViewMatrix()))));
				Init.LOGGER.info("[MMTR-LIGHT] 本窗口 IViewRotMat = {}", rowMajor(inverseView));
			}
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] draw state diagnostics failed", exception);
		}
	}

	private static String round(double value) {
		return String.format("%.2f", value);
	}

	private static double square(double value) {
		return value * value;
	}

	/**
	 * 真实世界光照值（诊断基准）：光场采样应当与它一致。
	 *
	 * <p>⚠️ 采样格点必须是**同一个格子**：片元与 {@link #sampleCpu} 取的都是
	 * {@code floor(世界坐标 − 0.5)}（见 §10 的坐标口径），这里若用 {@code floor(世界坐标)} 就是隔壁那格 ——
	 * 车厢贴着站台/道床时，两格的差别恰好是"空气（天空光 15）"与"实心（0/0）"，
	 * 于是诊断行会显示成"光场 0/0 而真实光照 6/15"，看起来像数据错了，其实是拿两格在比
	 * （2026-10-03 实机：`车心 AO … 格点实心=是` 已经把这件事写在旁边了）。</p>
	 */
	private String worldLightAt(double worldX, double worldY, double worldZ) {
		final ClientWorld currentWorld = world;
		if (currentWorld == null) {
			return "?";
		}
		cursor.set((int) Math.floor(worldX - 0.5), (int) Math.floor(worldY - 0.5), (int) Math.floor(worldZ - 0.5));
		return currentWorld.getLightLevel(LightType.BLOCK, cursor) + "/" + currentWorld.getLightLevel(LightType.SKY, cursor);
	}

	/** 与片元着色器 {@code mmtrFieldLight()} 同构的 CPU 采样（诊断用）。 */
	private String sampleCpu(double worldX, double worldY, double worldZ) {
		// 采样格点 = **绝对世界**方块中心 ⇒ floor(world - 0.5)。片元里是同一套（先把相机相对坐标
		// 用 LUT 元数据折回绝对坐标），两边必须逐字一致 —— §10 那个"走动时光照滑动"就是这个不一致。
		final int[] light = lightAtBlockCpu(
				(int) Math.floor(worldX - 0.5),
				(int) Math.floor(worldY - 0.5),
				(int) Math.floor(worldZ - 0.5));
		return light == null ? "无数据" : light[0] + "/" + light[1];
	}

	/** 光场采样（CPU 侧），逻辑与片元着色器的 {@code mmtrLightAt} 逐行对应；返回 null = 该 section 没数据。 */
	private int[] lightAtBlockCpu(int blockX, int blockY, int blockZ) {
		final int cellX = (blockX >> 4) - gridOriginX;
		final int cellY = (blockY >> 4) - gridOriginY;
		final int cellZ = (blockZ >> 4) - gridOriginZ;
		if (cellX < 0 || cellX >= GRID_X || cellY < 0 || cellY >= GRID_Y || cellZ < 0 || cellZ >= GRID_Z) {
			return null;
		}

		final int slot = lutCpu.get(cellX + (cellZ * GRID_Y + cellY) * LUT_W);
		if (slot <= 0 || slot > MAX_SECTIONS) {
			return null;
		}

		final int localX = blockX & 15;
		final int localY = blockY & 15;
		final int localZ = blockZ & 15;
		final int u = localX + (localZ & 3) * 16;
		final int v = localY + (localZ >> 2) * 16;
		final int value = atlasCpu.get((slot - 1) * SECTION_BYTES + u + v * TILE) & 0xFF;
		return new int[]{value & 15, (value >> 4) & 15};
	}

	// ---- AO 的 CPU 镜像（与 include 里的 mmtrAmbientOcclusion 逐行对应，用于 CPU/GPU 交叉核对） ----

	/** 光场实心位图里这个方块实不实心：1 = 实心，0 = 不实心，-1 = 这个 section 没数据。 */
	private int solidAtCpu(int blockX, int blockY, int blockZ) {
		final int cellX = (blockX >> 4) - gridOriginX;
		final int cellY = (blockY >> 4) - gridOriginY;
		final int cellZ = (blockZ >> 4) - gridOriginZ;
		if (cellX < 0 || cellX >= GRID_X || cellY < 0 || cellY >= GRID_Y || cellZ < 0 || cellZ >= GRID_Z) {
			return -1;
		}

		final int slot = lutCpu.get(cellX + (cellZ * GRID_Y + cellY) * LUT_W);
		if (slot <= 0 || slot > MAX_SECTIONS) {
			return -1;
		}

		final int bitIndex = (blockX & 15) + ((blockZ & 15) << 4) + ((blockY & 15) << 8);
		final int word = solidCpu.get((slot - 1) * SOLID_BYTES + (bitIndex >> 3)) & 0xFF;
		return (word >> (bitIndex & 7)) & 1;
	}

	/**
	 * 与片元着色器 {@code mmtrAmbientOcclusion()} 同构的 CPU 采样（诊断用）。
	 *
	 * <p>格点同样取 {@code floor(世界坐标 − 0.5)}（方块中心约定），法线是世界空间法线。
	 * 返回值：NaN = 这个位置的 section 没数据；否则 0.2..1 的 AO 乘数。</p>
	 */
	private double sampleAoCpu(double worldX, double worldY, double worldZ, double normalX, double normalY, double normalZ) {
		final double strength = aoStrengthPermille / 1000.0;
		if (strength <= 0) {
			return 1.0;
		}

		final double exactX = worldX - 0.5;
		final double exactY = worldY - 0.5;
		final double exactZ = worldZ - 0.5;
		final int baseX = (int) Math.floor(exactX);
		final int baseY = (int) Math.floor(exactY);
		final int baseZ = (int) Math.floor(exactZ);
		final double tX = exactX - baseX;
		final double tY = exactY - baseY;
		final double tZ = exactZ - baseZ;

		// 3×3×3 实心位图（bit 序号与 GLSL mmtrSolidRing 一致）
		int solidMask = 0;
		int unknown = 0;
		for (int y = -1; y <= 1; y++) {
			for (int z = -1; z <= 1; z++) {
				for (int x = -1; x <= 1; x++) {
					final int solid = solidAtCpu(baseX + x, baseY + y, baseZ + z);
					if (solid < 0) {
						unknown++;
					} else if (solid == 1) {
						solidMask |= 1 << ((x + 1) + (z + 1) * 3 + (y + 1) * 9);
					}
				}
			}
		}
		if (unknown == 27) {
			return Double.NaN;
		}
		if ((solidMask & 0x7FFFFFF) == 0x7FFFFFF) {
			return 1.0 + (0.2 - 1.0) * Math.min(strength, 1.0);
		}

		double nX = normalX;
		double nY = normalY;
		double nZ = normalZ;
		final double length = Math.sqrt(nX * nX + nY * nY + nZ * nZ);
		if (length < 1e-4) {
			nX = 0;
			nY = 1;
			nZ = 0;
		} else {
			nX /= length;
			nY /= length;
			nZ /= length;
		}

		double ao = 0;
		if (nX > 1e-5) {
			ao += nX * nX * directionAoCpu(solidMask, 1, 4, 10, 13, tX, tY, tZ);
		} else if (nX < -1e-5) {
			ao += nX * nX * directionAoCpu(solidMask, 0, 3, 9, 12, tX, tY, tZ);
		}
		if (nY > 1e-5) {
			ao += nY * nY * directionAoCpu(solidMask, 9, 12, 10, 13, tX, tY, tZ);
		} else if (nY < -1e-5) {
			ao += nY * nY * directionAoCpu(solidMask, 0, 3, 1, 4, tX, tY, tZ);
		}
		if (nZ > 1e-5) {
			ao += nZ * nZ * directionAoCpu(solidMask, 3, 12, 4, 13, tX, tY, tZ);
		} else if (nZ < -1e-5) {
			ao += nZ * nZ * directionAoCpu(solidMask, 0, 9, 1, 10, tX, tY, tZ);
		}

		return 1.0 + (ao - 1.0) * Math.min(strength, 1.0);
	}

	/** 一个方向上的 AO（8 个角各自算出 AO 后三线性插值）—— 与 GLSL {@code mmtrAoForDirection} 对应。 */
	private static double directionAoCpu(int solidMask, int base0, int base1, int base2, int base3, double tX, double tY, double tZ) {
		final double[] corners = new double[8];
		corners[0] = cornerAoCpu(solidMask, base0, base1, base2, base3, 0);
		corners[1] = cornerAoCpu(solidMask, base0, base1, base2, base3, 1);
		corners[2] = cornerAoCpu(solidMask, base0, base1, base2, base3, 3);
		corners[3] = cornerAoCpu(solidMask, base0, base1, base2, base3, 4);
		corners[4] = cornerAoCpu(solidMask, base0, base1, base2, base3, 9);
		corners[5] = cornerAoCpu(solidMask, base0, base1, base2, base3, 10);
		corners[6] = cornerAoCpu(solidMask, base0, base1, base2, base3, 12);
		corners[7] = cornerAoCpu(solidMask, base0, base1, base2, base3, 13);

		final double ao00 = mix(corners[0], corners[1], tX);
		final double ao01 = mix(corners[2], corners[3], tX);
		final double ao10 = mix(corners[4], corners[5], tX);
		final double ao11 = mix(corners[6], corners[7], tX);
		return mix(mix(ao00, ao01, tZ), mix(ao10, ao11, tZ), tY);
	}

	/** 一个角周围 4 个方块里的实心数 → AO（1 − 0.2 × 实心数，与 Flywheel 的 validCount 公式等价）。 */
	private static double cornerAoCpu(int solidMask, int base0, int base1, int base2, int base3, int corner) {
		int solidCount = 0;
		solidCount += (solidMask >> (base0 + corner)) & 1;
		solidCount += (solidMask >> (base1 + corner)) & 1;
		solidCount += (solidMask >> (base2 + corner)) & 1;
		solidCount += (solidMask >> (base3 + corner)) & 1;
		return 1.0 - 0.2 * solidCount;
	}

	private static double mix(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/**
	 * 一个 AO 探针的可读输出：AO 值 + "采样格点那个方块实不实心"。
	 *
	 * <p>为什么要连着实心位一起打：这两个值合起来**自身就能证伪**。
	 * 车厢中心是空气（实心=否、AO 应当 ≈1），车心下方几格多半在道床/结构里（实心=是、AO 应当明显 &lt;1）。
	 * 如果位序或行号写错了（Java 与 GLSL 对不上），这两个探针要么都恒为 1、要么乱跳 ——
	 * 配合假色截图（蓝通道 = AO）就能定位到底是"数据没采"还是"编码错"。</p>
	 */
	private String aoProbeCpu(double worldX, double worldY, double worldZ) {
		final double ao = sampleAoCpu(worldX, worldY, worldZ, 0, 1, 0);
		final int solid = solidAtCpu((int) Math.floor(worldX - 0.5), (int) Math.floor(worldY - 0.5), (int) Math.floor(worldZ - 0.5));
		return String.format("AO=%s（格点实心=%s）",
				Double.isNaN(ao) ? "无数据" : String.format("%.2f", ao),
				solid < 0 ? "无数据" : solid == 1 ? "是" : "否");
	}

	/**
	 * 一次性验证"CPU 里的光场数据真的到了 GPU 纹理里"：把图集/LUT 读回来统计非零纹素。
	 * <b>必须等真的采过数据之后再看</b>（第一帧两个纹理本来就该是空的 —— 早先那次 nonZero=0 就是这个原因）。
	 * 现在 CPU 侧的非零数也一起打出来，好区分"没数据"和"没上传"。</p>
	 */
	private void verifyGpuTexturesOnce() {
		if (gpuTexturesVerified || atlasTex == 0 || lutTex == 0 || sectionToSlot.isEmpty() || collectedSections == 0) {
			return;
		}
		gpuTexturesVerified = true;

		try {
			int cpuNonZero = 0;
			for (int i = 0; i < MAX_SECTIONS * SECTION_BYTES; i++) {
				if (atlasCpu.get(i) != 0) {
					cpuNonZero++;
				}
			}

			final int previousActive = GlStateManager._getActiveTexture();
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);

			GlStateManager._bindTexture(atlasTex);
			final ByteBuffer atlasReadback = BufferUtils.createByteBuffer(ATLAS_SIZE * ATLAS_SIZE);
			preparePackState(1);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, atlasReadback);
			int atlasNonZero = 0;
			int atlasSum = 0;
			for (int i = 0; i < ATLAS_SIZE * ATLAS_SIZE; i++) {
				final int value = atlasReadback.get(i) & 0xFF;
				if (value != 0) {
					atlasNonZero++;
				}
				atlasSum += value;
			}

			// 实心位图也读回来一次：直接证明"AO 的输入数据真的到了 GPU 且不是一片零"
			// （0.2 秒一次的一次性回读，128 KB；不会留在热路径上）
			GlStateManager._bindTexture(solidTex);
			final ByteBuffer solidReadback = BufferUtils.createByteBuffer(MAX_SECTIONS * SOLID_BYTES);
			preparePackState(1);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, solidReadback);
			int solidNonZeroBytes = 0;
			int solidSolidCount = 0;
			for (int i = 0; i < MAX_SECTIONS * SOLID_BYTES; i++) {
				final int value = solidReadback.get(i) & 0xFF;
				if (value != 0) {
					solidNonZeroBytes++;
				}
				solidSolidCount += Integer.bitCount(value);
			}

			GlStateManager._bindTexture(lutTex);
			final IntBuffer lutReadback = BufferUtils.createIntBuffer(LUT_W * LUT_H);
			preparePackState(4);
			GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, lutReadback);
			int lutNonZero = 0;
			final StringBuilder slots = new StringBuilder();
			for (int i = 0; i < LUT_W * LUT_H; i++) {
				if (lutReadback.get(i) != 0) {
					lutNonZero++;
					if (slots.length() < 60) {
						slots.append(lutReadback.get(i)).append('@').append(i).append(' ');
					}
				}
			}

			// CPU 侧非零数：与 GPU 侧对比就能分开"LUT 本来就没写"和"写了但没上传"
			int lutCpuNonZero = 0;
			for (int i = 0; i < LUT_W * LUT_H; i++) {
				if (lutCpu.get(i) != 0) {
					lutCpuNonZero++;
				}
			}
			lastGpuLutNonZero = lutNonZero;

			GlStateManager._activeTexture(previousActive);

			Init.LOGGER.info("[MMTR-LIGHT] GPU readback: atlas nonZero={} sum={} (cpu nonZero={}) | 实心位图 非零字节={} 实心方块={} | lut nonZero={}/{} (cpu nonZero={}) [{}] | glError={} | slots={}",
					atlasNonZero, atlasSum, cpuNonZero, solidNonZeroBytes, solidSolidCount,
					lutNonZero, LUT_W * LUT_H, lutCpuNonZero, slots.toString().trim(), GL11.glGetError(), sectionToSlot.size());

			// 回读是有代价的：只有真的看到 LUT 有数据（或试够次数）才收手 ——
			// 早先那份一次性实现正好落在"相机附近还没有任何 section"的那一帧，于是"LUT 全零"
			// 这个结论本身就不可信（诊断误报，白查了半天）。
			if (lutNonZero > 0 || ++gpuReadbackAttempts >= 6) {
				gpuTexturesVerified = true;
			}
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] gpu readback failed", exception);
			gpuTexturesVerified = true;
		}
	}

	/** 让出资源（世界卸载 / 关服）。 */
	public void reset(ClientWorld newWorld) {
		boundWorld = newWorld;
		world = newWorld;
		sectionToSlot.clear();
		slotInUse.clear();
		slotDirty.clear();
		slotReady.clear();
		requested.clear();
		queued.clear();
		collectQueue.clear();
		java.util.Arrays.fill(slotToSection, EMPTY);
		java.util.Arrays.fill(slotLastFrame, 0);
		atlasCpu.clear();
		atlasCpu.put(new byte[MAX_SECTIONS * SECTION_BYTES]);
		atlasCpu.clear();
		solidCpu.clear();
		solidCpu.put(new byte[MAX_SECTIONS * SOLID_BYTES]);
		solidCpu.clear();
		solidDirty.clear();
		for (int slot = 0; slot < MAX_SECTIONS; slot++) {
			solidDirty.set(slot);
		}
		lutCpu.clear();
		lutCpu.put(new int[LUT_W * LUT_H]);
		lutCpu.clear();
		lutDirty = true;
		lastCamX = Double.NaN;
		lastCamY = Double.NaN;
		lastCamZ = Double.NaN;
	}

	// ------------------------------------------------------------------ 槽位 / 采集

	private int ensureSlot(long key) {
		final Integer existing = sectionToSlot.get(key);
		if (existing != null) {
			/*
			 * 两种"要重采"：
			 *   · 还没采满（notes/367：光场**不发布**没采满的槽位，所以"采不上就一直不亮"必须避免）；
			 *   · 采过但已经过时（滚动刷新，见 REFRESH_MILLIS）—— markChunkDirty 没有调用点，
			 *     这条就是"光照变了就重采"的落点。
			 * 两条都按节拍来：零光重试 1 s、正常刷新 3 s，避免一个永远采不到好数据的 section
			 * 每帧都去挤采集预算（队列按离相机远近排序，它就是最近的，会把别人饿死）。
			 */
			final long sinceCollected = System.currentTimeMillis() - slotCollectedMillis[existing];
			// 零光的那一节按**退避**重试（1 s、2 s、3 s…最多 8 s）：真的全暗的一节不能每秒去挤采集预算。
			final long due = slotReady.get(existing) ? REFRESH_MILLIS
					: ZERO_RETRY_MILLIS * Math.max(1, Math.min(8, slotZeroRefusals[existing]));
			if (sinceCollected >= due && !queued.contains(key)) {
				enqueueCollect(key);
			}
			return existing;
		}

		for (int slot = 1; slot <= MAX_SECTIONS; slot++) {
			if (!slotInUse.get(slot)) {
				slotInUse.set(slot);
				sectionToSlot.put(key, slot);
				slotToSection[slot] = key;
				slotDirty.set(slot - 1);
				slotReady.clear(slot);
				slotZeroRefusals[slot] = 0;
				slotCollectedMillis[slot] = System.currentTimeMillis();
				clearAtlasTile(slot);
				clearSolidRow(slot);
				lutDirty = true;
				enqueueCollect(key);
				return slot;
			}
		}

		// 槽位耗尽：挤掉最久没被请求的那个
		int oldestSlot = 1;
		int oldestFrame = Integer.MAX_VALUE;
		for (int slot = 1; slot <= MAX_SECTIONS; slot++) {
			if (slotLastFrame[slot] < oldestFrame) {
				oldestFrame = slotLastFrame[slot];
				oldestSlot = slot;
			}
		}
		releaseSlot(oldestSlot);
		slotInUse.set(oldestSlot);
		sectionToSlot.put(key, oldestSlot);
		slotToSection[oldestSlot] = key;
		slotDirty.set(oldestSlot - 1);
		slotReady.clear(oldestSlot);
		slotZeroRefusals[oldestSlot] = 0;
		slotCollectedMillis[oldestSlot] = System.currentTimeMillis();
		clearAtlasTile(oldestSlot);
		clearSolidRow(oldestSlot);
		lutDirty = true;
		enqueueCollect(key);
		return oldestSlot;
	}

	/**
	 * 清空某个槽位的光图块（4096 字节）。
	 *
	 * <p>为什么与 {@code slotReady} 两道都要（notes/367）：{@code slotReady} 保证"没采满的槽位不进 LUT"，
	 * 这一句保证**即使**哪条路径漏了闸，也不可能采到"上一节的光" —— 槽位是复用的，
	 * 光图集的旧数据虽然只是"过时"，但迁到另一个 section 上就是"凭空的另一个地方的光"。
	 * 4 KB 的 memset，只在分槽时发生，可以忽略。</p>
	 */
	private void clearAtlasTile(int slot) {
		final int base = (slot - 1) * SECTION_BYTES;
		for (int i = 0; i < SECTION_BYTES; i++) {
			atlasCpu.put(base + i, (byte) 0);
		}
		slotDirty.set(slot - 1);
	}

	/**
	 * 清空某个槽位的实心位图行。
	 *
	 * <p>为什么必须在分槽时清：槽位是会被回收复用的，而实心位图与光图集不同 —— 光图集的旧数据只是"过时"，
	 * 实心位图的旧数据会**凭空产生遮蔽**（把某个素不相识的 section 的墙当成这里的墙），
	 * 表现是"车开到某处突然整片发暗"。清成 0 = 不实心 ⇒ AO = 1（无遮蔽），是最保守的初值。</p>
	 */
	private void clearSolidRow(int slot) {
		final int base = (slot - 1) * SOLID_BYTES;
		for (int i = 0; i < SOLID_BYTES; i++) {
			solidCpu.put(base + i, (byte) 0);
		}
		solidDirty.set(slot - 1);
	}

	private void releaseSlot(int slot) {
		if (!slotInUse.get(slot)) {
			return;
		}
		final long key = slotToSection[slot];
		if (key != EMPTY) {
			sectionToSlot.remove(key);
			queued.remove(key);
		}
		slotToSection[slot] = EMPTY;
		slotInUse.clear(slot);
		slotReady.clear(slot);
		slotZeroRefusals[slot] = 0;
		slotCollectedMillis[slot] = 0;
		lutDirty = true;
	}

	private void expireSlots() {
		for (int slot = 1; slot <= MAX_SECTIONS; slot++) {
			if (slotInUse.get(slot) && frame - slotLastFrame[slot] > EXPIRE_FRAMES) {
				releaseSlot(slot);
			}
		}
	}

	private void enqueueCollect(long key) {
		if (queued.add(key)) {
			collectQueue.add(new CollectTask(key, 0));
		}
	}

	private void collectSlice() {
		final ClientWorld currentWorld = world;
		if (currentWorld == null || collectQueue.isEmpty()) {
			return;
		}

		// 优先级：先采**离相机最近**的 section，而不是按 FIFO 排。
		// 原因：一列车一次能登记几十个 section，FIFO 时"玩家正看着的那节车"可能排在几十个 section 后面，
		// 于是它一直停在"槽位已分配、图块还是零"的状态 —— 假色诊断图里那些**全黑的车体/钢轨**就是这个。
		final double camX = lastCamX;
		final double camY = lastCamY;
		final double camZ = lastCamZ;
		CollectTask task = null;

		if (Double.isNaN(camX)) {
			task = collectQueue.peek();
		} else {
			double bestDistance = Double.MAX_VALUE;
			for (final CollectTask candidate : collectQueue) {
				final double dx = keyX(candidate.key) * 16.0 + 8 - camX;
				final double dy = keyY(candidate.key) * 16.0 + 8 - camY;
				final double dz = keyZ(candidate.key) * 16.0 + 8 - camZ;
				final double distance = dx * dx + dy * dy + dz * dz;
				if (distance < bestDistance) {
					bestDistance = distance;
					task = candidate;
				}
			}
		}

		if (task == null) {
			return;
		}

		final Integer slotObject = sectionToSlot.get(task.key);
		if (slotObject == null) {
			collectQueue.remove(task);
			queued.remove(task.key);
			return;
		}

		final int slot = slotObject;
		final int base = (slot - 1) * SECTION_BYTES;
		final int solidBase = (slot - 1) * SOLID_BYTES;
		final int sectionX = keyX(task.key) << 4;
		final int sectionY = keyY(task.key) << 4;
		final int sectionZ = keyZ(task.key) << 4;

		// 区块**没到手**就别采（notes/367）：此刻读到的只会是"空气 + 光 0"，采了就等于把这一节
		// 钉成纯黑（而且 `slotReady` 没置位，所以它不会进 LUT —— 车厢退回 per-draw 光，可预测）。
		// 任务丢掉、槽位留着：区块到手后 `ensureSlot` 会因为"没采满"重新入队。
		if (!isSectionLoaded(keyX(task.key), keyZ(task.key))) {
			collectQueue.remove(task);
			queued.remove(task.key);
			return;
		}

		final int end = Math.min(16, task.nextLayer + COLLECT_LAYERS_PER_FRAME);

		for (int z = task.nextLayer; z < end; z++) {
			for (int y = 0; y < 16; y++) {
				// 实心位图：一行 (y,z) = 2 字节 = 16 个 x 的 bit。先在寄存器里攒，最后落 2 个字节 ——
				// 逐 bit 读改写会是 4096 次 ByteBuffer 读改写，白花时间。
				int solidMask = 0;
				for (int x = 0; x < 16; x++) {
					cursor.set(sectionX + x, sectionY + y, sectionZ + z);
					final int block = currentWorld.getLightLevel(LightType.BLOCK, cursor);
					final int sky = currentWorld.getLightLevel(LightType.SKY, cursor);
					// 槽内布局：u = x + (z & 3) * 16，v = y + (z >> 2) * 16（与 GLSL 一致）
					final int u = x + (z & 3) * 16;
					final int v = y + (z >> 2) * 16;
					atlasCpu.put(base + u + v * TILE, (byte) ((block & 15) | ((sky & 15) << 4)));

					// AO 用的实心判据 = 原版 AO 用的那一个（net.minecraft.client.render.block.
					// BlockModelRenderer$AmbientOcclusionCalculator 里调的也是 isOpaqueFullCube），
					// 所以"我们算出来的遮蔽"和"地形自己烘出来的遮蔽"是同一套口径。
					if (currentWorld.getBlockState(cursor).isOpaqueFullCube(currentWorld, cursor)) {
						solidMask |= 1 << x;
					}
				}
				// 位序：bit = x + (z << 4) + (y << 8) ⇒ 行内偏移 2 * z + 32 * y，低字节 = x 0..7
				solidCpu.put(solidBase + 2 * z + 32 * y, (byte) (solidMask & 0xFF));
				solidCpu.put(solidBase + 2 * z + 32 * y + 1, (byte) ((solidMask >> 8) & 0xFF));
			}
		}

		task.nextLayer = end;
		slotDirty.set(slot - 1);
		solidDirty.set(slot - 1);

		if (end >= 16) {
			collectQueue.remove(task);
			queued.remove(task.key);
			collectedSections++;
			slotCollectedMillis[slot] = System.currentTimeMillis();
			/*
			 * ★ 采满 ≠ 数据可信：**"纯空气 + 零光"的 section 是"区块还没到手"，不是"那里没光"**
			 *   （空区块占位的机制与判据见 slotZeroRefusals 的注释）。
			 *   这种一节绝不能发布：LUT 里槽位 ≠ 0 就是着色器眼里的"这里有数据"，
			 *   发布出去等于把车厢钉成纯黑，而且因为 markChunkDirty 没有调用点，它会一直黑下去。
			 *   处理：不置 slotReady（LUT 那一格写 0 ⇒ 着色器退回 MTR 的 per-draw 光，可预测）、
			 *   清掉图块与实心行、按节拍重采（ensureSlot 里 1 s 一次），直到真区块把数据换上来。
			 */
			if (summarizeSection(slot, task.key)) {
				slotZeroRefusals[slot]++;
				zeroRefusals++;
				slotReady.clear(slot);
				clearAtlasTile(slot);
				clearSolidRow(slot);
				lutDirty = true;
				if (slotZeroRefusals[slot] == 1) {
					Init.LOGGER.info("[MMTR-LIGHT] 零光 section ⇒ 不发布、等真区块（第 {} 次重采）：{}", slotZeroRefusals[slot], lastSectionSummary);
				}
				// 诊断行里带标记：否则"最近采到的一节"看上去还是一节全零的 section，像是没修。
				lastSectionSummary = "【未发布·零光，等真区块】" + lastSectionSummary;
			} else {
				// 采满整整一节 = 从这一刻起才准进 LUT（notes/367）
				slotReady.set(slot);
			}
		}
	}

	/**
	 * 把一个刚采完的 section 的光照范围打进日志 —— 这是"光场里真的有数据、不是一片零"的硬证据。
	 * （只扫 4096 个字节，忽略不计。）
	 *
	 * @return **这一节是不是"纯空气 + 零光"**（= 区块还没到手，不能当数据发布；见 {@link #slotZeroRefusals}）
	 */
	private boolean summarizeSection(int slot, long key) {
		final int base = (slot - 1) * SECTION_BYTES;
		final int solidBase = (slot - 1) * SOLID_BYTES;
		int blockMin = 15;
		int blockMax = 0;
		int blockSum = 0;
		int skyMin = 15;
		int skyMax = 0;
		int skySum = 0;

		for (int i = 0; i < 16 * 16 * 16; i++) {
			// 注意步长是 1（早先写成 i * 2，等于只扫了一半的方块、还跨到别的 z 层去了 —— 诊断数据是错的）
			final int value = atlasCpu.get(base + i) & 0xFF;
			final int block = value & 15;
			final int sky = (value >> 4) & 15;
			blockMin = Math.min(blockMin, block);
			blockMax = Math.max(blockMax, block);
			blockSum += block;
			skyMin = Math.min(skyMin, sky);
			skyMax = Math.max(skyMax, sky);
			skySum += sky;
		}

		// 实心位图的比例：AO 的输入数据有没有真的采进来，这一眼就能看出来
		// （车厢所在的 section 通常是几个百分点，道床/隧道里的 section 会很高）。
		int solidCount = 0;
		for (int i = 0; i < SOLID_BYTES; i++) {
			solidCount += Integer.bitCount(solidCpu.get(solidBase + i) & 0xFF);
		}

		lastSectionSummary = String.format("section(%d,%d,%d) slot=%d block %d..%d avg %.2f / sky %d..%d avg %.2f / 实心 %.1f%%",
				keyX(key), keyY(key), keyZ(key), slot, blockMin, blockMax, blockSum / 4096.0, skyMin, skyMax, skySum / 4096.0, solidCount / 40.96);

		return blockMax == 0 && skyMax == 0 && solidCount == 0;
	}

	/**
	 * 什么时候必须重载一次资源：凡是"着色器会不会被换掉"的输入变了就要重载。
	 *
	 * <p>为什么必须自动做：MTR 只**在资源重载时**重新请求着色器（{@code ShaderManager.reloadShaders()}），
	 * 所以下面三种情况不重载的话，一直在跑的会是**上一份编译好的 program**，现象是"设置改了但画面没变"：</p>
	 * <ul>
	 *   <li>{@code reload=<新值>}（nonce）：手动触发，用来切假色诊断着色器；</li>
	 *   <li><b>光影包被关掉</b>：Iris 在跑的时候 {@code canUseCustomShader()} 为 false，我们故意不换着色器；
	 *       用户一关光影包它变回 true —— 但 MTR 手里那份 program 还是"无光场"的。
	 *       <b>这正是"现在开不开光影又都是老式的"的成因</b>（实测 16:30 开包、16:35 关包，光场统计一直在跑
	 *       但画面是老式的 per-draw 光，直到 16:35:18 手动重载才恢复）；</li>
	 *   <li>{@code enabled=false/true}：光场总开关，用来做"不增加 draw call"的同机位 A/B。</li>
	 * </ul>
	 */
	private void reloadIfShaderInputsChanged() {
		final boolean canUseCustomShader = Utilities.canUseCustomShader();
		final boolean enabled = isEnabled();

		boolean reload = false;
		String reason = null;

		if (!diagnosticsReloadNonce.isEmpty() && !diagnosticsReloadNonce.equals(lastReloadNonce)) {
			lastReloadNonce = diagnosticsReloadNonce;
			reload = true;
			reason = "reload=" + diagnosticsReloadNonce;
		}

		if (lastCanUseCustomShaderState == null) {
			lastCanUseCustomShaderState = canUseCustomShader;
			Init.LOGGER.info("[MMTR-LIGHT] canUseCustomShader 初始 = {}（false = 有光影包在跑，光场不介入）", canUseCustomShader);
		} else if (canUseCustomShader != lastCanUseCustomShaderState) {
			lastCanUseCustomShaderState = canUseCustomShader;
			if (canUseCustomShader) {
				// 只有"变回可用"才需要重载（变不可用时 MTR 自己会因为 program 失效而重载）。
				reload = true;
				reason = "光影包已停用，把带光场的着色器装回去";
			} else {
				Init.LOGGER.info("[MMTR-LIGHT] 光影包已启用 → 光场不介入（MTR 会走回原生 RenderLayer 路径）");
			}
		}

		if (lastEnabledState == null) {
			lastEnabledState = enabled;
			Init.LOGGER.info("[MMTR-LIGHT] enabled={}（properties 可运行时切换，会自动重载着色器）", enabled);
		} else if (enabled != lastEnabledState) {
			lastEnabledState = enabled;
			reload = true;
			reason = "enabled=" + enabled;
		}

		if (reload && world != null) {
			Init.LOGGER.info("[MMTR-LIGHT] 触发资源重载：{}", reason);
			// 注入文本是**按会话缓存**的（Iris 一次包装载会问几百个文件，每次都读盘太浪费）。
			// 但资源重载会重新装载光影包、重新走一遍注入 —— 那时必须重读自己的 GLSL，
			// 否则改了 include 却看不到效果（只能重启客户端）。所以在这里把缓存作废。
			org.mtr.mod.render.light.MmtrShaderPackLightField.getInstance().invalidate();
			try {
				MinecraftClient.getInstance().reloadResources();
			} catch (Exception exception) {
				Init.LOGGER.error("[MMTR-LIGHT] 资源重载失败", exception);
			}
		}
	}

	/** 诊断用自动截图（run/mmtr-lightfield.properties: screenshot=N）。在优化渲染器画完之后调用，拿到的是这一帧的世界。 */
	public void maybeCaptureScreenshot() {
		refreshDiagnostics();
		reloadIfShaderInputsChanged();

		if (diagnosticsScreenshotCount != lastScreenshotRequest) {
			// 改了 properties 里的 screenshot=N 就重新开始记数（否则第一轮用完之后再也截不到图 —— 实测踩过）。
			lastScreenshotRequest = diagnosticsScreenshotCount;
			screenshotsTaken = 0;
		}

		if (diagnosticsScreenshotCount <= 0 || screenshotsTaken >= diagnosticsScreenshotCount || world == null) {
			return;
		}

		final long now = System.currentTimeMillis();
		if (now - lastScreenshotMillis < DIAGNOSTICS_INTERVAL_MILLIS) {
			return;
		}

		lastScreenshotMillis = now;
		screenshotsTaken++;
		try {
			final MinecraftClient client = MinecraftClient.getInstance();
			ScreenshotRecorder.saveScreenshot(client.runDirectory, client.getFramebuffer(), text -> {
			});
			Init.LOGGER.info("[MMTR-LIGHT] screenshot {}/{} saved", screenshotsTaken, diagnosticsScreenshotCount);
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] screenshot failed", exception);
		}
	}

	// ------------------------------------------------------------------ LUT / 上传

	private void rebuildLut(double camX, double camY, double camZ) {
		lutCpu.clear();
		lutCpu.put(new int[LUT_W * LUT_H]);
		lutCpu.clear();

		// 元数据列（x = GRID_X）：
		//   行 0/1/2 = 相机所在**方块**坐标（整数，floor）
		//   行 3/4/5 = 小数部分 × 65536（定点，精度 1/65536 格）
		//   行 6     = AO 强度 × 1000（0 = 关掉 AO，着色器连那 27 次实心取位都不做）
		//
		// ⚠️ 必须把**精确**位置传过去，不能只传 floor：着色器只有"相机相对坐标"，
		//    若它加的是整数相机坐标，就差了 frac(相机位置)，而 frac 每走一格扫一个周期 ——
		//    实测症状"图案随走动滑动、走一段又重置回正确位置、如此反复"就是这个（周期 = 1 格）。
		//    传精确定点值之后 exact = relWorld + camPos − 0.5 **正好**等于"世界坐标 − 0.5"，
		//    取整得到的绝对方块坐标与 CPU 完全一致，没有任何残余漂移。
		//    （不用 float 位模式：GLSL 1.50 没有 floatBitsToUint/intBitsToFloat，离线编译器会直接报错。）
		lutCpu.put(GRID_X + 0 * LUT_W, cameraBlockX);
		lutCpu.put(GRID_X + 1 * LUT_W, cameraBlockY);
		lutCpu.put(GRID_X + 2 * LUT_W, cameraBlockZ);
		lutCpu.put(GRID_X + 3 * LUT_W, (int) Math.round((camX - cameraBlockX) * 65536.0));
		lutCpu.put(GRID_X + 4 * LUT_W, (int) Math.round((camY - cameraBlockY) * 65536.0));
		lutCpu.put(GRID_X + 5 * LUT_W, (int) Math.round((camZ - cameraBlockZ) * 65536.0));
		lutCpu.put(GRID_X + 6 * LUT_W, aoStrengthPermille);

		int cells = 0;
		int tracked = 0;

		for (int slot = 1; slot <= MAX_SECTIONS; slot++) {
			final long key = slotToSection[slot];
			if (key == EMPTY) {
				continue;
			}
			tracked++;

			// ★ **只发布采满过的槽位**（notes/367）：LUT 里"槽位 ≠ 0"就是着色器眼里
			//   "这一节有数据、用光场的值"。没采满的槽位图块是零（或被清过），发布出去 =
			//   把车厢整节画成纯黑。不发布 ⇒ 格子是 0 ⇒ 退回 MTR 的 per-draw 光。
			//   代价只是"这一节头几帧走 per-draw 光"—— 那正是它原来一直的样子。
			if (!slotReady.get(slot)) {
				continue;
			}

			// **绝对** section → 格号：一对一。
			// 早先这里按"相机相对 section"写、还写 ±1 邻域，是因为相机位置的小数部分会让一个世界 section
			// 跨两个相对 section；统一到绝对坐标之后这个麻烦整体消失了（§10）。
			final int cellX = keyX(key) - gridOriginX;
			final int cellY = keyY(key) - gridOriginY;
			final int cellZ = keyZ(key) - gridOriginZ;
			if (cellX < 0 || cellX >= GRID_X || cellY < 0 || cellY >= GRID_Y || cellZ < 0 || cellZ >= GRID_Z) {
				continue;
			}

			lutCpu.put(cellX + (cellZ * GRID_Y + cellY) * LUT_W, slot);
			cells++;
		}

		lutCpu.clear();
		lutDirty = false;
		lastLutTrackedSections = tracked;
		lastLutCellsWritten = cells;
	}

	/**
	 * 这一批（材质组）该用哪张 LUT 纹理 —— MTR 的 {@code ShaderManager.setupShaderBatchState()} 会在
	 * 每个批次开始时从 {@code RenderSystem} 取 Sampler3/4 绑上去，所以"按批次换采样器"就是
	 * **让某一类几何不参与光场**的手段：
	 *
	 * <p><b>钢轨不参与光场</b>（用户 2026-09-28 决定）。原因：钢轨是固定的世界几何，MTR 给它的光照是
	 * 每段一个常量（{@code RenderRails} 在段中点采一次光），本来就有台阶；而光场只覆盖"车辆登记过的
	 * section"，于是车附近的钢轨会走光场、远处走原版 ⇒ 长轨道上会出现一条**网格边界亮度台阶**。
	 * 干脆整类排除，行为可预测。</p>
	 *
	 * <p>实现：给钢轨批次绑一张**全零的 LUT**。着色器查表命中槽位 0 = 该 section 没数据
	 * ⇒ {@code mmtrFieldLight()} 返回"无效" ⇒ 自动退回 {@code lightMapColor}（MTR 原来的 per-draw 光）。
	 * 全程不需要改一行 GLSL，也不影响批次划分/draw call。</p>
	 *
	 * @param texture 该批次的贴图（{@code MaterialProperties.getTexture()}）
	 * @return 要绑到 Sampler3 的纹理
	 */
	public int lutTextureForBatch(org.mtr.mapping.holder.Identifier texture) {
		if (texture != null && isRailTexture(texture.getPath())) {
			railBatchCount++;
			if (railTextureLogs < MAX_RAIL_TEXTURE_LOGS) {
				railTextureLogs++;
				Init.LOGGER.info("[MMTR-LIGHT] 钢轨批次（不参与光场）：贴图 {}:{}", texture.getNamespace(), texture.getPath());
			}
			return dummyLutTex;
		}

		vehicleBatchCount++;
		return lutTex;
	}

	/**
	 * 是不是 3D 钢轨的贴图。
	 *
	 * <p>内建 3D 钢轨来自 {@code mtr_custom_resources.json} 的
	 * {@code "modelResource": "mtr:models/rail/rail.obj"} / {@code rail_siding.obj}，
	 * 贴图与模型同目录（{@code mtr:models/rail/rail.png}、{@code rail_siding.png}）。</p>
	 *
	 * <p>判据故意放宽到"路径里含 {@code /rail/}"并以日志把**第一次**判成钢轨的贴图打出来 ——
	 * 万一某个车辆贴图被误判，日志里一眼能看到（宁可先看见，不要先猜）。
	 * 资源包自定义的 3D 钢轨若把贴图放在别处，这条判据识别不了（会继续参与光场）。</p>
	 */
	private static boolean isRailTexture(String path) {
		return path.contains("/rail/") || path.endsWith("/rail.png") || path.endsWith("/rail_siding.png");
	}

	/** 设置本批次要用的 LUT 采样器（见 {@link #lutTextureForBatch}）。由 {@code ShaderManagerMixin} 调。 */
	public void bindLutForBatch(org.mtr.mapping.holder.Identifier texture) {
		final int lut = lutTextureForBatch(texture);
		if (isUnderShaderpack()) {
			// 光影包模式：批次级的采样器由 bindPackSamplers 在 program 绑好之后处理（不能用 3/4/5）。
			lastPackLut = lut;
			return;
		}
		RenderSystem.setShaderTexture(SAMPLER_LUT, lut);
	}

	/**
	 * 光影包模式：把光场的三个采样器挂到**当前 program** 上（由 {@code ShaderManagerMixin} 在
	 * {@code setupShaderBatchState} **RETURN** 处调用 —— 那时 program 已 bind、Iris 自己的采样器也绑完了）。
	 *
	 * <p>做法：按 GL program id 缓存"uniform 位置 + 三个空闲纹理单元"。空闲单元是**枚举该程序所有 sampler
	 * uniform 反查出来的**（Iris 从低往高分配，我们绝不碰它用到的那些），而不是写死 3/4/5。</p>
	 *
	 * @param texture 该批次的贴图（钢轨批次给全零 LUT ⇒ 与无光影时一致的"不参与光场"语义）
	 */
	public void bindPackSamplers(org.mtr.mapping.holder.Identifier texture) {
		if (!isEnabled() || !isUnderShaderpack() || atlasTex == 0 || lutTex == 0 || solidTex == 0) {
			return;
		}

		final int programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		if (programId == 0) {
			return;
		}

		PackSamplerBinding binding = packSamplerBindings.get(programId);
		if (binding == null) {
			binding = PackSamplerBinding.create(programId, packSamplerLogs < MAX_PACK_SAMPLER_LOGS);
			if (binding == null) {
				packSamplerBindings.put(programId, PackSamplerBinding.NONE);
				return;
			}
			packSamplerBindings.put(programId, binding);
			packSamplerLogs++;
		} else if (binding == PackSamplerBinding.NONE) {
			return;
		}

		final int lut = isRailTexture(texture == null ? "" : texture.getPath()) ? dummyLutTex : lutTex;
		binding.bind(lut, atlasTex, solidTex);
		bindPackSamplerCount++;
	}

	private int bindPackSamplerCount;

	/**
	 * 作废"每个 program 的光场采样器绑定"缓存。
	 *
	 * <p>与 {@code MmtrHeadlights.resetProgramCache()} 同一个理由、同一个时机：这张表的键是
	 * **GL program id**，而光影包（重新）装载会销毁一批 program、GL 再把那些 id 回收发给新 program
	 * ⇒ 不清就会把旧 id 的 uniform 位置与纹理单元套到新 program 上（那会往别人的 uniform 里写值）。
	 * 调用点是 {@code MmtrShaderPackLightField.invalidate()}；{@code IncludeProcessor} 的**构造钩子**
	 * 保证"包装载"这个时机一定命中（notes/351 §4.3）。</p>
	 */
	public void clearPackSamplerCache() {
		packSamplerBindings.clear();
		packSamplerLogs = 0;
	}

	/**
	 * 一个 program 上的三个光场采样器：位置 + 挑好的空闲纹理单元。
	 * {@link #NONE} 表示"这个 program 里没有我们的 uniform（不是我们注入过的程序/已被优化掉）"。
	 */
	private static final class PackSamplerBinding {

		private static final PackSamplerBinding NONE = new PackSamplerBinding();

		private final int lutLocation;
		private final int atlasLocation;
		private final int solidLocation;
		/** 注入代码里 `uniform mat4 mmtrModelMat`（MTR 每 draw 的模型矩阵）的位置；-1 = 没有。 */
		private final int modelMatLocation;
		/** 注入代码里 `uniform int mmtrNormalFix`（法线空间修正开关）的位置；-1 = 没有。 */
		private final int normalFixLocation;
		/** 注入代码里 `uniform int mmtrProbe`（二分定位探针）的位置；-1 = 没有。 */
		private final int probeLocation;
		/** 注入代码里 `uniform float mmtrFieldMix`（光场占比，逐片元）的位置；-1 = 没有。 */
		private final int fieldMixLocation;
		private final int lutUnit;
		private final int atlasUnit;
		private final int solidUnit;

		private PackSamplerBinding() {
			lutLocation = -1;
			atlasLocation = -1;
			solidLocation = -1;
			modelMatLocation = -1;
			normalFixLocation = -1;
			probeLocation = -1;
			fieldMixLocation = -1;
			lutUnit = 0;
			atlasUnit = 0;
			solidUnit = 0;
		}

		private PackSamplerBinding(int lutLocation, int atlasLocation, int solidLocation, int modelMatLocation, int normalFixLocation, int probeLocation, int fieldMixLocation, int lutUnit, int atlasUnit, int solidUnit) {
			this.lutLocation = lutLocation;
			this.atlasLocation = atlasLocation;
			this.solidLocation = solidLocation;
			this.modelMatLocation = modelMatLocation;
			this.normalFixLocation = normalFixLocation;
			this.probeLocation = probeLocation;
			this.fieldMixLocation = fieldMixLocation;
			this.lutUnit = lutUnit;
			this.atlasUnit = atlasUnit;
			this.solidUnit = solidUnit;
		}

		/**
		 * 一次性诊断：这个 GL program 到底是谁，以及我们注入的标记有没有进到它里面。
		 *
		 * <p>为什么要它：如果 <b>按名字查 mmtrLut 得到 -1</b>，只有两种可能 ——
		 * ① 这不是我们注入过的那个 program；② 注入进去了但成了死代码（没人调用 ⇒ 整个函数和它的
		 * uniform 一起被编译器删掉）。把 active uniform 名字列出来能立刻分清 ①，
		 * 把附着着色器的源码标记数出来能分清 ②。</p>
		 */
		private static String describeProgram(int programId) {
			try {
				final int uniformCount = GL20.glGetProgrami(programId, GL20.GL_ACTIVE_UNIFORMS);
				final IntBuffer lengthBuffer = BufferUtils.createIntBuffer(1);
				final IntBuffer sizeBuffer = BufferUtils.createIntBuffer(1);
				final IntBuffer typeBuffer = BufferUtils.createIntBuffer(1);
				final ByteBuffer nameBuffer = BufferUtils.createByteBuffer(256);
				final StringBuilder builder = new StringBuilder();
				builder.append(uniformCount).append(" 个 uniform: ");
				for (int i = 0; i < Math.min(uniformCount, 16); i++) {
					lengthBuffer.clear();
					sizeBuffer.clear();
					typeBuffer.clear();
					nameBuffer.clear();
					GL20.glGetActiveUniform(programId, i, lengthBuffer, sizeBuffer, typeBuffer, nameBuffer);
					final int length = Math.max(0, lengthBuffer.get(0));
					for (int c = 0; c < length && c < nameBuffer.capacity(); c++) {
						builder.append((char) nameBuffer.get(c));
					}
					builder.append(' ');
				}

				// 尽力而为：Iris 可能在链接后删掉了 shader 对象，那时这里会是 0 个
				final int attached = GL20.glGetProgrami(programId, GL20.GL_ATTACHED_SHADERS);
				builder.append("| 附着着色器=").append(attached);
				if (attached > 0) {
					final IntBuffer countBuffer = BufferUtils.createIntBuffer(1);
					final IntBuffer shaders = BufferUtils.createIntBuffer(attached);
					GL20.glGetAttachedShaders(programId, countBuffer, shaders);
					for (int i = 0; i < Math.min(attached, countBuffer.get(0)); i++) {
						final int shader = shaders.get(i);
						final String source = GL20.glGetShaderSource(shader);
						builder.append(" [").append(GL20.glGetShaderi(shader, GL20.GL_SHADER_TYPE) == GL20.GL_VERTEX_SHADER ? "VS" : "FS")
								.append(' ').append(source.length()).append(" 字符")
								.append(" mmtrLightUv=").append(occurrences(source, "mmtrLightUv"))
								.append(" mmtrFragmentLmCoord=").append(occurrences(source, "mmtrFragmentLmCoord"))
								.append(" iris_UV2=").append(occurrences(source, "iris_UV2"))
								.append(" gl_MultiTexCoord1=").append(occurrences(source, "gl_MultiTexCoord1"))
								.append(']');
					}
				}
				return builder.toString();
			} catch (Exception exception) {
				return "探针失败: " + exception;
			}
		}

		private static int occurrences(String text, String needle) {
			int count = 0;
			int index = text.indexOf(needle);
			while (index >= 0) {
				count++;
				index = text.indexOf(needle, index + needle.length());
			}
			return count;
		}

		/**
		 * 一次性诊断：把包程序里那几个矩阵 uniform 的**真实值**读回来。
		 *
		 * <p>要定死的问题：包按 **vanilla 口径**期待"视空间法线 / 相机相对世界坐标"，而 MTR 喂的
		 * {@code Normal} 是**模型局部空间**、位置是模型局部坐标。判断"包里已有的那个换算矩阵到底是哪个
		 * 空间"不需要猜 —— 把 {@code iris_ModelViewMat} / {@code iris_NormalMat} /
		 * {@code gbufferModelViewInverse} 的数值直接打出来就行（≈单位矩阵 = 那个 draw 时刻它确实是空的）。</p>
		 */
		private static void logPackMatrixSpaces(int programId) {
			try {
				final StringBuilder builder = new StringBuilder();
				for (final String name : new String[]{"iris_ModelViewMat", "iris_NormalMat", "gbufferModelViewInverse", "mmtrModelMat"}) {
					final int location = GlStateManager._glGetUniformLocation(programId, name);
					if (location < 0) {
						builder.append(' ').append(name).append("=无");
						continue;
					}
					final FloatBuffer buffer = BufferUtils.createFloatBuffer(16);
					GL20.glGetUniformfv(programId, location, buffer);
					final int size = "iris_NormalMat".equals(name) ? 9 : 16;
					builder.append(' ').append(name).append("=[");
					for (int i = 0; i < size; i++) {
						builder.append(i == 0 ? "" : " ").append(String.format("%.3f", buffer.get(i)));
					}
					builder.append(']');
				}
				Init.LOGGER.info("[MMTR-LIGHT] 包程序矩阵探针（program {}）：{}", programId, builder);
			} catch (Exception exception) {
				Init.LOGGER.error("[MMTR-LIGHT] 包程序矩阵探针失败", exception);
			}
		}

		/** @return null 表示这个 program 里没有我们的 uniform（不是注入过的程序） */
		private static PackSamplerBinding create(int programId, boolean log) {
			final int lutLocation = GlStateManager._glGetUniformLocation(programId, "mmtrLut");
			final int atlasLocation = GlStateManager._glGetUniformLocation(programId, "mmtrAtlas");
			// ⚠️ `mmtrSolid`（AO 的实心位图）**允许不存在**：片元阶段那条路目前不消费 AO，
			//    编译器完全可能把它判成死代码（实测同一驱动上它是活的，但不能靠这个）。
			//    旧写法要求三个都 ≥0，一旦它变 -1 就整条路静默跳过（症状是"注入好像没生效"）——
			//    那是把"AO 不可用"升级成了"取光不可用"，代价完全不成比例。
			final int solidLocation = GlStateManager._glGetUniformLocation(programId, "mmtrSolid");
			if (lutLocation < 0 || atlasLocation < 0) {
				if (log) {
					Init.LOGGER.info("[MMTR-LIGHT] 光影采样器：program {} 里没有 mmtrLut/mmtrAtlas（{}/{}/{}），跳过。探针：{}",
							programId, lutLocation, atlasLocation, solidLocation, describeProgram(programId));
				}
				return null;
			}

			// MTR 每 draw 的模型矩阵。查不到就是 -1（上传时静默跳过）—— 注入文本万一没跟上也不会炸。
			final int modelMatLocation = GlStateManager._glGetUniformLocation(programId, "mmtrModelMat");
			final int normalFixLocation = GlStateManager._glGetUniformLocation(programId, "mmtrNormalFix");
			final int probeLocation = GlStateManager._glGetUniformLocation(programId, "mmtrProbe");
			final int fieldMixLocation = GlStateManager._glGetUniformLocation(programId, "mmtrFieldMix");

			if (log) {
				logPackMatrixSpaces(programId);
			}

			// 枚举该 program 的 sampler uniform 实际占了哪些单元（Iris 会从低往高分配），再从**高位**挑空的。
			// 用 GL 2.0 的 glGetActiveUniform（名字+类型一起给），避免依赖 GL 3.1 的 *_UNIFORM_TYPE 查询常量。
			final java.util.BitSet used = new java.util.BitSet();
			// 诊断用：把每个 sampler 的名字 + 它当前的单元一起记下来。
			// 为什么必须打出来：我们靠这份清单避开 Iris 的单元；万一清单不完整（比如枚举时机太早、
			// Iris 还没把 colortex 的单元写进去），我们就会**覆盖包自己的某个 colortex 单元** ——
			// 那种错误不会让车厢变黑，而是让包里别的东西（反射/法线数据）悄悄读错。见 notes/344 §17.24。
			final StringBuilder samplerList = new StringBuilder();
			final int uniformCount = GL20.glGetProgrami(programId, GL20.GL_ACTIVE_UNIFORMS);
			final IntBuffer lengthBuffer = BufferUtils.createIntBuffer(1);
			final IntBuffer sizeBuffer = BufferUtils.createIntBuffer(1);
			final IntBuffer typeBuffer = BufferUtils.createIntBuffer(1);
			final ByteBuffer nameBuffer = BufferUtils.createByteBuffer(256);
			final IntBuffer unitBuffer = BufferUtils.createIntBuffer(1);
			for (int i = 0; i < uniformCount; i++) {
				lengthBuffer.clear();
				sizeBuffer.clear();
				typeBuffer.clear();
				nameBuffer.clear();
				GL20.glGetActiveUniform(programId, i, lengthBuffer, sizeBuffer, typeBuffer, nameBuffer);
				final int type = typeBuffer.get(0);
				if (type != GL20.GL_SAMPLER_2D && type != GL30.GL_UNSIGNED_INT_SAMPLER_2D) {
					continue;
				}
				final int length = Math.max(0, lengthBuffer.get(0));
				final StringBuilder name = new StringBuilder();
				for (int c = 0; c < length && c < nameBuffer.capacity(); c++) {
					name.append((char) nameBuffer.get(c));
				}
				final int location = GlStateManager._glGetUniformLocation(programId, name.toString());
				if (location < 0) {
					continue;
				}
				unitBuffer.clear();
				GL20.glGetUniformiv(programId, location, unitBuffer);
				final int unit = unitBuffer.get(0);
				if (unit >= 0 && unit < 64) {
					used.set(unit);
				}
				if (samplerList.length() < 400) {
					samplerList.append(' ').append(name).append('@').append(unit);
				}
			}

			// 单元上限必须用**片元阶段**的（GL_MAX_TEXTURE_IMAGE_UNITS）：这些 sampler 是在片元着色器里采样的，
			// 用 GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS 有可能挑到一个片元阶段不存在的单元。
			final int maxUnits = Math.min(32, GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
			final int lutUnit = freeUnit(used, maxUnits, 8);
			final int atlasUnit = freeUnit(used, maxUnits, lutUnit + 1);
			final int solidUnit = freeUnit(used, maxUnits, atlasUnit + 1);
			if (lutUnit < 0 || atlasUnit < 0 || solidUnit < 0) {
				if (log) {
					Init.LOGGER.warn("[MMTR-LIGHT] 光影采样器：program {} 找不到 3 个空闲纹理单元（已用 {} 个），跳过",
							programId, used.cardinality());
				}
				return null;
			}

			if (log) {
				Init.LOGGER.info("[MMTR-LIGHT] 光影采样器：program {} 已用单元={} → 光场用 {}/{}/{}（LUT/图集/实心位图）| 片元单元上限={} | 该程序的 sampler（名字@单元）：{} | {}",
						programId, used, lutUnit, atlasUnit, solidUnit, maxUnits, samplerList.toString().trim(), describeProgram(programId));
			}
			return new PackSamplerBinding(lutLocation, atlasLocation, solidLocation, modelMatLocation, normalFixLocation, probeLocation, fieldMixLocation, lutUnit, atlasUnit, solidUnit);
		}

		/** 从 from 开始往上找第一个没被占用的单元；找不到返回 -1。 */
		private static int freeUnit(java.util.BitSet used, int maxUnits, int from) {
			for (int unit = Math.max(0, from); unit < maxUnits; unit++) {
				if (!used.get(unit)) {
					return unit;
				}
			}
			return -1;
		}

		private void bind(int lutTexture, int atlasTexture, int solidTexture) {
			GL20.glUniform1i(lutLocation, lutUnit);
			GL20.glUniform1i(atlasLocation, atlasUnit);

			final int previousActive = GlStateManager._getActiveTexture();
			bindUnit(lutUnit, lutTexture);
			bindUnit(atlasUnit, atlasTexture);
			if (solidLocation >= 0) {
				// 只有程序里真的有 mmtrSolid 才绑（见 create 的说明：允许它被优化掉）
				GL20.glUniform1i(solidLocation, solidUnit);
				bindUnit(solidUnit, solidTexture);
			}
			GlStateManager._activeTexture(previousActive);
		}

		private static void bindUnit(int unit, int texture) {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
			GlStateManager._bindTexture(texture);
		}
	}

	/** 本批次决定要用的 LUT（光影包模式下由 bindPackSamplers 消费）。 */
	private int lastPackLut;

	private void uploadLut() {
		final int previousActive = GlStateManager._getActiveTexture();
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(lutTex);
		prepareUnpackState(4);
		lutCpu.clear();
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, LUT_W, LUT_H, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, lutCpu);
		GlStateManager._activeTexture(previousActive);
	}

	/**
	 * 上传前把 pixel store 状态**显式**摆正。
	 *
	 * <p>为什么必须显式设：{@code GL_UNPACK_ROW_LENGTH} 这些是全局状态，别的渲染代码（Sodium 传 mipmap 时就会用）
	 * 可能把它留在别的值上。我们按"紧凑排布"的假设上传，一旦 ROW_LENGTH 不是 0，
	 * 驱动就会按那个行宽去读缓冲区 —— 读出去几十倍的数据，**直接访问越界崩掉进程**
	 * （2026-09-28 15:51 客户端就是这么崩的：退出码 0xC0000005，没有 Java 崩溃报告）。</p>
	 */
	private static void prepareUnpackState(int alignment) {
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
	}

	/** 回读（glGetTexImage）同样要摆正 PACK 侧的状态。 */
	private static void preparePackState(int alignment) {
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, alignment);
		GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
	}

	private void uploadDirtyTiles() {
		// ⚠️ 两个 dirty 集合都要看：实心位图有自己的行（reset() 会把 256 行全标脏，而那时 slotDirty 是空的），
		//    只看 slotDirty 会让"清空过的实心位图"永远传不上去 ⇒ 残留遮蔽。
		if (slotDirty.isEmpty() && solidDirty.isEmpty()) {
			return;
		}

		final int previousActive = GlStateManager._getActiveTexture();
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(atlasTex);
		prepareUnpackState(1);

		for (int index = slotDirty.nextSetBit(0); index >= 0; index = slotDirty.nextSetBit(index + 1)) {
			final int slot = index + 1;
			final int tileX = (slot - 1) % ATLAS_TILES_X;
			final int tileY = (slot - 1) / ATLAS_TILES_X;
			atlasCpu.position(index * SECTION_BYTES);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, tileX * TILE, tileY * TILE, TILE, TILE, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, atlasCpu);
			uploadedTiles++;
		}

		atlasCpu.clear();
		slotDirty.clear();

		// 实心位图：一行一个 section（行号 = 槽位 − 1），一行只有 512 字节，上传成本可忽略。
		if (!solidDirty.isEmpty()) {
			GlStateManager._bindTexture(solidTex);
			prepareUnpackState(1);
			for (int index = solidDirty.nextSetBit(0); index >= 0; index = solidDirty.nextSetBit(index + 1)) {
				final int slot = index + 1;
				solidCpu.position((slot - 1) * SOLID_BYTES);
				GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, slot - 1, SOLID_TEX_WIDTH, 1, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, solidCpu);
			}
			solidCpu.clear();
			solidDirty.clear();
		}

		GlStateManager._activeTexture(previousActive);
	}

	// ------------------------------------------------------------------ GL 资源

	private boolean ensureTextures() {
		if (atlasTex != 0 && !GL11.glIsTexture(atlasTex)) {
			atlasTex = 0;
		}
		if (lutTex != 0 && !GL11.glIsTexture(lutTex)) {
			lutTex = 0;
		}
		if (solidTex != 0 && !GL11.glIsTexture(solidTex)) {
			solidTex = 0;
		}

		final int previousActive = GlStateManager._getActiveTexture();
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);

		if (atlasTex == 0) {
			atlasTex = GlStateManager._genTexture();
			GlStateManager._bindTexture(atlasTex);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			prepareUnpackState(1);
			atlasCpu.clear();
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8UI, ATLAS_SIZE, ATLAS_SIZE, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, atlasCpu);
			slotDirty.clear();
			for (int slot = 0; slot < MAX_SECTIONS; slot++) {
				slotDirty.set(slot);
			}
		}

		if (lutTex == 0) {
			lutTex = GlStateManager._genTexture();
			GlStateManager._bindTexture(lutTex);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			prepareUnpackState(4);
			lutCpu.clear();
			lutCpu.put(new int[LUT_W * LUT_H]);
			lutCpu.clear();
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, LUT_W, LUT_H, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, lutCpu);
			lutDirty = true;
		}

		if (solidTex == 0) {
			// 实心位图（AO）：R8UI，512×256 —— 一行一个 section，每字节管 8 个方块。
			// 用 R8UI（整型纹理、NEAREST）而不是普通 R8：我们要的是**精确的 0/1**，
			// 不能让过滤/归一化把位图搅成"半实心"。
			solidTex = GlStateManager._genTexture();
			GlStateManager._bindTexture(solidTex);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			prepareUnpackState(1);
			solidCpu.clear();
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8UI, SOLID_TEX_WIDTH, MAX_SECTIONS, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, solidCpu);
			solidDirty.clear();
			for (int slot = 0; slot < MAX_SECTIONS; slot++) {
				solidDirty.set(slot);
			}
		}

		if (dummyLutTex == 0 || !GL11.glIsTexture(dummyLutTex)) {
			// 全零 LUT：尺寸必须与真 LUT 一致 —— texelFetch 越界是未定义值，不能靠"1×1 纹理"糊过去。
			// 全零 ⇒ 查表命中槽位 0（=这个 section 没数据）⇒ 着色器退回 per-draw 光。
			dummyLutTex = GlStateManager._genTexture();
			GlStateManager._bindTexture(dummyLutTex);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			prepareUnpackState(4);
			final IntBuffer zeros = BufferUtils.createIntBuffer(LUT_W * LUT_H);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32UI, LUT_W, LUT_H, 0, GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_INT, zeros);
		}

		GlStateManager._activeTexture(previousActive);
		return atlasTex != 0 && lutTex != 0 && solidTex != 0 && dummyLutTex != 0;
	}

	// ------------------------------------------------------------------ 小工具

	private static int floorToSection(double coordinate) {
		return (int) Math.floor(coordinate) >> 4;
	}

	private static int sectionOf(double relativeCoordinate) {
		return (int) Math.floor(relativeCoordinate) >> 4;
	}

	private static long sectionKey(int x, int y, int z) {
		return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
	}

	/** 21 位有符号字段的取回（全程 long 运算，避免 {@code int << 43} 变成 {@code int << 11} 那个坑）。 */
	private static int keyX(long key) {
		return (int) (key << 1 >> 43);
	}

	private static int keyY(long key) {
		return (int) (key << 22 >> 43);
	}

	private static int keyZ(long key) {
		return (int) (key << 43 >> 43);
	}

	private static final class CollectTask {

		private final long key;
		private int nextLayer;

		private CollectTask(long key, int nextLayer) {
			this.key = key;
			this.nextLayer = nextLayer;
		}
	}
}
