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
 *   <li><b>记账</b>：每秒一行 {@code [MMTR-NET]}，把"这套东西到底花了多少字节"变成可核对的数
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
	/** 服务端时钟 - 本地时钟（{@code PING} 记录）；{@code Long.MIN_VALUE} = 还没收到过。 */
	private static long serverMillisOffset = Long.MIN_VALUE;

	private MmtrVehicleMotionClient() {
	}

	/** 这辆车是不是已经归运动流（①）管 —— ② 的补丁据此让出位置/手柄那几个字段。 */
	public static boolean isMotionManaged(long vehicleId) {
		return VEHICLE_TO_SLOT.containsKey(vehicleId);
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
			withMirror(slotVehicle(motion.slot()), mirror -> mirror.mmtrApplySyncMotion(motion.railProgress(), motion.speed()));
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
			serverMillisOffset = System.currentTimeMillis() - ping.serverMillis();
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
		Init.LOGGER.info("[MMTR-NET] 运动流：帧/s={} 记录/s={} 字节/s={} 槽位={} 未知槽位={} 坏帧={} 腿记录={} 时钟差={}ms",
			frames, records, bytes, SLOT_TO_VEHICLE.size(), unknownSlots, malformedFrames, legRecords,
			serverMillisOffset == Long.MIN_VALUE ? "-" : Long.toString(serverMillisOffset));
		windowStartMillis = now;
		frames = 0;
		bytes = 0;
		records = 0;
		unknownSlots = 0;
		malformedFrames = 0;
		legRecords = 0;
	}
}
