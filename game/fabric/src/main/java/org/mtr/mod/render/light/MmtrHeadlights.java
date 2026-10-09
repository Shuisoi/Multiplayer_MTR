package org.mtr.mod.render.light;

import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.mtr.core.mmtr.MmtrLightSwitch;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.Init;
import org.mtr.mod.client.MmtrVehicleAnchors;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.render.PositionAndRotation;

import java.nio.FloatBuffer;
import java.util.Map;

/**
 * MMTR 车灯：把模型里的 {@code mmtr_light_<cab>_<n>} 锚点变成**逐片元**的光源。
 *
 * <h3>为什么是"逐片元的光源"而不是"往世界里放光"</h3>
 * <p>MC 的方块光是**烘进几何**的（地形顶点光 / MTR 每 draw 一个常量），所以任何"改世界光照"的做法
 * 都必然带来两个后果：粒度是**格**（16 级整数场，锥边是方块状），以及一移动就要**重烘网格**。
 * 车灯要的是连续的锥形衰减与半影 —— 那不是"采样更细"能解决的，只能**把光做成函数**：在片元里按
 * 世界坐标现算（这正是所有现代引擎的做法，也是本仓光场对车厢已经做过的事，见 notes/344）。</p>
 *
 * <h3>这套东西覆盖哪些几何</h3>
 * <p>它只加在**我们自己的那个 program**（{@code mmtr_vehicle_light}）里，而 MTR 的优化渲染器用它画
 * <b>车厢 + 3D 钢轨</b> ⇒ 车灯会照亮车体、灯罩附近的车头、以及**车头前方那段钢轨**。
 * 地形（道床、隧道壁、站台）走的是原版 {@code rendertype_solid} 那一族，**不在本次范围内**；
 * 那条路要另开一份地形 program（方案见 notes/345 §5），与这里的数学是同一套。</p>
 *
 * <h3>坐标口径（必须与着色器一致）</h3>
 * <p>着色器里每像素的位置是**相机相对世界坐标**（{@code IViewRotMat * ModelViewMat * ModelMat * Position}），
 * 所以这里上传的灯位也必须是"世界 − 真相机"。用的是**真相机**（{@code gameRenderer.getCamera()}），
 * 不是 MTR 那个滞后 0~1 格的渲染偏移 —— 混用会让光跟着玩家走动滑（notes/344 §10.4 的教训）。</p>
 *
 * <h3>每一盏灯该亮什么（notes/352：档位 + 端 + 换向器，不猜行进方向）</h3>
 *
 * <p>判据全部来自**引擎的镜像**，这里不猜任何东西：</p>
 * <ol>
 *   <li><b>端</b>：锚点是车体局部坐标，而 <b>+Z = 引擎的 B 端</b>
 *       （{@code MmtrVehicleAnchors.engineEndOfSeat}，由 bogie1→bogie2 定义）⇒ 一盏灯属于哪一端是几何事实；</li>
 *   <li><b>档位</b>：每个驾驶室各一个开关（{@code mmtrLightA} / {@code mmtrLightB}），由司机在驾驶室里按 L 循环；</li>
 *   <li><b>换向器</b>：{@code mmtrReverser == 0}（N）⇒ 两端固定红；</li>
 *   <li>判据本身在引擎的 {@code MmtrLightSwitch.lampState}（纯函数 + 真值表），
 *       客户端与引擎跑的是**同一份**规则，不是各写一遍。</li>
 * </ol>
 *
 * <p>为什么以前那套"按逐帧位置增量求行进方向"必须退休：车停着（进站、等信号、无人）时它判不出朝向，
 * 只能退回 {@code headlightAmbiguous} 那种含糊档；而司机在驾驶室里**明确拨了开关**，
 * 灯的答案不该再依赖"车有没有在动"。顺带也就没有"停着时两端都亮半强度"这种既不像前照灯也不像尾灯的状态了。</p>
 *
 * <p><b>尾灯不照世界</b>（用户口径"无需灯光射线"）：地形那一侧（Sodium / 光影包的地形 program）上传时
 * **跳过尾灯候选**，尾灯只在车厢/实体那一侧留一圈很近的溢光（见 {@code upload} 里的 {@code tail} 过滤）。
 *
 * <h3>灯罩的**颜色**不走着色器（2026-10-03 改口径）</h3>
 *
 * <p>车灯的加性项是锥形 × 兰伯特的，而灯罩自己两条都不满足（在锥顶之外 + 法线背对灯心）⇒
 * **车灯照不亮自己的灯罩**：灯罩的颜色从来不是"被照亮"的结果，而是"这块玻璃是什么颜色"。</p>
 *
 * <p>2026-10-03 之前这笔颜色由着色器在某个半径内**染色**（notes/373 的 {@code mmtrMarkerGlow}：
 * 把 {@code Pos.w} 的符号当标志位、按距离把灯罩 mix 成灯色）。那条路要同时凑齐一整套条件才生效
 * （着色器得是最新的、程序得认得 uniform、灯要在 8 个槽位里、还得离相机够近），
 * 现场表现就是"一会儿红一会儿不红，只能靠猜"。用户口径：「不用切，直接改颜色就行了，不用那么复杂」。</p>
 *
 * <p>现在改走 MTR 优化渲染器**本来就有的**那条路：{@code OptimizedRenderer.queue(model, pose, color, light)}
 * 的 color 在 GL 里就是 {@code COLOR} 顶点属性，而在 MTR 的顶点映射里它正是
 * {@code VertexAttributeSource.GLOBAL}（每个 draw 一个常量，见 {@code OptimizedModel.DEFAULT_MAPPING}）
 * ⇒ **一次 draw 一个颜色**，粒度正好是"一条灯的灯罩"。于是颜色在 {@link #lampColor} 里按
 * "这一节车的灯罩在哪一端 + 那一端的档位"算出来，由 {@code VehicleResource.queue} 交给
 * {@code PartCondition.MMTR_LAMP} 那一组几何（灯罩本身照旧由 MTR 画：贴图、剔除、批次都不用管）。</p>
 *
 * <p>所有开关都在 {@code run/mmtr-lightfield.properties} 里，**改一行 2 秒生效、不用重启**
 * （读取口在 {@link MmtrLightField#refreshDiagnostics()}，与光场共用一份）。</p>
 */
public final class MmtrHeadlights {

