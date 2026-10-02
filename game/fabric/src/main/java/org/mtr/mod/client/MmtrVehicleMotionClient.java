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
	/** 服务端时钟 - 本地时钟（{@code PING} 记录）；{@code Long.MIN_VALUE} = 还没收到过。 */
	private static long serverMillisOffset = Long.MIN_VALUE;

	/**
	 * 位置校正的阈值（米）。**本地积分够准就不要硬对齐** —— 硬对齐每 100 ms 来一次，
	 * 在画面上就是"每秒十级的台阶"（用户 2026-10-03 实机报"新的通信逻辑很卡"）。
	 *
	 * <p>所以：误差在阈值内 ⇒ **位置交给本地积分**，只把速度软拉向服务端（消除长期漂移）；
	 * 超过阈值（首次对齐 / 连挂手术 / 本地积分根本没跑）才真的写位置。
	 * {@link #hardAligns} 这个计数就是这条判据的可见性：它若接近帧数，说明本地积分没在跑
	 * （那才是病根 —— 不是把阈值调大能解决的）。</p>
	 */
	private static final double SNAP_THRESHOLD_M = 0.5;

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
		} else if (record instanceof Legs) {
			// 腿阴影的接头在 S3b 的客户端一半（要按 hex 重建 PathData 并重新锚定里程）——
			// 在它落地之前，服务端那边这个通道默认是关的（mmtr.motion.legs=false），所以这里只记账。
			legRecords++;
		} else if (record instanceof Drop drop) {
			final Long vehicleId = SLOT_TO_VEHICLE.remove(drop.slot());
			if (vehicleId != null) {
				VEHICLE_TO_SLOT.remove(vehicleId);
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
	 * 落一条 {@code MOTION}：**误差小的时候不写位置**（见 {@link #SNAP_THRESHOLD_M}）。
	 *
	 * <p>这一支是"很卡"的正面修正：原先每条 MOTION 都无条件写 {@code railProgress}，
	 * 于是位置变成"服务端 10 Hz 采样"的阶梯 —— 本地每帧那点积分被每 100 ms 抹掉一次，
	 * 画面上就是跳。现在只有"本地积分明显跟不上"（首次对齐、连挂、或者本地根本没在积分）才写位置。</p>
	 */
	private static void applyMotion(VehicleExtension mirror, Motion motion) {
		final double error = motion.railProgress() - mirror.getRailProgress();
		maxAbsErrorM = Math.max(maxAbsErrorM, Math.abs(error));
		if (Math.abs(error) > SNAP_THRESHOLD_M) {
			hardAligns++;
			mirror.mmtrApplySyncMotion(motion.railProgress(), motion.speed());
		} else if (motion.speed() != null) {
			// 位置交给本地积分（传回它自己的 railProgress = 不改位置），只把速度软拉向服务端。
			mirror.mmtrApplySyncMotion(mirror.getRailProgress(), motion.speed());
		}
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
		Init.LOGGER.info("[MMTR-MOTION] 运动流：帧/s={} 记录/s={} 字节/s={} 槽位={} 未知槽位={} 坏帧={} 腿记录={} 最大误差={}m 硬对齐={} 阴影越界={}次/{}m 时钟差={}ms",
			frames, records, bytes, SLOT_TO_VEHICLE.size(), unknownSlots, malformedFrames, legRecords,
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
		hardAligns = 0;
		maxAbsErrorM = 0;
		shadowOverrunCount = 0;
		maxShadowOverrunM = 0;
	}
}
