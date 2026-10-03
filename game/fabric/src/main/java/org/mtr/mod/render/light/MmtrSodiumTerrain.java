package org.mtr.mod.render.light;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.mtr.mod.Init;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * MMTR 车灯 · <b>世界方块那一侧</b>（notes/345 §6）：把 Sodium 的地形 program 换成带 {@code mmtrHeadlightTerm} 的版本。
 *
 * <h3>为什么地形要单独接一次</h3>
 * <p>车厢那条路（{@code mmtr_vehicle_light}）只覆盖 MTR 自己画的东西 —— <b>车厢 + 3D 钢轨</b>。
 * 道床、隧道壁、站台、草、水这些走的是区块渲染器，跟 MTR 一点关系都没有，所以"车灯照亮了车厢、
 * 但眼前的地面还是黑的"就是缺了这一环。</p>
 *
 * <h3>为什么是 Sodium，而不是原版 {@code rendertype_solid}</h3>
 * <p>实测（2026-09-29）：dev 客户端里装着 <b>Sodium 0.5.8</b>，区块渲染整体走它自己的程序
 * （{@code sodium:blocks/block_layer_opaque.vsh/.fsh}，见 {@code ShaderChunkRenderer}），
 * 原版的 {@code rendertype_solid} 一族根本没被用到。所以覆盖原版着色器是白做 —— 必须换 Sodium 那两份。</p>
 *
 * <h3>怎么塞进去（为什么不走资源包）</h3>
 * <p>实测 {@code ShaderLoader.getShaderSource()} 是
 * {@code ShaderLoader.class.getResourceAsStream("/assets/<ns>/shaders/<path>")} ——
 * <b>走 classpath，不走走资源包栈</b>。所以"往自己的资源包里放同名文件"是靠不住的（谁在 classpath 前面谁赢）。
 * 做法是拦那个方法本身：{@link org.mtr.mixin.sodium.SodiumShaderLoaderMixin} 在 RETURN 处整体替换源码，
 * 与本仓 MTR 那条路（{@code PatchingResourceProviderMixin}）是同一个套路。</p>
 *
 * <h3>三条铁律（都在笔记里有实测依据）</h3>
 * <ol>
 *   <li><b>坐标口径</b>：Sodium 的 {@code position = _vert_position + u_RegionOffset + 16*chunkCoord}
 *       就是<b>相机相对世界坐标</b>（{@code u_RegionOffset} = 区块原点 − 相机位置，见
 *       {@code DefaultChunkRenderer.setModelMatrixUniforms}；fog 的 {@code getFragDistance} 也按它算）
 *       ⇒ 与 {@link MmtrHeadlights} 上传的灯位是同一个空间，不用换算。</li>
 *   <li><b>反照率取在乘光照贴图之前</b>：Sodium 的 fsh 里 {@code v_Color = _vert_color * lightmap}，
 *       隧道里它是全黑的，拿它当反照率 ⇒ 灯永远是黑的。所以我们在顶点阶段单独把
 *       {@code _vert_color.rgb}（tint × AO）传下去。</li>
 *   <li><b>地形顶点格式没有法线</b>（实测 {@code chunk_vertex.glsl} 只有 position/texcoord/lightcoord/color）
 *       ⇒ 片元里用屏幕空间导数重建面法线（体素地形全是平面四边形，重建出来就是真法线）。</li>
 * </ol>
 *
 * <p><b>颜色偏一档的边界</b>：这里只换了"颜色"，不换整个 G-buffer 契约；光影包（Iris）开着时区块走包自己的
 * 程序，Sodium 这两份根本不被编译 ⇒ 地形车灯不生效（与光场同一条边界，见 notes/345 §5.3）。</p>
 *
 * <p><b>没有遮挡</b>：光不会被墙挡住（与车厢那条路一样，是已知边界）。</p>
 */
public final class MmtrSodiumTerrain {

	/** Sodium 请求的顶点着色器标识符（{@code ShaderChunkRenderer.compileProgram} 里的那个字符串）。 */
	public static final Identifier SODIUM_VERTEX = new Identifier("sodium", "blocks/block_layer_opaque.vsh");
	/** Sodium 请求的片元着色器标识符。 */
	public static final Identifier SODIUM_FRAGMENT = new Identifier("sodium", "blocks/block_layer_opaque.fsh");
	/**
	 * 我们让 Sodium 去 import 的那份共享数学。它**不存在**于 Sodium 的 jar 里 ——
	 * 由这里把它替换成 {@code assets/minecraft/shaders/include/mmtr_headlight.glsl} 的内容，
	 * 于是车厢与地形用的是**同一份** {@code mmtrHeadlightTerm}（只存在一份实现，数学不会漂）。
	 */
	public static final Identifier SODIUM_INCLUDE = new Identifier("sodium", "include/mmtr_headlight.glsl");

