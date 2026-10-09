package org.mtr.mod.client;

import org.mtr.mod.Init;

import java.util.HashMap;
import java.util.Map;

/**
 * **位置权威的显式状态**（notes/409 §2/§3）：这辆车的位置"按谁算的显示"。
 *
 * <h2>它替代了什么</h2>
 * <p>在这之前，权威是 {@code MmtrVehicleMotionClient#mmtrDisplayTick} 里**每 tick 现算的一个谓词**
 * （{@code MmtrDriveInput.isLocallyDriving}：坐在司机位上 + 钥匙在我手里 + 距上次本地写 ≤ 600 ms）。
 * 后果有三条，都是用户点出来的（"这需要一个明确的渲染交接"）：</p>
 * <ol>
 *   <li>"现在谁在开"这件事**在任何时刻都说不出来** —— 日志里、别的子系统里都没有一处写着它；</li>
 *   <li>窗口一过期就**静默**回到服务端权威，**没有任何一行日志**，现场表现是"车忽然被拽了一下"；</li>
 *   <li>上行（notes/409 §4）要回答"我什么时候开始上传"，而一个每 600 ms 都可能闪断的谓词答不了。</li>
 * </ol>
 *
 * <h2>进入条件是"交接那一刻"，不是"我先动了手柄"</h2>
 * <p>用户口径（notes/409 §0）："在停站符合交接条件后，**明确赋予玩家驾驶的权利**并使玩家与列车之间绑定，
 * 此时玩家客户端下载当前列车位置路径需要旁路至对账纠正路径" —— 所以进入本地权威的判据
 * <b>不是</b>那个 600 ms 的手柄窗口（拿它当初判据会变成"先动手柄才旁路"，与口径相反），而是**两条之和**
 * （第二条是 notes/409 §4.6 第 13 条补上的，补完之后才与口径完全一致）：</p>
 * <ol>
 *   <li><b>"这间驾驶室是我的、我坐在里面"</b> —— {@code MmtrDriveInput.holdsCabOf}；</li>
 *   <li><b>"引擎确实把这趟车的驾驶权给了我"</b> —— {@link MmtrDutyView#holdsDrivingRight}
 *       （引擎推过来的值守镜像是 {@code DRIVING}/{@code DRIVING_EXIT_ARMED} 且值守人是我）。</li>
 * </ol>
 * <p>只有第一条时会漂出这样一个状态：客户端自认本地权威、上行一直发、而引擎**每一条都丢**
 * （"不是这辆车的驾驶权持有人"），两边各算各的 ⇒ 对账误差长大 ⇒ 权威在本地/服务端之间翻转 ⇒
 * 下载那一份把速度拉回引擎的 0。这就是用户报的"经过一个节点速度就归 0"的机制。</p>
 *
 * <p>那条窗口原来的职责（"证明引擎确实在收我这份值"）现在由两处更强的机制承担：
 * {@code MmtrDriveInput.noteEcho} 的**解除**（回声连续对不上就停用本地闭环）与下面
 * {@link Source#CLIENT} 的"对账超阈值退回"（那是唯一能自动发现"我这边算飞了"的通路）。</p>
 *
 * <h2>过渡态为什么必要</h2>
 * <p>直接 {@code 服务端 → 本地} 会让本地物理与"下载那一帧"在某几拍里硬碰：按本地容忍则误差一直挂着，
 * 按服务端吸收则在交接瞬间被拽一下。两拍的确认窗口把两种都避掉（notes/409 §2）。</p>
 *
 * <p>本类**只做状态与理由**，不做任何位置读写 —— 读写在 {@code mmtrDisplayTick} 里按返回的状态分支。</p>
 */
public final class MmtrAuthority {

	/** 位置按谁算的显示。 */
	public enum Source {
		/** 服务端权威：下载的位置就是要显示的位置（吸收误差 / 硬对齐）。 */
		SERVER("服务端"),
		/** 正在交还：条件已不成立或对账超阈值，但还没确认服务端那一帧（吸收但**不**硬对齐，免得拽一下）。 */
		HANDOVER_OUT("交接中"),
		/** **本地权威**：我自己的物理是要显示的位置，下载降级为对账信号。 */
		CLIENT("本地");

		public final String word;

		Source(String word) {
			this.word = word;
		}
	}

	/** 交接要连续几拍才算数（两侧都用它：进去要 2 拍，确认也要 2 拍）。 */
	private static final int CONFIRM_TICKS = 2;

