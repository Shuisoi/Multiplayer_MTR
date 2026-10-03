package org.mtr.mod.mmtr;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import org.mtr.core.Main;
import org.mtr.core.data.Client;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.net.MmtrMotionFrame;
import org.mtr.core.simulation.Simulator;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketMmtrVehicleMotion;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * **列车运动流**的服务端逐 tick 组帧器（notes/369 ①）。
 *
 * <h2>它取代了什么</h2>
 * <p>旧协议：位置与手柄挤在 ②（JSON 快照/补丁）里，而 ② 只在"脏拍"才发 —— 实测客户端
 * **每秒**才收到一次，而且那一份常常是 7.4 KB 的整份（notes/368 §1：1191/1711 个包是整份）。
 * 于是镜像要么冻住（输入没到），要么每秒被拉回一次（位移掉帧）。</p>
 *
 * <h2>它怎么发</h2>
 * <ul>
 *   <li><b>一个客户端一帧一个包</b>（默认 10 Hz = 每 2 tick）：通道头的固定开销只付一次；</li>
 *   <li><b>只发变的</b>：位置每帧（7 B/车），速度每 5 帧（+4 B），手柄与旗标/夹紧量走**边沿**
 *       （变了才发），腿阴影走**增量**（默认开；稳态 0 字节，只有新踏上一根轨时才发那根轨）
 *       —— **形状一变（换端）就发整张表**（+ 锚点 + 逐腿方向），另有 20 秒一次的整表兜底
 *       （notes/375：v1 的纯增量表达不了"整表反序"，客户端只能拿旧表硬撑 = 车不动再瞬移）；</li>
 *   <li><b>可见集与 ② 同源</b>：用 {@link Client#tracksVehicle}（② 上次真的发过镜像给这个客户端的那些车）——
 *       于是不会出现"① 有包、客户端没镜像"或"有镜像、① 永远不动它"；</li>
 *   <li><b>槽位是每客户端一份</b>的 u16，随 {@code SLOT} 记录一次性告知，{@code DROP} 时归还。</li>
 * </ul>
 *
 * <h2>开关（出问题时的回退）</h2>
 * <pre>
 *   -Dmmtr.motion.stream=false   ⇒ 完全回到旧行为（一个字节都不发）
 *   -Dmmtr.motion.hz=5|10|20     ⇒ 帧率（默认 10）
 *   -Dmmtr.motion.legs=false     ⇒ 关掉腿表通道（**默认开**：客户端那一半已落地，见 notes/369 S3b。
 *                                   关掉的表现是"车头走到阴影末端就停住、等下一拍整份再跳"）
 *   -Dmmtr.motion.legs.fullSeconds=60 ⇒ 每隔这么多秒发一次**整表**兜底（0 = 关；notes/375）
 * </pre>
 */
public final class MmtrVehicleMotionSync {

	private static final int HZ = Math.max(1, Math.min(20, Integer.getInteger("mmtr.motion.hz", 10)));
	private static final int FRAME_TICKS = Math.max(1, 20 / HZ);
	private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("mmtr.motion.stream", "true"));
	private static final boolean LEGS_ENABLED = !"false".equalsIgnoreCase(System.getProperty("mmtr.motion.legs", "true"));
	/** 每隔这么多帧带一次速度（客户端本地积分够准，用不着每帧校速：省 4 B/车/帧）。 */
	private static final int SPEED_EVERY_FRAMES = 5;
	/** 每隔这么多帧带一次服务端时钟（误差缓冲要按时间收敛）。 */
	private static final int PING_EVERY_FRAMES = SPEED_EVERY_FRAMES * 4;
	/**
	 * 每隔这么多帧把**边沿型**记录（手柄 + 旗标/夹紧量）无条件重发一次。
	 *
	 * <p>为什么边沿之外还要有这个："这辆车第一次进这个客户端"那一帧，服务端是"先发 SLOT、
	 * 之后只在变化时发 CONTROL/STATE"，而客户端可能在收到 SLOT 时**镜像还没建起来**
	 * （② 的整份快照还在同一条 TCP 上排队），于是那一次的初值就被丢掉了 —— 而它之后不会再发，
	 * 客户端就会拿着一份空的旗标/夹紧量跑下去。重发一次（10 帧 = 1 s，每车 24 B/s）把这条路封死，
	 * 顺带让镜像被整份快照重建之后自己收敛回来。</p>
	 */
	private static final int EDGE_REFRESH_EVERY_FRAMES = HZ;
	/**
	 * 每隔这么多帧无条件发一次**整张腿表**（兜底，notes/375）。
	 *
	 * <p>兜底要治的是"客户端手上那张表与服务端不同源"里**剩下**的那一类：`缺轨` 已经由客户端的
	 * **本地重试**治了（轨数据到了就接上，≤1 秒，不花一个字节），② 重建镜像也已经由
	 * {@code Client#tookFullVehicleUpdate} 当场重新锚定了；剩下的只有"客户端原子拒绝了增量、
	 * 而两本账再也没对齐"这种没想到的情况 —— 60 秒一次足够把它拉回来。</p>
	 *
	 * <p>代价：一张表 ~660 B（6 根腿）⇒ {@code 660 / 60 ≈ 11 B/s/车}。8 辆车在场 ≈ 88 B/s，
	 * 而它平时一次都用不到（真正的路是"形状变了就发"）。可用
	 * {@code -Dmmtr.motion.legs.fullSeconds=} 调（0 = 关掉定时兜底）。</p>
	 */
	private static final int FULL_LEGS_REFRESH_EVERY_FRAMES = Math.max(0, Integer.getInteger("mmtr.motion.legs.fullSeconds", 60)) * HZ;

	private static final Map<UUID, ClientFrames> CLIENTS = new HashMap<>();

	/** 服务端侧记账（5 秒一行）：这一路到底花了多少字节 —— 指标要能核对，不靠感觉。 */
	private static long windowStartMillis;
	private static int sentFrames;
	private static int sentBytes;
	private static int sentRecords;
	/** 这一窗口里发了几次**整张**腿表（notes/375：形状变了必发，另有定时兜底）。 */
	private static int sentFullLegTables;

	private MmtrVehicleMotionSync() {
	}

	/**
	 * 注册到 {@code END_SERVER_TICK}。
	 *
	 * <p>**必须在 {@code MmtrRouteMirror.register()} 之前**（见 {@code MTR.onInitialize()}）：
	 * 性能探针靠"MmtrRouteMirror 的钩子最后跑"来收尾（{@code MmtrTickProbe.onHooksFinished}），
	 * 插在它后面会让探针读数随文件顺序变 —— 那种静默错正是 notes/337 记下的坑。</p>
	 */
	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(MmtrVehicleMotionSync::tick);
	}

	private static void tick(MinecraftServer minecraftServer) {
		if (!ENABLED) {
			return;
		}
		final int ticks = minecraftServer.getTicks();
		if (ticks % FRAME_TICKS != 0) {
			return;
		}
		final int frameIndex = ticks / FRAME_TICKS;
		final boolean withSpeed = frameIndex % SPEED_EVERY_FRAMES == 0;
		final boolean withPing = frameIndex % PING_EVERY_FRAMES == 0;
		final long probeT = org.mtr.core.mmtr.probe.MmtrProbe.begin();
		try {
			tickMeasured(minecraftServer, withSpeed, withPing);
		} finally {
			org.mtr.core.mmtr.probe.MmtrProbe.end("server.motionStream", probeT);
		}
	}

	private static void tickMeasured(MinecraftServer minecraftServer, boolean withSpeed, boolean withPing) {
		final Main main = Init.getMain();
		if (main == null) {
			return;
		}
		final int frameIndex = minecraftServer.getTicks() / FRAME_TICKS;
		final boolean withEdgeRefresh = frameIndex % EDGE_REFRESH_EVERY_FRAMES == 0;
		final boolean withFullLegs = LEGS_ENABLED && FULL_LEGS_REFRESH_EVERY_FRAMES > 0 && frameIndex % FULL_LEGS_REFRESH_EVERY_FRAMES == 0;
		for (final ServerWorld serverWorld : minecraftServer.getWorlds()) {
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(serverWorld)));
			if (simulator == null || simulator.clients.isEmpty()) {
				continue;
			}
			final Map<UUID, org.mtr.mapping.holder.ServerPlayerEntity> players = new HashMap<>();
			MinecraftServerHelper.iteratePlayers(new org.mtr.mapping.holder.ServerWorld(serverWorld), player -> players.put(player.getUuid(), player));
			for (final Client client : simulator.clients) {
				final org.mtr.mapping.holder.ServerPlayerEntity player = players.get(client.uuid);
				if (player == null) {
					// 这个引擎客户端不在这个维度（换维度的那一拍）：下一帧再说。
					continue;
				}
				final char[] frame = buildFrame(simulator, client, withSpeed, withPing, withEdgeRefresh, withFullLegs);
				if (frame != null) {
					Init.REGISTRY.sendPacketToClient(player, new PacketMmtrVehicleMotion(frame));
					sentFrames++;
					sentBytes += frame.length * 2;
					sentRecords += new MmtrMotionFrame.Reader(frame).remainingRecords();
				}
			}
		}
		logIfDue();
	}

	/** 服务端侧 5 秒一行：帧数/记录数/字节数（跨所有客户端）+ 整表腿表发了几次（notes/375 的读数）。 */
	private static void logIfDue() {
		final long now = System.currentTimeMillis();
		if (windowStartMillis == 0) {
			windowStartMillis = now;
			return;
		}
		final long elapsed = now - windowStartMillis;
		if (elapsed < 5000) {
			return;
		}
		/*
		 * **必须走 System.out**：模组的 log4j logger 在**专用服务端**的日志里看不见
		 * （2026-10-03 实测：`logs/latest.log` 最后 3000 行里 2999 行是 `[STDOUT]`，
		 * 带 `(MinecraftTransitRailway)` 的只有 1 行），而这一行恰恰是要在服务端日志里核对的读数。
		 * 与 `[MMTR-SUB]` / `[MMTR-DRV]` 那些状态类消息同一个口径。
		 */
		System.out.println("[MMTR-MOTION] 运动流(服务端)："
			+ (sentFrames * 1000L / elapsed) + " 帧/s "
			+ (sentRecords * 1000L / elapsed) + " 记录/s "
			+ (sentBytes * 1000L / elapsed) + " 字节/s 客户端=" + CLIENTS.size() + " 帧率=" + HZ + "Hz"
			+ " 腿整表=" + sentFullLegTables + "（形状变了/定时兜底）");
		windowStartMillis = now;
		sentFrames = 0;
		sentBytes = 0;
		sentRecords = 0;
		sentFullLegTables = 0;
	}

	/** @return 这一帧的载荷；{@code null} = 一条记录都没有（不发包） */
	private static char[] buildFrame(Simulator simulator, Client client, boolean withSpeed, boolean withPing, boolean withEdgeRefresh, boolean withFullLegs) {
		final ClientFrames state = CLIENTS.computeIfAbsent(client.uuid, uuid -> new ClientFrames());
		final MmtrMotionFrame.Writer writer = new MmtrMotionFrame.Writer();
		final Set<Long> seen = new HashSet<>();

		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			final long vehicleId = vehicle.getId();
			if (!client.tracksVehicle(vehicleId)) {
				// 与 ② 同源：② 没给这个客户端发过镜像的车，① 也不发（客户端只会把它丢进"未知槽位"）。
				return;
			}
			seen.add(vehicleId);
			writeVehicle(writer, state, client, vehicle, withSpeed, withEdgeRefresh, withFullLegs);
		}));

		// 车离开视距 / 被删 / 驾驶室解散：释放槽位并告诉客户端（镜像的生死仍归 ② 管，这里只归还编号）。
		for (final Iterator<Map.Entry<Long, SentState>> iterator = state.byVehicle.entrySet().iterator(); iterator.hasNext(); ) {
			final Map.Entry<Long, SentState> entry = iterator.next();
			if (!seen.contains(entry.getKey())) {
				writer.drop(entry.getValue().slot);
				state.freeSlot(entry.getValue().slot);
				iterator.remove();
			}
		}

		/*
		 * `PING` 只在**这一帧真的有内容**时搭车发：它服务的是"误差缓冲按时间收敛"，
		 * 而视距内一辆车都没有时没有东西要收敛 —— 那时每秒 10 字节 × 每个客户端纯属白扔
		 * （64 个客户端 = 640 B/s 的噪声，而这一路的目标就是把稳态开销压到近零；
		 * 2026-10-03 空场实测：`槽位=0` 却仍有 `帧/s=1 字节/s=10`）。
		 */
		if (withPing && !writer.isEmpty()) {
			writer.ping(simulator.getCurrentMillis());
		}
		return writer.isEmpty() ? null : writer.toCharArray();
	}

	private static void writeVehicle(MmtrMotionFrame.Writer writer, ClientFrames state, Client client, Vehicle vehicle, boolean withSpeed, boolean withEdgeRefresh, boolean withFullLegs) {
		final long vehicleId = vehicle.getId();
		final int flags = flagsOf(vehicle);
		final int control = MmtrMotionFrame.packControl(
			vehicle.getMmtrThrottleFromSync(),
			vehicle.getMmtrBrakeFromSync(),
			vehicle.getMmtrDriveHandleFromSync(),
			vehicle.getMmtrCruiseKmhFromSync(),
			vehicle.getMmtrReverserFromSync(),
			vehicle.isMmtrEmergencyFromSync(),
			// 灯光档位不在 ① 里发：它两端各一份、由 ② 权威携带（① 的位留着只是为了格式完整）。
			0);
		final double runStopTarget = vehicle.getMmtrRunStopTargetFromSync();
		final double runTotalDistance = vehicle.getMmtrRunTotalDistanceFromSync();
		final double blockStopM = vehicle.getMmtrBlockStopM();

		SentState sent = state.byVehicle.get(vehicleId);
		if (sent == null) {
			final int slot = state.allocateSlot();
			if (slot == 0) {
				return;
			}
			sent = new SentState(slot);
			state.byVehicle.put(vehicleId, sent);
			writer.slot(slot, vehicleId, flags, runStopTarget, runTotalDistance, blockStopM);
			// 首帧**硬对齐**：带位置与速度，客户端在第一条 MOTION 之前就已经站对地方。
			writer.motion(slot, vehicle.getRailProgress(), vehicle.getSpeed());
			sent.control = control;
			sent.flags = flags;
			sent.runStopTarget = runStopTarget;
			sent.runTotalDistance = runTotalDistance;
			sent.blockStopM = blockStopM;
			if (LEGS_ENABLED) {
				// 首帧发**整张表**（不是增量）：客户端此刻多半还没有镜像（② 的整份在路上），
				// 丢掉也无所谓 —— 之后每一拍要么是形状变了的整表、要么是能接上的增量，
				// 而且还有定时兜底，不会像 v1 那样"丢了初值就永远对不上"。
				final List<MmtrMotionFrame.Leg> legs = vehicle.getMmtrMotionLegsForWire();
				writer.legs(slot, MmtrMotionFrame.LEGS_FULL_REPLACE, vehicle.getMmtrMotionPathAnchorM(), legs);
				sent.legs = legs;
				sentFullLegTables++;
			}
			return;
		}

		if (control != sent.control) {
			writer.control(sent.slot, control);
			sent.control = control;
		} else if (withEdgeRefresh) {
			// 自愈：客户端可能在收到 SLOT 时还没有镜像，那一次初值就丢了 —— 每秒补发一遍边沿。
			writer.control(sent.slot, control);
		}
		if (flags != sent.flags || runStopTarget != sent.runStopTarget || runTotalDistance != sent.runTotalDistance || blockStopM != sent.blockStopM) {
			writer.state(sent.slot, flags, runStopTarget, runTotalDistance, blockStopM);
			sent.flags = flags;
			sent.runStopTarget = runStopTarget;
			sent.runTotalDistance = runTotalDistance;
			sent.blockStopM = blockStopM;
		} else if (withEdgeRefresh) {
			writer.state(sent.slot, flags, runStopTarget, runTotalDistance, blockStopM);
		}
		if (LEGS_ENABLED) {
			/*
			 * 腿表（notes/375）：**形状变了就发整表**，只有"旧表原封不动、只多了车头那几根"才走增量。
			 *
			 * <p>{@code legDelta} 比的是 {@link MmtrMotionFrame.Leg}（轨 + 方向）。换端是"同一批轨、
			 * 相反顺序"：只比 hex 的话会算出"丢两根、再在车头接两根"，客户端接出一张方向错的表 ——
			 * 现场就是"换端开出去车不动、过一会儿瞬移"。比方向之后换端必然落到
			 * {@code replacesWholeTable()}，于是发整表 + 锚点，客户端重建。</p>
			 *
			 * <p>另外两处也发整表：**② 这一拍整份重建过这辆车的镜像**（客户端手上的表换成了 ② 那份，
			 * 与这里的基准可能不同源 ⇒ 继续发增量只会被原子拒绝，几何停在旧值上）与**定时兜底**
			 * （客户端"缺轨"这类它自己修不了的情况）。</p>
			 */
			final List<MmtrMotionFrame.Leg> legs = vehicle.getMmtrMotionLegsForWire();
			final double anchorM = vehicle.getMmtrMotionPathAnchorM();
			if (client.tookFullVehicleUpdate(vehicleId)) {
				writer.legs(sent.slot, MmtrMotionFrame.LEGS_FULL_REPLACE, anchorM, legs);
				sent.legs = legs;
				sentFullLegTables++;
			} else {
				final MmtrMotionFrame.LegDelta delta = MmtrMotionFrame.legDelta(sent.legs, legs);
				if (delta != null && !delta.isEmpty()) {
					if (delta.replacesWholeTable()) {
						writer.legs(sent.slot, MmtrMotionFrame.LEGS_FULL_REPLACE, anchorM, legs);
						sentFullLegTables++;
					} else {
						writer.legs(sent.slot, delta.droppedFromTrainTail(), anchorM, legs.subList(delta.appendedFromIndex(), legs.size()));
					}
					sent.legs = legs;
				} else if (withFullLegs) {
					// 定时兜底：客户端手上那张表与这里不同源时（接不上、缺轨），靠它回到正轨。
					writer.legs(sent.slot, MmtrMotionFrame.LEGS_FULL_REPLACE, anchorM, legs);
					sent.legs = legs;
					sentFullLegTables++;
				}
			}
		}

		final double speed = vehicle.getSpeed();
		if (speed != 0 || withSpeed) {
			if (withSpeed) {
				writer.motion(sent.slot, vehicle.getRailProgress(), speed);
			} else {
				writer.motion(sent.slot, vehicle.getRailProgress());
			}
		}
	}

	/** ① 的旗标（与 {@code MmtrMotionFrame.FLAG_*} 一一对应；门与本务手动那几位刻意不发）。 */
	private static int flagsOf(Vehicle vehicle) {
		int flags = 0;
		if (vehicle.isMmtrActiveFromSync()) {
			flags |= MmtrMotionFrame.FLAG_MMTR_ACTIVE;
		}
		if (vehicle.isMmtrMotionMirrorFromSync()) {
			flags |= MmtrMotionFrame.FLAG_MOTION_MIRROR;
		}
		if (vehicle.getReversed()) {
			flags |= MmtrMotionFrame.FLAG_REVERSED;
		}
		if (vehicle.isMmtrPinnedFromSync()) {
			flags |= MmtrMotionFrame.FLAG_PINNED;
		}
		if (vehicle.isMmtrProtectionFromSync()) {
			flags |= MmtrMotionFrame.FLAG_PROTECTION;
		}
		if (vehicle.isMmtrBlockHeldFromSync()) {
			flags |= MmtrMotionFrame.FLAG_BLOCK_HELD;
		}
		if (vehicle.isMmtrAuthorityTripped()) {
			flags |= MmtrMotionFrame.FLAG_AUTHORITY_TRIPPED;
		}
		return flags;
	}

	/** 一个客户端一套编号与"上次发出去的值"。 */
	private static final class ClientFrames {

		private final Map<Long, SentState> byVehicle = new HashMap<>();
		private final BitSet usedSlots = new BitSet(0x10000);
		private int nextSlot = 1;

		private int allocateSlot() {
			for (int i = nextSlot; i <= 0xFFFF; i++) {
				if (!usedSlots.get(i)) {
					usedSlots.set(i);
					nextSlot = i + 1;
					return i;
				}
			}
			for (int i = 1; i < nextSlot; i++) {
				if (!usedSlots.get(i)) {
					usedSlots.set(i);
					return i;
				}
			}
			return 0;
		}

		private void freeSlot(int slot) {
			if (slot > 0) {
				usedSlots.clear(slot);
				if (slot < nextSlot) {
					nextSlot = slot;
				}
			}
		}
	}

	/** 一个（客户端 × 车）的"上次发出去的值"：边沿判据全靠它，所以它必须逐字段记全。 */
	private static final class SentState {

		private final int slot;
		private int control;
		private int flags;
		private double runStopTarget;
		private double runTotalDistance;
		private double blockStopM;
		/** 上次发出去的**腿表**（含方向 —— 它同时也是 {@code legDelta} 的基准，所以必须带方向）。 */
		private List<MmtrMotionFrame.Leg> legs = List.of();

		private SentState(int slot) {
			this.slot = slot;
		}
	}
}