	private static final String OUR_VERTEX_PATH = "/assets/mtr/shaders/sodium/mmtr_terrain_headlight.vsh";
	private static final String OUR_FRAGMENT_PATH = "/assets/mtr/shaders/sodium/mmtr_terrain_headlight.fsh";
	private static final String SHARED_INCLUDE_PATH = "/assets/minecraft/shaders/include/mmtr_headlight.glsl";

	/**
	 * Sodium 原版源码里必须还在的"锚"。
	 *
	 * <p>为什么需要：我们是**整体替换**，如果哪天 Sodium 换了顶点解包方式（{@code _vert_init} /
	 * {@code _get_draw_translation} 没了），我们那份副本就会用错误的格式去读顶点 —— 那比"地形没车灯"
	 * 严重得多（整个世界的方块会画歪/画飞）。所以锚不在就<b>不换</b>，并且大声报一次。</p>
	 */
	private static final String[] VERTEX_ANCHORS = {"_vert_init()", "_get_draw_translation(_draw_id)", "_sample_lightmap(u_LightTex"};
	private static final String[] FRAGMENT_ANCHORS = {"texture(u_BlockTex, v_TexCoord", "_linearFog(diffuseColor"};

	/** 成功加载的替换源码（三条：vsh / fsh / 共享 include）。null = 还没试过；空 map = 试过且失败。 */
	private static Map<Identifier, String> replacements;
	private static boolean loadFailed;
	private static boolean servedLogged;
	private static boolean anchorMismatchLogged;
	private static boolean shaderpackLogged;
	/** "本机没装 Sodium" / "mixin 配置没注册" 各只报一次（这是 5 秒一次的诊断行，不能变成刷屏）。 */
	private static boolean sodiumMissingLogged;
	private static boolean configMissingLogged;
	/** mixin 配置注册判定的缓存（null = 还没判过）。 */
	private static Boolean configRegistered;

	private MmtrSodiumTerrain() {
	}

	/**
	 * Sodium 在 {@code ShaderLoader.getShaderSource} 的 RETURN 处问这里要源码。
	 *
	 * <p>返回 null = 不插手（Sodium 用自己的源码）。只有三种情况会返回 null：
	 * 光场总开关关着、我们的资源读不出来、或者 Sodium 的源码里锚不在了。</p>
	 *
	 * <p>注意这里**不看** {@code terrainHeadlights}：着色器只在渲染器初始化时编译一次（之后一直缓存），
	 * 按开关决定"换不换"等于让那个开关变成"要重启才生效"。所以源码永远换，开关走 uniform
	 * （{@code terrainHeadlights=false} ⇒ {@code mmtrTerrainLuxScale=0}，见 {@link MmtrHeadlights}）。</p>
	 */
	public static String sourceFor(Identifier identifier) {
		if (!MmtrLightField.isEnabled()) {
			return null;
		}

		final Map<Identifier, String> loaded = load();
		if (loaded == null) {
			return null;
		}
		return loaded.get(identifier);
	}

	/**
	 * 只回答"我们供给 Sodium 的那一份 include"（{@code sodium:include/mmtr_headlight.glsl}，它**不在** Sodium 的 jar 里）。
	 *
	 * <p><b>为什么必须单独一个入口</b>：Sodium 的 {@code getShaderSource} 对 classpath 上找不到的 include 是
	 * <b>直接抛 RuntimeException</b>（{@code Shader not found: …}），而 {@code @At("RETURN")} 的注入在
	 * <b>异常路径上不会执行</b> ⇒ 那条路救不了这个文件。2026-09-29 实测就是崩在这里：
	 * 一进世界、Sodium 第一次编译区块 program 时 {@code Shader not found: /assets/sodium/shaders/include/mmtr_headlight.glsl}，
	 * 客户端直接闪退。所以这一份改在注入的 <b>HEAD</b> 处回答（见 {@code SodiumShaderLoaderMixin}）。</p>
	 *
	 * <p>另一条保底：这个文件同时也**随 mod 资源发布**在 {@code assets/sodium/shaders/include/} ——
	 * 万一哪天 HEAD 注入没挂上，Sodium 自己也能在 classpath 上找到它（两份内容由探针断言逐字节相同）。</p>
	 *
	 * @return null = 不插手（让 Sodium 自己去找、或自己抛它自己的错）
	 */
	public static String syntheticIncludeFor(Identifier identifier) {
		if (!SODIUM_INCLUDE.equals(identifier) || !MmtrLightField.isEnabled()) {
			return null;
		}
		final Map<Identifier, String> loaded = load();
		return loaded == null ? null : loaded.get(SODIUM_INCLUDE);
	}