	/**
	 * 同帧上传的车灯上限。**必须与着色器里 {@code mmtrHeadlightPos/Dir/Color} 的数组长度一致**
	 * （{@code mmtr_lightfield_mtr.glsl} 里的 {@code MMTR_MAX_HEADLIGHTS}）。
	 */
	public static final int MAX_LIGHTS = 8;

	/** 主前照灯的颜色（暖白，接近卤素/LED 混色）。 */
	private static final float[] HEADLIGHT_RGB = {1.00F, 0.96F, 0.88F};
	/** 尾灯的颜色（红）。 */
	private static final float[] TAILLIGHT_RGB = {1.00F, 0.12F, 0.06F};
	/**
	 * 强度基准的灯罩面积（m²）。实测 SAF420 现役车灯 = 左右各一个 0.26 × 0.26 m 的方块灯罩
	 * ⇒ 0.0676 m²。见 notes/345 §2.1。
	 */
	private static final double REFERENCE_LENS_AREA_M2 = 0.0676;
	/** 尾灯的射程是主灯的多少倍（尾灯只照亮车尾那一小块）。 */
	private static final float TAILLIGHT_RANGE_SCALE = 0.30F;
	private static final long LOG_INTERVAL_MILLIS = 5000L;

	/*
	 * --------------------------------------------------------------------------------------------
	 * **灯罩的颜色**（2026-10-03：用户口径「不用切，直接改颜色就行了」）
	 *
	 * 灯的三种 = 三个档位（引擎 MmtrLightSwitch）：近光 / 远光 = 白玻璃罩、尾灯 = 红玻璃罩、
	 * 关闭 = 不亮的暗玻璃。**颜色是这一块玻璃自己的颜色**，与"有没有被光照到"无关
	 * （车灯照不亮自己的灯罩，见类注释），所以三档就是三个常数。
	 *
	 * ⚠️ **打包口径是 RGBA（0xRRGGBBAA），不是 MC 惯用的 ARGB**：MTR 把颜色当逐 draw 的常量顶点属性
	 * 上传（{@code VertexAttributeState.apply()} 里 {@code glVertexAttrib4f(loc, (c>>24)/255, (c>>16)/255,
	 * (c>>8)/255, (c&255)/255)}），四个分量按 x=最高字节 ⇒ 着色器里的 {@code Color.r} 取的是**最高字节**。
	 * 传 MC 那套 0xAARRGGBB 会把红蓝对调、还把 alpha 塞进 r（症状："红的不红、蓝的不蓝"）。
	 * 所以一律用 {@link #lensRgba} 生成，不手写十六进制。
	 * --------------------------------------------------------------------------------------------
	 */

	/** 近光灯罩：暖白（卤素/LED 混色，与光场里 HEADLIGHT_RGB 同一口径）。 */
	private static final int LENS_LOW_RGBA = lensRgba(0xFF, 0xF4, 0xE0);
	/** 远光灯罩：冷白（LED）—— 与近光并排时一眼能看出哪一档在亮。 */
	private static final int LENS_HIGH_RGBA = lensRgba(0xE8, 0xF4, 0xFF);
	/** 尾灯灯罩：红（乘在白色灯罩贴图上 ⇒ 红玻璃）。 */
	private static final int LENS_TAIL_RGBA = lensRgba(0xFF, 0x2A, 0x1E);
	/** 这一端关着（{@link MmtrLightSwitch#OFF}）或车不在段上（回库）：不亮的暗玻璃，不是透明。 */
	private static final int LENS_OFF_RGBA = lensRgba(0x3A, 0x3E, 0x44);
	/**
	 * 这节车没有灯（拖车）：**不染**。
	 *
	 * <p>⚠️ 这个值同时是"这次 draw 是不是灯罩"的判据：{@code VehicleResource.queue} 只对
	 * {@code MMTR_LAMP} 那一组几何传灯色，其余部件一律传 {@link #LENS_NONE_RGBA}。
	 * 所以四个灯色**都**不等于它 —— 近光/远光因此是有色温的白（暖白 / 冷白），而不是纯白 0xFFFFFFFF；
	 * 而且它们的 alpha 都是 {@link #LENS_ALPHA}（不是 255），见那个常量的说明。</p>
	 */
	private static final int LENS_NONE_RGBA = 0xFFFFFFFF;

	/**
	 * 灯色的 **alpha = 250/255（≈0.980）**：它是"这一片片元是灯罩"的标志位，着色器据此走**自发光**。
	 *
	 * <h3>为什么用 alpha 而不是再加一个 uniform（2026-10-03）</h3>
	 *
	 * <p>用户口径：「红了，而且有光，但是灯本身不是发亮的」—— 顶点色只让灯罩**变红**，
	 * 它仍然是"贴图 × 灯色 × 光照"的一块玻璃；灯该有的样子是**自发光**（亮的直接是灯色）。
	 * 着色器要知道"这一片是灯罩"，而这个信息必须**跟着几何走**：逐 draw 的 uniform 是按 program 存的，
	 * 一次灯罩 draw 写下的值会一直留到同一个 program 的下一次 draw（原版实体、我们自己别的绘制
	 * 都吃同一份 program）⇒ 只要漏清一次，别人的东西就会被染成灯色。</p>
	 *
	 * <p>顶点色本身就是逐 draw 的（MTR 的 {@code VertexAttributeState}），alpha 空着没用
	 * （灯罩不透明），于是拿它当标志：**250/255 而不是 255**，肉眼与 1.0 无法区分，
	 * 而着色器里是一次浮点比较就能认出来的确定值。非灯罩的部件拿到的仍是 255（纯白）。</p>
	 */
	public static final int LENS_ALPHA = 0xFA;

	/**
	 * 把 RGB 打成 {@code OptimizedRenderer.queue} 那份颜色（**RGBA 打包**，见上面那段口径）。
	 *
	 * <p>alpha 恒为 {@link #LENS_ALPHA}（不是 255）：那是"灯罩"标志位，fsh 据此自发光。
	 * 低字节写错会让灯罩整个消失（着色器开头有一条 {@code color.a < 0.1 ⇒ discard}）。</p>
	 */
	private static int lensRgba(int red, int green, int blue) {
		return (red << 24) | (green << 16) | (blue << 8) | LENS_ALPHA;
	}