	/**
	 * **对账超阈值退回之后，误差要连续收在阈值以内这么久，才允许再进本地权威**（notes/409 §4.6 第 16 条）。
	 *
	 * <p>实机 2026-10-09（车 -5473622327282561525，司机推着车顶在红灯+未开通岔口上）：引擎按住这列车
	 * 不采纳上传位移，而客户端本地闭环照旧把车往前推 —— 对账一超阈值就退回服务端，退回后那几拍对账
	 * 又"正常"，2 拍确认一到就又进本地 ⇒ 再推 ⇒ 再退…… 实机读数 **`交回 67 次`**，司机看到的是
	 * "一直往前开、又被服务端纠正回去"。</p>
	 *
	 * <p>引擎**真的在按住我**时，误差不会连续收住（下载那份不动、我这份一直推），所以这条闩就卡住了：
	 * 本地权威不再反复进入。等引擎重新动起来（下载那份跟上本地、误差收住），闩自动解开。</p>
	 */
	private static final long RECONCILE_LATCH_MILLIS = 1500;

	private static final Map<Long, Entry> STATES = new HashMap<>();

	// 1/s 读数（[MMTR-MOTION] 行）—— 刻意让"权威"这件事有一份能从日志证伪的计数。
	private static int clientFrames;
	private static int serverFrames;
	private static int handoverFrames;
	private static int transitionsToClient;
	private static int transitionsToServer;
	private static int handoverRejected;

	private MmtrAuthority() {
	}

	/** 这辆车现在的权威在哪（没记录过 = {@link Source#SERVER}，与今天的行为一致）。 */
	public static Source source(long vehicleId) {
		final Entry entry = STATES.get(vehicleId);
		return entry == null ? Source.SERVER : entry.source;
	}

	/** 渲染那一支要问的就是这一句。 */
	public static boolean isClient(long vehicleId) {
		return source(vehicleId) == Source.CLIENT;
	}

