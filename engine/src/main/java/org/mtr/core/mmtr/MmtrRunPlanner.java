package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionWalker;
import org.mtr.core.simulation.Simulator;

/**
 * MMTR (L3, slice 6): run planner for live Motion-Core vehicles — the engine-side counterpart of the
 * E2E yard test discovery, promoted to a service the task/ops layer calls for MOVE_TO-style steps.
 * Given a motion vehicle and a target real rail, it plans a graph route from the vehicle's current
 * position to a stop ON the target rail, decides the operator setting for every en-route turnout that
 * keeps the vehicle on the route (the same straightest/diverging cos ranking the walker and
 * MmtrNodeRouter use — the -96-style forks stay unset and live), and computes the cumulative stop
 * distance in the vehicle's own walker-distance space. Ops/tasks then simply arm
 * {@code setMmtrMotionAuto(true)} + {@code setMmtrMotionStopTarget(plan.stopCumulativeM, openDoors)}
 * and the vehicle runs itself there, forks elected exactly as planned.
 */
public final class MmtrRunPlanner {

	private static final class NodeRec {
		final Position from;
		final Rail rail;

		NodeRec(Position from, Rail rail) {
			this.from = from;
			this.rail = rail;
		}
	}

	public static final class Plan {
		public boolean feasible;
		public String reason = "unplanned";
		/** Node chain from the vehicle's current ahead node to the far end of the target rail. */
		public final ObjectArrayList<Position> nodes = new ObjectArrayList<>();
		/** Turnout operator settings: {nodeX, nodeY, nodeZ, viaHex, op} for every en-route fork. */
		public final ObjectArrayList<String[]> forkOps = new ObjectArrayList<>();
		/** Cumulative stop distance in the vehicle's walker space (head rests there). */
		public double stopCumulativeM = -1;
		public String targetRailHex = "";
	}

	private MmtrRunPlanner() {
	}

