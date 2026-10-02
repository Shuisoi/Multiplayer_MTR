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
 *   <li><b>接腿</b>：{@code LEGS} 增量接到镜像的腿阴影上（S3b）—— 阴影原来只在 ② 的整份快照里重建，
 *       于是车头每隔 1.5–6 秒就会跑出阴影末端被夹回来（现场就是"卡"）。</li>
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
	 * 腿阴影增量（{@code LEGS}）的落地记账：接了几根 / 丢了几根 / 为什么停在那里。
	 *
	 * <p>这几个数就是"卡顿有没有被治好"的判据：{@code 接} 持续增长说明阴影跟着车头在长；
	 * {@code 无基准/整表/缺轨/接不上} 任何一个持续增长，都说明那条路没有按设计走（见
	 * {@code MmtrLegAppender} 的三种拒绝），配合 {@code 阴影越界} 就能定位卡在哪一步。</p>
	 */
	private static int legAppended;
	private static int legDropped;
	private static int legNoBase;
	private static int legFullReplace;
	private static int legUnknownRail;
	private static int legDiscontinuous;
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
	 */
	public static void noteShadowOverrun(double metres) {
		shadowOverrunCount++;
		maxShadowOverrunM = Math.max(maxShadowOverrunM, metres);
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
		logIfDue();
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
	 * 落一条 {@code LEGS}（notes/369 S3b）：把"服务端新踏上的那几根轨"接到镜像的腿阴影末尾。
	 *
	 * <p>为什么要这一支：腿阴影只在 ② 的**整份快照**里重建，而整份快照跟着脏拍走（新踏上一根轨，
	 * 60 km/h 下 1.5–6 秒一次）。中间那几秒车头已经跑出阴影末端，摆车只能把它夹回去 ——
	 * 现场就是**「车移动依旧是卡的」**。把增量接上之后，阴影跟着车头连续延长，那个"停一下"就没了。</p>
	 *
	 * <p>几何与里程（含三种"不猜"的拒绝）全在引擎 {@code MmtrLegAppender} 里（那边有单元测试）；
	 * 这里只记账：接/丢的根数，以及终止原因的分类计数。</p>
	 */
	private static void applyLegs(Legs legs) {
		final Long vehicleId = slotVehicle(legs.slot());
		if (vehicleId == null) {
			return;
		}
		withMirror(vehicleId, mirror -> {
			final MmtrLegAppender.Applied applied = mirror.mmtrApplyLegDeltaFromSync(legs.droppedFromTrainTail(), legs.newLegs());
			legAppended += applied.appended();
			legDropped += applied.dropped();
			switch (applied.reason()) {
				case OK -> {
				}
				case NO_BASE -> legNoBase++;
				case FULL_REPLACE -> legFullReplace++;
				case UNKNOWN_RAIL -> legUnknownRail++;
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
		Init.LOGGER.info("[MMTR-MOTION] 运动流：帧/s={} 记录/s={} 字节/s={} 槽位={} 未知槽位={} 坏帧={} 腿记录={}（接={} 丢={} 无基准={} 整表={} 缺轨={} 接不上={}）最大误差={}m 硬对齐={} 阴影越界={}次/{}m 时钟差={}ms",
			frames, records, bytes, SLOT_TO_VEHICLE.size(), unknownSlots, malformedFrames, legRecords,
			legAppended, legDropped, legNoBase, legFullReplace, legUnknownRail, legDiscontinuous,
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
		legNoBase = 0;
		legFullReplace = 0;
		legUnknownRail = 0;
		legDiscontinuous = 0;
		hardAligns = 0;
		maxAbsErrorM = 0;
		shadowOverrunCount = 0;
		maxShadowOverrunM = 0;
	}
}
