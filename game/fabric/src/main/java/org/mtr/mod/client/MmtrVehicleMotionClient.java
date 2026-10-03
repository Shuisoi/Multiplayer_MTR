package org.mtr.mod.client;

import org.mtr.core.mmtr.net.MmtrMotionFrame;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Control;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Drop;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Legs;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Motion;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Ping;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Record;
import org.mtr.core.mmtr.net.MmtrMotionFrame.Slot;
import org.mtr.core.mmtr.net.MmtrMotionFrame.State;
import org.mtr.core.path.MmtrLegAppender;
import org.mtr.mod.Init;
import org.mtr.mod.data.VehicleExtension;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * **列车运动流**的客户端落点（notes/369 ①/③）。
 *
 * <h2>它做的三件事</h2>
 * <ol>
 *   <li><b>槽位表</b>：{@code slot → 车辆 id}。槽位是**服务端为这个客户端分配**的（每客户端一份），
 *       随 {@code SLOT} 记录一次性告知 —— 于是 ① 完全不必依赖 ② 的脏拍（那种依赖正是 notes/368 的病根）。</li>
 *   <li><b>落镜像</b>：位置/速度、手柄三元组、运动旗标与三个夹紧量写进对应那辆车的镜像
 *       （引擎侧 {@code Vehicle.mmtrApplySync*}，所有权见那里）。</li>
 *   <li><b>接腿</b>：{@code LEGS} 记录接到镜像的腿阴影上（S3b）—— 阴影原来只在 ② 的整份快照里重建，
 *       于是车头每隔 1.5–6 秒就会跑出阴影末端被夹回来（现场就是"卡"）。记录有两种形态：
 *       **增量**（车头新踏上一根轨：丢车尾几根、车头接几根）与**整表**（换端：同一批轨、相反顺序，
 *       带锚点与逐腿方向，见 notes/375）—— 后者是"车不动再瞬移"的正面修法。</li>
 *   <li><b>记账</b>：每秒一行 {@code [MMTR-MOTION]}，把"这套东西到底花了多少字节"变成可核对的数
 *       —— "低网络开销"是这个重构的首要指标，没有这一行就只能靠感觉。</li>
 * </ol>
 *
 * <h2>刻意不做的两件</h2>
 * <ul>
 *   <li><b>不删镜像</b>：{@code DROP} 只释放槽位。镜像的生死由 ② 那条"不在消息里就删车"的规则管，
 *       两处都删就会有两个所有权（这个仓库被咬过多次的那种）。</li>
 *   <li><b>不写门</b>：门仍在 ② 手里（见引擎那三支的所有权说明）。{@code STATE} 里门那几个位先收下不用。</li>
 * </ul>
 */
public final class MmtrVehicleMotionClient {

	/** 槽位 → 车辆 id（服务端为**本客户端**分配的编号）。 */
	private static final Map<Integer, Long> SLOT_TO_VEHICLE = new HashMap<>();
	/** 车辆 id → 槽位（用于 ② 的补丁过滤：这辆车归 ① 管了吗）。 */
	private static final Map<Long, Integer> VEHICLE_TO_SLOT = new HashMap<>();

