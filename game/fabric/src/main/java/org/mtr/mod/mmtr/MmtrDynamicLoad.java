package org.mtr.mod.mmtr;

import org.mtr.core.data.Position;
import org.mtr.core.operation.DataRequest;
import org.mtr.mapping.holder.Camera;
import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Vector3d;
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
 * 数据在路上走一个来回，落地时正好和该 tick 的区块落地撞在一起 ⇒ 单 tick 300 ms。
 * 节点（信号灯/道岔）恰好是轨最密的地方，所以卡在节点上最明显。</p>
 *
 * <h2>这条管线做什么</h2>
 *
 * <p>把"要数据"从**区块事件驱动**改成**沿运动方向的前视、固定距离、切片**：</p>
 *
 * <ol>
 *   <li><b>前视点</b>：取相机速度（EMA 平滑）方向，往前 {@code mmtr.dynload.lead} 米（默认 192）——
 *       这是"固定距离"这一条：与区块到没到、视距多大都无关；</li>
 *   <li><b>切片</b>：每次请求半径只有 {@code mmtr.dynload.radius} 米（默认 128），而且只请求
 *       **客户端还没有的**（{@code writeExistingIds}）⇒ 单次载荷小、落地便宜（没有新东西时
 *       {@code DataResponse.write()} 直接空转）；</li>
 *   <li><b>节流</b>：前视点每走 {@code mmtr.dynload.step} 米（默认 32）且距上次至少
 *       {@code mmtr.dynload.period} 毫秒（默认 250）才发下一次 —— 请求量随车速走，不随帧率走；</li>
 *   <li><b>原来的那条路一条都不删</b>：区块加载触发的那次请求照旧（它是"站着不动也能收敛"的
 *       保障，见 {@link MmtrDataResync}）。前视拉取只是让**车前方**的东西提前落地 ——
 *       到达时就没什么可落地的了，那 300 ms 也就没了。</li>
 * </ol>
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
 *   <li>{@code -Dmmtr.dynload.lead/radius/step/period=…}：四个参数可单独调，便于一次只动一个量；</li>
 *   <li>读数在 {@code [MMTR-LOAD]}（见 {@link MmtrLoadProbe}）的"前视拉取=n 次（半径…平均前视…）"。</li>
 * </ul>
 */
public final class MmtrDynamicLoad {

	/** 整条管线开关：{@code -Dmmtr.dynload=0} 关。 */
	private static final boolean ENABLED = !"0".equals(System.getProperty("mmtr.dynload", "1").trim());

	/** 前视距离（米）：请求中心在相机运动方向上的提前量。 */
	private static final long LEAD_M = Long.getLong("mmtr.dynload.lead", 192L);
	/** 单次请求半径（米）：切片大小（越小越平摊，但请求越频繁）。 */
	private static final long RADIUS_M = Long.getLong("mmtr.dynload.radius", 128L);
	/** 前视点每走这么多米才发下一次请求。 */
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
		final double leadX = x + velocityX / speed * LEAD_M;
		final double leadZ = z + velocityZ / speed * LEAD_M;
		if (!Double.isNaN(lastRequestX)) {
			final double movedX = leadX - lastRequestX;
			final double movedZ = leadZ - lastRequestZ;
			if (movedX * movedX + movedZ * movedZ < STEP_M * STEP_M) {
				return;
			}
		}
		if (lastRequestMillis != 0L && nowMillis - lastRequestMillis < MIN_PERIOD_MILLIS) {
			return;
		}
		lastRequestX = leadX;
		lastRequestZ = leadZ;
		lastRequestMillis = nowMillis;

		/*
		 * 与 InitClient 里那次拉取同一形状、同一个包（不引入第二条数据通道），只差三件事：
		 * 圆心在前视点上、半径只有切片那么大、由"前视点走了多远"驱动而不是"区块加载"驱动。
		 */
		final DataRequest dataRequest = new DataRequest(
				player.getUuid(),
				new Position(Math.round(leadX), Math.round(y), Math.round(leadZ)),
				RADIUS_M
		);
		dataRequest.writeExistingIds(MinecraftClientData.getInstance());
		InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRequestData(dataRequest));
		MmtrLoadProbe.lookAheadRequest(LEAD_M, RADIUS_M);
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
