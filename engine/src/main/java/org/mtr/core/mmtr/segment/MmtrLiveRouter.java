package org.mtr.core.mmtr.segment;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;

/**
 * Live, turnout-authoritative runtime router (M2-Core slice A/B). Unlike the legacy one-shot
 * route baked into a {@code Vehicle} at dispatch (via {@code SidingPathFinder}), this walks the
 * real rail graph <em>one node at a time</em>: on every reached node it enumerates the forward
 * continuations of the approach rail, classifies the straightest (branch0) vs diverging
 * (branch1) using the same cosine rule as {@code MmtrPointRegistry.discover}, and elects the next
 * rail via {@link MmtrNodeRouter} from the current operator branch / task target. A fork with no
 * authority set and no task target halts the route (the train must wait — it is never auto-routed).
 *
 * <p>This replaces "decide at path-generation time, then drive a baked route" with "decide at the
 * node, live" — the foundation of 自由开. Movement physics onto each elected rail is the Vehicle
 * layer's job later; this router produces the ordered rail sequence to follow.</p>
 */
public final class MmtrLiveRouter {

	public enum Status {
		ON_ROUTE, AT_TARGET, AWAITING_AUTHORITY, END_OF_LINE, MAX_STEPS
	}

	/** The ordered rail sequence a live route follows, plus where/why it stopped. */
	public static final class Route {
		public final ObjectArrayList<String> railHexOrder = new ObjectArrayList<>();
		public Status status;
		/** Node key (x,y,z) where the route halted, when status is AWAITING_AUTHORITY. */
		public @Nullable String haltNodeKey;

		Route() {
			this.status = Status.ON_ROUTE;
			this.haltNodeKey = null;
		}

		Route finish(Status status, @Nullable String haltNodeKey) {
			this.status = status;
			this.haltNodeKey = haltNodeKey;
			return this;
		}
	}

	private MmtrLiveRouter() {
	}

	/**
	 * @param data the rail graph.
	 * @param startRail the rail the consist starts on.
	 * @param startAt one endpoint of {@code startRail}; travel proceeds toward its other end.
	 * @param targetRailHex optional rail to stop on (task terminal / destination siding rail).
	 * @param branches operator-set turnout branches.
	 * @param maxSteps safety cap on nodes walked.
	 */
	public static Route route(Data data, Rail startRail, Position startAt, @Nullable String targetRailHex, BranchStore branches, int maxSteps) {
		final Route route = new Route();
		route.railHexOrder.add(startRail.getHexId());
		if (startRail.getHexId().equals(targetRailHex)) {
			return route.finish(Status.AT_TARGET, null);
		}

		// The node we travel toward = the far end of startRail.
		final Object2ObjectOpenHashMap<Position, Rail> startNeighbors = data.positionsToRail.get(startAt);
		if (startNeighbors == null) {
			return route.finish(Status.END_OF_LINE, null);
		}
		Position node = null;
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : startNeighbors.object2ObjectEntrySet()) {
			if (e.getValue() == startRail) {
				node = e.getKey();
				break;
			}
		}
		if (node == null) {
			return route.finish(Status.END_OF_LINE, null);
		}

		Position prevNode = startAt;
		Rail currentRail = startRail;
		final Position[] haltNode = {null};

		for (int step = 0; step < maxSteps; step++) {
			if (currentRail.getHexId().equals(targetRailHex)) {
				return route.finish(Status.AT_TARGET, null);
			}
			final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
			if (neighbors == null) {
				return route.finish(Status.END_OF_LINE, null);
			}

			// Forwards = continuations of the approach rail at this node.
			final ObjectArrayList<Rail> forwardRails = new ObjectArrayList<>();
			final ObjectArrayList<Position> forwardEnds = new ObjectArrayList<>();
			for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
				if (e.getValue() != currentRail) {
					forwardRails.add(e.getValue());
					forwardEnds.add(e.getKey());
				}
			}

			final Rail next;
			if (forwardRails.isEmpty()) {
				return route.finish(Status.END_OF_LINE, null);
			} else if (forwardRails.size() == 1) {
				next = forwardRails.get(0);
			} else {
				final @Nullable Rail elected = electRailAtFork(node, prevNode, forwardRails, forwardEnds, currentRail, targetRailHex, branches, haltNode);
				if (elected == null) {
					return route.finish(Status.AWAITING_AUTHORITY, haltNode[0] == null ? nodeKey(node) : nodeKey(haltNode[0]));
				}
				next = elected;
			}

			route.railHexOrder.add(next.getHexId());
			if (next.getHexId().equals(targetRailHex)) {
				return route.finish(Status.AT_TARGET, null);
			}

			// Cross onto the elected rail: its far end is the next node.
			Position nextNode = null;
			for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
				if (e.getValue() == next) {
					nextNode = e.getKey();
					break;
				}
			}
			prevNode = node;
			node = nextNode;
			currentRail = next;
		}
		return route.finish(Status.MAX_STEPS, null);
	}

	/**
	 * Classifies the continuations (straightest = branch0, nearest diverging = branch1) by the
	 * arrival direction's cosine and elects one via {@link MmtrNodeRouter}. Returns the elected
	 * rail, or null when a fork has no operator branch and no matching task target (authority
	 * required — the train waits).
	 */
	private static @Nullable Rail electRailAtFork(Position node, Position prevNode, ObjectArrayList<Rail> forwardRails, ObjectArrayList<Position> forwardEnds, Rail viaRail, @Nullable String taskTargetHex, BranchStore branches, Position[] haltNode) {
		final double ax = node.getX() - prevNode.getX();
		final double az = node.getZ() - prevNode.getZ();
		final double[] cos = new double[forwardRails.size()];
		for (int i = 0; i < forwardRails.size(); i++) {
			final double bx = forwardEnds.get(i).getX() - node.getX();
			final double bz = forwardEnds.get(i).getZ() - node.getZ();
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

		final MmtrNodeRouter.Continuation continuation = new MmtrNodeRouter.Continuation(forwardRails.get(b0).getHexId(), forwardRails.get(b1).getHexId());
		final @Nullable String chosenHex = MmtrNodeRouter.electFromStore(continuation, branches, node.getX(), node.getY(), node.getZ(), viaRail.getHexId(), taskTargetHex);
		if (chosenHex == null) {
			haltNode[0] = node;
			return null;
		}
		for (int i = 0; i < forwardRails.size(); i++) {
			if (forwardRails.get(i).getHexId().equals(chosenHex)) {
				return forwardRails.get(i);
			}
		}
		haltNode[0] = node;
		return null;
	}

	private static String nodeKey(Position p) {
		return p.getX() + "," + p.getY() + "," + p.getZ();
	}
}