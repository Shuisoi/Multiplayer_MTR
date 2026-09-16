package org.mtr.core.data;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.point.MmtrPointAuthority;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.mmtr.signal.MmtrSignalAspect;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 acceptance (design §2「信号 = 进路 × 闭塞」): the display aspect of a rail is the occupancy
 * chain, narrowed by a SET main route and held at danger while a route over it is still PENDING.
 * The rule lives in {@link MmtrSignalAspect} and is shared by the ops feed and (once mirrored) the
 * in-game renderer, replacing the two duplicated "at a fork every branch counts" walks.
 *
 * <p>Network: entry E (-20..0) -> fork N (0) -> {straight S (0..60) -> S2 (60..120) | diverge D
 * (0..60,+20)}. A signal protecting E sees depth 1 = E, 2 = S/D, 3 = S2.</p>
 */
public final class MmtrSignalAspectTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static final class Net {
		final Simulator sim;
		final Rail entry;
		final Rail straight;
		final Rail diverge;
		final Rail beyond;
		final Position a = new Position(-20, 0, 0);
		final Position fork = new Position(0, 0, 0);
		final Position b = new Position(60, 0, 0);
		final Position c = new Position(60, 0, 20);

		Net(String savePath) {
			sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
			entry = through(a, fork);
			straight = through(fork, b);
			diverge = through(fork, c);
			beyond = through(b, new Position(120, 0, 0));
			sim.rails.add(entry);
			sim.rails.add(straight);
			sim.rails.add(diverge);
			sim.rails.add(beyond);
			sim.sync();
			// ④: a fork nobody has decided shows danger (no route through the junction can be set). The
			// real server presets EVERY (fork, approach) pair to operator branch 0
			// (Simulator.mmtrDefaultPointsZero), so preset them here too - otherwise these "free driving"
			// nets would all read red.
			sim.positionsToRail.get(fork).forEach((otherEnd, rail) ->
				sim.mmtrPointBranches.set(fork.getX(), fork.getY(), fork.getZ(), rail.getHexId(), 0));
		}

		/** Mark a rail occupied the way a standing train does (manual block -> CURRENTLY_RESERVE). */
		void occupy(Rail rail) {
			occupyArcInTrees(sim, rail, 0, rail.railMath.getLength());
		}

		MmtrSignalAspect aspect() {
			return new MmtrSignalAspect(sim, sim.mmtrRoutes);
		}

