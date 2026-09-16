package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.mmtr.signal.MmtrMovementAuthority;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 的 ① 与 ④：**红灯不停冒进 / 黄灯不停车** —— 这两条是**端到端守卫**，不是规则 (5) 的证据。
 *
 * <h3>写作时实测过的一件事（别把它读成规则 (5) 的验收）</h3>
 * <p>我原本以为"用预留信号色把灯压红 ⇒ 只有规则 (5) 会响"，于是拿它当规则 (5) 的隔离证据。
 * 写完做了个对照实验：<b>把规则 (5) 整条关掉再跑</b> —— 结果本类的两条用例<b>照样全绿</b>，
 * 而 {@code MmtrSignalAuthorityStopTests}（②③）两条**都红**（车一路走到 31.999 m 的区间边界，
 * 而不是被扣在出发信号附近）。</p>
 *
 * <p>也就是说：</p>
 * <ul>
 *   <li><b>① 这条不是规则 (5) 的证据</b>：旧的停车规则（下一区间被占 / 区间出口是未设道岔）本来就
 *       会在红灯前把车扣住 —— 修前就有的行为。它的价值是**回归守卫**：谁把红/黄搞反、
 *       或者让规则 (5) 越权覆盖了旧规则给出更远的目标，它会立刻红。</li>
 *   <li><b>真正隔离规则 (5) 的是 ②③</b>（进路 PENDING 那条通道）—— 那个实验就是它们的证据。</li>
 * </ul>
 *
 * <p>把这段留在代码里，是因为"这条用例到底证明了什么"比"它绿不绿"重要：
 * 一条绿着但证明不了任何东西的用例，比没有更糟（它会让人以为那件事已经被覆盖）。</p>
 *
 * <p>灯挂在两根轨的**起点节点**上（现实里灯就立在节点旁），面朝东（角度 270）：
 * 于是"我即将进入的那根轨"就是它守的那一段。</p>
 */
