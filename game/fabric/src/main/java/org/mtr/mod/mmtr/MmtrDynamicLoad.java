package org.mtr.mod.mmtr;

import org.mtr.core.data.Position;
import org.mtr.core.operation.DataRequest;
import org.mtr.mapping.holder.Camera;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.packet.PacketRequestData;

/**
 * **动态项前视加载管线**（客户端半边，notes/410）。
 *
 * <h2>为什么要有这条管线</h2>
 *
 * <p>现场症状：客户端列车经过带信号灯/道岔的节点时**卡一下**。实测（2026-10-09 客户端日志）：</p>
 *
 * <pre>
 *   [22:23:05] [MMTR-LAG] 客户端 tick 间隔 314ms（正常 50ms）
 *   [22:23:08] [MMTR-LAG] 客户端 tick 间隔 274ms
 *   [22:23:09] [MMTR-LAG] 客户端 tick 间隔 234ms
 *   [22:23:05] [MMTR-FRAME] 帧间隔 p50=16ms p95=31ms 最慢=163ms ｜ 已量段合计=5.31ms/帧
 * </pre>
 *
 * <p>也就是说：卡顿**不在渲染段里**（埋了点的段合计只有 5–6 ms/帧），而在"数据与区块到达客户端并被
 * 落地"这条路上。而这条路今天的时机是**事件驱动、恰好赶上**的：</p>
 *
 * <pre>
 *   InitClient：registerChunkLoad → lastUpdatePacketMillis = now + 500ms
 *               到点后发一次 DataRequest（相机位置，半径 = 渲染距离 × 16）
 * </pre>
 *
 * <p>于是"前方一堆新轨/新区块"这件事，是在**车已经开到那里、区块刚进来**的那一刻才去要的：
 * 数据在路上走一个来回，落地时正好和该 tick 的区块落地撞在一起 ⇒ 单 tick 300 ms。节点
 * （信号灯/道岔）恰好是轨最密的地方，所以卡在节点上最明显。</p>
 *
 * <h2>这条管线做什么</h2>
 *
 * <p>把"要数据"从**区块事件驱动**改成**沿运动方向的提早拉取**：</p>
 *
 * <ol>
 *   <li><b>请求窗口 = 相机窗口的<u>超集</u></b>：圆心是<strong>相机所在方块</strong>，半径是
 *       {@code max(渲染距离 × 16, mmtr.dynload.lead + mmtr.dynload.radius)}（默认 192 + 128 = 320 米）
 *       —— 见下面"圆心为什么必须是相机"那一段，**这条不是可选的**；</li>
 *   <li><b>增量</b>：{@code writeExistingIds} 会把客户端已有的 id 带上，服务端对已有项只回 id
 *       （不重发数据），所以没有新东西时 {@code DataResponse.write()} 直接空转，落地便宜；</li>
 *   <li><b>节流</b>：相机每走 {@code mmtr.dynload.step} 米（默认 32）且距上次至少
 *       {@code mmtr.dynload.period} 毫秒（默认 250）才发下一次 —— 请求量随车速走，不随帧率走；</li>
 *   <li><b>原来的那条路一条都不删</b>：区块加载触发的那次请求照旧（它是"站着不动也能收敛"的
 *       保障，见 {@link org.mtr.mod.client.MmtrDataResync}）。本条只是让**前方**的东西提前落地 ——
 *       到达时就没什么可落地的了，那 300 ms 也就没了。</li>
 * </ol>
 *
 * <h2>圆心为什么必须是相机（2026-10-09 现场事故，必读）</h2>
 *
 * <p>本类第一版把圆心放在**相机往前 192 米的前视点**上、半径只有 128 米。这个写法把玩家的轨道
 * 弄没了：客户端看到"轨道和列车随移动不规律地消失/出现"，例如"走到节点左边就只剩左边的轨"。</p>
 *
 * <p>原因在引擎侧的数据形状：{@code PacketRequestData} 的响应是
 * {@link org.mtr.core.operation.DataResponse}，它的 {@code write()} <strong>不是合并，是"按窗口替换"</strong>
 * ——</p>
 *
 * <pre>
 *   DataResponse.write()（ClientData 上）：
 *     data.rails.removeIf(rail -&gt; !railsToKeep.contains(rail.getHexId()));   // 窗口外的全删
 *     data.rails.addAll(rails);                                              // 窗口内的补上
 *     …
 *     data.sync();
 * </pre>
 *
 * <p>而 {@code railsToKeep} 只装**这一次请求半径内**的轨（引擎 {@code DataRequest.getData()} 按
 * {@code rail.closeTo(clientPosition, requestRadius)} 过滤）。于是圆心一放到车前方 192 米，
 * 玩家**身后与两侧**的轨就被这次响应合法地删掉了；车一移动，窗口跟着移动，删除集合随之变化 ——
 * 轨道消失、以及"列车所在的轨被删掉所以列车画不出来"，都从这里来。</p>
 *
 * <p>所以规矩写死在代码里：<b>客户端主动拉的窗口必须覆盖相机（是例行窗口的超集）</b>，
 * 只能变大不能变小。前视的效果由"<u>半径大到足以含住前方</u> + 相机一移动就再拉一次"给出，
 * 而不是由"把圆心挪到前面"给出。</p>
 *
 * <h2>本管线今天管到哪一层（诚实边界）</h2>
 *
 * <ul>
 *   <li><b>钢轨 / 节点 / 站台（MTR 数据层）</b> —— 就是这里（{@code DataRequest} → 客户端
 *       {@code MinecraftClientData}），管线已经接管"提前量"；</li>
 *   <li><b>信号灯 / 道岔的状态</b> —— 走 {@code PacketMmtrRoutes}（引擎算好、**变化时**才发、
 *       载荷是全世界的一份镜像）。它们不是按距离发的，所以本轮**没有被这条管线覆盖**：
 *       要覆盖它，得让引擎按"客户端前视窗口"裁剪灯/岔那一份（引擎侧要加一个按客户端位置的
 *       投影），那是下一步（notes/410 §管线 待办）；</li>
 *   <li><b>站台客流</b> —— 服务端 {@code MmtrCrowdModule}，闸门是"玩家半径（默认 96 格）+ 单站台
 *       上限（默认 48）"，与**车**无关。要让客流在车到达之前就位，同样得把闸门的圆心从"玩家"
 *       挪到"最近列车的前视点"，也是下一步。</li>
 * </ul>
 *
 * <p>换言之：本类先解决**测得出来**的那一段（轨道数据层），另外两段在探针有读数、且它们的载荷
 * 被证明也在长 tick 账上之后再动手 —— 先量后改，而不是一次改三处。</p>
 *
 * <h2>开关</h2>
 * <ul>
 *   <li>{@code -Dmmtr.dynload=0}：整条管线关掉（回到"只在区块加载后拉一次"的老路）；</li>
 *   <li>{@code -Dmmtr.dynload.lead/radius/step/period=…}：四个参数可单独调，便于一次只动一个量；
 *       {@code lead + radius} 决定请求窗口的下限半径（永远不小于"渲染距离 × 16"）；</li>
 *   <li>读数在 {@code [MMTR-LOAD]}（见 {@code MmtrLoadProbe}）的"前视拉取=n 次（半径…平均前视…）"。</li>
 * </ul>
 */