	/**
	 * 换源码前的自检：Sodium 的原始源码必须还是我们副本所对应的那一版。
	 *
	 * @param identifier 请求的标识符
	 * @param original   Sodium 自己的源码
	 * @return true = 可以换
	 */
	public static boolean anchorsIntact(Identifier identifier, String original) {
		final String[] anchors;
		if (SODIUM_VERTEX.equals(identifier)) {
			anchors = VERTEX_ANCHORS;
		} else if (SODIUM_FRAGMENT.equals(identifier)) {
			anchors = FRAGMENT_ANCHORS;
		} else {
			return true;
		}
		for (final String anchor : anchors) {
			if (original == null || !original.contains(anchor)) {
				if (!anchorMismatchLogged) {
					anchorMismatchLogged = true;
					// 日志本身绝不许把这条替换链带崩（在一个"另类 mod 的着色器加载"路径上，任何异常都是崩游戏）：
					// 这里只报一次、而且报不出来就算了，判据照旧是"不换"。
					try {
						Init.LOGGER.error("[MMTR-LIGHT] 地形：Sodium 的 {} 里找不到锚「{}」（Sodium 换了顶点/雾的写法？）"
								+ " ⇒ **不替换**，地形不会有车灯（总比把世界画歪好）。请核对 notes/345 §6 的副本。", identifier, anchor);
					} catch (Throwable ignored) {
					}
				}
				return false;
			}
		}
		return true;
	}

	/** 替换成功后说一次（"换没换上"必须在日志里一眼可见，不能靠看）。 */
	public static void logServedOnce() {
		if (servedLogged) {
			return;
		}
		servedLogged = true;
		// 与 anchorsIntact 同一个理由：这条路上的异常等于崩游戏，日志报不出来也得让替换本身成功。
		try {
			Init.LOGGER.info("[MMTR-LIGHT] 地形：Sodium 地形着色器已换成带车灯的版本（{} / {} / 共享 include {}）",
					SODIUM_VERTEX.getPath(), SODIUM_FRAGMENT.getPath(), SHARED_INCLUDE_PATH);
			if (MmtrLightField.isUnderShaderpack() && !shaderpackLogged) {
				shaderpackLogged = true;
				Init.LOGGER.info("[MMTR-LIGHT] 地形：当前**光影包在跑** ⇒ 区块走包自己的程序，地形车灯不会显示"
						+ "（与光场同一条边界，notes/345 §5.3）");
			}
		} catch (Throwable ignored) {
		}
	}

	/** 供诊断行使用的一句话（"地形那侧接上了没有"）。 */
	public static String describe() {
		if (loadFailed) {
			return "关(资源缺失)";
		}
		if (!MmtrLightField.isEnabled()) {
			return "关(光场总开关)";
		}
		if (!MmtrLightField.terrainHeadlightsEnabled()) {
			return "关(properties)";
		}
		if (!sodiumLoaded()) {
			// 没有 Sodium 时区块走原版 rendertype_solid 一族 —— 那条路本版**还没接**（notes/345 §6）。
			// 这条必须与"接上了但还没编译"区分开：否则"地形不亮"会被误读成"等一会儿就好"。
			if (!sodiumMissingLogged) {
				sodiumMissingLogged = true;
				logQuietly(() -> Init.LOGGER.info("[MMTR-LIGHT] 地形：本机**没有 Sodium** ⇒ 区块走原版 rendertype_solid 一族，"
						+ "那条路本版尚未接（notes/345 §6）⇒ 世界里只有车厢与 3D 钢轨吃车灯"));
			}
			return "关(无Sodium)";
		}
		if (!mixinConfigRegistered()) {
			// 本仓在这上面栽过两次（notes/344 §17.1、notes/345 §7.20）：配置漏注册的症状与"补丁没生效"一模一样，
			// 而日志原先只说"开(未编译)"，看不出是这一条 ⇒ 这里把判据本身放进诊断行。
			if (!configMissingLogged) {
				configMissingLogged = true;
				logQuietly(() -> Init.LOGGER.error("[MMTR-LIGHT] 地形：**mixin 配置没注册** —— fabric.mod.json 的 mixins 数组里没有 "
						+ "\"mtr.sodium.mixins.json\" ⇒ 换上地形着色器的钩子根本没挂上，地形不会有车灯"
						+ "（Sodium 的着色器一辈子只编译一次，改完要重启客户端）"));
			}
			return "关(mixin配置未注册)";
		}
		return servedLogged ? "开" : "开(未编译)";
	}

