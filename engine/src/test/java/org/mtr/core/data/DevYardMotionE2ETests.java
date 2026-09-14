package org.mtr.core.data;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mtr.core.mmtr.ConsistTypeRegistry;
import org.mtr.core.mmtr.ControlState;
import org.mtr.core.mmtr.point.MmtrPointRegistry;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.point.MmtrSwitch;
import org.mtr.core.mmtr.point.MmtrTurnout;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.operation.MmtrDriveControl;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3 slice 3 (end to end, real dev world): a vehicle spawned by the yard motion seam
 * ({@link Siding#spawnMmtrMotionVehicle}) on a REAL depot siding — parked, nothing baked — is driven
 * by a driver with the existing cab control out of the yard, across the real depot throat to the -96
 * turnout, halts there (its approach is not the one the point is set for — 用户 2026-09-13 决策 (a)：
 * 背向禁止通行、车停在岔前等道岔扳过来), and after a LIVE flip (through the simulator's authoritative
 * BranchStore, the same store mmtr-point-op mutates) crosses onto the elected real rail.
 * The test first discovers the yard siding whose rail graph connects to the -96 fork and pre-sets the
 * en-route turnouts so the run is deterministic; the -96 point itself is left at its default 0 (决策 (b)：
 * 一处道岔只有一个位置、默认 0，没有"未知态") so the live flip is what lets the vehicle through.
 */
public final class DevYardMotionE2ETests {

	private static final String CONSIST_JSON = "{"
		+ "\"consistTypes\":[{\"id\":\"emu\",\"controlMode\":\"NOTCHED\",\"powerNotches\":7,\"brakeNotches\":8,"
		+ "\"maxSpeedKmh\":120,\"maxManualSpeedKmh\":120,\"tractionAccelerationMps2\":0.6,\"serviceBrakeDecelerationMps2\":0.9,\"emergencyDecelerationMps2\":1.5}]"
		+ "}";
	private static final Path DEV_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/saves/新的世界/mtr");
	private static final long NX = -96, NY = -60, NZ = 76;

	/** BFS result from a yard mouth node toward the -96 node. */
	private static final class Route {
		final ObjectArrayList<Position> nodes = new ObjectArrayList<>(); // yard mouth ... -96
		double distanceM;
		/** Pre-set turnout operators: {nodeX, nodeY, nodeZ, viaHex, op} for every en-route fork. */
		final ObjectArrayList<String[]> forkOps = new ObjectArrayList<>();
		boolean feasible = true;
	}

	private static final class RouteNode {
		final Position from;
		final Rail rail;

		RouteNode(Position from, Rail rail) {
			this.from = from;
			this.rail = rail;
		}
	}

	/** BFS over the real rail graph; shortest first arrival at the -96 node. */
	private static Route planToMinus96(Simulator sim, Position startNode, Position target) {
		final Object2ObjectOpenHashMap<Position, RouteNode> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<Position> queue = new ObjectArrayList<>();
		queue.add(startNode);
		prev.put(startNode, new RouteNode(null, null));
		Position targetNode = null;
		while (!queue.isEmpty()) {
			final Position node = queue.remove(0);
			if (node.equals(target)) {
				targetNode = node;
				break;
			}
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
			if (neighbors == null) {
				continue;
			}
			neighbors.forEach((other, rail) -> {
				if (!prev.containsKey(other)) {
					prev.put(other, new RouteNode(node, rail));
					queue.add(other);
				}
			});
		}
		if (targetNode == null) {
			return null;
		}
		final Route route = new Route();
		Position cursor = targetNode;
		while (cursor != null) {
			route.nodes.add(0, cursor);
			final RouteNode rn = prev.get(cursor);
			cursor = rn == null || rn.from == null ? null : rn.from;
		}
		for (int i = 1; i < route.nodes.size(); i++) {
			route.distanceM += prev.get(route.nodes.get(i)).rail.railMath.getLength();
		}
		// Forks at every intermediate node: figure out the operator that keeps the walker on the path.
		for (int i = 1; i + 1 < route.nodes.size(); i++) {
			final Position node = route.nodes.get(i);
			final Rail incoming = prev.get(node).rail;
			final Position approach = route.nodes.get(i - 1);
			final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
			if (forwards.size() >= 2) {
				final Rail desired = prev.get(route.nodes.get(i + 1)).rail;
				final int op = branchOperator(sim, approach, node, forwards, desired);
				if (op < 0) {
					route.feasible = false;
					return route;
				}
				route.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
			}
		}
		return route;
	}

	private static ObjectArrayList<Rail> forwardRails(Simulator sim, Position node, Rail current) {
		final ObjectArrayList<Rail> out = new ObjectArrayList<>();
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
		if (neighbors != null) {
			neighbors.forEach((other, rail) -> {
				if (rail != current) {
					out.add(rail);
				}
			});
		}
		return out;
	}

	/** -1 when the desired next rail is not the walker's branch0/branch1 choice at this fork. */
	private static int branchOperator(Simulator sim, Position approachNode, Position forkNode, ObjectArrayList<Rail> forwards, Rail desired) {
		final double ax = forkNode.getX() - approachNode.getX();
		final double az = forkNode.getZ() - approachNode.getZ();
		final double[] cos = new double[forwards.size()];
		for (int i = 0; i < forwards.size(); i++) {
			final Position other = otherEndOf(sim, forkNode, forwards.get(i));
			if (other == null) {
				return -1;
			}
			final double bx = other.getX() - forkNode.getX();
			final double bz = other.getZ() - forkNode.getZ();
			final double la = Math.sqrt(ax * ax + az * az);
			final double lb = Math.sqrt(bx * bx + bz * bz);
			cos[i] = la == 0 || lb == 0 ? -2 : (ax * bx + az * bz) / (la * lb);
		}
		int b0 = 0;
		for (int i = 1; i < cos.length; i++) {
			if (cos[i] > cos[b0]) {
				b0 = i;
			}
		}
		int b1 = b0 == 0 ? 1 : 0;
		for (int i = 0; i < cos.length; i++) {
			if (i != b0 && cos[i] > cos[b1]) {
				b1 = i;
			}
		}
		if (forwards.get(b0) == desired) {
			return 0;
		}
		if (forwards.get(b1) == desired) {
			return 1;
		}
		return -1;
	}

	private static Position otherEndOf(Simulator sim, Position node, Rail rail) {
		final Position[] found = {null};
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
		if (neighbors != null) {
			neighbors.forEach((other, r) -> {
				if (found[0] == null && r == rail && !other.equals(node)) {
					found[0] = other;
				}
			});
		}
		return found[0];
	}

	private static final class YardCandidate {
		final Siding siding;
		final Route route;
		final MmtrMotionWalker yardWalker;

		YardCandidate(Siding siding, Route route, MmtrMotionWalker yardWalker) {
			this.siding = siding;
			this.route = route;
			this.yardWalker = yardWalker;
		}
	}

	@Test
	public void realYardDepartureToMinus96WithLiveFlip() {
		Assumptions.assumeTrue(Files.isDirectory(DEV_MTR_ROOT), "dev world save not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_MTR_ROOT, false);
		sim.mmtrConsistTypes = ConsistTypeRegistry.parse(CONSIST_JSON);
		sim.mmtrDefaultConsistTypeId = "emu";
		final Position minus96 = new Position(NX, NY, NZ);
		final BranchStore store = sim.mmtrPointBranches;
		final ObjectArrayList<VehicleCar> probeCars = new ObjectArrayList<>();
		probeCars.add(new VehicleCar("probe", 2, 1, 10, 0, 1, 0.1, 0.1));

		// Discover the depot yard siding whose mouth can reach the -96 fork through feasible turnouts.
		final YardCandidate[] best = {null};
		final int[] candidates = {0};
		sim.sidings.forEach(siding -> {
			siding.setVehicleCars(probeCars); // probe template so the yard walker can size the parking
			final MmtrMotionWalker walker = siding.mmtrMotionWalkerFromYard(null, store, null);
			if (walker == null) {
				return;
			}
			final Route route = planToMinus96(sim, walker.aheadNode(), minus96);
			if (route == null || !route.feasible || route.nodes.size() < 3) {
				return;
			}
			candidates[0]++;
			if (best[0] == null || route.distanceM < best[0].route.distanceM) {
				best[0] = new YardCandidate(siding, route, walker);
			}
		});
		Assumptions.assumeTrue(best[0] != null, "no real yard siding reaches the -96 fork through feasible turnouts (" + candidates[0] + " candidate(s) found)");
		final YardCandidate chosen = best[0];

		// The -96 node is a multi-rail junction; the yard route approaches it over its own last rail
		// (which may differ from the switch registry's via). It must be a real fork from this approach
		// (>= 2 forward rails) for the live-flip stage to apply.
		final Position lastFrom = chosen.route.nodes.get(chosen.route.nodes.size() - 2);
		final String viaHex = railBetween(sim, lastFrom, minus96).getHexId();
		final ObjectArrayList<Rail> forwardsAtMinus96 = forwardRails(sim, minus96, railBetween(sim, lastFrom, minus96));
		Assumptions.assumeTrue(forwardsAtMinus96.size() >= 2, "-96 arrival from this yard approach is not a fork (" + forwardsAtMinus96.size() + " forward rail(s))");
		final Rail straightestFromYard = straightestRail(sim, lastFrom, minus96, forwardsAtMinus96);
		Assumptions.assumeTrue(straightestFromYard != null, "could not rank the -96 forward rails");

		// Pre-set every en-route turnout (the -96 fork stays unset for the live flip).
		for (final String[] op : chosen.route.forkOps) {
			if (Long.parseLong(op[0]) == NX && Long.parseLong(op[1]) == NY && Long.parseLong(op[2]) == NZ) {
				continue;
			}
			store.set(Long.parseLong(op[0]), Long.parseLong(op[1]), Long.parseLong(op[2]), op[3], Integer.parseInt(op[4]));
		}

		// Dispatch through the yard motion seam (clear any save-time parked stock first).
		chosen.siding.clearParkedVehicles();
		final Vehicle vehicle = chosen.siding.spawnMmtrMotionVehicle(chosen.yardWalker);
		Assumptions.assumeTrue(vehicle != null, "yard seam could not dispatch on the chosen siding (yard not idle?)");
		assertTrue(vehicle.isMmtrMotion(), "vehicle runs in live Motion-Core mode from the real yard");

		final UUID driver = UUID.randomUUID();
		final ObjectArrayList<VehicleRidingEntity> entities = new ObjectArrayList<>();
		entities.add(new VehicleRidingEntity(driver, 0, 0, 0, 0, false, true, true, false, false, false, false));
		vehicle.updateRidingEntities(entities);
		new MmtrDriveControl(vehicle.getId(), new ControlState().setThrottleNotch(3).setReverser(1), driver).apply(sim);
		assertTrue(vehicle.isMmtrManualOverride(), "drive command must hold the override");

		// Drive out of the yard to the -96 fork (" + Math.round(chosen.route.distanceM) + " m planned).
		boolean arrived = false;
		for (int i = 0; i < 2000 && !arrived; i++) {
			chosen.siding.simulateVehicles(1000, null);
			arrived = vehicle.getMmtrMotionWalker().haltedAtAuthority() && viaHex.equals(vehicle.getMmtrMotionWalker().railHex());
		}
		assertTrue(arrived, "yard-departed vehicle must reach the -96 fork its approach is not open for and wait (rail=" + vehicle.getMmtrMotionWalker().railHex() + " progress=" + Math.round(vehicle.getRailProgress()) + ")");
		assertTrue(vehicle.getRailProgress() > chosen.route.distanceM - 40, "progress must cover the planned route, got " + Math.round(vehicle.getRailProgress()) + " vs " + Math.round(chosen.route.distanceM));

		// LIVE flip: 决策 (a)「车停在岔前，等道岔扳过来」——人工把这一侧的进路扳通，同一列车才通过，
		// 而且是走在**道岔开通的那条轨**（从这一进向看最直的那根）上。
		store.set(NX, NY, NZ, viaHex, 0);
		boolean crossed = false;
		for (int i = 0; i < 500 && !crossed; i++) {
			chosen.siding.simulateVehicles(1000, null);
			crossed = straightestFromYard.getHexId().equals(vehicle.getMmtrMotionWalker().railHex());
		}
		assertTrue(crossed, "yard-departed vehicle must cross the -96 fork onto the straightest real rail after the live flip (rail=" + vehicle.getMmtrMotionWalker().railHex() + ")");
		final MmtrTurnout turnout = sim.mmtrTurnout(NX, NY, NZ);
		if (turnout != null) {
			// 一处道岔只有一个位置：刚才那次扳动必须真的把道岔扳到了能开通这一侧的位置
			final int position = sim.mmtrTurnoutPosition(NX, NY, NZ);
			assertEquals(straightestFromYard.getHexId(), turnout.continuationFrom(viaHex, position),
				"the flipped point must be open for this approach (position " + position + ")");
			final int otherPosition = position == MmtrTurnout.REVERSE ? MmtrTurnout.NORMAL : MmtrTurnout.REVERSE;
			assertNotEquals(turnout.prohibitedRailHex(position), turnout.prohibitedRailHex(otherPosition), "the two positions prohibit different rails");
		}
		assertTrue(vehicle.getIsOnRoute(), "crossed vehicle is on route");

		// Cleanup: remove the departed vehicle so reruns start from an idle yard.
		chosen.siding.removeVehicleById(vehicle.getId());
	}

	private static Rail railBetween(Simulator sim, Position a, Position b) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(a);
		if (neighbors != null) {
			final Rail[] found = {null};
			neighbors.forEach((other, rail) -> {
				if (found[0] == null && other.equals(b)) {
					found[0] = rail;
				}
			});
			return found[0];
		}
		return null;
	}

	/** The forward rail with the largest continuation cosine from the given approach (walker branch 0). */
	private static Rail straightestRail(Simulator sim, Position approachNode, Position forkNode, ObjectArrayList<Rail> forwards) {
		Rail best = null;
		double bestCos = -2;
		final double ax = forkNode.getX() - approachNode.getX();
		final double az = forkNode.getZ() - approachNode.getZ();
		for (final Rail rail : forwards) {
			final Position other = otherEndOf(sim, forkNode, rail);
			if (other == null) {
				continue;
			}
			final double bx = other.getX() - forkNode.getX();
			final double bz = other.getZ() - forkNode.getZ();
			final double la = Math.sqrt(ax * ax + az * az);
			final double lb = Math.sqrt(bx * bx + bz * bz);
			final double cos = la == 0 || lb == 0 ? -2 : (ax * bx + az * bz) / (la * lb);
			if (cos > bestCos) {
				bestCos = cos;
				best = rail;
			}
		}
		return best;
	}

	private static MmtrSwitch devSwitch(Simulator sim) {
		final ObjectArrayList<MmtrSwitch> all = MmtrPointRegistry.discover(sim);
		for (final MmtrSwitch s : all) {
			if (s.nodeX == NX && s.nodeY == NY && s.nodeZ == NZ) {
				return s;
			}
		}
		return null;
	}
}