public final class MmtrDynamicLoad {

	/** 整条管线开关：{@code -Dmmtr.dynload=0} 关。 */
	private static final boolean ENABLED = !"0".equals(System.getProperty("mmtr.dynload", "1").trim());

	/** 前视距离（米）：请求窗口要在相机前方至少覆盖这么远。 */
	private static final long LEAD_M = Long.getLong("mmtr.dynload.lead", 192L);
	/** 前视切片（米）：在前视距离之外再留的余量。 */
	private static final long RADIUS_M = Long.getLong("mmtr.dynload.radius", 128L);
	/** 相机每走这么多米才发下一次请求。 */
	private static final double STEP_M = Long.getLong("mmtr.dynload.step", 32L);
	/** 两次请求之间至少隔这么久（毫秒）：防止低速抖动时刷请求。 */
	private static final long MIN_PERIOD_MILLIS = Long.getLong("mmtr.dynload.period", 250L);
	/** 低于这个速度就当"没在走"，不发前视请求（站着的玩家由老路 + 重拉键负责）。 */
	private static final double MIN_SPEED_METRES_PER_SECOND = 1.0;
	/** 速度的指数平滑系数（相机位置逐帧抖动，直接取差分会放出噪声）。 */
	private static final double SMOOTHING = 0.25;

