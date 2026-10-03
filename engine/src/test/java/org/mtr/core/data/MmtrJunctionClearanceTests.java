package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ② 岔区清限 / 侧面防护: a section boundary sits ON the junction node, so the plain section test calls
 * the junction clear the moment a standing consist's tail leaves the node. The clearance zone keeps the
 * junction occupied until the tail is {@link Vehicle#MMTR_JUNCTION_CLEARANCE_M} past the node.
 *
 * <p>Network: west yards (12 m) -&gt; W(-20..0) -&gt; junction J(0) {E(0..40) -&gt; east yard(30 m)
 * | S(0 -&gt; 40,+12) -&gt; south yard}. Train 1 stands on E with its tail 8 m past J (inside the 10 m
 * clearance zone); train 2 approaches J from the second west yard and must be held at the boundary
 * until train 1 has cleared both the zone and E.</p>
 *
 * <p><b>为什么两条车走同一条进路</b>（判据修正后必须这样写，notes/130）：</p>
 * <ul>
 *   <li>一处道岔两条进路互斥（T1），岔口不可能同时给两条进路开门；</li>
 *   <li>修正之后人工扳岔多了"**车压在岔上不许扳**"这道闸（本类第二个用例），所以
 *       "车 1 压在清空区里、再把道岔扳去给车 2 开另一条路"这件事**根本做不成** —— 那正是这条闸的目的。</li>
 * </ul>
 * <p>于是清空区规则真正的守护对象是同一进路上的**后续车**：它必须停在岔前，直到前车出清这 10 m。
 * 修前这个夹具的岔口**没有物理模型**（岔股只偏 16.7°，正落在老判据返回 null 的那一带），
 * 所以"从岔股一侧开进岔口"看起来不需要道岔同意 —— 那正是缺口本身。</p>
 */
public final class MmtrJunctionClearanceTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"massKg\":60000,\"maxTractiveEffortN\":36000,\"serviceBrakeForceN\":54000,\"emergencyBrakeForceN\":90000}]"
		+ "}";

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail diverge(Position node, Position far) {
		return Rail.newRail(node, Angle.fromAngle(45), far, Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static Rail sidingRail(Position from, Position to) {
		final double bearing = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
		return Rail.newSidingRail(from, Angle.fromAngle((float) bearing), to, Angle.fromAngle((float) bearing),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, TransportMode.TRAIN);
	}

	private static final class JunctionNet {
		final Simulator sim;
		final Position j = new Position(0, 0, 0);
		final Rail w;
		final Rail e;
		final Rail s;
		final Siding westYard;
		/**
		 * 第二条西股道（在第一条更西边 12 m）。
		 *
		 * <p>为什么需要它：一条股道同时只能出一列车（第一列还在线上时，{@code mmtrMotionWalkerFromYard}
		 * 不再给第二列发走行器），而本用例要两列车先后从西侧开出、走同一条进路。</p>
		 */
		final Siding westYard2;
		final Siding southYard;
		/** 东股道：让车 1 能整列开出 E，把 E 让给车 2（否则同轨占用会把"放行"那一半挡住）。 */
		final Siding eastYard;
		final BranchStore store = new BranchStore();
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = newTrees();
		/** West yard 12 m + W 20 m: the arc at which E/S begin, seen from the west. */
		final double junctionFromWestM = 12.0 + 20.0;
		/** 第二条西股道 12 m + 第一条 12 m + W 20 m. */
		final double junctionFromWest2M = 12.0 + 12.0 + 20.0;

		JunctionNet(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			final Position westBack = new Position(-32, 0, 0);
			final Position westBack2 = new Position(-44, 0, 0);
			final Position westMouth = new Position(-20, 0, 0);
			final Position eastEnd = new Position(40, 0, 0);
			final Position eastBack = new Position(70, 0, 0);
			final Position southMouth = new Position(40, 0, 12);
			final Position southBack = new Position(52, 0, 12);
			final Rail westY = sidingRail(westBack, westMouth);
			final Rail westY2 = sidingRail(westBack2, westBack);
			final Rail eastY = sidingRail(eastEnd, eastBack);
			w = through(westMouth, j);
			e = through(j, eastEnd);
			s = diverge(j, southMouth);
			final Rail southY = sidingRail(southBack, southMouth);
			final Depot depot = new Depot(TransportMode.TRAIN, sim);
			westYard = new Siding(westBack, westMouth, 12, TransportMode.TRAIN, sim);
			westYard2 = new Siding(westBack2, westBack, 12, TransportMode.TRAIN, sim);
			eastYard = new Siding(eastEnd, eastBack, 30, TransportMode.TRAIN, sim);
			southYard = new Siding(southBack, southMouth, 12, TransportMode.TRAIN, sim);
			depot.setName("Junction Yard");
			depot.setCorners(new Position(-50, -3, -3), new Position(76, 3, 20));
			sim.rails.add(westY);
			sim.rails.add(westY2);
			sim.rails.add(eastY);
			sim.rails.add(w);
			sim.rails.add(e);
			sim.rails.add(s);
			sim.rails.add(southY);
			sim.depots.add(depot);
			sim.sidings.add(westYard);
			sim.sidings.add(westYard2);
			sim.sidings.add(eastYard);
			sim.sidings.add(southYard);
			final ObjectArrayList<VehicleCar> cars = new ObjectArrayList<>();
			cars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));
			westYard.setVehicleCars(cars);
			westYard2.setVehicleCars(cars);
			eastYard.setVehicleCars(cars);
			southYard.setVehicleCars(cars);
			sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
			sim.mmtrDefaultConsistTypeId = "emu";
			sim.sync();
			assertTrue(depot.savedRails.contains(westYard), "the west yard must attach to the depot");
			assertTrue(depot.savedRails.contains(westYard2), "the second west yard must attach to the depot");
			assertTrue(depot.savedRails.contains(eastYard), "the east yard must attach to the depot");
			assertTrue(depot.savedRails.contains(southYard), "the south yard must attach to the depot");
			westYard.tick();
			westYard2.tick();
			eastYard.tick();
			southYard.tick();
		}

		Vehicle spawn(Siding yard) {
			final MmtrMotionWalker walker = yard.mmtrMotionWalkerFromYard(null, store, null);
			assertNotNull(walker, "yard walker must resolve");
			final Vehicle vehicle = yard.spawnMmtrMotionVehicle(walker);
			assertNotNull(vehicle, "motion seam must spawn");
			return vehicle;
		}

		void tick() {
			trees.removeFirst();
			trees.add(new Object2ObjectAVLTreeMap<>());
			westYard.simulateVehicles(1000, trees);
			westYard2.simulateVehicles(1000, trees);
			eastYard.simulateVehicles(1000, trees);
			southYard.simulateVehicles(1000, trees);
		}

		void tickUntil(java.util.function.BooleanSupplier condition, int maxTicks) {
			for (int i = 0; i < maxTicks && !condition.getAsBoolean(); i++) {
				tick();
			}
			assertTrue(condition.getAsBoolean(), "condition not met within " + maxTicks + " ticks");
		}

		/** Operator sets the leg at J approached via W: 0 = straight (E), 1 = diverging (S). */
		void setLegViaW(int leg) {
			store.set(j.getX(), j.getY(), j.getZ(), w.getHexId(), leg);
		}

		/** 收尾：人工搬岔 = 覆盖 + **锁定**（notes/130），锁随存档落盘 —— 不清就会污染下一跑。 */
		void releaseOperatorHold() {
			final MmtrTurnout turnout = sim.mmtrTurnout(j.getX(), j.getY(), j.getZ());
			assertNotNull(turnout, "这个岔口现在是真道岔（有物理模型）");
			for (final String via : new String[]{turnout.stemRailHex, turnout.farRailHex, turnout.branchRailHex}) {
				sim.mmtrPointAuthority.unlock(j.getX(), j.getY(), j.getZ(), via);
			}
			sim.mmtrSetTurnoutPosition(j.getX(), j.getY(), j.getZ(), MmtrTurnout.NORMAL);
			sim.persistMmtrPointBranches();
		}
	}

	private static ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> newTrees() {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		return trees;
	}

	@Test
	public void aMovementIsHeldAtTheJunctionWhenAnotherConsistsTailIsStillInsideTheClearanceZone() {
		final JunctionNet n = new JunctionNet("build/mmtr-junction-clearance");
		n.setLegViaW(0);

		// Train 1 runs from the west yard onto E and stops with its tail 8 m past J - inside the 10 m
		// clearance zone.
		final Vehicle first = n.spawn(n.westYard);
		first.setMmtrMotionAuto(true);
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 10.0, true);
		n.tickUntil(first::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.e.getHexId(), first.getMmtrMotionWalker().railHex(), "train 1 stands on E");
		final double tailArcM = first.getMmtrMotionWalker().offsetM() - first.vehicleExtraData.getTotalVehicleLength();
		assertTrue(tailArcM < Vehicle.MMTR_JUNCTION_CLEARANCE_M, "train 1's tail is inside the clearance zone (arc " + tailArcM + ")");

		// Train 2 comes from the second west yard on the SAME route (W -> E): see the class comment for
		// why a second route cannot exist while train 1 fouls the zone. Only the clearance rule can stop it.
		final Vehicle second = n.spawn(n.westYard2);
		second.setMmtrMotionAuto(true);
		second.setMmtrMotionStopTarget(n.junctionFromWest2M + 26.0, true);
		n.tickUntil(() -> second.getSpeed() == 0 && second.isMmtrBlockHeldFromSync(), 4000);
		assertEquals(n.w.getHexId(), second.getMmtrMotionWalker().railHex(), "held on the approach, not on the junction");
		assertEquals(n.w.railMath.getLength() - 0.001, second.getMmtrMotionWalker().offsetM(), 0.05, "rests epsilon short of the junction node");
		assertFalse(n.e.getHexId().equals(second.getMmtrMotionWalker().railHex()), "never crossed the fouled junction");

		// Train 1 draws on through the junction and into the east yard, clearing the zone AND E:
		// the same held train 2 proceeds.
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 68.0, false);
		n.tickUntil(() -> first.getMmtrMotionWalker().offsetM() - first.vehicleExtraData.getTotalVehicleLength() > Vehicle.MMTR_JUNCTION_CLEARANCE_M, 4000);
		n.tickUntil(() -> n.e.getHexId().equals(second.getMmtrMotionWalker().railHex()), 4000);
		assertEquals(n.e.getHexId(), second.getMmtrMotionWalker().railHex(), "crosses the junction once the clearance zone is clear");
	}

	/**
	 * ④ 新闸门（用户 2026-09-14："车压在岔上就拒绝人工扳岔"）：把道岔从车下抽走是脱轨级事故，
	 * 所以人工扳岔在**岔区净空被占**时被拒，并给出可读原因；车出清以后照常受理。
	 *
	 * <p>判定与灯显示"岔区净空被占"读**同一段**代码（{@code MmtrJunctionState}），
	 * 否则会出现"这盏灯说岔区被占、道岔却照样能扳"这种自相矛盾的状态。本用例先断言显示层说"被占"，
	 * 再断言扳岔被拒 —— 两者必须一致。</p>
	 *
	 * <p>"压在岔上"按显示层同一条量纲：车体与 10 m 净空区的重叠要达到**半车长**（{@code foulsZone}
	 * 的松弛规则，notes/101 —— 目的是不让"尾巴刚擦到边"误报）。所以本用例让车 1 **骑在岔上**
	 * （车头刚过 J 1 m，整列车身盖住 W 侧那 10 m 净空区），而不是让尾巴擦过边界。</p>
	 */
	@Test
	public void operatorThrowIsRefusedWhileAConsistFoulsTheJunctionClearanceZone() {
		final JunctionNet n = new JunctionNet("build/mmtr-junction-clearance-guard");
		n.releaseOperatorHold(); // 归一化：位置回 0、解掉上一跑可能留下的锁（夹具目录是复用的）
		n.setLegViaW(0);
		final Vehicle first = n.spawn(n.westYard);
		first.setMmtrMotionAuto(true);
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 1.0, true); // 车头刚过 J：整列车骑在岔上
		n.tickUntil(first::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.e.getHexId(), first.getMmtrMotionWalker().railHex(), "车 1 的车头已经过岔");

		/*
		 * 闸门读的是**引擎自己那份**占用表（S1 与灯显示读的同一份）。夹具的 {@code trees} 是它私有的
		 * 副本，所以这里按引擎的写法把车 1 的足迹写进引擎那份（同 MmtrSignalAspectTests.occupyArcInTrees）。
		 */
		setSimulatorFootprint(n.sim, n.w, 1.0, n.w.railMath.getLength());
		final String displayReason = org.mtr.core.mmtr.signal.MmtrJunctionState.reason(n.sim, n.j, n.sim.mmtrOccupancyTrees());
		assertTrue(displayReason.contains("岔区净空被占"), "前提：显示层认这处岔区被占：" + displayReason);

		assertFalse(n.sim.mmtrSetPoint(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), 1), "车压在岔上 ⇒ 人工扳岔被拒");
		final String reason = n.sim.mmtrLastOperatorThrowBlockedReason();
		assertNotNull(reason, "要给出可读原因，而不是笼统的失败");
		assertTrue(reason.contains("岔区净空被占"), "原因要点名净空被占：" + reason);
		assertTrue(reason.contains("10.0 m"), "原因要点名多少米内：" + reason);
		assertEquals(0, n.sim.mmtrTurnoutPosition(n.j.getX(), n.j.getY(), n.j.getZ()), "被拒以后位置没动");
		assertFalse(n.sim.mmtrPointAuthority.isTurnoutLocked(n.j.getX(), n.j.getY(), n.j.getZ(), n.sim.mmtrTurnout(0, 0, 0)),
			"被拒不算「人工位」，不留下锁");

		// 车往前开、整列离开净空区 ⇒ 同样的扳岔照常受理（闸门不是「一律不许扳」）
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 30.0, false);
		n.tickUntil(() -> first.getMmtrMotionWalker().offsetM() > Vehicle.MMTR_JUNCTION_CLEARANCE_M + first.vehicleExtraData.getTotalVehicleLength(), 4000);
		clearSimulatorFootprints(n.sim);
		setSimulatorFootprint(n.sim, n.e, Vehicle.MMTR_JUNCTION_CLEARANCE_M + 1.0, first.getMmtrMotionWalker().offsetM());
		assertFalse(org.mtr.core.mmtr.signal.MmtrJunctionState.reason(n.sim, n.j, n.sim.mmtrOccupancyTrees()).contains("岔区净空被占"),
			"车开出净空区以后不再算被占");
		assertTrue(n.sim.mmtrSetPoint(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), 1), "车出清以后照常受理");
		assertEquals(1, n.sim.mmtrTurnoutPosition(n.j.getX(), n.j.getY(), n.j.getZ()), "位置扳到岔股（W↔S）");
		n.releaseOperatorHold();
	}

	/**
	 * **自动进路扳岔也有净空闸**（notes/130 §8 第 2 条遗留）：人工扳岔与意图扳岔早就有这道闸，
	 * 只有"跟着授权自动扳"这条路没有。
	 *
	 * <p>两条自动路都要拦：① 每 tick 的 {@code mmtrSyncTurnoutPositionsToGrants}（跟着授权折位置）；
	 * ② **授权申请本身**（T1 之后位置由持有者决定，所以新的持有者在申请那一步就会把位置改掉 ——
	 * 前车跨过岔口释放持有、尾巴却还压在净空区里时，道岔就在它脚下被换位了）。</p>
	 *
	 * <p>闸门与显示层读同一段代码，所以三条路（人工 / 意图 / 自动）对"能不能扳"给的是同一个答案。</p>
	 */
	@Test
	public void theAutomaticGrantPathsAreRefusedWhileAConsistFoulsTheJunction() {
		final JunctionNet n = new JunctionNet("build/mmtr-junction-auto-guard");
		n.releaseOperatorHold();
		n.setLegViaW(0);
		final Vehicle first = n.spawn(n.westYard);
		first.setMmtrMotionAuto(true);
		first.setMmtrMotionStopTarget(n.junctionFromWestM + 1.0, true);
		n.tickUntil(first::isMmtrMotionStoppedAtTarget, 4000);
		assertEquals(n.e.getHexId(), first.getMmtrMotionWalker().railHex(), "车 1 的车头已经过岔（整列骑在岔上）");

		final MmtrTurnout turnout = n.sim.mmtrTurnout(n.j.getX(), n.j.getY(), n.j.getZ());
		assertNotNull(turnout, "这个岔口是真道岔（有物理模型）");
		final int legToBranch = turnout.branchLeg.getOrDefault(n.w.getHexId(), -1);
		assertTrue(legToBranch >= 0 && turnout.positionForLeg(n.w.getHexId(), legToBranch) == MmtrTurnout.REVERSE,
			"夹具：从 W 进向开往岔股 S 要的就是位置 1");

		// 前车仍在净空区里（引擎那份占用表按引擎的写法写）
		setSimulatorFootprint(n.sim, n.w, 1.0, n.w.railMath.getLength());
		assertTrue(org.mtr.core.mmtr.signal.MmtrJunctionState.blockedThrowReason(n.sim, n.j) != null, "前提：岔区净空被占");

		// ① 另一条进路申请要位置 1 → 在道岔上排队，而不是把位置从车下改掉
		assertEquals(org.mtr.core.mmtr.point.MmtrPointAuthority.Result.QUEUED,
			n.sim.mmtrPointAuthority.request(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), "vOther", legToBranch,
				n.sim.getCurrentMillis() + 60_000),
			"净空被占时，改位置的申请要排队（修前这里直接 GRANTED 并把位置改掉）");
		assertEquals(MmtrTurnout.NORMAL, n.sim.mmtrTurnoutPosition(n.j.getX(), n.j.getY(), n.j.getZ()), "位置没被改");
		assertFalse(n.sim.mmtrPointAuthority.isGrantedTo(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), "vOther"),
			"被挡下时不留半个持有（T1b：要么全有、要么全无）");

		// ② 每 tick 的自动同步同样不扳
		n.sim.mmtrSyncTurnoutPositionsToGrants();
		assertEquals(MmtrTurnout.NORMAL, n.sim.mmtrTurnoutPosition(n.j.getX(), n.j.getY(), n.j.getZ()), "自动同步也不许扳");

		// ③ 请求方**自己**压在岔上不算被挡：它按着自己的位，本来就该能改自己的需要（否则换死自己）
		n.sim.mmtrPointAuthority.releaseAll("vOther");
		assertEquals(org.mtr.core.mmtr.point.MmtrPointAuthority.Result.GRANTED,
			n.sim.mmtrPointAuthority.request(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), "v999999005", legToBranch,
				n.sim.getCurrentMillis() + 60_000),
			"压着岔的那列车自己申请换位不算被挡（排除请求方自己）");

		// ④ 车出清净空区 → 自动同步照常把道岔扳到授权要的那一位
		clearSimulatorFootprints(n.sim);
		n.sim.mmtrPointAuthority.releaseAll("v999999005");
		n.sim.mmtrPointAuthority.request(n.j.getX(), n.j.getY(), n.j.getZ(), n.w.getHexId(), "vOther", legToBranch,
			n.sim.getCurrentMillis() + 60_000);
		n.sim.mmtrSyncTurnoutPositionsToGrants();
		assertEquals(MmtrTurnout.REVERSE, n.sim.mmtrTurnoutPosition(n.j.getX(), n.j.getY(), n.j.getZ()), "车出清以后照常扳");
		n.sim.mmtrPointAuthority.releaseAll("vOther");
		n.releaseOperatorHold();
	}

	/** 把一段足迹**覆盖式**写进引擎自己那份占用表（S1 与闸门读的就是它）。 */
	private static void setSimulatorFootprint(Simulator sim, Rail rail, double fromM, double toM) {
		setSimulatorFootprint(sim, rail, fromM, toM, 999_999_005L);
	}

	/** 同上，并指定这列车的 id（净空闸要能"排除请求方自己"）。 */
	private static void setSimulatorFootprint(Simulator sim, Rail rail, double fromM, double toM, long vehicleId) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = sim.mmtrOccupancyTrees();
		assertNotNull(trees, "引擎必须有占用表");
		final Position[] ordered = rail.mmtrOrderedPositions();
		Data.put(trees.get(1), ordered[0], ordered[1], vehiclePosition -> {
			final VehiclePosition value = new VehiclePosition();
			value.addSegment(fromM, toM, vehicleId);
			return value;
		}, Object2ObjectAVLTreeMap::new);
	}

	/** 清空引擎那份占用表（夹具不驱动引擎自己的 tick，所以清与写都由用例负责）。 */
	private static void clearSimulatorFootprints(Simulator sim) {
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = sim.mmtrOccupancyTrees();
		if (trees != null) {
			trees.get(1).clear();
		}
	}
}
