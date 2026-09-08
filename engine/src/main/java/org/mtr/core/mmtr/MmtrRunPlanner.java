package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
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
		/** Absolute walker-space distance of every {@link #forkOps} entry (parallel, same index) -
		 * the approach-locking layer requests a fork only once the train is near it. */
		public final ObjectArrayList<Double> forkMeters = new ObjectArrayList<>();
		/** Cumulative stop distance in the vehicle's walker space (head rests there). */
		public double stopCumulativeM = -1;
		public String targetRailHex = "";
		/**
		 * 尽头换向 (terminal flip): absolute walker distance at which the run reaches the dead end
		 * of {@link #flipRailHex} - the vehicle stops there and changes ends (换端) before the plan
		 * continues to {@link #stopCumulativeM}. {@code -1} = the plan needs no flip.
		 */
		public double flipCumulativeM = -1;
		/** Hex of the dead-end rail the run must flip (换端) at; empty when no flip is planned. */
		public String flipRailHex = "";
	}

	private MmtrRunPlanner() {
	}

	/**
	 * Plans the run of {@code vehicle} to a stop on {@code targetRailHex}: {@code stopFraction} of the
	 * target rail's length from its entry end (1.0 = its far end). Infeasible when the vehicle is not
	 * in motion mode, the target rail is missing/unreachable, a needed turnout branch is not the
	 * walker's branch0/1 choice, or the stop lies at/before the vehicle's current position.
	 *
	 * <p>When the straight-ahead plan is infeasible (target requires reversing 掉头), a second
	 * attempt plans the run through a terminal flip (尽头换向): forward to the dead end of the
	 * single-continuation corridor ahead, change ends there, run back and continue to the target.</p>
	 */
	public static Plan planToRail(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan forward = planToRailForward(sim, vehicle, targetRailHex, stopFraction);
		if (forward.feasible) {
			return forward;
		}
		final Plan viaFlip = planToRailViaDeadEndFlip(sim, vehicle, targetRailHex, stopFraction);
		if (viaFlip.feasible) {
			System.out.println("[MMTR-RUN] planned via 尽头换向 flip @" + Math.round(viaFlip.flipCumulativeM) + "m (rail " + viaFlip.flipRailHex + ") - " + forward.reason);
			return viaFlip;
		}
		return forward;
	}

	private static Plan planToRailForward(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
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
		final Position behind = walker.enteredFromPosition();
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
				// Real-yard fix (P3 real-machine): BFS is undirected, so from the CURRENT ahead node
				// it must never hop back onto the rail the walker came from - the train cannot reverse.
				// Real yards often continue behind the parked rail (extra leads), which used to let the
				// planner route "forward" trains backwards through the yard rear (halt at the real fork).
				if (node.equals(startNode) && behind != null && other.equals(behind)) {
					return;
				}
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
		// Each fork records its absolute walker-space distance (parallel with forkOps) so the
		// approach-locking layer can request it only when the train is actually near it.
		double cumulM = fromCurrentToStartNode;
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
				final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
				if (op < 0) {
					plan.reason = "turnout at node requires a branch outside the walker's branch0/1 choice";
					return plan;
				}
				plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
				plan.forkMeters.add(walker.distanceM() + cumulM);
			}
			// Advance the cumulative distance over the segment nodes[i] -> nodes[i+1].
			final Position nextNode = plan.nodes.get(i + 1);
			final Rail segmentRail = nextNode.equals(farEnd) ? target : prev.get(nextNode) == null ? null : prev.get(nextNode).rail;
			if (segmentRail != null) {
				cumulM += segmentRail.railMath.getLength();
			}
		}
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/**
	 * 尽头换向 fallback (real-junction 人字 rule): when the direct forward plan is infeasible the
	 * train can still reach a target behind it by driving to the dead end of the single-continuation
	 * corridor ahead (a real turnout cannot fold 180° at the crossing - only the near-straight arm
	 * is drivable), stopping there, changing ends (换端), and running back out. Produces a plan with
	 * {@code flipCumulativeM}/{@code flipRailHex} set; the vehicle flips once its head rests exactly
	 * at that dead end. Multi-rail corridors / corridors whose far end is not a dead end are refused
	 * (v1) with a reason.
	 */
	private static Plan planToRailViaDeadEndFlip(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "flip: vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		final Rail currentRail = findRail(sim, walker.railHex());
		if (target == null || currentRail == null) {
			plan.reason = target == null ? "flip: target rail " + targetRailHex + " not found" : "flip: current rail not found";
			return plan;
		}
		final Position[] targetEnds = railEndpoints(sim, target);
		if (targetEnds[0] == null || targetEnds[1] == null) {
			plan.reason = "flip: target rail endpoints not in graph";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "flip: target is the rail the vehicle is already on";
			return plan;
		}
		final Position startNode = walker.aheadNode();
		final Position entryEnd = walker.enteredFromPosition();
		if (startNode == null || entryEnd == null) {
			plan.reason = "flip: walker has no ahead/entry node";
			return plan;
		}
		final double nowM = walker.distanceM();
		final double remM = Math.max(0, currentRail.railMath.getLength() - walker.offsetM());

		// Resolve the flip corridor: where the train is heading, only the near-straight continuation
		// (legs) is drivable. A single continuation that ends in a true dead end is the flip rail.
		final Object2ObjectOpenHashMap<Position, Rail> startNeighbors = sim.positionsToRail.get(startNode);
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg> legsHere = startNeighbors == null || startNeighbors.isEmpty()
			? new ObjectArrayList<>()
			: org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(startNode, entryEnd, currentRail, startNeighbors,
				sim.mmtrJunctionLegs.get(startNode.getX(), startNode.getY(), startNode.getZ(), currentRail.getHexId()));
		final Rail flipRail;
		final Position deadEnd;
		final Position flipEntry;
		final double toFlipEndM;
		if (startNeighbors == null || startNeighbors.isEmpty() || legsHere.isEmpty()) {
			// The current rail itself dead-ends at its far node.
			flipRail = currentRail;
			deadEnd = startNode;
			flipEntry = entryEnd;
			toFlipEndM = remM;
		} else if (legsHere.size() == 1) {
			flipRail = findRail(sim, legsHere.get(0).railHex);
			if (flipRail == null) {
				plan.reason = "flip: corridor rail not in graph";
				return plan;
			}
			if (flipRail == target) {
				plan.reason = "flip: target rail is the flip-corridor rail itself";
				return plan;
			}
			final Position far = otherEndOf(sim, startNode, flipRail);
			if (far == null) {
				plan.reason = "flip: corridor rail endpoint missing";
				return plan;
			}
			deadEnd = far;
			flipEntry = startNode;
			toFlipEndM = remM + flipRail.railMath.getLength();
		} else {
			plan.reason = "flip: the corridor ahead forks (>=2 continuations) before any dead end - set a 进向表 entry or split the run";
			return plan;
		}

		// The far end of the flip rail must be a true dead end (nothing else joins it).
		final Object2ObjectOpenHashMap<Position, Rail> deadNeighbors = sim.positionsToRail.get(deadEnd);
		final boolean[] onlyFlipRail = {false};
		if (deadNeighbors != null && !deadNeighbors.isEmpty()) {
			deadNeighbors.forEach((other, rail) -> onlyFlipRail[0] = rail == flipRail);
		}
		if (deadNeighbors == null || deadNeighbors.size() != 1 || !onlyFlipRail[0]) {
			plan.reason = "flip: the far end of rail " + flipRail.getHexId() + " is not a dead end";
			return plan;
		}

		final double flipAtM = nowM + toFlipEndM;
		final double backM = flipRail.railMath.getLength();

		// After the flip the train runs the flip rail back to its entry and continues from there.
		// BFS from the entry node to the target, never re-entering the flip rail (it is the dead
		// end behind the train).
		final Object2ObjectOpenHashMap<Position, NodeRec> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<Position> queue = new ObjectArrayList<>();
		queue.add(flipEntry);
		prev.put(flipEntry, new NodeRec(null, null));
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
				if (rail == flipRail) {
					return; // the flip rail only leads back into the dead end
				}
				if (!prev.containsKey(other)) {
					prev.put(other, new NodeRec(node, rail));
					queue.add(other);
				}
			});
		}
		if (entry == null) {
			plan.reason = "flip: target rail " + targetRailHex + " is not reachable after the terminal flip";
			return plan;
		}
		final Position farEnd = entry.equals(targetEnds[0]) ? targetEnds[1] : targetEnds[0];
		Position cursor = entry;
		final ObjectArrayList<Position> reversed = new ObjectArrayList<>();
		while (cursor != null) {
			reversed.add(cursor);
			final NodeRec rec = prev.get(cursor);
			cursor = rec == null || rec.from == null ? null : rec.from;
		}
		final ObjectArrayList<Position> chain = new ObjectArrayList<>();
		for (int i = reversed.size() - 1; i >= 0; i--) {
			chain.add(reversed.get(i));
		}
		chain.add(farEnd);

		// Cumulative metres after the flip: back along the flip rail to its entry, then the BFS chain.
		double plannedM = 0;
		for (int i = 1; i < chain.size(); i++) {
			final NodeRec rec = prev.get(chain.get(i));
			final Rail rail = chain.get(i).equals(farEnd) ? target : rec == null ? null : rec.rail;
			if (rail == null) {
				plan.reason = "flip: route reconstruction failed";
				return plan;
			}
			plannedM += rail.railMath.getLength();
		}
		final double clamp = Math.max(0.0, Math.min(1.0, stopFraction));
		final double stopAbs = flipAtM + backM + plannedM - (1.0 - clamp) * target.railMath.getLength();
		if (stopAbs <= flipAtM + 1e-6) {
			plan.reason = "flip: stop would lie at or behind the flip point";
			return plan;
		}
		plan.stopCumulativeM = stopAbs;
		plan.flipCumulativeM = flipAtM;
		plan.flipRailHex = flipRail.getHexId();

		// Turnout decisions after the flip, in the same ordering the walker will elect at runtime
		// (approach node of the flip rail = the dead end).
		double cumulM = backM;
		for (int i = 0; i + 1 < chain.size(); i++) {
			final Position node = chain.get(i);
			final Position approach = i == 0 ? deadEnd : chain.get(i - 1);
			final Rail incoming = i == 0 ? flipRail : (prev.get(chain.get(i)) == null ? flipRail : prev.get(chain.get(i)).rail);
			final Rail desired = chain.get(i + 1).equals(farEnd) ? target : prev.get(chain.get(i + 1)) == null ? null : prev.get(chain.get(i + 1)).rail;
			if (desired == null) {
				plan.reason = "flip: fork reconstruction failed";
				return plan;
			}
			final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
			if (forwards.size() >= 2) {
				final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
				if (op < 0) {
					plan.reason = "flip: after the flip the turnout at the corridor entry requires a branch outside the walker's choice";
					return plan;
				}
				plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
				plan.forkMeters.add(flipAtM + cumulM);
			}
			final Position nextNode = chain.get(i + 1);
			final Rail segmentRail = nextNode.equals(farEnd) ? target : prev.get(nextNode) == null ? null : prev.get(nextNode).rail;
			if (segmentRail != null) {
				cumulM += segmentRail.railMath.getLength();
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

	/**
	 * P3: request every en-route turnout of a feasible plan through the {@link MmtrPointAuthority}
	 * under {@code owner} (approach locking - requests land before the vehicle arrives). Re-requesting
	 * refreshes the grant/queue window and is idempotent. Returns whether the owner currently holds
	 * every fork (all granted now; queued forks stay queued and the caller waits/retries).
	 */
	public static boolean requestForkOps(Plan plan, org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, long untilMillis) {
		return requestForkOps(plan.forkOps, authority, owner, untilMillis);
	}

	/** Request a specific set of fork ops (plan.forkOps or a still-pending subset of them). */
	public static boolean requestForkOps(ObjectArrayList<String[]> forkOps, org.mtr.core.mmtr.point.MmtrPointAuthority authority, String owner, long untilMillis) {
		boolean all = true; // an empty op set has nothing to wait on
		for (final String[] op : forkOps) {
			final long x = Long.parseLong(op[0]);
			final long y = Long.parseLong(op[1]);
			final long z = Long.parseLong(op[2]);
			final String viaHex = op[3];
			final int leg = Integer.parseInt(op[4]);
			if (authority.request(x, y, z, viaHex, owner, leg, untilMillis) != org.mtr.core.mmtr.point.MmtrPointAuthority.Result.GRANTED) {
				all = false;
			}
		}
		return all;
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

	/** Index of the desired next rail in the direction-ordered fork legs (same ordering the
	 * walker's electAtFork uses at runtime), or -1 when desired is not a candidate leg. */
	private static int branchOperator(Simulator sim, Position approachNode, Position forkNode, Rail incoming, ObjectArrayList<Rail> forwards, Rail desired) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(forkNode);
		if (neighbors == null || incoming == null || desired == null) {
			return -1;
		}
		final ObjectArrayList<org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg> legs = org.mtr.core.mmtr.point.MmtrPoint.computeOrderedLegs(forkNode, approachNode, incoming, neighbors,
			sim.mmtrJunctionLegs.get(forkNode.getX(), forkNode.getY(), forkNode.getZ(), incoming.getHexId()));
		for (int i = 0; i < legs.size(); i++) {
			if (legs.get(i).railHex.equals(desired.getHexId())) {
				return i;
			}
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