	private static double velocityX;
	private static double velocityZ;
	private static double lastCameraX;
	private static double lastCameraZ;
	private static long lastCameraMillis;
	private static double lastRequestX = Double.NaN;
	private static double lastRequestZ = Double.NaN;
	private static long lastRequestMillis;

	private MmtrDynamicLoad() {
	}

	public static boolean on() {
		return ENABLED;
	}

	/**
	 * 请求半径：<b>决不小于客户端例行窗口</b>（渲染距离 × 16），再保证前方够远。
	 * 见类注释"圆心为什么必须是相机"—— 这个 max 是防"把玩家身后的轨删掉"的那道闸。
	 */
	public static long requestRadius() {
		return Math.max(MinecraftClientHelper.getRenderDistance() * 16L, LEAD_M + RADIUS_M);
	}

	/** 每个客户端 tick 调一次（在 {@code InitClient} 的 client tick 里，紧挨着老的那次拉取）。 */
	public static void tick() {
		if (!ENABLED) {
			return;
		}
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		if (minecraftClient.getWorldMapped() == null) {
			reset();
			return;
		}
		final ClientPlayerEntity player = minecraftClient.getPlayerMapped();
		if (player == null || minecraftClient.getGameRendererMapped() == null || minecraftClient.getGameRendererMapped().getCamera() == null) {
			return;
		}
		final Camera camera = minecraftClient.getGameRendererMapped().getCamera();
		final Vector3d cameraPosition = camera.getPos();
		final double x = cameraPosition.getXMapped();
		final double y = cameraPosition.getYMapped();
		final double z = cameraPosition.getZMapped();
		final long nowMillis = System.currentTimeMillis();
		updateVelocity(x, z, nowMillis);

		final double speed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
		if (speed < MIN_SPEED_METRES_PER_SECOND) {
			return;
		}
		// 窗口圆心 = 相机本身。往前 192 米那个圆心第一版就是这样写的，会把身后的轨删掉（类注释）。
		if (!Double.isNaN(lastRequestX)) {
			final double movedX = x - lastRequestX;
			final double movedZ = z - lastRequestZ;
			if (movedX * movedX + movedZ * movedZ < STEP_M * STEP_M) {
				return;
			}
		}
		if (lastRequestMillis != 0L && nowMillis - lastRequestMillis < MIN_PERIOD_MILLIS) {
			return;
		}
		lastRequestX = x;
		lastRequestZ = z;
		lastRequestMillis = nowMillis;

		/*
		 * 与 InitClient / MmtrDataResync 那次拉取同一形状、同一个包（不引入第二条数据通道），只差两件事：
		 * 由"相机走了多远"驱动而不是"区块加载"驱动；半径不小于例行窗口、并保证前方够远。
		 */
		final long requestRadius = requestRadius();
		final DataRequest dataRequest = new DataRequest(
				player.getUuid(),
				new Position(Math.round(x), Math.round(y), Math.round(z)),
				requestRadius
		);
		dataRequest.writeExistingIds(MinecraftClientData.getInstance());
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRequestData(dataRequest));
		MmtrLoadProbe.lookAheadRequest(requestRadius);
	}

	/** 换世界/离开世界时清速度与上次请求点，免得拿上一个世界的速度当前视。 */
	public static void reset() {
		velocityX = 0;
		velocityZ = 0;
		lastCameraX = 0;
		lastCameraZ = 0;
		lastCameraMillis = 0L;
		lastRequestX = Double.NaN;
		lastRequestZ = Double.NaN;
		lastRequestMillis = 0L;
	}

	private static void updateVelocity(double x, double z, long nowMillis) {
		if (lastCameraMillis != 0L) {
			final double seconds = (nowMillis - lastCameraMillis) / 1000.0;
			// 卡顿/暂停（seconds 很大）与同一毫秒（seconds=0）都不能用来算速度
			if (seconds > 0.001 && seconds < 0.5) {
				velocityX = velocityX * (1 - SMOOTHING) + (x - lastCameraX) / seconds * SMOOTHING;
				velocityZ = velocityZ * (1 - SMOOTHING) + (z - lastCameraZ) / seconds * SMOOTHING;
			}
		}
		lastCameraX = x;
		lastCameraZ = z;
		lastCameraMillis = nowMillis;
	}
}