	/** 本机有没有 Sodium（`sodium` 这个 mod id；判不出来时当作有，免得造一个假警报）。 */
	private static boolean sodiumLoaded() {
		try {
			return FabricLoader.getInstance().isModLoaded("sodium");
		} catch (Throwable ignored) {
			return true;
		}
	}

	/**
	 * 我们的 mixin 配置到底注册了没有。
	 *
	 * <p>Fabric **只**从 {@code fabric.mod.json} 的 {@code mixins} 数组加载 mixin 配置，**不扫描** {@code *.mixins.json}。
	 * 漏了那一条的效果是"钩子从未挂上"，而画面与日志看起来都"一切正常"（这正是它两次骗过人的原因）。
	 * 判据读的是**游戏真正读的那份文件**：ModContainer 的根路径就是加载器找 {@code fabric.mod.json} 的同一处
	 * （dev 环境 = {@code build/resources/main}）—— 所以它比"我改过 src 了"可靠。</p>
	 *
	 * <p>判不出来（没有容器、读不了、异常）一律当作**已注册**：宁可漏报，也不要在诊断里造一个假警报。</p>
	 */
	public static boolean mixinConfigRegistered() {
		if (configRegistered != null) {
			return configRegistered;
		}
		boolean registered = true;
		try {
			final Optional<Path> path = FabricLoader.getInstance().getModContainer("mmtr")
					.flatMap(container -> container.findPath("fabric.mod.json"));
			if (path.isPresent()) {
				registered = Files.readString(path.get(), StandardCharsets.UTF_8).contains("\"mtr.sodium.mixins.json\"");
			}
		} catch (Throwable ignored) {
			// 判不出来就不报警
		}
		configRegistered = registered;
		return registered;
	}

	/**
	 * 日志绝不许把这条替换链带崩：它在"另一个 mod 的着色器加载"路径上，也在每 5 秒的诊断行上，
	 * 任何异常都不该变成"不替换 / 崩游戏 / 刷屏"，所以这里的失败一律吞掉（与 {@link #logServedOnce()} 同一个理由）。
	 */
	private static void logQuietly(Runnable runnable) {
		try {
			runnable.run();
		} catch (Throwable ignored) {
		}
	}

	private static synchronized Map<Identifier, String> load() {
		if (replacements != null) {
			return replacements;
		}
		if (loadFailed) {
			return null;
		}

		try {
			final Map<Identifier, String> loaded = new HashMap<>();
			loaded.put(SODIUM_VERTEX, readResource(OUR_VERTEX_PATH));
			loaded.put(SODIUM_FRAGMENT, readResource(OUR_FRAGMENT_PATH));
			loaded.put(SODIUM_INCLUDE, readResource(SHARED_INCLUDE_PATH));
			replacements = loaded;
			return loaded;
		} catch (Exception exception) {
			// 读不出来就整个不插手（三条是一套，缺一条 Sodium 的 #import 会直接抛异常 → 崩）
			loadFailed = true;
			try {
				Init.LOGGER.error("[MMTR-LIGHT] 地形：读不到自己的地形着色器资源，地形不会有车灯（其余一切照旧）", exception);
			} catch (Throwable ignored) {
			}
			return null;
		}
	}

	private static String readResource(String path) throws IOException {
		try (InputStream inputStream = MmtrSodiumTerrain.class.getResourceAsStream(path)) {
			if (inputStream == null) {
				throw new IOException("missing resource " + path);
			}
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