	/**
	 * Plans the run of {@code vehicle} to a stop on {@code targetRailHex}: {@code stopFraction} of the
	 * target rail's length from its entry end (1.0 = its far end). Infeasible when the vehicle is not
	 * in motion mode, the target rail is missing/unreachable, a needed turnout branch is not the
	 * walker's branch0/1 choice, or the stop lies at/before the vehicle's current position.
	 */
	public static Plan planToRail(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final MmtrMotionWalker walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		if (target == null) {
			plan.reason = "target rail " + targetRailHex + " not found";
			return plan;
		}
		final Position startNode = walker.aheadNode();
		final Rail currentRail = findRail(sim, walker.railHex());
		if (startNode == null || currentRail == null) {
			plan.reason = "walker has no current rail / ahead node";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "target rail is the rail the vehicle is already on; arm the stop distance directly";
			return plan;
		}

		// BFS over the real rail graph until either endpoint of the target rail is reached; the
		// reached endpoint is the ENTRY side, the far end is where the rail is fully traversed.
		final Position[] targetEnds = railEndpoints(sim, target);
		if (targetEnds[0] == null || targetEnds[1] == null) {
			plan.reason = "target rail endpoints not in graph";
			return plan;
		}
		final Object2ObjectOpenHashMap<Position, NodeRec> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<Position> queue = new ObjectArrayList<>();
		queue.add(startNode);
		prev.put(startNode, new NodeRec(null, null));
		Position entry = null;
		while (!queue.isEmpty()) {
			final Position node = queue.remove(0);
			if (node.equals(targetEnds[0]) || node.equals(targetEnds[1])) {
				entry = node;
				break;
			}
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(node);
			if (neighbors == null) {
				continue;
			}
			neighbors.forEach((other, rail) -> {
				if (!prev.containsKey(other)) {
					prev.put(other, new NodeRec(node, rail));
					queue.add(other);
				}
			});
		}
		if (entry == null) {
			plan.reason = "target rail " + targetRailHex + " is not reachable from the vehicle";
			return plan;
		}

		// Reconstruct the node chain startNode -> ... -> entry -> farEndOfTarget.
		final Position farEnd = entry.equals(targetEnds[0]) ? targetEnds[1] : targetEnds[0];
		Position cursor = entry;
		final ObjectArrayList<Position> reversed = new ObjectArrayList<>();
		while (cursor != null) {
			reversed.add(cursor);
			final NodeRec rec = prev.get(cursor);
			cursor = rec == null || rec.from == null ? null : rec.from;
		}
		for (int i = reversed.size() - 1; i >= 0; i--) {
			plan.nodes.add(reversed.get(i));
		}
		plan.nodes.add(farEnd);

		// Cumulative metres from the vehicle's current position to the stop: remainder of the current
		// rail + every planned rail up to the target entry + fraction of the target rail.
		double fromCurrentToStartNode = currentRail.railMath.getLength() - walker.offsetM();
		if (fromCurrentToStartNode < 0) {
			fromCurrentToStartNode = 0;
		}
		double plannedM = fromCurrentToStartNode;
		// Rails between consecutive nodes (nodes[0] == startNode): sum until reaching entry.
		for (int i = 1; i < plan.nodes.size(); i++) {
			final NodeRec rec = prev.get(plan.nodes.get(i));
			final Rail rail = plan.nodes.get(i).equals(farEnd) ? target : rec.rail;
			if (rail == null) {
				plan.reason = "route reconstruction failed";
				return plan;
			}
			plannedM += rail.railMath.getLength();
		}
		plan.stopCumulativeM = walker.distanceM() + plannedM - (1.0 - Math.max(0.0, Math.min(1.0, stopFraction))) * target.railMath.getLength();
		if (plan.stopCumulativeM <= walker.distanceM() + 1e-6) {
			plan.reason = "stop would lie at or behind the vehicle position";
			return plan;
		}

		// Turnout decisions at every node that has >= 2 forward rails (excluding the incoming rail).
		for (int i = 0; i + 1 < plan.nodes.size(); i++) {
			final Position node = plan.nodes.get(i);
			final Position approach = i == 0 ? walker.enteredFromPosition() : plan.nodes.get(i - 1);
			if (approach == null && i == 0) {
				plan.reason = "walker has no entry node for the first turnout";
				return plan;
			}
			final Rail incoming = i == 0 ? currentRail : (prev.get(plan.nodes.get(i)) == null ? currentRail : prev.get(plan.nodes.get(i)).rail);
			final Rail desired = plan.nodes.get(i + 1).equals(farEnd) ? target : prev.get(plan.nodes.get(i + 1)).rail;
			final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
			if (forwards.size() >= 2) {
				final int op = branchOperator(sim, approach, node, forwards, desired);
				if (op < 0) {
					plan.reason = "turnout at node requires a branch outside the walker's branch0/1 choice";
					return plan;
				}
				plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
			}
		}
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/**
	 * The real graph rail of the platform/siding with the given id, when drawn (null otherwise).
	 * Shared by the mission control op and the vehicle's mission self-arm.
	 */
	@Nullable
	public static Rail findSavedRailRail(Simulator simulator, long savedRailId) {
		final Rail[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] == null && siding.getId() == savedRailId) {
				found[0] = siding.mmtrGraphRail();
			}
		});
		if (found[0] == null) {
			simulator.platforms.forEach(platform -> {
				if (found[0] == null && platform.getId() == savedRailId) {
					found[0] = platform.mmtrGraphRail();
				}
			});
		}
		return found[0];
	}

	/** Applies a feasible plan's turnout presets into {@code store} (skips identical settings). */
	public static void applyForkOps(Plan plan, BranchStore store) {
		for (final String[] op : plan.forkOps) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String viaHex = op[3];
			final int branch = Integer.parseInt(op[4]);
			if (!store.contains(x, y, z, viaHex) || store.get(x, y, z, viaHex) != branch) {
				store.set(x, y, z, viaHex, branch);
			}
		}
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

	/** -1 when the desired next rail is not the walker's branch0/branch1 choice at this node. */
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

	@Nullable
	private static Rail findRail(Simulator sim, String hex) {
		if (hex == null || hex.isEmpty()) {
			return null;
		}
		final Rail[] found = {null};
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((q, rail) -> {
			if (found[0] == null && rail.getHexId().equals(hex)) {
				found[0] = rail;
			}
		}));
		return found[0];
	}

	private static Position[] railEndpoints(Simulator sim, Rail rail) {
		final Position[] found = new Position[2];
		sim.positionsToRail.forEach((pos, neigh) -> neigh.forEach((other, r) -> {
			if (r == rail) {
				if (found[0] == null) {
					found[0] = pos;
				} else if (!pos.equals(found[0]) && found[1] == null) {
					found[1] = pos;
				}
			}
		}));
		return found;
	}

	@Nullable
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
}
