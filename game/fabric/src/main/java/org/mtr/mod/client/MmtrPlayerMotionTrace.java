package org.mtr.mod.client;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.Init;

/**
 * 客户端"人卡在两帧之间来回抽搐"的现场取证（notes/177）。
 *
 * <h2>为什么要它</h2>
 * <p>实机反馈："首次进入游戏后正常移动，然后在两帧之间卡住来回抽搐，游戏里的车正常"。
 * 这句话排掉了两个方向：客户端渲染没停（{@code [MMTR-DBG] world render running} 每 2 秒一条，从不断档），
 * 服务端的车也正常（{@code [MMTR-HLTH]} 里 {@code riders=0}、tick 21 ms、没有 "Can't keep up"）。
 * 剩下的唯一解释是**玩家自己的坐标在每 tick 被两个来源抢**：客户端把他按在车上/某处，
 * 服务端把他按回另一处 —— 于是画面在两帧之间来回跳。</p>
 *
 * <h2>怎么定位到"谁在拉"</h2>
 * <p>Minecraft 的坐标只有两个来源：①**tick 内**的本地物理与 {@link VehicleRidingMovement#movePlayer}
 * （它会在 tick 末尾再执行一次，见那里的注释）；②**tick 之间**由网络线程应用的服务端位置校正
 * （{@code PlayerPositionLookS2CPacket}）。两者在时间上分得开，所以这里在**每个 tick 的开工前**和
 * **收工后**各取一次坐标：</p>
 * <ul>
 *   <li>"上一拍收工" → "这一拍开工" 之间的跳跃 = **外部移动**（服务端校正，或在两拍之间落地的
 *       {@code movePlayer}）—— 这是"被谁按回去"的直接证据；</li>
 *   <li>"这一拍开工" → "收工" 之间的位移 = 本地物理 + 车内定位，超过步行/疾跑能给出的步长
 *       （0.6 格/tick = 12 格/秒）就是本地在被瞬移；</li>
 *   <li>相邻两拍位移**方向相反**且幅度不小 = 典型的"来回抽搐"（钉子效应），单独计数。</li>
 * </ul>
 *
 * <p>总共只有几条异常行（每窗口封顶 {@link #MAX_ANOMALY_LINES_PER_WINDOW} 条）加一条 5 秒汇总，
 * 正常游玩时日志里几乎看不到它；一旦抽搐，每一拍都会留下证据。</p>
 */
public final class MmtrPlayerMotionTrace {

	private static final long WINDOW_MILLIS = 5_000L;
	/** 客户端 tick 间隔超过它就记一笔（正常 50 ms，20 TPS）。 */
	private static final long TICK_GAP_WARN_MILLIS = 150L;
	/** 单 tick 位移超过它就不可能是走出来的（0.6 格/tick = 12 格/秒，疾跑上限约 5.6 格/秒）。 */
	private static final double STEP_WARN = 0.6D;
	/** tick 之间被挪动的下限（低于这个是浮点噪声/重力）。 */
	private static final double EXTERNAL_WARN = 0.05D;
	/** 反向判定的幅度下限，避免把站着不动时的抖动算进来。 */
	private static final double REVERSAL_MIN_STEP = 0.15D;
	/** 帧间隔超过它就记一笔（60 FPS = 16.7 ms，120 FPS = 8.3 ms）。 */
	private static final long FRAME_GAP_WARN_MILLIS = 250L;
	private static final int MAX_ANOMALY_LINES_PER_WINDOW = 30;

	private static long windowStartMillis;
	private static long lastTickStartMillis;
	private static long maxTickGapMillis;
	private static long lastFrameMillis;
	private static long maxFrameGapMillis;
	private static int frames;
	private static int longFrames;
	private static int ticks;
	private static int anomalyLines;
	private static int bigSteps;
	private static int reversals;
	private static int externalMoves;
	private static int longTicks;
	private static double maxStep;
	private static double maxExternalStep;
	private static double startX, startY, startZ;
	private static double previousEndX, previousEndY, previousEndZ;
	private static double previousStepX, previousStepZ;
	private static boolean hasPreviousEnd;

	private MmtrPlayerMotionTrace() {
	}

	/** 每个客户端 tick 的**开工前**调用（{@code registerStartClientTick} 的第一件事）。 */
	public static void tickStart() {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		final long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			windowStartMillis = now;
		}
		if (player == null) {
			hasPreviousEnd = false;
			lastTickStartMillis = 0;
			return;
		}

		final double x = player.getX();
		final double y = player.getY();
		final double z = player.getZ();

		if (lastTickStartMillis > 0) {
			final long gap = now - lastTickStartMillis;
			ticks++;
			if (gap > maxTickGapMillis) {
				maxTickGapMillis = gap;
			}
			if (gap > TICK_GAP_WARN_MILLIS) {
				longTicks++;
				anomaly("客户端 tick 间隔 " + gap + "ms（正常 50ms）");
			}
		}
		lastTickStartMillis = now;

		if (hasPreviousEnd) {
			final double step = distance(previousEndX, previousEndY, previousEndZ, x, y, z);
			if (step > EXTERNAL_WARN) {
				externalMoves++;
				if (step > maxExternalStep) {
					maxExternalStep = step;
				}
				anomaly("tick 之间被外部挪动 " + format(step) + " 格（服务端校正 / 车内定位在拍间落地）→ (" + format(x) + "," + format(z) + ")" + context());
			}
		}