	private static long windowStartMillis;
	private static int frames;
	private static int bytes;
	private static int records;
	private static int unknownSlots;
	private static int malformedFrames;
	private static int legRecords;
	/** 本窗口内"位置硬对齐"的次数与最大误差（米）—— 判断"本地积分到底有没有在跑"就靠这两个数。 */
	private static int hardAligns;
	private static double maxAbsErrorM;
	/** 渲染时走出腿阴影末端的次数与最大越界量（米）——"该接 LEGS 了"的证据。 */
	private static int shadowOverrunCount;
	private static double maxShadowOverrunM;
	/**
	 * "腿表跟不上"的计时（每辆车一份，notes/375）：越界第一次出现的时刻与这期间的最大越界量。
	 * 连续 {@link #SHADOW_STALE_LOG_MS} 就自己喊一声 —— 那时画面上的车正是"不动，然后瞬移"。
	 */
	private static final Map<Long, Long> SHADOW_STALE_SINCE = new HashMap<>();
	private static final Map<Long, Double> SHADOW_STALE_MAX = new HashMap<>();
	/** 同一辆车"腿表跟不上"最多每这么多毫秒报一行（够说明问题，又不至于刷屏）。 */
	private static final long SHADOW_STALE_LOG_MS = 5000;
	/**
	 * 腿阴影（{@code LEGS}）的落地记账：接了几根 / 丢了几根 / 整表重建了几次 / 为什么停在那里。
	 *
	 * <p>这几个数就是"卡顿有没有被治好"的判据：{@code 接} 持续增长说明阴影跟着车头在长；
	 * {@code 整表} 增长说明服务端发了整张表而客户端重建成功（换端就靠这一支，notes/375）；
	 * {@code 无基准/空表/缺轨/接不上} 任何一个持续增长，都说明那条路没有按设计走（见
	 * {@code MmtrLegAppender} 的几种拒绝），配合 {@code 阴影越界} 就能定位卡在哪一步。</p>
	 */
	private static int legAppended;
	private static int legDropped;
	private static int legReplaced;
	private static int legNoBase;
	private static int legEmptyTable;
	private static int legUnknownRail;
	private static int legDiscontinuous;
	/**
	 * 被拒的腿表记录（每辆车最多一条，永远保留**最新**那条）与本地重试的记账。
	 * 只有 {@code 缺轨} 值得重试（轨数据到了就能接上），其余原因重试没有意义。
	 */
	private static final Map<Long, PendingLegs> PENDING_LEGS = new HashMap<>();
	private static final long RETRY_WINDOW_MS = 1200;
	private static final int RETRY_MAX_ATTEMPTS = 12;
	private static int legRetryApplied;
	private static int legRetryGaveUp;

	/** 一条等待重试的腿表记录（{@code receivedMillis} = 收到它的本地时刻，用来卡"表不许过期"）。 */
	private record PendingLegs(Legs legs, long receivedMillis, int attempts) {
	}
	/** 服务端时钟 - 本地时钟（{@code PING} 记录）；{@code Long.MIN_VALUE} = 还没收到过。 */
	private static long serverMillisOffset = Long.MIN_VALUE;

	/**
	 * **③ 显示模型**：每辆车"服务端最近一帧的位置 / 速度 / 收到它的本地时刻"。
	 *
	 * <h2>为什么不能直接把服务端位置写进镜像</h2>
	 * <p>① 是 10 Hz 采样，而画面是 20+ Hz（甚至上百帧）。把采样值直接写进去，位置就是一条
	 * **每秒十级的台阶** —— 用户 2026-10-03 的原话"自己驾驶列车时客户端会在原地不动、然后突然
	 * 瞬移到正常位置"、"AI 驾驶的列车会有明显的位移掉帧"。原来那个"误差 &gt; 0.5 m 才硬对齐"的
	 * 折中只是把台阶变稀：实测 {@code 硬对齐=10~11/秒}（≈每一帧都在写），误差 1.7~7 m
	 * —— 那就是"停下、攒误差、一次性跳过去"。</p>
	 *
	 * <p>正确的做法是把"服务端位置"当成**误差信号**而不是命令：本地每 tick 自己往前走（物理 +
	 * 这一项），误差用**固定时间常数**（{@link #DISPLAY_TAU_MS}）连续吸收掉 —— 没有阈值、
	 * 没有台阶。只有误差大到"这不是同一条轨道上的事"（{@link #DISPLAY_HARD_SNAP_M}：镜像刚重建、
	 * 连挂手术、本地根本没在积分）才真的瞬移。</p>
	 */
	private static final Map<Long, ServerSample> SERVER_SAMPLE = new HashMap<>();

	/** 误差吸收的时间常数（毫秒）：越小越贴服务端、越大越平滑（160 ms ≈ 三次 tick 收敛）。 */
	private static final double DISPLAY_TAU_MS = 160;
	/** 超过这个误差（米）才认为是"真瞬移"而不是"该吸收的误差"。 */
	private static final double DISPLAY_HARD_SNAP_M = 8;

