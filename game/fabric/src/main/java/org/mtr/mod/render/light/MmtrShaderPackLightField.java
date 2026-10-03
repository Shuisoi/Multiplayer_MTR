package org.mtr.mod.render.light;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import org.mtr.mod.Init;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把光场取光**注入进光影包**（与包无关的那种注入）—— 顶点段与**片元段**各一段。
 *
 * <p>背景（完整推导见 notes/344 §15–§17.24）：光影包开着时 MTR 的车厢是**包自己的程序**画的，
 * 光照值是每 draw 一个常量顶点属性 ⇒ "整段一个光值"。我们拿不到那个程序的源码，只能注入。</p>
 *
 * <p><b>为什么现在写在片元阶段</b>：用户实测「手持光源完全正常，固定光源不对」。包的手持光是
 * 在片元阶段逐像素算的（{@code GetHeldLighting(playerPos, …)}），而"改写光照属性"只能到顶点阶段 ——
 * MTR 的车体是大面片低模，一个面几个角各采一次光、中间线性过渡 ⇒ 取到的光是对的，落在面上是错的。
 * 片元阶段还白送一个逐像素"相机相对世界坐标"：包自己在片元 main() 里算好
 * {@code playerPos = ViewToPlayer(ScreenToView(gl_FragCoord))}（与 held light 同一个量），
 * 我们只要把包建立本像素光照贴图值的那一行换成走光场的版本即可 —— 下游全部照旧。</p>
 *
 * <p>本类做四件事：</p>
 * <ol>
 *   <li><b>token 改写</b>（在 include 展开后的包**顶点**源码上做文本替换）：
 *       {@code gl_MultiTexCoord1} → {@code mmtrLightFromPack(gl_MultiTexCoord1)}、
 *       {@code vaUV2} → {@code mmtrLightFromIvec2(vaUV2)}（现在这两个包装是恒等的，留着是为了
 *       回退"逐顶点取光"时只改函数体），以及 {@code gl_Normal}/{@code vaNormal} → {@code mmtrNormalFromPack(…)}。
 *       之所以必须"改 token"而不是"用宏影子属性名"：Iris 的流水线是「先完整预处理（把所有
 *       {@code #define}/{@code #ifdef}/include 求值拍平）→ 再 AST 改写（{@code iris_UV2} 是这一步才插进去的）
 *       → 打印」，宏影子结构性无效（让函数变死代码、uniform 被编译器删掉，
 *       实测 {@code glGetUniformLocation} 拿到 -1）。证据与时间线见 §17.4 / §17.9。</li>
 *   <li><b>顶点段注入</b>：piece 1（三条原型）插在 {@code #version} 之后；piece V（uniform + 法线包装 +
 *       恒等的光照包装）插在**顶点阶段**的 {@code void main()} 之前。</li>
 *   <li><b>片元段注入</b>：piece F（uniform + 共享取光核心 + {@code mmtrFragmentLmCoord}）插在
 *       {@code #version} 之后；再把包建立本像素光照贴图值的那一行（{@link #FRAGMENT_ANCHOR}）
 *       改写成 {@code mmtrFragmentLmCoord(lmCoord, playerPos)}。</li>
 *   <li><b>失败一定是安全的</b>：锚点找不到 / 自己的 GLSL 读不到 —— 一律不注入那一处并记一行日志，
 *       包源码保持原样（不会有编译错误、不会黑屏）。</li>
 * </ol>
 */
public final class MmtrShaderPackLightField {

	private static final MmtrShaderPackLightField INSTANCE = new MmtrShaderPackLightField();

	public static MmtrShaderPackLightField getInstance() {
		return INSTANCE;
	}

	private static final String SNIPPET_PATH = "shaders/include/mmtr_lightfield_pack.glsl";
	private static final String FRAGMENT_SNIPPET_PATH = "shaders/include/mmtr_lightfield_pack_frag.glsl";
	private static final String CORE_PATH = "shaders/include/mmtr_lightfield.glsl";
	private static final String CORE_PLACEHOLDER = "//@MMTR_CORE_INCLUDE@";

	/** piece H 的骨架（车灯加性项，notes/345 §5 第 3 条边界的落地）。 */
	private static final String HEADLIGHT_SNIPPET_PATH = "shaders/include/mmtr_lightfield_pack_headlight.glsl";
	/** 共享车灯数学：**与 Sodium 那条路是同一份文件**（notes/345 §6 第 2 条 —— 只有一份，观感才不会各自漂）。 */
	private static final String HEADLIGHT_CORE_PATH = "shaders/include/mmtr_headlight.glsl";
	private static final String HEADLIGHT_PLACEHOLDER = "//@MMTR_HEADLIGHT_INCLUDE@";

	/** piece 1：三条原型。{@link #TOP_MARKER} 同时充当"这份源码已经处理过"的标记（内容幂等）。 */
	private static final String TOP_PROTOTYPES = "vec4 mmtrLightFromPack(vec4 mmtrPackUv2);\n"
			+ "ivec2 mmtrLightFromIvec2(ivec2 mmtrPackUv2);\n"
			+ "vec3 mmtrNormalFromPack(vec3 mmtrPackNormal);";
	private static final String TOP_MARKER = "vec4 mmtrLightFromPack(vec4 mmtrPackUv2);";

	/**
	 * piece F（片元阶段）的**内容幂等标记**：函数定义的第一行。
	 * 和顶点阶段一样，资源重载会拿一份没处理过的源码再来一次，不能靠"这个路径做过"来挡。
	 */
	private static final String FRAGMENT_MARKER = "vec2 mmtrFragmentLmCoord(vec2 mmtrLm, vec3 mmtrPlayerPos) {";

	/**
	 * 片元阶段的锚点：包在片元 main() 里建立"本像素光照贴图值"的那一行。
	 *
	 * <p>为什么是它（而不是另找一个地方插代码）：这一行之后，`lmCoordM` 会一路喂给
	 * `DoLighting(...)`/`GetLighting(...)` —— 方块光曲线、天空光、阴影、AO、漫反射全部照旧，
	 * 我们只把**这一个值**换成逐像素从光场取的。而这一行上面三行，包刚刚算好了
	 * `playerPos`（`ViewToPlayer(ScreenToView(gl_FragCoord))`，与 held light 同一个量），
	 * 所以新值要用的坐标是白送的。</p>
	 *
	 * <p>实测（Complementary Reimagined r5.9.3 `program/gbuffers_entities.glsl:144`）：
	 * {@code     vec2 lmCoordM = lmCoord;} —— 实体程序的**所有**变体都编译同一个文件，
	 * 所以这一个锚点覆盖 solid/cutout/alpha/translucent/glowing/diffuse 全部变体。</p>
	 */
	private static final Pattern FRAGMENT_ANCHOR = Pattern.compile("^(\\s*)vec2\\s+lmCoordM\\s*=\\s*lmCoord\\s*;\\s*$");

	/**
	 * piece H 的**内容幂等标记**：函数定义的第一行。
	 */
	private static final String HEADLIGHT_MARKER = "void mmtrPackHeadlightAdd(inout vec4 color, vec3 playerPos, vec3 worldNormal, vec3 albedo) {";

	/**
	 * piece H 的锚点：包算完本像素光照的那一次调用 —— {@code DoLighting(color, …)}。
	 *
	 * <p>为什么是它：这一行**之后**，{@code color} 从"反照率"变成"反照率 × 包的光照"，
	 * 随后原样写进 colortex0。在这之后加一项就是纯粹的"再多一点光"，包自己的
	 * 光照曲线 / 阴影 / AO 一个都不动（与 Sodium 那条路的 {@code diffuseColor.rgb += mmtrLight;} 同构）。</p>
	 *
	 * <p>为什么不能匹配函数**定义**：定义那行长成 {@code void DoLighting(inout vec4 color, …)}，
	 * 括号里第一个 token 是 {@code inout} 而不是 {@code color}；这条正则要求行首就是 {@code DoLighting(}
	 * 且第一个实参是 {@code color} ⇒ 只认调用点。实测（Complementary Reimagined r5.9.3）
	 * 片元段里这样的调用**恰好一处**。</p>
	 *
	 * <p>为什么 Albedo 要在这一行**之前**捕获：见 {@code mmtr_lightfield_pack_headlight.glsl} 开头。</p>
	 */
	private static final Pattern HEADLIGHT_ANCHOR = Pattern.compile("^\\s*DoLighting\\s*\\(\\s*color\\b");

	/** 光照贴图属性的两种写法（Iris 会把两者都收敛到 iris_UV2）。 */
	private static final Pattern LEGACY_LIGHT_ATTRIBUTE = Pattern.compile("\\bgl_MultiTexCoord1\\b");
	private static final Pattern MODERN_LIGHT_ATTRIBUTE = Pattern.compile("\\bvaUV2\\b");

	/**
	 * 法线属性。
	 *
	 * <p>MTR 的 `Normal` 是**模型局部空间**（它自己 shader 要 `mat3(ModelViewMat * ModelMat) * Normal`
	 * 才得到视空间法线），而包按 vanilla 口径期待**视空间**法线（vanilla 实体法线在 CPU 侧就被相机
	 * 旋转转过，那个 draw 时刻 `iris_NormalMat ≈ 单位矩阵`）。不转换就会"不同面对光的反射不同"。</p>
	 */
	private static final Pattern LEGACY_NORMAL = Pattern.compile("\\bgl_Normal\\b");
	private static final Pattern MODERN_NORMAL = Pattern.compile("\\bvaNormal\\b");

	/**
	 * 属性**声明行**不能包（`in ivec2 mmtrLightFromIvec2(vaUV2);` 是语法错误）。
	 * Complementary 这类旧式包不声明它们（`gl_*` 是内建），这两条为现代写法的包兜底。
	 */
	private static final Pattern ATTRIBUTE_DECLARATION = Pattern.compile("^\\s*(?:in|attribute)\\s+(?:ivec2|vec2|uvec2)\\s+(?:vaUV2|iris_UV2)\\b");
	private static final Pattern NORMAL_DECLARATION = Pattern.compile("^\\s*(?:in|attribute)\\s+vec3\\s+(?:vaNormal|iris_Normal)\\b");

	/** 只注入 MTR 在光影下画车厢走的那几个程序的**顶点**阶段。 */
	private static final String[] TARGET_PREFIXES = {"gbuffers_entities", "gbuffers_beaconbeam"};

	/**
	 * 片元阶段的目标：只注入实体程序。
	 *
	 * <p>为什么窄一些：片元阶段这一版要改的是"包建立本像素光照贴图值"那一行
	 * （{@link #FRAGMENT_ANCHOR}），只有实体程序里有它；信标光束（beaconbeam）那种自发光几何
	 * 不需要、也不该走光场。目标的名字前缀相同（这些变体都编译同一个
	 * {@code program/gbuffers_entities.glsl}），所以覆盖 solid/cutout/alpha/translucent/glowing/diffuse。</p>
	 */
	private static final String[] FRAGMENT_TARGET_PREFIXES = {"gbuffers_entities"};

	/**
	 * piece H（车灯加性项）的目标：**世界方块 + 实体**。
	 *
	 * <p>为什么比光场那条路宽：光场修的是"MTR 那个 draw 的光照值是整车一个常数"（只有车厢走那条路），
	 * 而车灯是**世界里的光**——它该照到地面、隧道壁、站台，也该照到别的列车、轨旁的猪牛羊。
	 * 所以地形这一档是**必须**有的（那正是 notes/345 §5 第 3 条边界），实体这一档是白送的。</p>
	 *
	 * <p>为什么带上 {@code gbuffers_terrain} 这个前缀就够：Iris 给地形编译的每一个变体
	 * （{@code gbuffers_terrain_solid} / {@code _cutout} / {@code _translucent}，装 Sodium 时还有
	 * {@code gbuffers_terrain_sodium_*}）都编译同一个 {@code program/gbuffers_terrain.glsl}，
	 * 而 Iris 问我们的路径名以它开头（实测于 {@code run/patched_shaders/074_gbuffers_terrain_sodium_solid.fsh}）。</p>
	 *
	 * <p>水（{@code gbuffers_water}）**暂不列入**：Sodium 那条路也只覆盖了不透明档，两条路保持同宽，
	 * 免得出现"哪条路亮了哪条没亮"这种说不清的状态。</p>
	 */
	private static final String[] HEADLIGHT_TARGET_PREFIXES = {"gbuffers_terrain", "gbuffers_entities"};

	/**
	 * 是否**注入**法线包装（`mmtrNormalFromPack(gl_Normal)`）。
	 *
	 * <p>注入本身是常开的：真正的"开/关"由 uniform {@code mmtrNormalFix} 在运行时决定
	 * （properties: {@code normalFix=}），因为"哪个更好"只能眼睛判，重启一次 3 分钟太贵。
	 * 2026-09-28 首次实测（当时是硬开关）用户反馈"没变化 / 反而变差了"，所以默认值是 0（关）。</p>
	 */
	private static final boolean NORMAL_WRAPPER_ENABLED = true;

	/** 拼好的注入文本（null = 不可用，直接放行）。 */
	private String injection;
	/** 拼好的**片元阶段**注入文本（null = 不可用 ⇒ 片元那条路整体不注入，包源码保持原样）。 */
	private String fragmentInjection;
	/** 拼好的**车灯**注入文本（piece H；null = 不可用 ⇒ 只不做车灯，光场那条路照旧）。 */
	private String headlightInjection;
	private boolean prepared;
	private final Set<String> loggedPaths = new HashSet<>();
	private final Set<String> patchedPaths = new HashSet<>();
	private int patchCalls;
	private int patchedPrograms;
	private int patchedFragmentPrograms;
	private int patchedHeadlightPrograms;
	private int rewrittenTokens;
	private String lastAnchor = "-";
	private String lastFragmentAnchor = "-";
	private String lastHeadlightAnchor = "-";

	private MmtrShaderPackLightField() {
	}

	/**
	 * 作废缓存的注入文本 + 日志抑制表，让下一次包装载重新读自己的 GLSL。
	 *
	 * <p>为什么需要：{@link #injection()} 是按会话缓存的（Iris 一次包装载会问几百个文件，
	 * 每次都读盘太浪费）。但资源重载会重新装载光影包、重新走一遍注入 —— 那时必须重读，
	 * 否则改了 include 看不到效果，只能重启客户端（实测踩过）。</p>
	 */
	public void invalidate() {
		prepared = false;
		injection = null;
		fragmentInjection = null;
		headlightInjection = null;
		loggedPaths.clear();
		// 包（重新）装载 = 上一批 GL program 会被销毁、program id 会被 GL 回收再发。
		// 车灯与光场那两张缓存都是**按 program id 索引**的（uniform 位置 + 纹理单元 + "本帧传过没"），
		// 不清就会把旧 id 的位置套到新 program 上 —— 那可能正好命中包自己的某个 uniform 并把它写脏。
		// 2026-09-29 实测踩到（notes/351 §4.3）：真机上每帧 7 次 GL_INVALID_OPERATION、3 分钟 45 MB 日志，
		// 而车灯一个像素都没传上去。**触发时机**是 IncludeProcessor 的构造钩子（见 IncludeProcessorMixin）：
		// 一个 IncludeProcessor = 一次包装载，那时新 program 还没建、旧 id 还没被复用。
		MmtrHeadlights.getInstance().resetProgramCache();
		MmtrLightField.getInstance().clearPackSamplerCache();
	}

	/**
	 * 准备注入文本：把骨架文件（{@code mmtr_lightfield_pack.glsl}）与共享核心（{@code mmtr_lightfield.glsl}）
	 * 拼成一段文本。读不到就返回 null（不注入）。
	 */
	private String injection() {
		prepare();
		return injection;
	}

	/** 片元阶段那份（骨架 {@code mmtr_lightfield_pack_frag.glsl} + 共享核心）。 */
	private String fragmentInjection() {
		prepare();
		return fragmentInjection;
	}

	/** 车灯那份（骨架 {@code mmtr_lightfield_pack_headlight.glsl} + 共享车灯数学）。 */
	private String headlightInjection() {
		prepare();
		return headlightInjection;
	}

	private void prepare() {
		if (prepared) {
			return;
		}
		prepared = true;
		try {
			final String core = readResource(CORE_PATH);
			if (core == null) {
				Init.LOGGER.error("[MMTR-LIGHT] 光影注入：读不到共享核心 {}，本次不注入", CORE_PATH);
				return;
			}

			// 顶点阶段：法线包装 + 恒等的光照包装（取光已搬到片元阶段）。
			final String snippet = readResource(SNIPPET_PATH);
			if (snippet == null) {
				Init.LOGGER.error("[MMTR-LIGHT] 光影注入：读不到自己的 GLSL 资源 {}，本次不注入顶点阶段", SNIPPET_PATH);
			} else {
				injection = snippet;
				Init.LOGGER.info("[MMTR-LIGHT] 光影注入：顶点段文本已备好（{} 字符）", injection.length());
			}

			// 片元阶段：逐片元按世界坐标取光（与包的 held light 同一条路）。
			final String fragmentSnippet = readResource(FRAGMENT_SNIPPET_PATH);
			if (fragmentSnippet == null) {
				Init.LOGGER.error("[MMTR-LIGHT] 光影注入：读不到自己的 GLSL 资源 {}，本次不注入片元阶段", FRAGMENT_SNIPPET_PATH);
			} else {
				fragmentInjection = inlinePlaceholder(fragmentSnippet, CORE_PLACEHOLDER, core, "片元段");
				if (fragmentInjection != null) {
					Init.LOGGER.info("[MMTR-LIGHT] 光影注入：片元段文本已备好（{} 字符，核心 {} 字符）", fragmentInjection.length(), core.length());
				}
			}

			// 车灯（piece H）：**内联**共享车灯数学 —— 它要进的是包自己的源码，包不认识 #moj_import / #import。
			final String headlightSnippet = readResource(HEADLIGHT_SNIPPET_PATH);
			final String headlightCore = readResource(HEADLIGHT_CORE_PATH);
			if (headlightSnippet == null) {
				Init.LOGGER.error("[MMTR-LIGHT] 光影注入：读不到自己的 GLSL 资源 {}，本次不注入车灯", HEADLIGHT_SNIPPET_PATH);
			} else if (headlightCore == null) {
				Init.LOGGER.error("[MMTR-LIGHT] 光影注入：读不到共享车灯数学 {}，本次不注入车灯", HEADLIGHT_CORE_PATH);
			} else {
				headlightInjection = inlinePlaceholder(headlightSnippet, HEADLIGHT_PLACEHOLDER, headlightCore, "车灯段");
				if (headlightInjection != null) {
					Init.LOGGER.info("[MMTR-LIGHT] 光影注入：车灯段文本已备好（{} 字符，共享数学 {} 字符）", headlightInjection.length(), headlightCore.length());
				}
			}
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] 光影注入：准备文本失败，本次不注入", exception);
		}
	}

	/**
	 * 读自己的 GLSL 资源。
	 *
	 * <p><b>必须走类路径，不能走原版 ResourceManager</b>：光影包是在
	 * {@code RenderSystem.initRenderer()} → {@code Iris.onRenderSystemInit()} → {@code Iris.loadShaderpack()}
	 * 里装载的，而这一步发生在 {@code MinecraftClient} **构造期间** —— 那时
	 * {@code MinecraftClient.getResourceManager()} 还是 {@code null}（实测 NPE 栈见 notes/344 §17.8）。
	 * 这两个文件是我们自己的实现细节（也不该被资源包覆盖），直接读自己的 classpath / jar 最稳。</p>
	 */
	private static String readResource(String path) {
		try (InputStream stream = MmtrShaderPackLightField.class.getResourceAsStream("/assets/minecraft/" + path)) {
			if (stream != null) {
				return readAll(stream);
			}
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] 光影注入：从类路径读取 {} 失败", path, exception);
		}

		// 兜底：类路径读不到时再试原版资源管理器（正常路径不会走到这里）
		final MinecraftClient client = MinecraftClient.getInstance();
		final ResourceManager manager = client == null ? null : client.getResourceManager();
		if (manager == null) {
			Init.LOGGER.error("[MMTR-LIGHT] 光影注入：类路径与资源管理器都读不到 {}，本次不注入", path);
			return null;
		}
		final Optional<Resource> resource = manager.getResource(new Identifier("minecraft", path));
		if (!resource.isPresent()) {
			return null;
		}
		try (InputStream stream = resource.get().getInputStream()) {
			return readAll(stream);
		} catch (Exception exception) {
			Init.LOGGER.error("[MMTR-LIGHT] 光影注入：读取 {} 失败", path, exception);
			return null;
		}
	}

	private static String readAll(InputStream stream) throws Exception {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			final StringBuilder builder = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line).append('\n');
			}
			return builder.toString();
		}
	}

	/**
	 * 把**独占一行的那条占位行**整行换成共享数学的内容。
	 *
	 * <p><b>为什么必须是"整行相等"而不是 {@code String.replace}</b>：占位符这个词也会出现在骨架文件
	 * 自己的**注释**里（解释它是干什么用的）。{@code replace} 会把注释里那一处一起换掉 ⇒ 共享数学被
	 * 内联**两遍**（第一遍落进注释、第二遍才是真的）⇒ 重复的 uniform 与函数定义 ⇒ **包的着色器编译不过**。
	 * 2026-09-29 实测踩到（探针一把抓住 5 条 FAIL，见 notes/351）。这与 notes/345 §7.20 那条
	 * "断言要用行首语义、不能对着 contains 写"是同一族教训：**匹配的粒度必须与真实的约束一致**。</p>
	 *
	 * @param snippet     骨架文件（顶点段不需要，片段段/车灯段各一份）
	 * @param placeholder 占位行（整行，比较时两边都 trim）
	 * @param core        要内联进去的共享数学
	 * @param what        日志里怎么称呼这一段
	 * @return 内联后的文本；占位行不是**恰好一行**时返回 null（宁可整段不注入，也不给包塞进坏源码）
	 */
	private static String inlinePlaceholder(String snippet, String placeholder, String core, String what) {
		final String[] lines = snippet.split("\n", -1);
		int found = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].trim().equals(placeholder)) {
				if (found >= 0) {
					Init.LOGGER.error("[MMTR-LIGHT] 光影注入：{} 里的占位行 {} 出现了不止一次（第 {} 行与第 {} 行）—— 本次不注入，包源码保持原样", what, placeholder, found + 1, i + 1);
					return null;
				}
				found = i;
			}
		}
		if (found < 0) {
			Init.LOGGER.error("[MMTR-LIGHT] 光影注入：{} 里找不到**独占一行**的占位行 {} —— 本次不注入，包源码保持原样", what, placeholder);
			return null;
		}

		final StringBuilder builder = new StringBuilder(snippet.length() + core.length());
		for (int i = 0; i < lines.length; i++) {
			if (i == found) {
				builder.append(core.endsWith("\n") ? core.substring(0, core.length() - 1) : core);
			} else {
				builder.append(lines[i]);
			}
			builder.append('\n');
		}
		return builder.toString();
	}

	/**
	 * 改写 Iris 装配好的某个文件源码。
	 *
	 * @param path   包的路径字符串（如 {@code /world0/gbuffers_entities.vsh} / {@code …fsh}）
	 * @param source include 展开后的完整源码（不可变列表）
	 * @return 注入后的新列表；不需要或无法注入时返回 null（调用方保持原样）
	 */
	public List<String> patch(String path, ImmutableList<String> source) {
		if (path == null || source == null) {
			return null;
		}

		// 仪表：Iris 到底问了哪些文件（只打前若干个不同的）。钩子有没有落上、路径长什么样，一眼就能看出来。
		patchCalls++;
		if (loggedPaths.size() < 24 && loggedPaths.add("PATH:" + path)) {
			Init.LOGGER.info("[MMTR-LIGHT] 光影注入：Iris 请求 {}（第 {} 次，{} 行）", path, patchCalls, source.size());
		}

		final String name = path.substring(path.lastIndexOf('/') + 1);
		final boolean fragment = name.endsWith(".fsh");
		if (!fragment && !name.endsWith(".vsh")) {
			return null;
		}

		if (!fragment) {
			// 顶点段只有一处（法线包装 + 恒等的光照包装），且只有 MTR 画的实体走它。
			return matchesAny(TARGET_PREFIXES, name) ? patchVertex(path, name, source) : null;
		}

		// 片元段有**两件互不相干的事**，目标集也不一样：
		//   · 光场（piece F）—— 修 MTR 车厢那个 draw 的光照值，只有实体程序有那行锚点；
		//   · 车灯（piece H）—— 世界里的光，地形与实体都要。
		// 两者各自有独立的幂等标记（资源重载会拿一份**没处理过**的源码再来一次，
		// 而两个目标集是相交的：gbuffers_entities 同时在两边）。
		final boolean fieldTarget = matchesAny(FRAGMENT_TARGET_PREFIXES, name);
		final boolean headlightTarget = matchesAny(HEADLIGHT_TARGET_PREFIXES, name);
		return fieldTarget || headlightTarget ? patchFragment(path, name, source, fieldTarget, headlightTarget) : null;
	}

	private static boolean matchesAny(String[] prefixes, String name) {
		for (final String prefix : prefixes) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 顶点阶段：piece 1（原型）+ piece V（恒等的光照包装 + 法线包装插在顶点 main 之前）
	 * + 把读光照/法线的 token 包一层。
	 */
	private List<String> patchVertex(String path, String name, List<String> lines) {
		// 内容幂等：资源重载会拿一份**没处理过**的原始源码再来一次，所以不能用"这个路径做过"来挡。
		for (final String line : lines) {
			if (line.trim().equals(TOP_MARKER)) {
				return null;
			}
		}

		final String text = injection();
		if (text == null) {
			return null;
		}

		final int mainIndex = findVertexMain(lines);
		if (mainIndex < 0 || !lines.get(mainIndex).contains("{")) {
			logOnce(path, "找不到**顶点阶段**的 void main()（或它不在同一行带 {），本次不注入");
			return null;
		}

		// piece 1 插在 #version 之后。**必须在所有包代码之前**：包的取光函数在文件很前面就调用了原型。
		final int versionIndex = findVersionLine(lines);
		final int topIndex = Math.min(versionIndex < 0 ? 0 : versionIndex + 1, lines.size());

		final List<String> result = new ArrayList<>(lines.size() + 420);
		int rewritten = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (i == topIndex) {
				for (final String line : TOP_PROTOTYPES.split("\n", -1)) {
					result.add(line);
				}
			}
			if (i == mainIndex) {
				for (final String line : text.split("\n", -1)) {
					result.add(line);
				}
			}

			final String rewrittenLine = rewriteAttributes(lines.get(i));
			if (!rewrittenLine.equals(lines.get(i))) {
				rewritten++;
			}
			result.add(rewrittenLine);
		}
		if (topIndex >= lines.size()) {
			for (final String line : TOP_PROTOTYPES.split("\n", -1)) {
				result.add(line);
			}
		}

		patchedPrograms++;
		patchedPaths.add(path);
		rewrittenTokens += rewritten;
		lastAnchor = name + ":" + (mainIndex + 1) + "/" + lines.size() + (versionIndex < 0 ? "(无#version)" : "");
		logOnce(path, "已注入顶点段（顶点 main 在第 " + (mainIndex + 1) + " 行，piece1 在第 " + (topIndex + 1)
				+ " 行，函数体 " + (mainIndex + 1 + text.split("\n", -1).length) + " 行，改写光照/法线 token " + rewritten
				+ " 处 → 共 " + result.size() + " 行）");
		return result;
	}

	/**
	 * 片元阶段：piece F（光场；uniform + 共享核心 + {@code mmtrFragmentLmCoord}）与
	 * piece H（车灯；uniform + 共享车灯数学 + {@code mmtrPackHeadlightAdd}）都插在 {@code #version} 之后，
	 * 再各自改写自己的锚点行：
	 * <ul>
	 *   <li>piece F：{@code vec2 lmCoordM = lmCoord;} → {@code mmtrFragmentLmCoord(lmCoord, playerPos)}；</li>
	 *   <li>piece H：{@code DoLighting(color, …)} **前**插一行捕获反照率、**后**插一行加性叠加车灯。</li>
	 * </ul>
	 *
	 * <p><b>失败一定是安全的</b>：锚点找不到就只插函数、不改包源码（函数变成死代码），
	 * 包编译照旧；文本读不到就整体返回 null。两个 piece 各自独立失败 —— 光场锚点没了不该拖累车灯。</p>
	 *
	 * @param fieldTarget     这个程序要不要 piece F（光场）
	 * @param headlightTarget 这个程序要不要 piece H（车灯）
	 */
	private List<String> patchFragment(String path, String name, List<String> lines, boolean fieldTarget, boolean headlightTarget) {
		final boolean needsField = fieldTarget && !containsMarker(lines, FRAGMENT_MARKER);
		final boolean needsHeadlight = headlightTarget && !containsMarker(lines, HEADLIGHT_MARKER);
		if (!needsField && !needsHeadlight) {
			return null;
		}

		final String fieldText = needsField ? fragmentInjection() : null;
		final String headlightText = needsHeadlight ? headlightInjection() : null;
		if (fieldText == null && headlightText == null) {
			return null;
		}

		final int versionIndex = findVersionLine(lines);
		final int topIndex = Math.min(versionIndex < 0 ? 0 : versionIndex + 1, lines.size());

		// 车灯的锚点：找到 DoLighting(color, …) 这一次调用的**起止行**（调用跨行，见 HEADLIGHT_ANCHOR 的注释）。
		final int headlightStart;
		final int headlightEnd;
		if (headlightText == null) {
			headlightStart = -1;
			headlightEnd = -1;
		} else {
			int start = -1;
			int end = -1;
			for (int i = 0; i < lines.size(); i++) {
				if (HEADLIGHT_ANCHOR.matcher(lines.get(i)).find()) {
					start = i;
					// 参数表里**不会**出现以 `);` 结尾的行（实测：`… dither,` / `… noVanillaAO,` / `… emission);`），
					// 所以"往后第一行含 `);`"就是这次调用的结尾；8 行封顶，找不到就整体放弃（宁可不动包）。
					for (int j = i; j < Math.min(i + 8, lines.size()); j++) {
						if (lines.get(j).contains(");")) {
							end = j;
							break;
						}
					}
					break;
				}
			}
			headlightStart = start;
			headlightEnd = end;
		}
		final boolean canHeadlight = headlightText != null && headlightStart >= 0 && headlightEnd >= headlightStart;
		if (headlightText != null && !canHeadlight) {
			logOnce(path, "找不到**车灯**的锚点 `DoLighting(color, …)`（或它的调用结尾），本次不注入车灯");
		}

		final List<String> result = new ArrayList<>(lines.size() + 900);
		int anchored = 0;
		int headlightCalls = 0;
		for (int i = 0; i < lines.size(); i++) {
			if (i == topIndex) {
				if (fieldText != null) {
					for (final String line : fieldText.split("\n", -1)) {
						result.add(line);
					}
				}
				if (headlightText != null) {
					for (final String line : headlightText.split("\n", -1)) {
						result.add(line);
					}
				}
			}

			final String line = lines.get(i);

			// piece H：反照率必须在 DoLighting **之前**捕获（之后 color 已经是"反照率 × 光照"）。
			if (canHeadlight && i == headlightStart) {
				result.add(indentOf(line) + "vec3 mmtrPackHeadlightAlbedo = color.rgb;");
			}

			if (fieldText != null) {
				final Matcher matcher = FRAGMENT_ANCHOR.matcher(line);
				if (matcher.matches()) {
					// 只换**一个值**：包之后用它走完整的方块光曲线/天空光/阴影/AO/漫反射。
					result.add(matcher.group(1) + "vec2 lmCoordM = mmtrFragmentLmCoord(lmCoord, playerPos);");
					anchored++;
				} else {
					result.add(line);
				}
			} else {
				result.add(line);
			}

			// piece H：这一次调用之后叠车灯（放在同一层花括号里 ⇒ worldGeoNormal / playerPos 都在作用域内）。
			if (canHeadlight && i == headlightEnd) {
				result.add(indentOf(line) + "mmtrPackHeadlightAdd(color, playerPos, worldGeoNormal, mmtrPackHeadlightAlbedo);");
				headlightCalls++;
			}
		}
		if (topIndex >= lines.size()) {
			if (fieldText != null) {
				for (final String line : fieldText.split("\n", -1)) {
					result.add(line);
				}
			}
			if (headlightText != null) {
				for (final String line : headlightText.split("\n", -1)) {
					result.add(line);
				}
			}
		}

		if (fieldText != null) {
			patchedFragmentPrograms++;
			lastFragmentAnchor = name + ":锚点×" + anchored + "/" + lines.size();
			logOnce(path, "已注入片元段（piece F 在第 " + (topIndex + 1) + " 行，" + fieldText.split("\n", -1).length
					+ " 行；lmCoordM 锚点命中 " + anchored + " 处 → 共 " + result.size() + " 行）");
		}
		if (headlightText != null) {
			patchedHeadlightPrograms++;
			lastHeadlightAnchor = name + ":锚点×" + headlightCalls + "/" + lines.size();
			logOnce(path, "已注入车灯段（piece H 在第 " + (topIndex + 1) + " 行，" + headlightText.split("\n", -1).length
					+ " 行；DoLighting 锚点命中 " + headlightCalls + " 处 → 共 " + result.size() + " 行）");
		}
		if (result.size() == lines.size()) {
			// 两个 piece 都只是"插了函数"而没有改写任何锚点 —— 那不是错误（锚点可能不在这份变体里），
			// 但**必须有记录**：否则"注入了却没效果"只能靠猜。
			logOnce(path, "注入了函数但没有改写任何锚点（field=" + (fieldText != null) + " headlight=" + (headlightText != null) + "）");
		}
		patchedPaths.add(path);
		return result;
	}

	/** 锚点行的缩进（把插入的语句对齐到包自己的排版上，diff 起来好看、也不容易被后续工具误判）。 */
	private static String indentOf(String line) {
		int end = 0;
		while (end < line.length() && (line.charAt(end) == ' ' || line.charAt(end) == '\t')) {
			end++;
		}
		return line.substring(0, end);
	}

	private static boolean containsMarker(List<String> lines, String marker) {
		for (final String line : lines) {
			if (line.trim().equals(marker)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 把"读光照属性 / 法线属性"的 token 包一层。返回原字符串表示这一行没有需要改写的东西。
	 *
	 * <p>注意用 {@code replaceAll} 一次扫描：替换结果不会被二次扫描，所以不会出现
	 * {@code mmtrLightFromPack(mmtrLightFromPack(...))} 这种套娃。</p>
	 */
	private static String rewriteAttributes(String line) {
		if (ATTRIBUTE_DECLARATION.matcher(line).find() || NORMAL_DECLARATION.matcher(line).find()) {
			// 声明行不能包（会变成 in ivec2 mmtrLightFromIvec2(vaUV2); 这种语法错误）
			return line;
		}
		String result = LEGACY_LIGHT_ATTRIBUTE.matcher(line).replaceAll("mmtrLightFromPack($0)");
		result = MODERN_LIGHT_ATTRIBUTE.matcher(result).replaceAll("mmtrLightFromIvec2($0)");
		if (NORMAL_WRAPPER_ENABLED) {
			result = LEGACY_NORMAL.matcher(result).replaceAll("mmtrNormalFromPack($0)");
			result = MODERN_NORMAL.matcher(result).replaceAll("mmtrNormalFromPack($0)");
		}
		return result;
	}

	private static int findVersionLine(List<String> lines) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).trim().startsWith("#version")) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 找**顶点阶段**的 {@code void main()}。
	 *
	 * <p>为什么不能简单找"第一个 {@code #ifdef VERTEX_SHADER} 之后的第一个 void main"（上一版就是这么写的）：
	 * 真实包 {@code world0/gbuffers_entities.vsh} 自己只有 7 行，{@code #ifdef VERTEX_SHADER} 在被 include 的
	 * 程序文件里；而 {@code lib/util/commonFunctions.glsl} 第 14 行**也有**一个 {@code #ifdef VERTEX_SHADER}，
	 * 它经 {@code lib/common.glsl} 被 include 在 {@code program/gbuffers_entities.glsl} 第 6 行 —— 也就是在
	 * 片元段（第 9 行）**之前**。于是上一版会命中第 130 行的**片元 main**，把只在顶点阶段合法的代码插进去 ⇒
	 * 编译失败 ⇒ 正是之前"车辆不显示 / 全黑"的形态。</p>
	 *
	 * <p>这里改成跟踪条件编译栈：只接受「有 {@code #ifdef VERTEX_SHADER} 包着、且没有被
	 * {@code #ifdef FRAGMENT_SHADER} 包着」的那个 {@code void main}。对真实文件这会精确落在文件末尾的顶点
	 * main（实测 {@code …entities.vsh:11585/11657}）。</p>
	 */
	private static int findVertexMain(List<String> lines) {
		final Deque<Boolean> fragmentFrames = new ArrayDeque<>();
		final Deque<Boolean> vertexFrames = new ArrayDeque<>();
		for (int i = 0; i < lines.size(); i++) {
			final String trimmed = lines.get(i).trim();

			final int kind = conditionalKind(trimmed);
			if (kind != 0) {
				final boolean fragment = kind == 1;
				final boolean vertex = kind == 2;
				if (trimmed.startsWith("#endif")) {
					if (!fragmentFrames.isEmpty()) {
						fragmentFrames.pop();
						vertexFrames.pop();
					}
				} else if (trimmed.startsWith("#else") || trimmed.startsWith("#elif")) {
					// 取反：`#ifdef FRAGMENT_SHADER ... #else` 的 else 分支就是"非片元"那一支
					if (!fragmentFrames.isEmpty()) {
						final boolean wasFragment = fragmentFrames.pop();
						final boolean wasVertex = vertexFrames.pop();
						fragmentFrames.push(wasVertex);
						vertexFrames.push(wasFragment);
					}
				} else {
					fragmentFrames.push(fragment);
					vertexFrames.push(vertex);
				}
				continue;
			}

			if (trimmed.startsWith("void main")) {
				boolean inFragment = false;
				boolean inVertex = false;
				for (final boolean value : fragmentFrames) {
					inFragment |= value;
				}
				for (final boolean value : vertexFrames) {
					inVertex |= value;
				}
				if (inVertex && !inFragment) {
					return i;
				}
			}
		}
		return -1;
	}

	/** 0 = 不是条件编译指令；1 = 只在片元阶段成立；2 = 只在顶点阶段成立；3 = 其它条件（中性）。 */
	private static int conditionalKind(String trimmed) {
		if (trimmed.startsWith("#ifdef ")) {
			final String name = firstToken(trimmed.substring(7));
			return "FRAGMENT_SHADER".equals(name) ? 1 : "VERTEX_SHADER".equals(name) ? 2 : 3;
		}
		if (trimmed.startsWith("#ifndef ")) {
			final String name = firstToken(trimmed.substring(8));
			return "VERTEX_SHADER".equals(name) ? 1 : "FRAGMENT_SHADER".equals(name) ? 2 : 3;
		}
		if (trimmed.startsWith("#if ")) {
			final String expression = trimmed.substring(4).replace(" ", "");
			if (expression.contains("!defined(VERTEX_SHADER)") || (expression.contains("defined(FRAGMENT_SHADER)") && !expression.contains("!defined(FRAGMENT_SHADER)"))) {
				return 1;
			}
			if (expression.contains("!defined(FRAGMENT_SHADER)") || (expression.contains("defined(VERTEX_SHADER)") && !expression.contains("!defined(VERTEX_SHADER)"))) {
				return 2;
			}
			return 3;
		}
		if (trimmed.startsWith("#endif") || trimmed.startsWith("#else") || trimmed.startsWith("#elif")) {
			return 3;
		}
		return 0;
	}

	private static String firstToken(String text) {
		final String trimmed = text.trim();
		int end = 0;
		while (end < trimmed.length() && (Character.isLetterOrDigit(trimmed.charAt(end)) || trimmed.charAt(end) == '_')) {
			end++;
		}
		return trimmed.substring(0, end);
	}

	private void logOnce(String path, String message) {
		if (loggedPaths.add(path)) {
			Init.LOGGER.info("[MMTR-LIGHT] 光影注入 {}：{}", path, message);
		}
	}

	public String describe() {
		return "顶点注入=" + patchedPrograms + "/片元注入=" + patchedFragmentPrograms + "/车灯注入=" + patchedHeadlightPrograms
				+ "/钩子调用=" + patchCalls
				+ " 顶点目标=" + String.join("/", TARGET_PREFIXES)
				+ " 片元目标=" + String.join("/", FRAGMENT_TARGET_PREFIXES)
				+ " 车灯目标=" + String.join("/", HEADLIGHT_TARGET_PREFIXES)
				+ " 就绪=顶点" + (injection != null) + "/片元" + (fragmentInjection != null) + "/车灯" + (headlightInjection != null)
				+ " 已注入路径=" + patchedPaths.size() + " 改写token=" + rewrittenTokens
				+ " 顶点锚点=" + lastAnchor + " 片元锚点=" + lastFragmentAnchor + " 车灯锚点=" + lastHeadlightAnchor;
	}

	/**
	 * 5 秒诊断行里的**短**版本。
	 *
	 * <p>为什么单开一个：上面那个完整版只在"第一次检测到光影包在跑"时打一次（{@code packShaderpackModeLog}），
	 * 而车灯这一侧要判的东西（注入了几个程序 / 锚点命中几处 / 文本备没备好）**必须能持续看到** ——
	 * 否则用户重启客户端之后，我只能靠"再进一次世界碰运气"去抓那一行。</p>
	 */
	public String describeHeadlight() {
		return "光影车灯=" + patchedHeadlightPrograms + "个程序/"
				+ (headlightInjection == null ? "文本缺失" : "文本就绪") + " 锚点=" + lastHeadlightAnchor;
	}
}