public final class MmtrRedLampStopTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	/** 面朝东（= +x）：notes/104 锁死的灯角度语义 南0/西90/北180/东270。 */
	private static final float FACING_EAST = 270;

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	/** 车场 → r1（一盏灯守在起点）→ r2（第二盏灯守在起点）。 */
	private static final class Corridor {
		final Simulator sim;
		final Rail r1;
		final Rail r2;
		final Siding siding;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		final double stopTargetM = 20.0 + 60.0 + 60.0 + 30.0;

		Corridor(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position yardBack = new Position(-30, 0, 0);
			final Position yardMouth = new Position(-10, 0, 0);
			final Rail y = Rail.newSidingRail(yardBack, Angle.fromAngle(0), yardMouth, Angle.fromAngle(0), Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
			r1 = through(yardMouth, new Position(50, 0, 0));
			r2 = through(new Position(50, 0, 0), new Position(110, 0, 0));
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			siding = new Siding(yardBack, yardMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Lamp Corridor Yard");
			depot.setCorners(new Position(-35, -3, -3), new Position(115, 3, 3));
			sim.rails.add(y);
			sim.rails.add(r1);
			sim.rails.add(r2);
			sim.depots.add(depot);
			sim.sidings.add(siding);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			siding.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			// 灯：两根轨各自的**起点节点**，面朝东
			addLamp(r1, 0);
			addLamp(r2, 0);
			siding.tick();
		}

		private void addLamp(Rail rail, double arcM) {
			final Vector position = rail.railMath.getPosition(arcM, false);
			sim.mmtrSignals.put((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()),
				FACING_EAST, 4, "AUTO", "");
		}

		/** 把这根轨的那个区间标成"别人占着"（预留信号色通道）—— **树里没有车**，只有规则 (5) 会因此响。 */
		void reserveSectionOf(Rail rail, long otherVehicleId) {
			/*
			 * notes/166 R4：占用只有一份来源 —— **占用树**。这里原来走"预留信号色"通道（B3b），
			 * 那条通道随 v1 整层删除；所以改成把外来车的足迹写进树里（车长 16 m 的量纲不变，
			 * 取整段弧窗足矣：占用判据要求重叠到车长的一半）。
			 */
			final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSpan span = sim.mmtrSections.trackSpanAt(rail.getHexId(), 10.0);
			assertNotNull(span, "这根轨上要有一个轨道区间（灯把它切出来了）");
			final it.unimi.dsi.fastutil.objects.ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = sim.mmtrOccupancyTrees();
			assertNotNull(trees, "占用树要在");
			final Position[] ordered = rail.mmtrOrderedPositions();
			Data.put(trees.get(1), ordered[0], ordered[1],
				vehiclePosition -> {
					final VehiclePosition value = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
					value.addSegment(span.arcFromM, span.arcToM, otherVehicleId);
					return value;
				}, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap::new);
		}

		Vehicle spawn() {
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "车场走行源要能建立");
			final Vehicle vehicle = siding.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion 车要能生成");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			siding.simulateVehicles(1000, trees);
		}
	}

	/**
	 * ① **红灯前停住、不冒进**（回归守卫，**不是**规则 (5) 的证据 —— 见类注释里的对照实验）。
	 *
	 * <p>红灯用**预留信号色**通道伪造（占用树里没有车），断言三件事：车没进那根被守的轨、
	 * 许可要求停车且理由写明红灯、扣住之后不再前进。</p>
	 */
	@Test
	public void aRedLampStopsTheTrainBeforeIt() {
		final Corridor n = new Corridor("build/mmtr-t3-red-lamp");
		n.reserveSectionOf(n.r1, 999_001L);   // 别人占着 r1 —— r1 起点那盏灯因此变红

		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		for (int i = 0; i < 400; i++) {
			n.tick();
		}

		assertFalse(n.r1.getHexId().equals(v.getMmtrMotionWalker().railHex()),
			"① 红灯在前 ⇒ 列车**不得进入 r1**（当前在 " + v.getMmtrMotionWalker().railHex() + "）");
		final MmtrMovementAuthority authority = v.mmtrMovementAuthority();
		assertNotNull(authority, "车要有行车许可");
		assertTrue(authority.mustStop(), "① 红灯构成停车义务：" + authority.reason);
		assertTrue(authority.reason.contains("红灯"), "理由要点明是红灯：" + authority.reason);

		final double held = v.getMmtrMotionWalker().distanceM();
		for (int i = 0; i < 200; i++) {
			n.tick();
		}
		assertEquals(held, v.getMmtrMotionWalker().distanceM(), 1e-6, "① 停在红灯前，不再冒进");
	}

	/**
	 * ④ **黄灯不停车**（只有注意义务）。
	 *
	 * <p>这是规则 (5) 的**方向性**守卫：把"信号控车"做错的最常见方式就是"黄灯也停" ——
	 * 那会让列车每过一盏黄灯就趴下，运营上完全不可用。占用放在**第二段**（r2）：
	 * 守在 r1 起点的那盏灯按链读到**单黄**，而 r1 本身是空的 ⇒ 车应当照常驶入 r1。</p>
	 *
	 * <p>（同 ①：关掉规则 (5) 它也不会红 —— 它守的是"别把黄灯做成停车"，不是"规则 (5) 生效"。）</p>
	 */
	@Test
	public void aYellowLampDoesNotStopTheTrain() {
		final Corridor n = new Corridor("build/mmtr-t3-yellow-lamp");
		n.reserveSectionOf(n.r2, 999_002L);   // r1 空、r2 被占 ⇒ r1 起点那盏灯 = 单黄

		final Vehicle v = n.spawn();
		v.setMmtrMotionAuto(true);
		v.setMmtrMotionStopTarget(n.stopTargetM, true);

		boolean sawYellowWithoutStopObligation = false;
		boolean enteredR1 = false;
		for (int i = 0; i < 400 && !enteredR1; i++) {
			n.tick();
			if (n.r1.getHexId().equals(v.getMmtrMotionWalker().railHex())) {
				enteredR1 = true;
			}
			final MmtrMovementAuthority authority = v.mmtrMovementAuthority();
			if (authority != null && authority.cautionOnly && !authority.mustStop()) {
				sawYellowWithoutStopObligation = true;
			}
		}

		assertTrue(enteredR1, "④ 单黄不构成停车义务 ⇒ 车照常驶入 r1（当前在 " + v.getMmtrMotionWalker().railHex() + "）");
		assertTrue(sawYellowWithoutStopObligation, "④ 途中确实读到过只注意、不停车的许可");
	}
}