	/**
	 * 每 tick 评一次迁移（由 {@link MmtrVehicleMotionClient#mmtrDisplayTick} 调 —— 对账误差只有它手里有）。
	 *
	 * @param inCab             "这间驾驶室是我的、我坐在里面"（{@code MmtrDriveInput.holdsCabOf}）
	 * @param refusal           **为什么现在不能旁路下载**（空串 = 可以）。判断由调用方拼好，它其实是**两条**：
	 *                          {@code inCab} **且**"引擎确实把这趟车的驾驶权给了我"
	 *                          （{@link MmtrDutyView#holdsDrivingRight}，notes/409 §4.6 第 13 条）。
	 *                          本类只负责把它写进日志/理由 —— **"不能旁路"这件事必须看得见**：
	 *                          少了它，客户端会一边"自认本地权威、一直上行"、一边被引擎每条丢掉。
	 * @param absErrorM         这一拍的对账误差绝对值（服务端投影 − 本地）
	 * @param correctThresholdM 纠偏阈值（{@code 基准 + 系数 × 速度}）
	 * @param hardMismatch      误差到了"硬失配"那一档（交接确认期间出现它就放弃本地权威）
	 * @return 这一拍该按哪个权威渲染
	 */
	public static Source tick(long vehicleId, boolean inCab, String refusal, double absErrorM,
			double correctThresholdM, boolean hardMismatch, long nowMillis) {
		final boolean allowed = refusal.isEmpty();
		final Entry entry = STATES.computeIfAbsent(vehicleId, key -> new Entry(vehicleId, Source.SERVER, nowMillis, "初始：服务端权威", 0));
		/*
		 * 闩的计时（见 {@link #RECONCILE_LATCH_MILLIS}）：误差**连续**收在阈值以内满这么久才解闩。
		 * 引擎按住我时误差收不住 ⇒ 闩一直挂着 ⇒ 本地权威不再反复进入、不再反复推车。
		 */
		if (absErrorM <= correctThresholdM && !hardMismatch) {
			if (entry.smallErrorSinceMillis == 0) {
				entry.smallErrorSinceMillis = nowMillis;
			}
			if (entry.reconcileLatched && nowMillis - entry.smallErrorSinceMillis >= RECONCILE_LATCH_MILLIS) {
				entry.reconcileLatched = false;
			}
		} else {
			entry.smallErrorSinceMillis = 0;
		}
		switch (entry.source) {
			case SERVER:
				if (allowed && !entry.reconcileLatched) {
					entry.notAllowedReason = "";
					entry.confirmTicks++;
					if (entry.confirmTicks >= CONFIRM_TICKS) {
						transition(entry, Source.HANDOVER_OUT, nowMillis,
							"驾驶权确认（坐在驾驶室 + 引擎把驾驶权给了我）：开始交接，下载准备降级为对账");
					}
				} else {
					entry.confirmTicks = 0;
					if (entry.reconcileLatched) {
						/*
						 * 闩挂着：刚刚因为"对账超阈值"退回服务端、而误差还没连续收住 —— 说明引擎多半
						 * 正在按住这列车（红灯 / 岔口未开通 / 无行车授权）。这时 refusal 是空串，
						 * 所以不解释"为什么不能旁路"，只记一行"先按住不上行"。
						 */
						if (inCab) {
							noteReconcileLatch(entry);
						}
					} else if (inCab) {
						noteNotAllowed(entry, refusal);
					} else {
						entry.notAllowedReason = "";
					}
				}
				break;
			case HANDOVER_OUT:
				/*
				 * 确认窗口：这两拍里误差**收回到纠偏阈值以内**才认账。
				 *
				 * <p>为什么确认条件是"误差 ≤ 纠偏阈值"而不是"没有硬失配"：{@link Source#CLIENT} 因为
				 * 误差超阈值退回这里之后，这一态**一直在吸收误差**（见 mmtrDisplayTick），误差会一步步缩小；
				 * 若只要求"没硬失配"，那就会在阈值附近来回抖（本地→交接中→本地…），
				 * 既纠正不了又刷日志。要求"收回到阈值以内"才闭环 —— 这是这一段唯一自洽的写法。</p>
				 */
				if (!allowed) {
					transition(entry, Source.SERVER, nowMillis, "交接未确认（" + refusal + "）");
				} else if (hardMismatch) {
					handoverRejected++;
					transition(entry, Source.SERVER, nowMillis,
						"交接未确认（对账硬失配 " + Math.round(absErrorM) + " m）：放弃本地权威，回到服务端");
				} else if (absErrorM <= correctThresholdM) {
					entry.confirmTicks++;
					if (entry.confirmTicks >= CONFIRM_TICKS) {
						transition(entry, Source.CLIENT, nowMillis,
							"对账两拍无硬失配：本地权威生效，下载只用于纠偏");
					}
				} else {
					// 还在吸收（误差会一步步缩小）：不进也不退；拍数清零，免得"半途的拍数"接上后面的确认。
					entry.confirmTicks = 0;
				}
				break;
			default:
				/*
				 * 本地权威的**退出**条件（notes/409 §2）：任一条成立就进入交接。
				 *   · 人不在那间驾驶室了 / 钥匙不是我的了 —— 最常见（下车、被抢钥匙、退出值守）；
				 *   · **引擎把驾驶权收回去了 / 从来没给** —— notes/409 §4.6 第 13 条的漂移态
				 *     （派车后车还在动 = ABOARD、归还作业之后）：这条闸门不成立时，我发出去的上行
				 *     每一条都会被引擎丢掉，继续"本地权威"就是两边互相拉；
				 *   · 对账误差超过纠偏阈值 —— 唯一能自动发现"我这边算飞了 / 引擎拒绝了我的手柄"的通路；
				 *     notes/409 §4.4 的上行夺回走的也是这一条。
				 */
				if (!allowed) {
					transition(entry, Source.HANDOVER_OUT, nowMillis,
						"本地闭环条件不再成立（" + refusal + "）：交回服务端");
				} else if (absErrorM > correctThresholdM) {
					/*
					 * 退回服务端**并且上闩**：这是"引擎大概在按住我"唯一的本地证据（notes/409 §4.6 第 16 条）。
					 * 不上闩的话 2 拍之后又会进本地、又推、又退 —— 实机 `交回 67 次` 就是这么来的。
					 */
					entry.reconcileLatched = true;
					entry.smallErrorSinceMillis = 0;
					transition(entry, Source.HANDOVER_OUT, nowMillis,
						"对账超阈值（" + Math.round(absErrorM) + " m > " + Math.round(correctThresholdM) + " m）：交回服务端，"
							+ "并且在误差连续收住 " + RECONCILE_LATCH_MILLIS + " ms 之前不再进本地权威（引擎可能正在按住这列车）");
				}
				break;
		}
		return entry.source;
	}