	/** 服务端一帧的位置/速度/收到时刻（速度单位与引擎一致：blocks/ms）。 */
	private record ServerSample(double railProgress, double speedMilliBlocksPerMs, long millis) {
	}

	private MmtrVehicleMotionClient() {
	}

	/** 这辆车是不是已经归运动流（①）管 —— ② 的补丁据此让出位置/手柄那几个字段。 */
	public static boolean isMotionManaged(long vehicleId) {
		return VEHICLE_TO_SLOT.containsKey(vehicleId);
	}

	/**
	 * 渲染时"车头走出了腿阴影末端"的越界量（米）——由 {@code VehicleExtension} 在摆车时上报。
	 *
	 * <p>这个数就是"该把 {@code LEGS} 通道接上"的直接证据：越界 &gt; 0 说明车头已经跑到
	 * 阴影（= 上一次刷新时的车头）之外，而摆车只能把它夹回去 —— 夹回去的表现是"车不再重叠、
	 * 但会停在阴影末端"，直到下一次 ② 整份带来新阴影（每长一条腿一次，1.5–6 秒）。</p>
	 *
	 * <p>notes/375 追加：**连续越界超过 {@link #SHADOW_STALE_LOG_MS} 就自己喊一声**。
	 * 那正是用户看到的"车不动、过一会儿瞬移"——而修前这件事在日志里只有一堆计数
	 * （`阴影越界=117次/803m`），没有任何一行说"这是车不动的原因"。现在超时的那一拍会打一行，
	 * 每 {@link #SHADOW_STALE_LOG_MS} 毫秒最多一行（不会刷屏），并在恢复时打一行"跟上"。</p>
	 */
	public static void noteShadowOverrun(long vehicleId, double metres) {
		shadowOverrunCount++;
		maxShadowOverrunM = Math.max(maxShadowOverrunM, metres);
		final long now = System.currentTimeMillis();
		final Long since = SHADOW_STALE_SINCE.get(vehicleId);
		if (since == null) {
			SHADOW_STALE_SINCE.put(vehicleId, now);
			SHADOW_STALE_MAX.put(vehicleId, metres);
			return;
		}
		SHADOW_STALE_MAX.merge(vehicleId, metres, Math::max);
		if (now - since >= SHADOW_STALE_LOG_MS) {
			Init.LOGGER.warn("[MMTR-MOTION] 腿表跟不上：车 {} 已连续 {}s 被夹在腿阴影末端（越界最大 {}m）—— 画面上的车就是「不动，然后瞬移」",
				vehicleId, (now - since) / 1000, Math.round(SHADOW_STALE_MAX.getOrDefault(vehicleId, metres) * 100.0) / 100.0);
			SHADOW_STALE_SINCE.put(vehicleId, now);
			SHADOW_STALE_MAX.put(vehicleId, 0.0);
		}
	}

	/** 这一拍车头又在阴影里了（摆车没夹）—— 清掉"腿表跟不上"的计时，必要时报一行"跟上"。 */
	public static void noteShadowCovered(long vehicleId) {
		if (SHADOW_STALE_SINCE.remove(vehicleId) != null) {
			SHADOW_STALE_MAX.remove(vehicleId);
			Init.LOGGER.info("[MMTR-MOTION] 腿表已跟上：车 {}（腿表重建/延长到车头之前了）", vehicleId);
		}
	}

	public static void receive(char[] frame) {
		frames++;
		bytes += frame.length * 2;
		final MmtrMotionFrame.Reader reader = new MmtrMotionFrame.Reader(frame);
		Record record;
		while ((record = reader.readNext()) != null) {
			records++;
			apply(record);
		}
		if (reader.isMalformed()) {
			malformedFrames++;
		}
		retryRefusedLegs();
		logIfDue();
	}

