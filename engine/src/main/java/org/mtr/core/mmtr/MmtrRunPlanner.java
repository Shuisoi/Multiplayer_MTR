package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.consist.MmtrConsistWalker;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
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
	 * Whether the walker can leave {@code node} on {@code desired} after arriving on {@code incoming}
	 * from {@code approachNode}: a node with a single forward rail needs no turnout decision, a real fork
	 * must offer {@code desired} among its drivable legs (a doubling-back leg is not one).
	 */
	private static boolean turnoutAllows(Simulator sim, Position node, @Nullable Position approachNode, @Nullable Rail incoming, Rail desired) {
		if (incoming == null || desired == incoming || approachNode == null) {
			return true;
		}
		final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
		return forwards.size() < 2 || branchOperator(sim, approachNode, node, incoming, forwards, desired) >= 0;
	}

	/**
	 * The node the consist is really heading toward. A consist body's {@code aheadNode()} is the B side
	 * of its leading spine leg, which is only the travel direction when the train runs toward B; a train
	 * running A-end-first (the common yard parking: system key at the A cab) travels the other way, so
	 * its raw ahead/entry nodes are swapped. Planning from the wrong end made a parked locomotive's
	 * route start at the dead end of its own siding (实机 2026-09-09, aassdd).
	 */
	private static @Nullable Position travelAheadNode(MmtrMotionPosition walker) {
		if (walker instanceof final MmtrConsistWalker consistWalker && !consistWalker.travelsTowardB()) {
			return walker.enteredFromPosition();
		}
		return walker.aheadNode();
	}

	/** The node the consist is really coming from (see {@link #travelAheadNode}). */
	private static @Nullable Position travelEntryNode(MmtrMotionPosition walker) {
		if (walker instanceof final MmtrConsistWalker consistWalker && !consistWalker.travelsTowardB()) {
			return walker.aheadNode();
		}
		return walker.enteredFromPosition();
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
			// A mission that cannot arm retries every tick (and several trains can be waiting at
			// once), so report at most one flip plan every few seconds: the unconditional print
			// flooded the log at thousands of lines a second.
			final long now = System.currentTimeMillis();
			if (now - mmtrLastFlipPlanLogMillis >= FLIP_PLAN_LOG_INTERVAL_MILLIS) {
				mmtrLastFlipPlanLogMillis = now;
				System.out.println("[MMTR-RUN] planned via 尽头换向 flip @" + Math.round(viaFlip.flipCumulativeM) + "m (rail " + viaFlip.flipRailHex + ") - " + forward.reason);
			}
			return viaFlip;
		}
		final Plan viaSetback = planToRailViaSetback(sim, vehicle, targetRailHex, stopFraction);
		if (viaSetback.feasible) {
			final long now = System.currentTimeMillis();
			if (now - mmtrLastFlipPlanLogMillis >= FLIP_PLAN_LOG_INTERVAL_MILLIS) {
				mmtrLastFlipPlanLogMillis = now;
				System.out.println("[MMTR-RUN] planned via 牵出—推进 setback @" + Math.round(viaSetback.flipCumulativeM) + "m (rail " + viaSetback.flipRailHex + ") - " + forward.reason);
			}
			return viaSetback;
		}
		// An unplannable movement is an operator-visible event (a task will fail), so report all three
		// attempts' reasons once: the forward search, the terminal flip and the setback search.
		System.out.println("[MMTR-RUN] no plan for " + targetRailHex + ": forward=" + forward.reason + " | flip=" + viaFlip.reason + " | setback=" + viaSetback.reason);
		return forward;
	}

	/** How far short of the reversal node the train stops, so the walker does not cross onto the next rail. */
	private static final double SETBACK_EPS_M = 0.2;

	/** One search state: where the train is, which rail it arrived on, and whether it has reversed. */
	private static final class SetbackState {
		final Position node;
		final @Nullable Rail arrival;
		final int reversals;

		SetbackState(Position node, @Nullable Rail arrival, int reversals) {
			this.node = node;
			this.arrival = arrival;
			this.reversals = reversals;
		}

		String key() {
			return node.getX() + "," + node.getY() + "," + node.getZ() + "|" + (arrival == null ? "" : arrival.getHexId()) + "|" + reversals;
		}
	}

	/** How a {@link SetbackState} was reached: from where, along which rail, and by reversing there. */
	private static final class SetbackRec {
		final @Nullable SetbackState from;
		final @Nullable Rail rail;
		final boolean reversedHere;

		SetbackRec(@Nullable SetbackState from, @Nullable Rail rail, boolean reversedHere) {
			this.from = from;
			this.rail = rail;
			this.reversedHere = reversedHere;
		}
	}

	/**
	 * C10 牵出—推进 (pull out, then set back): when neither a straight run nor a terminal flip can reach
	 * the target, a real shunt reverses ONCE mid-route - the train runs past the junction into the lead,
	 * stops, changes ends and comes back into the branch that leads to the target. This is the normal way
	 * into a stub siding whose only entry faces the wrong way (实机 2026-09-09: the aassdd long track).
	 *
	 * <p>Search: BFS over (node, arrival rail, reversals used) with at most one reversal, where a
	 * reversal is a zero-length transition at a node onto a different rail (including back along the
	 * arrival rail). The plan is a normal plan plus {@code flipRailHex}/{@code flipCumulativeM} at the
	 * reversal point, so the existing vehicle-side flip handling stops the train there, changes ends and
	 * continues with the forks already preset for the second leg.
	 */
	private static Plan planToRailViaSetback(Simulator sim, Vehicle vehicle, String targetRailHex, double stopFraction) {
		final Plan plan = new Plan();
		plan.targetRailHex = targetRailHex;
		final org.mtr.core.mmtr.segment.MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			plan.reason = "setback: vehicle is not in live Motion-Core mode";
			return plan;
		}
		final Rail target = findRail(sim, targetRailHex);
		final Rail currentRail = findRail(sim, walker.railHex());
		final Position startNode = travelAheadNode(walker);
		if (target == null || currentRail == null || startNode == null) {
			plan.reason = "setback: walker or target rail unavailable";
			return plan;
		}
		if (currentRail == target) {
			plan.reason = "setback: target is the rail the vehicle is already on";
			return plan;
		}

		final Object2ObjectOpenHashMap<String, SetbackRec> prev = new Object2ObjectOpenHashMap<>();
		final ObjectArrayList<SetbackState> queue = new ObjectArrayList<>();
		final SetbackState start = new SetbackState(startNode, currentRail, 0);
		prev.put(start.key(), new SetbackRec(null, null, false));
		queue.add(start);
		SetbackState goalFrom = null;
		Position goalFar = null;
		boolean goalReversed = false;
		while (!queue.isEmpty() && goalFrom == null) {
			final SetbackState state = queue.remove(0);
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = sim.positionsToRail.get(state.node);
			if (neighbors == null) {
				continue;
			}
			final ObjectArrayList<SetbackState> nextStates = new ObjectArrayList<>();
			final ObjectArrayList<Rail> nextRails = new ObjectArrayList<>();
			final ObjectArrayList<Boolean> nextReversed = new ObjectArrayList<>();
			final Position approach = state.arrival == null ? null : otherEndOf(sim, state.node, state.arrival);
			neighbors.forEach((other, rail) -> {
				// A turnout only offers branches the walker can actually drive (a leg that doubles back
				// is a 人字 move and is not in the ordered legs). Validate every transition here, during
				// the search: aborting only after a route is reconstructed would reject the whole plan
				// even though a longer route - e.g. pull out past the fork and set back into it - is
				// perfectly drivable (实机 2026-09-09, the aassdd junction).
				if (rail != state.arrival && !turnoutAllows(sim, state.node, approach, state.arrival, rail)) {
					return;
				}
				if (rail != state.arrival) {
					nextStates.add(new SetbackState(other, rail, state.reversals));
					nextRails.add(rail);
					nextReversed.add(false);
				}
				if (state.reversals == 0 && !state.node.equals(startNode)) {
					// Reverse at this node (change ends) and depart along `rail` - which may be the rail
					// the train arrived on (backing out of the lead) or another branch at the junction.
					// Reversing AT THE START node is deliberately excluded: that is the "parked facing the
					// wrong way" case, which the mission handles by reversing the travel direction before
					// planning again - the forward search must never quietly route a parked train backwards
					// out of its own siding (see MmtrRunPlannerTests.plannerReachesTheYardRearOnlyThroughAnExplicitReversal).
					nextStates.add(new SetbackState(other, rail, 1));
					nextRails.add(rail);
					nextReversed.add(true);
				}
			});
			for (int i = 0; i < nextStates.size(); i++) {
				final SetbackState next = nextStates.get(i);
				if (nextRails.get(i) == target) {
					goalFrom = state;
					goalFar = next.node;
					goalReversed = nextReversed.get(i);
					break;
				}
				if (!prev.containsKey(next.key())) {
					prev.put(next.key(), new SetbackRec(state, nextRails.get(i), nextReversed.get(i)));
					queue.add(next);
				}
			}
		}
		if (goalFrom == null) {
			plan.reason = "setback: target rail " + targetRailHex + " is not reachable with one reversal";
			return plan;
		}

		// Reconstruct the rail traversal (goal edge last) and reverse it into run order.
		final ObjectArrayList<Rail> rails = new ObjectArrayList<>();
		final ObjectArrayList<Boolean> reversedAt = new ObjectArrayList<>();
		rails.add(target);
		reversedAt.add(goalReversed);
		SetbackState cursor = goalFrom;
		while (cursor != null) {
			final SetbackRec rec = prev.get(cursor.key());
			if (rec == null || rec.from == null) {
				break;
			}
			rails.add(rec.rail);
			reversedAt.add(rec.reversedHere);
			cursor = rec.from;
		}
		final ObjectArrayList<Rail> orderedRails = new ObjectArrayList<>();
		final ObjectArrayList<Boolean> orderedReversed = new ObjectArrayList<>();
		for (int i = rails.size() - 1; i >= 0; i--) {
			orderedRails.add(rails.get(i));
			orderedReversed.add(reversedAt.get(i));
		}

		// Node chain: startNode, then the far endpoint of every traversed rail.
		final ObjectArrayList<Position> nodes = new ObjectArrayList<>();
		nodes.add(startNode);
		for (int i = 0; i < orderedRails.size(); i++) {
			final Position from = nodes.get(i);
			final Position to = otherEndOf(sim, from, orderedRails.get(i));
			if (to == null) {
				plan.reason = "setback: rail endpoint missing in the route";
				return plan;
			}
			nodes.add(to);
		}
		if (!nodes.get(nodes.size() - 1).equals(goalFar)) {
			plan.reason = "setback: route reconstruction ended on the wrong node";
			return plan;
		}

		// Distances: the remainder of the current rail, then every traversed rail (the last one only
		// up to the stop fraction). The reversal stops SETBACK_EPS_M short of its node.
		final double toStartNodeM = Math.max(0, currentRail.railMath.getLength() - walker.offsetM());
		final double clamp = Math.max(0.0, Math.min(1.0, stopFraction));
		double travelledM = toStartNodeM;
		double flipAtM = -1;
		for (int i = 0; i < orderedRails.size(); i++) {
			if (orderedReversed.get(i)) {
				flipAtM = walker.distanceM() + travelledM - SETBACK_EPS_M;
			}
			travelledM += i == orderedRails.size() - 1 ? orderedRails.get(i).railMath.getLength() * clamp : orderedRails.get(i).railMath.getLength();
		}
		plan.stopCumulativeM = walker.distanceM() + travelledM;
		if (flipAtM > 0) {
			plan.flipCumulativeM = flipAtM;
			final int flipRailIndex = orderedReversed.indexOf(true);
			plan.flipRailHex = (flipRailIndex <= 0 ? currentRail : orderedRails.get(flipRailIndex - 1)).getHexId();
		}
		if (plan.stopCumulativeM <= walker.distanceM() + 1e-6) {
			plan.reason = "setback: stop would lie at or behind the vehicle position";
			return plan;
		}

		// Turnout decisions: the fork at each rail's entry node, in the order the walker will meet them.
		double cumulM = toStartNodeM;
		for (int i = 0; i < orderedRails.size(); i++) {
			final Position node = nodes.get(i);
			final Rail incoming = i == 0 ? currentRail : orderedRails.get(i - 1);
			final Rail desired = orderedRails.get(i);
			final Position approach = i == 0 ? travelEntryNode(walker) : nodes.get(i - 1);
			if (desired != incoming && approach != null) {
				final ObjectArrayList<Rail> forwards = forwardRails(sim, node, incoming);
				if (forwards.size() >= 2) {
					final int op = branchOperator(sim, approach, node, incoming, forwards, desired);
					if (op < 0) {
						plan.reason = "setback: turnout at " + node + " requires a branch outside the walker's choice";
						return plan;
					}
					plan.forkOps.add(new String[]{String.valueOf(node.getX()), String.valueOf(node.getY()), String.valueOf(node.getZ()), incoming.getHexId(), String.valueOf(op)});
					plan.forkMeters.add(walker.distanceM() + cumulM);
				}
			}
			cumulM += i == orderedRails.size() - 1 ? desired.railMath.getLength() * clamp : desired.railMath.getLength();
		}
		plan.feasible = true;
		plan.reason = "ok";
		return plan;
	}

	/** Minimum gap between two flip-plan reports, ms. */
	private static final long FLIP_PLAN_LOG_INTERVAL_MILLIS = 5000;
	private static long mmtrLastFlipPlanLogMillis;

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
		final Position startNode = travelAheadNode(walker);
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
		final Position behind = travelEntryNode(walker);
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
			final Position approach = i == 0 ? travelEntryNode(walker) : plan.nodes.get(i - 1);
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
		final Position startNode = travelAheadNode(walker);
		final Position entryEnd = travelEntryNode(walker);
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