	/**
	 * "坐在驾驶室里、但不能旁路下载"——**只在理由变化时记一行**（不每 tick 刷，notes/408 的教训）。
	 *
	 * <p>这一行的用处是让"客户端没有在旁路"这件事**可证伪**：以前这个状态是隐形的
	 * （客户端自认本地权威、上行一直发、引擎一直丢），只能从服务端那半边的
	 * {@code [MMTR-UP] 丢弃（…不是这辆车的驾驶权持有人…）} 推出来。</p>
	 */
	private static void noteNotAllowed(Entry entry, String refusal) {
		if (!refusal.equals(entry.notAllowedReason)) {
			entry.notAllowedReason = refusal;
			Init.LOGGER.info("[MMTR-AUTH] 车 {}：不旁路下载、不上行（{}）", entry.vehicleId, refusal);
		}
	}

	/**
	 * "闩挂着、先按住不上行"——每个闩只记一行（理由变化才记，不刷屏）。
	 *
	 * @see #RECONCILE_LATCH_MILLIS
	 */
	private static void noteReconcileLatch(Entry entry) {
		if (!"reconcile".equals(entry.notAllowedReason)) {
			entry.notAllowedReason = "reconcile";
			Init.LOGGER.info("[MMTR-AUTH] 车 {}：对账超阈值退回之后**先按住**——引擎多半正在按住这列车"
				+ "（红灯 / 岔口未开通 / 无行车授权）；误差连续收住 {} ms 之前不再进本地权威",
				entry.vehicleId, RECONCILE_LATCH_MILLIS);
		}
	}

	/** 这一拍按哪个权威渲染 —— 由调用方报回来，只用于计数（让"权威"有一份日志读数）。 */
	public static void countFrame(Source source) {
		switch (source) {
			case CLIENT -> clientFrames++;
			case HANDOVER_OUT -> handoverFrames++;
			default -> serverFrames++;
		}
	}

	/** 车没了 / 换了维度：把记录清掉（下一个人上车时不该继承上一个人的交接状态）。 */
	public static void forget(long vehicleId) {
		STATES.remove(vehicleId);
	}

	private static void transition(Entry entry, Source to, long nowMillis, String reason) {
		final Source from = entry.source;
		entry.source = to;
		entry.sinceMillis = nowMillis;
		entry.reason = reason;
		entry.confirmTicks = 0;
		if (to == Source.CLIENT) {
			transitionsToClient++;
		} else if (from == Source.HANDOVER_OUT && to == Source.SERVER) {
			transitionsToServer++;
		}
		/*
		 * 每次迁移**恰好一行**。这条是刻意记的：notes/408 踩过"每 tick 重发同一句"的坑
		 * （到站通知 14 秒刷了 262 条），所以这里只允许在**状态真的变了**的那一拍输出。
		 */
		Init.LOGGER.info("[MMTR-AUTH] 车 {}：{} → {}（{}）", entry.vehicleId, from.word, to.word, reason);
	}

	/** {@code [MMTR-MOTION]} 行那一小段：权威=本地N/服务端M/交接中K（切换次数与拒绝次数也要能看）。 */
	public static String report() {
		return "权威=本地" + clientFrames + "/服务端" + serverFrames + "/交接中" + handoverFrames
			+ " 切换=本地" + transitionsToClient + "次·交回" + transitionsToServer + "次·否决" + handoverRejected + "次";
	}

	/** 1/s 汇总之后清零（与 {@code MmtrVehicleMotionClient} 的窗口计数同一节拍）。 */
	public static void resetWindow() {
		clientFrames = 0;
		serverFrames = 0;
		handoverFrames = 0;
		transitionsToClient = 0;
		transitionsToServer = 0;
		handoverRejected = 0;
	}

	private static final class Entry {
		private final long vehicleId;
		private Source source;
		private long sinceMillis;
		private String reason;
		private int confirmTicks;
		/** 上一次记过的"不能旁路"的理由（只在**变化**时记一行，见 {@link #noteNotAllowed}）。 */
		private String notAllowedReason = "";
		/** 对账超阈值退回之后的闩：见 {@link #RECONCILE_LATCH_MILLIS}。 */
		private boolean reconcileLatched;
		/** 误差**连续**收在阈值以内的起点（0 = 这一拍不在阈值内）。 */
		private long smallErrorSinceMillis;

		private Entry(long vehicleId, Source source, long sinceMillis, String reason, int confirmTicks) {
			this.vehicleId = vehicleId;
			this.source = source;
			this.sinceMillis = sinceMillis;
			this.reason = reason;
			this.confirmTicks = confirmTicks;
		}
	}
}