	/**
	 * **被拒的腿表本地重试**（notes/375）。
	 *
	 * <p>被拒的唯一"会自愈"的原因是 {@code UNKNOWN_RAIL}：本地轨表还没收到那根轨的数据。
	 * 那种情况下等下一次整表（最长 20 秒兜底）意味着**车在阴影里被夹 20 秒** —— 而轨数据通常
	 * 一两拍之内就到了。所以把被拒的那条记录留一小会儿，每收到一帧（10 Hz）重试一次，
	 * 成功即落地、超时即放弃（最多 {@link #RETRY_WINDOW_MS} 毫秒 —— **不许拿一张过期的表重建**：
	 * 表的锚点是"那一刻的里程"，隔久了重建出来的是一张落后的几何，反而制造阴影越界）。</p>
	 */
	private static void retryRefusedLegs() {
		if (PENDING_LEGS.isEmpty()) {
			return;
		}
		final long now = System.currentTimeMillis();
		for (final Iterator<Map.Entry<Long, PendingLegs>> iterator = PENDING_LEGS.entrySet().iterator(); iterator.hasNext(); ) {
			final Map.Entry<Long, PendingLegs> entry = iterator.next();
			final PendingLegs pending = entry.getValue();
			if (now - pending.receivedMillis() > RETRY_WINDOW_MS || pending.attempts() >= RETRY_MAX_ATTEMPTS) {
				iterator.remove();
				legRetryGaveUp++;
				continue;
			}
			final boolean[] applied = {false};
			withMirror(entry.getKey(), mirror -> {
				final MmtrLegAppender.Applied result = mirror.mmtrApplyLegDeltaFromSync(pending.legs().droppedFromTrainTail(), pending.legs().anchorM(), pending.legs().newLegs());
				if (result.reason() == MmtrLegAppender.Reason.REPLACED || result.reason() == MmtrLegAppender.Reason.OK) {
					applied[0] = true;
					legRetryApplied++;
				}
			});
			if (applied[0]) {
				iterator.remove();
			} else {
				entry.setValue(new PendingLegs(pending.legs(), pending.receivedMillis(), pending.attempts() + 1));
			}
		}
	}

	private static void apply(Record record) {
		if (record instanceof Slot slot) {
			SLOT_TO_VEHICLE.put(slot.slot(), slot.vehicleId());
			VEHICLE_TO_SLOT.put(slot.vehicleId(), slot.slot());
			// SLOT 的载荷就是"首帧的 STATE"：直接落一遍，客户端在收到第一条 MOTION 之前就已经对齐。
			withMirror(slot.vehicleId(), mirror -> mirror.mmtrApplySyncState(slot.flags(), slot.runStopTarget(), slot.runTotalDistance(), slot.blockStopM()));
		} else if (record instanceof Motion motion) {
			withMirror(slotVehicle(motion.slot()), mirror -> applyMotion(mirror, motion));
		} else if (record instanceof Control control) {
			withMirror(slotVehicle(control.slot()), mirror -> mirror.mmtrApplySyncControl(control.packed()));
		} else if (record instanceof State state) {
			withMirror(slotVehicle(state.slot()), mirror -> mirror.mmtrApplySyncState(state.flags(), state.runStopTarget(), state.runTotalDistance(), state.blockStopM()));
		} else if (record instanceof Legs legs) {
			legRecords++;
			applyLegs(legs);
		} else if (record instanceof Drop drop) {
			final Long vehicleId = SLOT_TO_VEHICLE.remove(drop.slot());
			if (vehicleId != null) {
				VEHICLE_TO_SLOT.remove(vehicleId);
				// 显示模型的样本是每辆车的：槽位还回去了就不再吸收它的误差（车走了就别再拖它）。
				SERVER_SAMPLE.remove(vehicleId);
			}
		} else if (record instanceof Ping ping) {
			/*
			 * 服务端时钟只发**低 32 位**（`u32 serverMillis`，49.7 天回绕一次够用），所以本地这一侧也要
			 * 同样截断再相减 —— 否则差值里混进整个 epoch（实测 2026-10-03：时钟差印出 1786706395147ms，
			 * 一眼就知道是把 1.79e12 和截断后的值相减了）。差值仍然正确（模 2³²）。
			 */
			serverMillisOffset = (System.currentTimeMillis() & 0xFFFFFFFFL) - ping.serverMillis();
		}
	}