		startX = x;
		startY = y;
		startZ = z;
	}

	/** 每个客户端 tick 的**收工后**调用（{@code registerEndClientTick} 的最后，此时车内定位已经跑过）。 */
	public static void tickEnd() {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		final long now = System.currentTimeMillis();
		if (player == null) {
			hasPreviousEnd = false;
			return;
		}

		final double x = player.getX();
		final double y = player.getY();
		final double z = player.getZ();
		final double stepX = x - startX;
		final double stepZ = z - startZ;
		final double step = Math.sqrt(stepX * stepX + stepZ * stepZ);

		if (step > maxStep) {
			maxStep = step;
		}
		if (step >= STEP_WARN) {
			bigSteps++;
			anomaly("单 tick 本地位移 " + format(step) + " 格（超出行走/疾跑上限）→ ("
					+ format(startX) + "," + format(startZ) + ") → (" + format(x) + "," + format(z) + ")" + context());
		} else if (step >= REVERSAL_MIN_STEP && hasPreviousEnd && stepX * previousStepX + stepZ * previousStepZ < 0) {
			reversals++;
			anomaly("位移反向（来回抽搐）：这一拍 " + format(stepX) + "/" + format(stepZ)
					+ " 上一拍 " + format(previousStepX) + "/" + format(previousStepZ) + context());
		}

		previousStepX = stepX;
		previousStepZ = stepZ;
		previousEndX = x;
		previousEndY = y;
		previousEndZ = z;
		hasPreviousEnd = true;

		if (now - windowStartMillis >= WINDOW_MILLIS) {
			summarise();
			resetWindow();
		}
	}

	/**
	 * 每**帧**调用一次（世界渲染的开头）。
	 *
	 * <p>为什么帧率必须单独量：tick 率和帧率是两条独立的线 —— "卡死"现场就是 tick 仍然 20/秒
	 * （{@code tick=100} 每窗口）而画面几乎不出帧：2026-09-16 那次 jstack 抓到渲染线程 86%、遮挡剔除
	 * 工作线程 73%（在 {@code Arrays.fill} 一个 256 MB 的数组），帧率塌了但 tick 看不出来。</p>
	 */
	public static void frame() {
		final long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			windowStartMillis = now;
		}
		if (lastFrameMillis > 0) {
			final long gap = now - lastFrameMillis;
			frames++;
			if (gap > maxFrameGapMillis) {
				maxFrameGapMillis = gap;
			}
			if (gap > FRAME_GAP_WARN_MILLIS) {
				longFrames++;
				anomaly("帧间隔 " + gap + "ms（渲染线程被卡住：" + (gap < 1000 ? "掉帧" : "近 " + (gap / 1000) + " 秒不出帧") + "）");
			}
		}
		lastFrameMillis = now;
	}

	private static void summarise() {
		Init.LOGGER.info("[MMTR-PERF] 5 秒窗口：帧={}（{} FPS）帧最慢={}ms tick={} tick最慢={}ms（超 {}ms 的有 {} 次）本地最大位移={} 格 外部最大挪动={} 格（{} 次）反向={} 次 大跳={} 次 车内定位={} 次 骑乘={} 镜像车={} 异常行={}",
				frames, frames * 1000L / Math.max(1L, System.currentTimeMillis() - windowStartMillis), maxFrameGapMillis, ticks, maxTickGapMillis, TICK_GAP_WARN_MILLIS, longTicks, format(maxStep), format(maxExternalStep), externalMoves,
				reversals, bigSteps, VehicleRidingMovement.mmtrGetAndResetMovePlayerCalls(), VehicleRidingMovement.getRidingVehicleId(),
				MinecraftClientData.getInstance().vehicles.size(), anomalyLines);
	}

	private static void resetWindow() {
		windowStartMillis = System.currentTimeMillis();
		ticks = 0;
		anomalyLines = 0;
		bigSteps = 0;
		reversals = 0;
		externalMoves = 0;
		longTicks = 0;
		maxStep = 0;
		maxExternalStep = 0;
		/*
		 * ★ 这两个必须在每个窗口清零（2026-09-16 修）：原来 `maxTickGapMillis` 只在 tickStart 里取最大、
		 * 从不清零，于是它记的是"客户端启动以来最慢的一拍"，之后每个窗口都印同一个陈旧数字
		 * （实测"tick最慢=595ms（超 150ms 的有 0 次）"这种自相矛盾的行就是这么来的）。
		 */
		maxTickGapMillis = 0;
		frames = 0;
		longFrames = 0;
		maxFrameGapMillis = 0;
	}

	private static void anomaly(String message) {
		if (anomalyLines < MAX_ANOMALY_LINES_PER_WINDOW) {
			anomalyLines++;
			Init.LOGGER.info("[MMTR-LAG] {}（第 {} 条/本窗口最多 {} 条）", message, anomalyLines, MAX_ANOMALY_LINES_PER_WINDOW);
		}
	}

	/** 出问题时最要紧的上下文：人在车里吗、客户端手上有几辆车。 */
	private static String context() {
		return "｜骑乘车=" + VehicleRidingMovement.getRidingVehicleId()
				+ " 镜像车=" + MinecraftClientData.getInstance().vehicles.size()
				+ " 车内定位累计=" + VehicleRidingMovement.mmtrGetMovePlayerCalls();
	}

	private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
		final double dx = x2 - x1;
		final double dy = y2 - y1;
		final double dz = z2 - z1;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static String format(double value) {
		return String.format("%.3f", value);
	}
}