	/**
	 * 按 {@link MmtrLightField#lampLensGain()} 把灯色提亮（properties: {@code lampLensGain}，2 秒热生效）。
	 *
	 * <p>做法与"红色先饱和"一致：三个通道各自乘系数并钳到 255 ⇒ 红尾灯在 1.5 倍时是 (255, 63, 45)，
	 * 看上去更"热"，而不是整块变成粉色。alpha（标志位）不动。</p>
	 */
	private static int withLensGain(int color) {
		final float gain = MmtrLightField.lampLensGain();
		if (gain == 1.0F) {
			return color;
		}
		final int red = Math.min(255, Math.round(((color >> 24) & 0xFF) * gain));
		final int green = Math.min(255, Math.round(((color >> 16) & 0xFF) * gain));
		final int blue = Math.min(255, Math.round(((color >> 8) & 0xFF) * gain));
		return (red << 24) | (green << 16) | (blue << 8) | LENS_ALPHA;
	}

	/**
	 * "从没命中过我们 uniform 的 program"多久复核一次位置。
	 *
	 * <p>为什么需要复核：GL 的 program 名字会被回收再发（光影包装载/重载会销毁一批 program），
	 * 缓存下来的位置就可能属于**另一个** program —— 那种情况下每一次 {@code glUniform*}
	 * 都是 GL_INVALID_OPERATION，症状是"日志刷屏 + 车灯一个都没传上去"（notes/351 §4.3）。
	 * 复核一次是 7 次 {@code glGetUniformLocation}，5 秒一次在帧时里看不出来。</p>
	 */
	private static final long RECHECK_INTERVAL_MILLIS = 5000L;

	private static final MmtrHeadlights INSTANCE = new MmtrHeadlights();

	public static MmtrHeadlights getInstance() {
		return INSTANCE;
	}

	// —— 本帧收集到的候选灯（每帧清空重填；用数组池避免每帧分配）——
	private final ObjectArrayList<Candidate> candidates = new ObjectArrayList<>();
	/** 每个 program 的 uniform 位置缓存（原版/光影包的程序里查不到，就是 −1 ⇒ 静默跳过）。 */
	private final Map<Integer, Locations> locationsByProgram = new Object2ObjectOpenHashMap<>();