		/** Publish and set a route over the given rails; {@code withFork} grants the fork leg 0 (straight). */
		MmtrRoute setRoute(long vehicleId, MmtrRoute.Kind kind, boolean withFork, Rail... rails) {
			final ObjectArrayList<String> hexes = new ObjectArrayList<>();
			for (final Rail rail : rails) {
				hexes.add(rail.getHexId());
			}
			final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
			if (withFork) {
				forks.add(new String[]{String.valueOf(fork.getX()), String.valueOf(fork.getY()), String.valueOf(fork.getZ()), entry.getHexId(), "0"});
			}
			final MmtrRoute route = sim.mmtrRoutes.request(new MmtrRoute(vehicleId, "v" + vehicleId, kind, hexes, forks, rails[rails.length - 1].getHexId(), 1000));
			if (withFork) {
				sim.mmtrPointAuthority.request(fork.getX(), fork.getY(), fork.getZ(), entry.getHexId(), "v" + vehicleId, 0, sim.getCurrentMillis() + 60_000);
			}
			sim.mmtrRoutes.refresh(vehicleId, sim.mmtrPointAuthority);
			return route;
		}
	}

	@Test
	public void occupancyChainGivesRedYellowAndDoubleYellow() {
		final Net n = new Net("build/mmtr-aspect-chain");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, n.aspect().aspectOf(n.entry.getHexId()), "clear line shows green");

		n.occupy(n.entry);
		assertEquals(MmtrSignalAspect.Aspect.RED, n.aspect().aspectOf(n.entry.getHexId()), "the protected rail itself occupied is red");

		final Net n2 = new Net("build/mmtr-aspect-chain2");
		n2.occupy(n2.straight);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n2.aspect().aspectOf(n2.entry.getHexId()), "one rail beyond occupied is single yellow");

		/*
		 * n3（notes/166 R4 按新语义改写）：Net 是**无灯站场**，而新模型里"没有任何信号灯的连通块
		 * 整块是一个大区间"（用户 2026-09-15 裁定）—— straight 与 beyond 之间只有一个度=2 的接头、
		 * 没有灯，所以它们属于**同一个**行车区间。"再往前一段"这一档在无灯区**不存在**：
		 * 占住 beyond 与占住 straight 读出来是同一个深度（单黄，不是双黄）。
		 *
		 * 一句话：**三档显示需要三盏灯**。要双黄请看带灯的两段/三段夹具
		 * （`theChainCountsBlocksBetweenLamps` 与 `MmtrSectionServiceTests` 的锚点用例）。
		 */
		final Net n3 = new Net("build/mmtr-aspect-chain3");
		n3.occupy(n3.beyond);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n3.aspect().aspectOf(n3.entry.getHexId()),
			"无灯站场：straight 与 beyond 是同一个大区间，占住它读单黄（不是双黄）");
	}

	@Test
	public void withoutARouteEveryForkBranchCounts() {
		final Net n = new Net("build/mmtr-aspect-fork");
		n.occupy(n.diverge);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n.aspect().aspectOf(n.entry.getHexId()),
			"free driving: the occupied diverging branch is seen from the entry signal (conservative rule)");
	}

	@Test
	public void aSetMainRouteNarrowsTheForkToItsOwnPath() {
		final Net n = new Net("build/mmtr-aspect-route");
		n.occupy(n.diverge);
		final MmtrRoute route = n.setRoute(1, MmtrRoute.Kind.MAIN, true, n.entry, n.straight, n.beyond);
		assertTrue(route.isEstablished(), "the route is SET (its fork is granted)");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, n.aspect().aspectOf(n.entry.getHexId()),
			"the interlocking locked the straight path: the occupied diverging branch no longer affects this signal");

		// The route's own path stays protected: an occupied rail ON the route is still seen.
		n.occupy(n.straight);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n.aspect().aspectOf(n.entry.getHexId()),
			"an occupied rail on the set route still gives a caution");
	}

	@Test
	public void aPendingRouteHoldsItsEntrySignalAtDanger() {
		final Net n = new Net("build/mmtr-aspect-pending");
		final ObjectArrayList<String> hexes = new ObjectArrayList<>();
		hexes.add(n.entry.getHexId());
		hexes.add(n.straight.getHexId());
		final ObjectArrayList<String[]> forks = new ObjectArrayList<>();
		forks.add(new String[]{String.valueOf(n.fork.getX()), String.valueOf(n.fork.getY()), String.valueOf(n.fork.getZ()), n.entry.getHexId(), "0"});
		final MmtrRoute route = n.sim.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, hexes, forks, n.straight.getHexId(), 1000));
		n.sim.mmtrRoutes.refresh(1, n.sim.mmtrPointAuthority);
		assertFalse(route.isEstablished(), "no grant yet");
		assertEquals(MmtrSignalAspect.Aspect.RED, n.aspect().aspectOf(n.entry.getHexId()),
			"a movement waiting outside its signal sees red even though the track is clear");
	}

	@Test
	public void aShuntRouteDoesNotClearTheMainHead() {
		final Net n = new Net("build/mmtr-aspect-shunt");
		n.occupy(n.diverge);
		final MmtrRoute route = n.setRoute(1, MmtrRoute.Kind.SHUNT, true, n.entry, n.straight, n.beyond);
		assertTrue(route.isEstablished(), "the shunt route is set");
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, n.aspect().aspectOf(n.entry.getHexId()),
			"a subsidiary aspect authorises the shunt with the main head still at danger - it narrows nothing");
	}

	/**
	 * A2 on a 折返 (setback / flip) route: the movement runs over the SAME rail twice (out and back),
	 * so the route's rail list contains it twice. The narrowing must pick the occurrence that matches
	 * the direction being walked - picking the wrong one would make the signal follow the path the
	 * train has already travelled.
	 *
	 * <p>Layout: E(-20..0) -&gt; N(0) -&gt; S(0..60) -&gt; M(60) -&gt; {S2(60..120) | D(60 -&gt; 120,+20)}.
	 * Route E, S, S2, S, E (out over S to S2, back over S to E). With BOTH the diverging D and the
	 * return target E occupied, the signal protecting S walked OUTBOUND from N must still be green:
	 * the locked path there is S2.</p>
	 */
	@Test
	public void aRouteThatRunsOverARailTwiceNarrowsPerDirection() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-aspect-doubled"), false);
		final Position a = new Position(-20, 0, 0);
		final Position n = new Position(0, 0, 0);
		final Position m = new Position(60, 0, 0);
		final Rail entry = through(a, n);
		final Rail s = through(n, m);
		final Rail s2 = through(m, new Position(120, 0, 0));
		final Rail d = through(m, new Position(120, 0, 20));
		sim.rails.add(entry);
		sim.rails.add(s);
		sim.rails.add(s2);
		sim.rails.add(d);
		sim.sync();
		/*
		 * notes/166 R5：这个夹具原来**无灯** —— 新模型下"没有信号灯的连通块整块是一个大区间"，
		 * 于是 entry 与 s 属于**同一段**（它们之间没有灯），"占住 entry 而 s 那盏灯仍单黄"这个前提
		 * 就不成立了（实测读红，而且那是对的）。按用例本意补两盏朝东的灯（MTR 角 270 = 东）：
		 *   n 处那盏守 s（本用例问的就是"保护 s 的那架信号"）；
		 *   m 处那盏守它面朝那一侧的**全部**腿（s2 与 d —— 一灯多腿，用户 2026-09-10 的"守整个咽喉"），
		 *   于是"岔股被占"能从 n 那盏灯的链上看到（depth 2 = 单黄）。
		 */
		sim.mmtrSignals.put((int) n.getX(), (int) n.getY(), (int) n.getZ(), 270, 4, "AUTO", "");
		sim.mmtrSignals.put((int) m.getX(), (int) m.getY(), (int) m.getZ(), 270, 4, "AUTO", "");
		// ④: decide the fork at M on every approach the way the real server's default preset does, so the
		// free driving signal is not held at danger for an undecided turnout.
		sim.positionsToRail.get(m).forEach((otherEnd, rail) ->
			sim.mmtrPointBranches.set(m.getX(), m.getY(), m.getZ(), rail.getHexId(), 0));

		occupy(sim, d);
		occupy(sim, entry);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, new MmtrSignalAspect(sim, sim.mmtrRoutes).aspectFrom(s.getHexId(), n),
			"no route: the occupied diverging branch is seen from the signal protecting S");

		final ObjectArrayList<String> rails = new ObjectArrayList<>();
		rails.add(entry.getHexId());
		rails.add(s.getHexId());
		rails.add(s2.getHexId());
		rails.add(s.getHexId());
		rails.add(entry.getHexId());
		final MmtrRoute route = sim.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, rails, new ObjectArrayList<>(), entry.getHexId(), 1000));
		sim.mmtrRoutes.refresh(1, sim.mmtrPointAuthority);
		assertTrue(route.isEstablished(), "a route without turnouts is set");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, new MmtrSignalAspect(sim, sim.mmtrRoutes).aspectFrom(s.getHexId(), n),
			"the locked path outbound is S2: neither the diverging branch nor the return target affects this signal");
	}

	private static void occupy(Simulator sim, Rail rail) {
		occupyArcInTrees(sim, rail, 0, rail.railMath.getLength());
	}

	/** 把 {@code rail} 的 {@code [fromM, toM)} 标成被占，写进调用方指定的那份占用树。 */
	private static void occupyArcIn(Rail rail, double fromM, double toM,
			ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		final Position[] ordered = rail.mmtrOrderedPositions();
		Data.put(trees.get(1), ordered[0], ordered[1],
			vehiclePosition -> {
				final VehiclePosition value = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				value.addSegment(fromM, toM, 999_999_004L);
				return value;
			}, Object2ObjectAVLTreeMap::new);
	}

	/**
	 * 占住 L1 的一段 —— **写进调用方指定的那份占用树**。
	 *
	 * <p>notes/166 R6/R7：占用只有占用树这一份来源，而有的用例是拿一份**局部**树去断言的
	 * （{@code new MmtrSignalAspect(sim, sim.mmtrRoutes, trees)}）。旧代码读的是全局的"预留信号色"
	 * 通道，所以"写进 sim 的树、却拿局部树断言"看不出来；新模型下那等于**根本没占**（实测：期望单黄读成绿）。</p>
	 */
	private static void occupySection(Rail rail, org.mtr.core.mmtr.signal.MmtrSectionService.TrackSpan span,
			ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees) {
		final Position[] ordered = rail.mmtrOrderedPositions();
		Data.put(trees.get(1), ordered[0], ordered[1],
			vehiclePosition -> {
				final VehiclePosition value = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				value.addSegment(span.arcFromM, span.arcToM, 999_999_004L);
				return value;
			}, Object2ObjectAVLTreeMap::new);
	}

	/**
	 * ④ 显示层: the signal now agrees with the motion rules (①/②/③). A block whose far end is a junction
	 * that cannot be cleared - another consist still fouls the junction's clearance zone, or (for a
	 * junction with NO physical-turnout model) nobody has decided the points - shows DANGER instead of
	 * green. A vehicle standing far away on the same rail does NOT restrict the junction (the clearance
	 * zone is only the first 10 m of each leg).
	 *
	 * <p>用户 2026-09-13 决策 (b) 之后，"没人决定"对**单开道岔**不再成立：一处道岔只有一个位置、只有
	 * 0 或 1、默认 0 —— 没有"未知态"可言（旧行为见 notes/115）。所以下面 (1) 是一次 45° 单开道岔
	 * （已定 → 绿），(1b) 是一个**没有物理道岔模型**的 120° 三岔口（三角线：没人决定 ⇒ 仍旧危险）。</p>
	 */
	@Test
	public void aTurnoutPositionDecidesTheJunctionAndAnUndecidedWyeStillHoldsDanger() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-aspect-junction"), false);
		final Position a = new Position(-20, 0, 0);
		final Position n = new Position(0, 0, 0);
		final Rail entry = through(a, n);
		final Rail straight = through(n, new Position(20, 0, 0));
		final Rail diverge = Rail.newRail(n, Angle.fromAngle(45), new Position(20, 0, 12), Angle.fromAngle(225), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
		sim.rails.add(entry);
		sim.rails.add(straight);
		sim.rails.add(diverge);
		sim.sync();

		// (1) 45° 单开道岔 = 一处物理道岔，位置默认 0（正线贯通）= **已决定** → 不压红。
		assertNotNull(sim.mmtrTurnout(n.getX(), n.getY(), n.getZ()), "the 45° fork must be recognised as one physical turnout");
		assertEquals(0, sim.mmtrTurnoutPosition(n.getX(), n.getY(), n.getZ()), "决策 (b)：一处道岔只有一个位置，默认 0");
		assertEquals(MmtrSignalAspect.Aspect.GREEN, new MmtrSignalAspect(sim, sim.mmtrRoutes).aspectOf(entry.getHexId()),
			"道岔位置已定（默认 0）→ 不能因为「这个岔口没人决定」而压红");

		// (1b) 120° 三岔口（三角线）：几何上不是单开道岔 —— 它有"岔尖"（从任一根看另外两根都在前方），
		// 但更直的那根进路也只到 cos 0.5（偏 60°），超过 45° 的门槛 ⇒ 三条线在一个点上交汇，
		// 现实里要用两组可动件（三开道岔）才做得出来。没有物理道岔模型 ⇒ 仍旧走老规则 ——
		// 没有任何人工位/授权 ⇒ 危险。
		final Simulator wye = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-aspect-junction-wye"), false);
		final Position c = new Position(0, 0, 0);
		final Rail wyeEntry = through(new Position(-20, 0, 0), c);
		final Rail armUp = Rail.newRail(c, Angle.fromAngle(60), new Position(17, 0, 30), Angle.fromAngle(240), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
		final Rail armDown = Rail.newRail(c, Angle.fromAngle(300), new Position(17, 0, -30), Angle.fromAngle(120), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
		wye.rails.add(wyeEntry);
		wye.rails.add(armUp);
		wye.rails.add(armDown);
		wye.sync();
		assertNull(wye.mmtrTurnout(c.getX(), c.getY(), c.getZ()), "a 120° wye is NOT a single turnout");
		assertEquals(MmtrSignalAspect.Aspect.RED, new MmtrSignalAspect(wye, wye.mmtrRoutes).aspectOf(wyeEntry.getHexId()),
			"没人决定的三岔口仍旧把守着进路的信号压红");
		wye.positionsToRail.get(c).forEach((otherEnd, rail) ->
			wye.mmtrPointBranches.set(c.getX(), c.getY(), c.getZ(), rail.getHexId(), 0));
		assertEquals(MmtrSignalAspect.Aspect.GREEN, new MmtrSignalAspect(wye, wye.mmtrRoutes).aspectOf(wyeEntry.getHexId()),
			"人工位把它定下来 → 线路空闲即绿");

		// (2) A consist still fouling the junction's clearance zone: the zone is the first 10 m of every
		// leg, so an occupancy at arc 3..5 of the straight leg blocks the junction.
		final ObjectArrayList<Object2ObjectAVLTreeMap<Position, Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = new ObjectArrayList<>();
		trees.add(new Object2ObjectAVLTreeMap<>());
		trees.add(new Object2ObjectAVLTreeMap<>());
		Data.put(trees.get(1), straight.mmtrOrderedPositions()[0], straight.mmtrOrderedPositions()[1],
			vehiclePosition -> {
				final VehiclePosition newVehiclePosition = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				newVehiclePosition.addSegment(3, 5, 999_999_005L);
				return newVehiclePosition;
			}, Object2ObjectAVLTreeMap::new);
		assertEquals(MmtrSignalAspect.Aspect.RED, new MmtrSignalAspect(sim, sim.mmtrRoutes, trees).aspectOf(entry.getHexId()),
			"a consist inside the clearance zone holds the signal protecting the approach rail at danger");

		// (3) With the junction decided and NO occupancy in its clearance zone, the same signal falls back
		// to the ordinary chain: an occupied block one section ahead is a caution, not a junction danger.
		trees.get(1).clear();
		/*
		 * notes/166 R7：占的是**清限区之外**那一段（岔口那侧前 10 m 属清限区）。
		 * 第一版把整根轨都标成占用 ⇒ 前 10 m 落在清限区里 ⇒ ④ 规则把岔口判成"清不掉" ⇒ 读**红**
		 * （那是规则正确、夹具写错）。这里占 12..20 m：既不在清限区，又足够占到"够算占用"（重叠 8 m）。
		 */
		occupyArcIn(straight, 12, 20, trees);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, new MmtrSignalAspect(sim, sim.mmtrRoutes, trees).aspectOf(entry.getHexId()),
			"a decided junction with the next block occupied is the ordinary single yellow");
	}

	/**
	 * B3b, re-expressed for 闭塞区间 v2: the chain counts BLOCKS. Under v2 a block is what one lamp
	 * protects, so a train standing beyond the NEXT lamp leaves the signal protecting the near block at a
	 * caution - it must not paint it red (B2/S3 already let the movement run up to that signal). Without a
	 * second lamp the whole rail is one block, so the same occupancy reads red.
	 *
	 * <p>Changed from the v1 case on purpose (notes/109): v1 put both "sections" on one rail by cutting it
	 * at a mid-rail lamp. v2 is direction-aware - a lamp protects the rail it LOOKS along - so the same
	 * two-block reading is now created by a SECOND LAMP, which is what actually happens on a map. The
	 * occupancy is also written into the shared occupancy trees, because the v2 chain reads them (the same
	 * source S1's own stop uses); the v1 reserved-colour channel alone no longer decides the aspect.</p>
	 */
	@Test
	public void theChainCountsBlocksBetweenLamps() {
		// Two lamps on the rail: the first protects [0, 100), the second protects [100, 200).
		final Simulator sim = splitRailSim("build/mmtr-aspect-sections", true);
		final Rail longRail = sim.rails.stream().filter(rail -> rail.railMath.getLength() > 100).findFirst().orElseThrow();
		assertEquals(2, sim.mmtrSections.trackSectionsOf(longRail.getHexId()).size(), "一盏轨中段的灯把轨切成 2 个轨道区间（L1）");
		assertEquals(2, sim.mmtrSections.allSections().size(), "and v2 sees two lamp-to-lamp blocks");
		final MmtrSignalAspect aspect = new MmtrSignalAspect(sim, sim.mmtrRoutes);
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectFrom(longRail.getHexId(), new Position(0, 0, 0)), "clear: green");

		// Occupancy beyond the NEXT lamp (the second block) is a caution for the first lamp, not red.
		occupyArcInTrees(sim, longRail, 120, 160);
		assertEquals(MmtrSignalAspect.Aspect.SINGLE_YELLOW, aspect.aspectFrom(longRail.getHexId(), new Position(0, 0, 0)),
			"a train in the block beyond the next lamp shows a caution at the first lamp");

		// Occupancy inside the first lamp's own block is red.
		occupyArcInTrees(sim, longRail, 10, 60);
		assertEquals(MmtrSignalAspect.Aspect.RED, aspect.aspectFrom(longRail.getHexId(), new Position(0, 0, 0)),
			"the protected block itself occupied is red");
	}

	/**
	 * S4 safety net: the v2 chain reads the shared occupancy TREES, so a hold that only ever went through
	 * the v1 per-section reserved-colour channel must not read as green. The v1 walk still speaks when v2
	 * finds nothing, and the more restrictive answer wins - a signal must never clear because the two
	 * occupancy channels disagree.
	 */
	@Test
	public void aReservedColourOnlyHoldStillShowsDangerOnAV2Rail() {
		final Simulator sim = splitRailSim("build/mmtr-aspect-colour-only", true);
		final Rail longRail = sim.rails.stream().filter(rail -> rail.railMath.getLength() > 100).findFirst().orElseThrow();
		final MmtrSignalAspect aspect = new MmtrSignalAspect(sim, sim.mmtrRoutes);
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectFrom(longRail.getHexId(), new Position(0, 0, 0)),
			"nothing held: green");

		// Reserve the near block's colour the v1 way, with NO footprint in the trees.
		final org.mtr.core.mmtr.signal.MmtrSectionService.TrackSpan nearSpan = sim.mmtrSections.trackSpanAt(longRail.getHexId(), 50);
		assertNotNull(nearSpan, "近侧那一段");
		occupySection(longRail, nearSpan, sim.mmtrOccupancyTrees());
		assertEquals(MmtrSignalAspect.Aspect.RED, aspect.aspectFrom(longRail.getHexId(), new Position(0, 0, 0)),
			"the v1 colour channel alone still holds the signal at danger");
	}

	/** Write a foreign footprint on {@code rail} between two arcs into the simulator's own occupancy trees. */
	private static void occupyArcInTrees(Simulator sim, Rail rail, double fromM, double toM) {
		final ObjectArrayList<it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<Position, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap<Position, VehiclePosition>>> trees = sim.mmtrOccupancyTrees();
		assertNotNull(trees, "the simulator must have occupancy trees");
		final Position[] ordered = rail.mmtrOrderedPositions();
		Data.put(trees.get(1), ordered[0], ordered[1],
			vehiclePosition -> {
				final VehiclePosition value = vehiclePosition == null ? new VehiclePosition() : vehiclePosition;
				value.addSegment(fromM, toM, 999_999_005L);
				return value;
			}, it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap::new);
	}

	/** Entry rail (-20..0) + a 200 m rail, optionally cut in two by a wayside light at arc 100. */
	private static Simulator splitRailSim(String savePath, boolean withSignal) {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get(savePath), false);
		final Rail entry = through(new Position(-20, 0, 0), new Position(0, 0, 0));
		final Rail longRail = through(new Position(0, 0, 0), new Position(200, 0, 0));
		sim.rails.add(entry);
		sim.rails.add(longRail);
		sim.sync();
		if (withSignal) {
			// Two lamps facing EAST along the rail: one at its near node, one at arc 100. Under the v2
			// directional model a lamp protects the rail it looks along, so a SECOND LAMP (not a mid-rail
			// light beside the track) is what creates the next block.
			sim.mmtrSignals.put(0, 0, 0, 270, 2, "set", longRail.getHexId());
			final org.mtr.core.tool.Vector middle = longRail.railMath.getPosition(100, false);
			sim.mmtrSignals.put((int) Math.floor(middle.x()), (int) Math.floor(middle.y()), (int) Math.floor(middle.z()), 270, 2, "set", longRail.getHexId());
		}
		return sim;
	}

	@Test
	public void unknownAndEmptyRailsAreSafe() {
		final Net n = new Net("build/mmtr-aspect-unknown");
		final MmtrSignalAspect aspect = n.aspect();
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectOf(null));
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectOf(""));
		assertEquals(MmtrSignalAspect.Aspect.GREEN, aspect.aspectOf("0000000000000000-0000000000000000"));
		assertEquals(4, aspect.aspectsForAllRails().size(), "one aspect per drawn rail");
	}
}