	/**
	 * 落一条 {@code MOTION}：**只把它记成误差信号，不写位置**（位置由 {@link #mmtrDisplayTick} 连续吸收）。
	 *
	 * <p>这一支是"很卡"的正面修正：原先每条 MOTION 都无条件写 {@code railProgress}（后来改成"误差
	 * &gt; 0.5 m 才写"）—— 两种写法都会把 10 Hz 采样变成台阶。现在位置一个字节都不写，只
	 * ① 更新速度（软拉，消除长期漂移）② 记下这一帧供显示模型投影。</p>
	 */
	private static void applyMotion(VehicleExtension mirror, Motion motion) {
		final double error = motion.railProgress() - mirror.getRailProgress();
		maxAbsErrorM = Math.max(maxAbsErrorM, Math.abs(error));
		if (motion.speed() != null) {
			// 位置交给显示模型（传回它自己的 railProgress = 不改位置），只把速度软拉向服务端。
			mirror.mmtrApplySyncMotion(mirror.getRailProgress(), motion.speed());
		}
		final ServerSample previous = SERVER_SAMPLE.get(mirror.getId());
		SERVER_SAMPLE.put(mirror.getId(), new ServerSample(
			motion.railProgress(),
			// 位置帧不带速度（每 5 帧才带一次）：沿用上一次的 —— 不带速度的帧正是"本地积分够准"那些帧。
			motion.speed() == null ? (previous == null ? 0 : previous.speedMilliBlocksPerMs()) : motion.speed(),
			System.currentTimeMillis()));
	}

	/**
	 * **③ 显示模型每 tick 一步**（由 {@code VehicleExtension#simulate} 调用；客户端每个车每 tick 一次）。
	 *
	 * <p>两步：</p>
	 * <ol>
	 *   <li><b>投影</b>：服务端那一帧"现在应该在哪" = 它当时的位置 + 速度 × 帧年龄。**必须投影** ——
	 *       否则会把网络延迟（几十到一百毫秒）当成误差，每帧把车往回拽，那又是一种"原地不动"。</li>
	 *   <li><b>吸收</b>：误差按固定时间常数（{@link #DISPLAY_TAU_MS}）走掉一部分。误差因此永远
	 *       不会被攒到某个阈值再一次性抹平 —— 台阶就是这么来的。误差大到不像同一条轨道上的事
	 *       （{@link #DISPLAY_HARD_SNAP_M}）才瞬移。</li>
	 * </ol>
	 */
	public static void mmtrDisplayTick(VehicleExtension vehicle, long millisElapsed) {
		final ServerSample sample = SERVER_SAMPLE.get(vehicle.getId());
		if (sample == null || millisElapsed <= 0) {
			return;
		}
		final double projected = sample.railProgress() + sample.speedMilliBlocksPerMs() * (System.currentTimeMillis() - sample.millis());
		final double error = projected - vehicle.getRailProgress();
		if (Math.abs(error) > DISPLAY_HARD_SNAP_M) {
			hardAligns++;
			maxAbsErrorM = Math.max(maxAbsErrorM, Math.abs(error));
			vehicle.mmtrApplySyncMotion(projected, null);
		} else {
			vehicle.mmtrApplySyncMotion(vehicle.getRailProgress() + error * Math.min(1.0, millisElapsed / DISPLAY_TAU_MS), null);
		}
	}