	private final FloatBuffer posBuffer = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
	private final FloatBuffer dirBuffer = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);
	private final FloatBuffer colorBuffer = BufferUtils.createFloatBuffer(MAX_LIGHTS * 4);

	private int collectingFrame = -1;
	private int uploadedThisFrame;
	private double camX;
	private double camY;
	private double camZ;
	private Object boundWorld;

	// —— 诊断（每 5 秒一行：让"有没有数据、方向判没判反"不靠眼睛）——
	private long lastLogMillis;
	private int statCars;
	private int statAnchors;
	private int statCollected;
	private int statUploaded;
	/** 本窗口给**地形** program 传了几盏（notes/345 §6 那一侧，诊断行用）。 */
	private int statTerrainUploaded;
	private final ObjectArrayList<String> statExamples = new ObjectArrayList<>();
	private int statExamplesLimit = 2;
	/**
	 * 本窗口最后画到的那列车的灯光状态（notes/352）：两端档位 + 换向器 N 的"固定红"。
	 * 没有它，"灯不亮"这件事只能靠猜是开关、是端、还是程序没注入。
	 */
	private String statLightSummary = "-";
	/**
	 * 本窗口最后问过颜色的那一节车（{@link #lampColor}）：车号 / 端 / 档位 / 灯罩颜色。
	 *
	 * <p>为什么它必须出现在日志里：灯罩的颜色是**逐 draw 的顶点色**，出问题时症状只有一种 ——
	 * "看起来还是白的"。而"算出来的颜色对不对"与"这个颜色有没有真的画上去"是两件事，
	 * 前者必须有日志（后者由眼睛）。没有这一行，2026-10-03 那种来回就只能靠猜。</p>
	 */
	private static String statLensSummary = "";
	/**
	 * 本窗口"灯罩颜色真的交给了一次 draw"的次数（{@link #noteColoredDraw}）。
	 *
	 * <p>为什么它必须出现：算出颜色 ≠ 颜色被画上去。MTR 有**两条**画部件的路
	 * （优化渲染器 {@code OptimizedRenderer.queue(..., color, light)} 与传统
	 * {@code ModelPartExtension.render}），灯罩走哪一条、颜色有没有到 GL，眼睛只能看出"还是白的"
	 * —— 2026-10-03 现场就在"数据不对"和"画的路不对"之间来回。两个计数器把两者分开：</p>
	 * <ul>
	 *   <li>{@code 染色} = 优化那条路真的排了带颜色的 draw；</li>
	 *   <li>{@code 传统} = 传统路真的画了一次灯罩（那条路没有颜色参数，画出来一定是白的）。</li>
	 * </ul>
	 */
	private static int statColoredDraws;
	/** 本窗口传统路（{@code ModelPartExtension.render}）画灯罩的次数 —— 见 {@link #statColoredDraws}。 */
	private static int statLegacyLampDraws;
	private final Object2ObjectOpenHashMap<String, Boolean> modelsWithoutLightsReported = new Object2ObjectOpenHashMap<>();

	private MmtrHeadlights() {
	}

	/**
	 * 开始本帧的收集（**惰性**：第一次 {@link #requestCar} 时做，而不是在某个固定钩子里）。
	 *
	 * <p>为什么不在光场的 {@code beginFrame} 里一起做：那个钩子在 {@code MainRenderer.render} 的**末尾**
	 * （所有 draw 之后，见 MainRenderer:194），而收集发生在之前的 {@code RenderVehicles.render} (:135)。
	 * 若在末尾清空，相机位置就永远是**上一帧**的（车 20 m/s 时差 0.33 格，光斑会相对几何轻微滞后）。
	 * 用光场的帧号当窗口 id，就能"在收集的那一刻读真相机"，同时保证每帧只清一次。</p>
	 */
	private void beginCollectionIfNeeded() {
		final int frameId = MmtrLightField.frameId();
		if (frameId == collectingFrame) {
			return;
		}
		collectingFrame = frameId;
		uploadedThisFrame = 0;
		candidates.clear();
		statCars = 0;
		statAnchors = 0;
		statCollected = 0;
		statExamples.clear();

		final net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		if (client.world != boundWorld) {
			// 换世界/重进：program 位置缓存一律作废（GL 名字会被回收再发，见 RECHECK_INTERVAL_MILLIS）。
			boundWorld = client.world;
			locationsByProgram.clear();
		}
		if (client.gameRenderer == null || client.gameRenderer.getCamera() == null) {
			return;
		}
		final net.minecraft.util.math.Vec3d cameraPosition = client.gameRenderer.getCamera().getPos();
		camX = cameraPosition.x;
		camY = cameraPosition.y;
		camZ = cameraPosition.z;
	}

	/**
	 * 登记一列车的车灯。只对**这一帧真的会被画**的车调用（调用点与光场的 {@code requestCar} 同一个分支）。
	 *
	 * @param vehicle              车列（借它拿车辆型号与"这是该型号的第几节"）
	 * @param carNumber            该车在编组里的序号
	 * @param carPositionAndRotation 该节车**绝对**（世界）位姿 —— 锚点是车体局部坐标，必须用它转世界
	 */
	public void requestCar(VehicleExtension vehicle, int carNumber, PositionAndRotation carPositionAndRotation) {
		if (!MmtrLightField.isEnabled() || !MmtrLightField.headlightsEnabled()) {
			return;
		}
		beginCollectionIfNeeded();

		final var cars = vehicle.getVehicleCarsAndPositions();
		if (carNumber < 0 || carNumber >= cars.size()) {
			return;
		}
		final String vehicleId = cars.get(carNumber).left().getVehicleId();
		final ObjectArrayList<Anchor> anchors = MmtrVehicleAnchors.get(vehicleId);
		if (anchors.isEmpty()) {
			return;
		}
		final ObjectArrayList<Anchor> lamps = MmtrVehicleAnchors.findLights(anchors, MmtrVehicleAnchors.modelCarIndex(vehicle, carNumber));
		if (lamps.isEmpty()) {
			// 一个模型有锚点却没有灯：说一次就够（否则每列车每帧都在刷）。
			if (modelsWithoutLightsReported.put(vehicleId, Boolean.TRUE) == null) {
				Init.LOGGER.info("[MMTR-LIGHT] 车灯：模型 {} 的锚点里没有 mmtr_light_*，这列车不会有车灯", vehicleId);
			}
			return;
		}

		statCars++;
		statAnchors += lamps.size();

		/*
		 * 灯光状态（notes/352）—— 全部是**引擎镜像里的真值**，一次读全，灯多也不重复查：
		 *   两端各一个开关（每个驾驶室一个）+ 换向器（N ⇒ 固定红）。
		 * mode 是诊断口（properties 的 headlightMode）：auto = 按档位；all = 全当白前照灯；off = 全灭。
		 */
		final String mode = MmtrLightField.headlightMode();
		final int lightA = vehicle.getMmtrLightAFromSync();
		final int lightB = vehicle.getMmtrLightBFromSync();
		final int reverser = vehicle.getMmtrReverserFromSync();
		final boolean lightHasOff = vehicle.isMmtrLightLocoFromSync();
		final float nightLux = MmtrLightField.headlightLux();
		final float dayLux = nightLux * MmtrLightField.headlightDayRatio();
		statLightSummary = MmtrLightSwitch.describe(lightA, lightB, reverser, lightHasOff);

		for (final Anchor lamp : lamps) {
			// 车体局部 → 世界：与 PositionAndRotation 里画车用的是同一套旋转顺序（X 再 Y 再平移）。
			final Vector worldPosition = carPositionAndRotation.transformForwards(lamp.position, Vector::rotateX, Vector::rotateY, Vector::add);
			final Vector worldDirection = new Vector(lamp.normal.x(), lamp.normal.y(), lamp.normal.z())
					.rotateX(carPositionAndRotation.pitch)
					.rotateY(carPositionAndRotation.yaw);
			final double directionLength = Math.sqrt(
					worldDirection.x() * worldDirection.x() + worldDirection.y() * worldDirection.y() + worldDirection.z() * worldDirection.z());
			if (directionLength < 1.0E-6) {
				continue;
			}
			final double dx = worldDirection.x() / directionLength;
			final double dy = worldDirection.y() / directionLength;
			final double dz = worldDirection.z() / directionLength;

			// 这一盏属于哪一端：**几何**说了算（车体局部 +Z = 引擎的 B 端），不是锚点编号。
			final int end = MmtrVehicleAnchors.engineEndOfSeat(lamp.position.z());
			final int switchState = MmtrLightSwitch.switchOfEnd(lightA, lightB, end);
			final int state = lampState(mode, switchState, reverser, lightHasOff);
			if (state == MmtrLightSwitch.OFF) {
				continue;
			}

			final boolean tail = state == MmtrLightSwitch.TAIL;
			// 强度按**灯罩面积**缩放（基准 = SAF420 现役的那 0.26 m 方块灯罩）。
			// 为什么用面积而不是命名里的序号：实测 SAF420 车头是**左右两盏一样的灯**，
			// 把 n>=2 当"弱标志灯"会让车头一亮一暗；而面积是模型自己带的意图 ——
			// 将来加一颗小标志灯，不用改命名规则就自动更弱。
			final float areaScale = (float) Math.max(0.35, Math.min(1.8, lamp.widthM * lamp.heightM / REFERENCE_LENS_AREA_M2));
			// 近光 / 远光（notes/352 的两档；2026-10-03 起就是这两种灯）：都是白灯，只差强度与射程。
			final float lux = (tail ? MmtrLightField.headlightTailLux() : state == MmtrLightSwitch.LOW ? dayLux : nightLux) * areaScale;
			final float range = (float) (MmtrLightField.headlightRangeM() * (tail ? TAILLIGHT_RANGE_SCALE : 1.0));
			final float innerDegrees = (float) MmtrLightField.headlightConeDeg();
			final float outerDegrees = (float) MmtrLightField.headlightPenumbraDeg();
			final float[] rgb = tail ? TAILLIGHT_RGB : HEADLIGHT_RGB;

			final double relativeX = worldPosition.x() - camX;
			final double relativeY = worldPosition.y() - camY;
			final double relativeZ = worldPosition.z() - camZ;
			final double cameraDistance = Math.sqrt(relativeX * relativeX + relativeY * relativeY + relativeZ * relativeZ);
			// 射程之外照不到任何东西（而且相机离得远时它只占几个像素）⇒ 直接不进候选，省一个槽位。
			if (cameraDistance > range + 64.0) {
				continue;
			}

			final Candidate candidate = new Candidate();
			candidate.x = relativeX;
			candidate.y = relativeY;
			candidate.z = relativeZ;
			candidate.dirX = dx;
			candidate.dirY = dy;
			candidate.dirZ = dz;
			candidate.red = rgb[0] * lux;
			candidate.green = rgb[1] * lux;
			candidate.blue = rgb[2] * lux;
			candidate.inverseRange = 1.0F / Math.max(range, 0.5F);
			candidate.cosInner = (float) Math.cos(Math.toRadians(Math.max(0.5F, innerDegrees)));
			candidate.cosOuter = (float) Math.cos(Math.toRadians(Math.max(1.0F, outerDegrees)));
			candidate.cameraDistance = cameraDistance;
			/** 尾灯不照世界（"无需灯光射线"）：地形那一侧上传时会跳过它，见 {@link #upload()}。 */
			candidate.tail = tail;
			candidates.add(candidate);
			statCollected++;

			if (statExamples.size() < statExamplesLimit) {
				statExamples.add(String.format("%s#%d %s 端=%s 档=%s 世界=(%.1f, %.1f, %.1f) → %s",
						vehicleId, carNumber, lamp.name,
						end == MmtrLightSwitch.END_B ? "B" : "A", MmtrLightSwitch.label(switchState),
						worldPosition.x(), worldPosition.y(), worldPosition.z(),
						describe(state, lux, range)));
			}
		}
	}

	/**
	 * 这一盏灯该亮什么（诊断口 {@code headlightMode} 在这里生效）。
	 *
	 * <p>判据本体是引擎的 {@link MmtrLightSwitch#lampState}（开关档位 + 换向器，纯函数）；
	 * 这里只加两个**诊断**档：{@code all} = 全当白前照灯（用来单独看"灯本身长什么样"）、
	 * {@code off} = 全灭 —— 都是 A/B 用的，正常玩不会碰到。</p>
	 */
	private static int lampState(String mode, int switchState, int reverser, boolean lightHasOff) {
		if ("off".equals(mode)) {
			return MmtrLightSwitch.OFF;
		}
		if ("all".equals(mode)) {
			return MmtrLightSwitch.HIGH;
		}
		return MmtrLightSwitch.lampState(MmtrLightSwitch.sanitize(switchState, lightHasOff), reverser);
	}

	private static String describe(int state, float lux, float range) {
		return String.format("%s lux=%.2f 射程=%.0f", MmtrLightSwitch.label(state), lux, range);
	}

	/*
	 * --------------------------------------------------------------------------------------------
	 * **灯罩这一笔颜色**（2026-10-03）：VehicleResource.queue 每画一节车就问一次。
	 *
	 * 判据与上面"这盏灯该亮什么"**逐字同源**（同一个 lampState：端 + 档位 + 换向器 + 诊断口），
	 * 唯一多出来的一步是"哪一端" —— 它由这一节车的灯锚点几何给出（车体局部 +Z = 引擎 B 端），
	 * 与 requestCar 里给每盏灯定端用的是同一条规则 ⇒ 灯罩的颜色与灯本身的光束不可能对不上。
	 *
	 * 返回的是**逐 draw 的顶点色**（RGBA 打包，见 LENS_* 常量那段口径）。
	 * --------------------------------------------------------------------------------------------
	 */

	/**
	 * 这一节车的**灯罩**该染成什么颜色（ARGB/RGBA 见返回值说明）。
	 *
	 * @param vehicle  车列
	 * @param carNumber 这一节车在编组里的序号（灯锚点、端号都按它取）
	 * @return 交给 {@code OptimizedRenderer.queue} 的颜色（{@link #lensRgba} 口径）；
	 *         这节车没有灯（拖车）、或整个车灯系统关着 ⇒ {@code 0xFFFFFFFF}（不染）
	 */
	public static int lampColor(VehicleExtension vehicle, int carNumber) {
		if (!MmtrLightField.isEnabled() || !MmtrLightField.headlightsEnabled()) {
			return LENS_NONE_RGBA;
		}
		final var cars = vehicle.getVehicleCarsAndPositions();
		if (carNumber < 0 || carNumber >= cars.size()) {
			return LENS_NONE_RGBA;
		}
		final ObjectArrayList<Anchor> lamps = MmtrVehicleAnchors.findLights(
				MmtrVehicleAnchors.get(cars.get(carNumber).left().getVehicleId()),
				MmtrVehicleAnchors.modelCarIndex(vehicle, carNumber));
		if (lamps.isEmpty()) {
			return LENS_NONE_RGBA;
		}

		final int end = MmtrVehicleAnchors.engineEndOfSeat(lamps.get(0).position.z());
		final int switchState = MmtrLightSwitch.switchOfEnd(vehicle.getMmtrLightAFromSync(), vehicle.getMmtrLightBFromSync(), end);
		final int lampState = lampState(MmtrLightField.headlightMode(), switchState, vehicle.getMmtrReverserFromSync(), vehicle.isMmtrLightLocoFromSync());
		// 不在段上（回库/停放）：这一段没有"运行中的灯"，画暗玻璃而不是让车头出现一个洞。
		final boolean onRoute = vehicle.getIsOnRoute();
		final int lensState = onRoute ? lampState : MmtrLightSwitch.OFF;
		final int color = lensColorOf(lensState);

		if (statLensSummary.isEmpty()) {
			statLensSummary = String.format("%s#%d 端=%s 档=%s（开关=%s）→ %s",
					cars.get(carNumber).left().getVehicleId(), carNumber,
					end == MmtrLightSwitch.END_B ? "B" : "A", MmtrLightSwitch.label(lampState),
					MmtrLightSwitch.label(MmtrLightSwitch.sanitize(switchState, vehicle.isMmtrLightLocoFromSync())),
					onRoute ? describeLens(lensState) : "灭=暗（不在段上）");
		}
		return color;
	}

	/**
	 * 档位 → 灯罩颜色（四种灯 = 四个常数，见 LENS_* 那段）。
	 *
	 * <p>亮度倍数（{@link MmtrLightField#lampLensGain()}）在这里乘上：它是 properties 里的一个键，
	 * 而这方法每帧都跑 ⇒ 调它 2 秒生效、不用重启。默认 1.0 = 就是灯色本身（纯饱和红）；
	 * **调大只会让红变浅（发粉）** —— 红通道早已饱和，倍数抬的是 G/B。
	 * 用户口径：「就这样（纯饱和红）」（notes/374 §5）。</p>
	 */
	private static int lensColorOf(int state) {
		switch (state) {
			case MmtrLightSwitch.LOW:
				return withLensGain(LENS_LOW_RGBA);
			case MmtrLightSwitch.HIGH:
				return withLensGain(LENS_HIGH_RGBA);
			case MmtrLightSwitch.TAIL:
				return withLensGain(LENS_TAIL_RGBA);
			default:
				return withLensGain(LENS_OFF_RGBA);
		}
	}

	/**
	 * 记一次"带颜色的 draw 排进了优化渲染器"（{@code OptimizedRendererWrapper.queue} 调）。
	 *
	 * <p>与 {@link #noteLegacyLampDraw()} 一起把"颜色算错了"和"颜色没走上那条路"分开 —— 见
	 * {@link #statColoredDraws} 的说明。</p>
	 */
	public static void noteColoredDraw() {
		statColoredDraws++;
	}

	/** 记一次"传统路画了灯罩"（那条路没有颜色参数 ⇒ 画出来一定是白的），见 {@link #statColoredDraws}。 */
	public static void noteLegacyLampDraw() {
		statLegacyLampDraws++;
	}

	/**
	 * 灯罩颜色的中文名（诊断行用）。
	 *
	 * <p>判据是**档位**而不是颜色值：颜色值会随 {@code lampLensGain} 变（改一行 2 秒生效），
	 * 拿它跟常数比就会在调过亮度之后一路报"不染"—— 2026-10-03 实测撞过这个假象。</p>
	 */
	private static String describeLens(int state) {
		switch (state) {
			case MmtrLightSwitch.LOW:
				return "近光=暖白";
			case MmtrLightSwitch.HIGH:
				return "远光=冷白";
			case MmtrLightSwitch.TAIL:
				return "尾灯=红";
			case MmtrLightSwitch.OFF:
				return "灭=暗";
			default:
				return "不染";
		}
	}

	/**
	 * 把本帧的车灯传给**当前绑定的 program**。
	 *
	 * <p>调用点是 MTR 每个 draw 的 {@code VertexAttributeState.apply()}（{@link MmtrLightField#onDrawState}），
	 * 那一刻 program 已经绑定、而且是逐 draw 的。这里每个 (帧, program) 只传一次 —— 灯是**全局**的
	 * （不随 draw 变），所以一次传完所有 draw 都吃同一份。</p>
	 *
	 * <p>原版/光影包的程序里没有这些 uniform ⇒ 查到的位置是 −1 ⇒ 静默跳过（不会画坏任何东西）。</p>
	 */
	public void uploadForCurrentProgram() {
		if (!canUpload()) {
			return;
		}
		// 逐 draw 那条路：program 走光场的批次级缓存（一个批次内恒定），不再每 draw 查一次 GL。
		upload(MmtrLightField.getInstance().currentProgramId());
	}

	/**
	 * 地形（世界方块）那一侧：Sodium 的区块 program 上也要有同一份灯表（notes/345 §6）。
	 *
	 * <p>调用点是 Sodium 的 {@code GlProgram.bind()}（见 {@link org.mtr.mixin.sodium.SodiumGlProgramMixin}）。
	 * 与车厢那条路的区别只有一个，而且是本质区别：<b>地形在实体之前画</b>，所以这里用的是**上一帧**
	 * 收集到的那份灯表。灯在世界里没动、动的是相机，所以上传时按"收集时的相机 → 现在的相机"整体平移一次
	 * （世界坐标 = 相机相对 + 相机，平移量对 8 盏灯是同一个），滞后就被消掉了。</p>
	 */
	public void uploadForTerrainProgram() {
		if (!canUpload()) {
			return;
		}
		/*
		 * 地形这条路由 Sodium 的 GlProgram.bind() 直接调进来，**不在 MTR 的批次循环里**
		 * ⇒ 光场那份"批次级 program"缓存对这一侧没有意义（可能是上一帧最后一个批次的残留），
		 * 必须现查。代价是每次 Sodium 绑 program 一次 —— 一帧只有几个，不是逐 draw。
		 */
		upload(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
	}

	private static boolean canUpload() {
		return MmtrLightField.isEnabled() && MmtrLightField.headlightsEnabled();
	}

	/**
	 * 作废"每个 program 的 uniform 位置 + 本帧传过没"这两张表。
	 *
	 * <p>为什么必须能作废：两张表都是**按 GL program id 索引**的（{@link #locationsByProgram} /
	 * {@code Locations.uploadedFrame}），而光影包每次（重新）装载都会销毁一批 program、
	 * GL 再把那些整数 id 回收发给新 program。不清就会把旧 id 的 uniform 位置套到新 program 上 ——
	 * 那个位置可能正好命中包自己的某个 uniform，于是我们把灯表写进别人的槽位（画面会莫名其妙）。
	 * 调用点：{@code MmtrShaderPackLightField.invalidate()}（= 包开始重新注入的那一刻）。</p>
	 */
	public void resetProgramCache() {
		locationsByProgram.clear();
	}

	private void upload(int programId) {
		Locations locations = locationsByProgram.get(programId);
		if (locations == null) {
			locations = new Locations(programId);
			locationsByProgram.put(programId, locations);
		}
		if (locations.uploadedFrame == collectingFrame) {
			return;
		}

		// ★ 上传**之前**复核一次 uniform 位置（notes/351 §4.3）。为什么不能一直信缓存：
		//   GL 的 program **名字会被回收再发** —— 光影包装载/重载会销毁一批 program，
		//   新 program 拿到旧 id，于是"旧 program 的 uniform 位置"被套到新 program 上，
		//   7 次 glUniform* 全部 GL_INVALID_OPERATION（实测：每帧刷屏、3 分钟 45 MB 日志），
		//   而**车灯一个像素都没传上去**。位置查起来很便宜（7 次 glGetUniformLocation），所以策略是：
		//     · 曾经命中过我们 uniform 的程序 → **每次上传前**都复核（这种程序每帧只有几个）；
		//     · 从没命中过的程序 → 每 5 秒复核一次（Iris 一帧会绑几十上百个 program，不能每次都查）。
		//   复核发现"从有变无"或"位置搬了"都会记一行日志 —— 那正是这一轮真机刷屏 45 MB 的 bug，
		//   下次它会以一行日志的形态出现。
		final long now = System.currentTimeMillis();
		if (locations.everOurs || now - locations.lastRefreshMillis >= RECHECK_INTERVAL_MILLIS) {
			locations.refresh(programId);
		}
		if (locations.countLocation < 0) {
			return;
		}

		try {
			// 先按"离相机最近"挑（槽位只有 8 个，远处的车让位给眼前的）。
			if (candidates.size() > MAX_LIGHTS) {
				candidates.sort((left, right) -> Double.compare(left.cameraDistance, right.cameraDistance));
			}

			/*
			 * ★ **尾灯不照世界**（notes/352，用户口径"尾灯无需灯光射线"）。
			 *
			 * <p>怎么认出"这是世界那一侧的 program"：地形程序身上有 {@code mmtrTerrainLuxScale}
			 * 这一族 uniform（Sodium 那份与光影包注入的那份都有），车厢/实体的程序没有。
			 * 尾灯因此只在车厢与实体上留一圈很近的红晕，不会在车尾地上铺一层红光。</p>
			 */
			final boolean terrainProgram = locations.terrainLuxScaleLocation >= 0;

			// 灯位是"世界 − 收集时的相机"，而这个 program 可能是在**之后**才画的
			// （地形那条路就固定是下一帧的地形 pass）⇒ 按相机位移整体搬过去。
			// 相机在一帧内是固定的，所以车厢那条路的位移恒为 0。
			double deltaX = 0;
			double deltaY = 0;
			double deltaZ = 0;
			final net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
			if (client.gameRenderer != null && client.gameRenderer.getCamera() != null) {
				final net.minecraft.util.math.Vec3d currentCamera = client.gameRenderer.getCamera().getPos();
				deltaX = camX - currentCamera.x;
				deltaY = camY - currentCamera.y;
				deltaZ = camZ - currentCamera.z;
			}

			posBuffer.clear();
			dirBuffer.clear();
			colorBuffer.clear();
			int written = 0;
			for (int index = 0; index < candidates.size() && written < MAX_LIGHTS; index++) {
				final Candidate candidate = candidates.get(index);
				if (terrainProgram && candidate.tail) {
					continue;
				}
				/*
				 * `Pos.w` = 1/射程（**永远是正的**）。
				 *
				 * <p>2026-10-03：这里以前用 w 的**符号**捎带"这是不是尾灯"那一位信息（notes/373 的
				 * 自发光染色）。灯罩颜色改成逐 draw 的顶点色之后（见类注释），着色器不再需要认尾灯
				 * ⇒ 这一位信息退休，w 就是字面意思（着色器里也不用再 abs）。</p>
				 */
				posBuffer.put((float) (candidate.x + deltaX)).put((float) (candidate.y + deltaY)).put((float) (candidate.z + deltaZ))
					.put(candidate.inverseRange);
				dirBuffer.put((float) candidate.dirX).put((float) candidate.dirY).put((float) candidate.dirZ).put(candidate.cosOuter);
				colorBuffer.put(candidate.red).put(candidate.green).put(candidate.blue).put(candidate.cosInner);
				written++;
			}
			posBuffer.flip();
			dirBuffer.flip();
			colorBuffer.flip();

			GL20.glUniform1i(locations.countLocation, written);
			if (written > 0) {
				GL20.glUniform4fv(locations.posLocation, posBuffer);
				GL20.glUniform4fv(locations.dirLocation, dirBuffer);
				GL20.glUniform4fv(locations.colorLocation, colorBuffer);
			}
			// 灯罩**不吃雾**（properties: lampLensFog）—— 与灯表一样是**每帧一次**的量，在同一个地方上传。
			// 查不到这个 uniform（原版/包的程序）⇒ 位置 −1 ⇒ 跳过。
			if (locations.lampFogLocation >= 0) {
				GL20.glUniform1f(locations.lampFogLocation, MmtrLightField.lampLensFog() ? 1.0F : 0.0F);
			}
			// 地形那一侧的几个调参口。车厢的 program 里没有这些 uniform ⇒ 位置是 −1 ⇒ 整段跳过。
			// 关掉地形车灯时**照旧上传**（把系数压成 0）：这样"开/关"是 2 秒生效的 A/B，不用重启也不用重编着色器。
			if (terrainProgram) {
				final boolean terrainEnabled = MmtrLightField.terrainHeadlightsEnabled();
				GL20.glUniform1f(locations.terrainLuxScaleLocation, terrainEnabled ? (float) MmtrLightField.terrainLuxScale() : 0.0F);
				GL20.glUniform1i(locations.terrainFlatLocation, MmtrLightField.terrainNormalFlat() ? 1 : 0);
				GL20.glUniform1i(locations.terrainDebugLocation, terrainEnabled && MmtrLightField.terrainDebug() ? 1 : 0);
				statTerrainUploaded = written;
			}
			locations.uploadedFrame = collectingFrame;
			uploadedThisFrame = written;
			statUploaded = written;
		} catch (Exception exception) {
			// 上传失败只是没有车灯，不该刷日志（下一帧还会再试）
		}
	}

	/** 每 5 秒一行：这一窗口里采到了几盏灯、传了几盏、灯罩判成什么颜色、以及两条"判定成什么"的实例。 */
	public void logAndReset() {
		final long now = System.currentTimeMillis();
		if (now - lastLogMillis < LOG_INTERVAL_MILLIS) {
			return;
		}
		lastLogMillis = now;
		if (statCars == 0 && statAnchors == 0) {
			return;
		}
		Init.LOGGER.info("[MMTR-LIGHT] 车灯：画到的车={} 锚点={} 候选={} 上传={}/{} 灯光={} 灯罩={} 染色draw={} 传统灯罩draw={} 地形={}(传{}盏) | {} | {}", statCars, statAnchors, statCollected, statUploaded, MAX_LIGHTS,
				statLightSummary,
				statLensSummary.isEmpty() ? "（这一窗口没有车问过颜色）" : statLensSummary,
				statColoredDraws, statLegacyLampDraws,
				MmtrSodiumTerrain.describe(), statTerrainUploaded,
				MmtrShaderPackLightField.getInstance().describeHeadlight(),
				statExamples.isEmpty() ? "（本窗口没有亮着的灯）" : String.join(" || ", statExamples));
		statUploaded = 0;
		statTerrainUploaded = 0;
		statLensSummary = "";
		statColoredDraws = 0;
		statLegacyLampDraws = 0;
	}

	/** 每个 program 的车灯 uniform 位置。任何一项 < 0 就说明这个 program 不认识车灯（原版/光影包）。 */
	private static final class Locations {

		private int countLocation;
		private int posLocation;
		private int dirLocation;
		private int colorLocation;
		/** 地形那一侧才有（notes/345 §6）：车厢的 program 里查不到，是 −1。 */
		private int terrainLuxScaleLocation;
		private int terrainFlatLocation;
		private int terrainDebugLocation;
		/** 灯罩**不吃雾**（notes/374）：{@code mmtrLampFog}。 */
		private int lampFogLocation;

		private int uploadedFrame = -1;
		/**
		 * 这个 program 曾经命中过我们的 uniform 吗。
		 * true ⇒ 每次上传前复核位置（它最可能因为 **GL program 名字被回收再发** 而错位，notes/351 §4.3）。
		 */
		private boolean everOurs;
		/** 上一次复核的时刻（从来没有我们的 uniform 的程序按 {@link #RECHECK_INTERVAL_MILLIS} 定期复核一次）。 */
		private long lastRefreshMillis;

		private Locations(int programId) {
			/*
			 * ★ 先全部置 −1 = "还没查过"。**不能留 Java 的默认 0**：0 是一个合法的 uniform 位置，
			 * 而 `refresh()` 里的 `wasOurs = countLocation >= 0` 会把它读成"上一次就有"
			 * ⇒ 第一次 refresh 就被当成"复核"而不是"初次认领"：既不报"uniform 齐了"，
			 * 也会让"从有变无 / 位置搬了"那两条警告在错误的基准上判断。
			 * （与存储槽位的哨兵同一个坑：0 必须是"没有"，不能兼作"还没查"。）
			 */
			countLocation = -1;
			posLocation = -1;
			dirLocation = -1;
			colorLocation = -1;
			terrainLuxScaleLocation = -1;
			terrainFlatLocation = -1;
			terrainDebugLocation = -1;
			lampFogLocation = -1;
			refresh(programId);
			// refresh 里已经写过 lastRefreshMillis，这里不用再动。
		}

		/**
		 * 重新查一遍 7 个 uniform 的位置，并在"归属变了 / 位置搬了"时记一行日志。
		 *
		 * <p>日志那两行就是这一轮真机刷屏 45 MB 那个 bug 的**指纹**：如果 program 的名字被回收再发，
		 * 我们会看到"program N 从有变无"或"位置搬了" —— 而不是几万条 GL_INVALID_OPERATION。</p>
		 */
		private void refresh(int programId) {
			final boolean wasOurs = countLocation >= 0;
			final int oldCount = countLocation;
			final int oldPos = posLocation;
			final int oldLux = terrainLuxScaleLocation;

			countLocation = GlStateManager._glGetUniformLocation(programId, "mmtrHeadlightCount");
			posLocation = GlStateManager._glGetUniformLocation(programId, "mmtrHeadlightPos");
			dirLocation = GlStateManager._glGetUniformLocation(programId, "mmtrHeadlightDir");
			colorLocation = GlStateManager._glGetUniformLocation(programId, "mmtrHeadlightColor");
			terrainLuxScaleLocation = GlStateManager._glGetUniformLocation(programId, "mmtrTerrainLuxScale");
			terrainFlatLocation = GlStateManager._glGetUniformLocation(programId, "mmtrTerrainFlat");
			terrainDebugLocation = GlStateManager._glGetUniformLocation(programId, "mmtrTerrainDebug");
			lampFogLocation = GlStateManager._glGetUniformLocation(programId, "mmtrLampFog");

			final boolean isOurs = countLocation >= 0 && posLocation >= 0 && dirLocation >= 0 && colorLocation >= 0;
			lastRefreshMillis = System.currentTimeMillis();

			if (wasOurs && !isOurs) {
				// GL 把同一个 program 名字发给了别人（或者这个 program 被重新链接了）。
				// 不刷新就会把灯表写到别人的 uniform 上 —— 那些调用全部是 GL_INVALID_OPERATION，
				// 症状是"日志刷屏 + 车灯一个都没传上去"。
				Init.LOGGER.warn("[MMTR-LIGHT] 车灯：program {} 的灯表 uniform **从有变无**（{} → {}）—— GL program 名字被回收再发了，位置缓存已刷新",
						programId, oldCount, countLocation);
			} else if (wasOurs && isOurs && (oldPos != posLocation || oldLux != terrainLuxScaleLocation)) {
				Init.LOGGER.warn("[MMTR-LIGHT] 车灯：program {} 的 uniform 位置**搬了**（pos {} → {}，lux {} → {}）—— 这个 program 被重新链接过，位置缓存已刷新",
						programId, oldPos, posLocation, oldLux, terrainLuxScaleLocation);
			} else if (!wasOurs && isOurs) {
				// 第一次认出"这是我们的 program"：这一行是"着色器注入成功"的指纹（notes/351/373）。
				Init.LOGGER.info("[MMTR-LIGHT] 车灯：program {} 的灯表 uniform 齐了（车灯光束 + 光场都在这一份着色器里）", programId);
			}

			everOurs = isOurs;
		}
	}

	/** 一盏候选灯（相机相对世界坐标 + 世界朝向 + 颜色/强度 + 锥角/射程）。 */
	private static final class Candidate {

		private double x;
		private double y;
		private double z;
		private double dirX;
		private double dirY;
		private double dirZ;
		private float red;
		private float green;
		private float blue;
		/** 1 / 射程：着色器每像素要用，先算好省一次除法。 */
		private float inverseRange;
		private float cosInner;
		private float cosOuter;
		private double cameraDistance;
		/** 这是不是尾灯（红色标志灯）：**不参与世界照明**，地形那一侧上传时跳过它（notes/352）。 */
		private boolean tail;
	}
}