	/**
	 * 落一条 {@code LEGS}（notes/369 S3b；整表那一支是 notes/375）：把服务端的腿表变更落到镜像上。
	 *
	 * <p>为什么要有这一支：腿阴影原来只在 ② 的**整份快照**里重建，而整份快照跟着脏拍走（新踏上一根轨，
	 * 60 km/h 下 1.5–6 秒一次）。中间那几秒车头已经跑出阴影末端，摆车只能把它夹回去 ——
	 * 现场就是**「车移动依旧是卡的」**。把增量接上之后，阴影跟着车头连续延长，那个"停一下"就没了。</p>
	 *
	 * <p>换端（整表反序）走的是同一支的**整表重建**：服务端把整张表 + 锚点 + 逐腿方向一起发来，
	 * 客户端按 {@code MmtrLegAppender.replaceWholeTable} 重建。这一支是 notes/375 之前缺的那一半 ——
	 * 那时换端只能"保持旧表等 ② 的整份"，而 ② 对"客户端已经持有的车"根本不发整份
	 * （见 {@code Client#update}：{@code patch == null} 时静默保活），于是车会**不动几十秒再瞬移**。</p>
	 */
	private static void applyLegs(Legs legs) {
		final Long vehicleId = slotVehicle(legs.slot());
		if (vehicleId == null) {
			return;
		}
		withMirror(vehicleId, mirror -> {
			final MmtrLegAppender.Applied applied = mirror.mmtrApplyLegDeltaFromSync(legs.droppedFromTrainTail(), legs.anchorM(), legs.newLegs());
			if (applied.reason() == MmtrLegAppender.Reason.OK) {
				// 只有**增量**才计入接/丢：整表替换的数量级完全不同（整张表），混在一起那一对数就没有意义了。
				legAppended += applied.appended();
				legDropped += applied.dropped();
			}
			switch (applied.reason()) {
				case OK -> PENDING_LEGS.remove(vehicleId);
				case REPLACED -> {
					legReplaced++;
					PENDING_LEGS.remove(vehicleId);
				}
				case NO_BASE -> legNoBase++;
				case EMPTY_TABLE -> legEmptyTable++;
				case UNKNOWN_RAIL -> {
					// 唯一"等一等就能接上"的原因：本地轨表还没收到那根轨。留最新的一条本地重试。
					legUnknownRail++;
					PENDING_LEGS.put(vehicleId, new PendingLegs(legs, System.currentTimeMillis(), 1));
				}
				case DISCONTINUOUS -> legDiscontinuous++;
			}
		});
	}

	private static Long slotVehicle(int slot) {
		final Long vehicleId = SLOT_TO_VEHICLE.get(slot);
		if (vehicleId == null) {
			// 没听过这个槽位：不知道是哪辆车 —— 不猜、不错写，只记一笔（n>0 就是 ①/② 不同源的红证）。
			unknownSlots++;
		}
		return vehicleId;
	}

	private static void withMirror(Long vehicleId, java.util.function.Consumer<VehicleExtension> consumer) {
		if (vehicleId == null) {
			return;
		}
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				consumer.accept(vehicle);
				return;
			}
		}
		// 槽位已知但镜像还没建起来（② 的整份快照还在路上）：这一帧丢掉即可，下一帧（≤100 ms）会补上。
	}

	private static void logIfDue() {
		final long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			windowStartMillis = now;
			return;
		}
		final long elapsed = now - windowStartMillis;
		if (elapsed < 1000) {
			return;
		}
		Init.LOGGER.info("[MMTR-MOTION] 运动流：帧/s={} 记录/s={} 字节/s={} 槽位={} 未知槽位={} 坏帧={} 腿记录={}（接={} 丢={} 整表={} 无基准={} 空表={} 缺轨={} 接不上={} 重试成={} 重试弃={}）最大误差={}m 硬对齐={} 阴影越界={}次/{}m 时钟差={}ms",
			frames, records, bytes, SLOT_TO_VEHICLE.size(), unknownSlots, malformedFrames, legRecords,
			legAppended, legDropped, legReplaced, legNoBase, legEmptyTable, legUnknownRail, legDiscontinuous,
			legRetryApplied, legRetryGaveUp,
			Math.round(maxAbsErrorM * 1000.0) / 1000.0, hardAligns,
			shadowOverrunCount, Math.round(maxShadowOverrunM * 1000.0) / 1000.0,
			serverMillisOffset == Long.MIN_VALUE ? "-" : Long.toString(serverMillisOffset));
		windowStartMillis = now;
		frames = 0;
		bytes = 0;
		records = 0;
		unknownSlots = 0;
		malformedFrames = 0;
		legRecords = 0;
		legAppended = 0;
		legDropped = 0;
		legReplaced = 0;
		legNoBase = 0;
		legEmptyTable = 0;
		legUnknownRail = 0;
		legDiscontinuous = 0;
		legRetryApplied = 0;
		legRetryGaveUp = 0;
		hardAligns = 0;
		maxAbsErrorM = 0;
		shadowOverrunCount = 0;
		maxShadowOverrunM = 0;
	}
}
