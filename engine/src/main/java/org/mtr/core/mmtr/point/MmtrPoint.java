package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.simulation.Simulator;

/**
 * MMTR turnout system, P1: DIRECTION-AWARE turnout abstraction (design doc 道岔系统-方向感知与多级控制).
 * A turnout is still keyed by (node, approach rail), but its continuations are derived with respect
 * to the approach direction instead of "every other rail at the node, top-2 by cosine":
 * <ul>
 * <li>candidates are the distinct rails reachable at the node from the approach rail;</li>
 * <li>each candidate is classified by geometry into {@link LegKind} (straight / left / right /
 * other, y ignored);</li>
 * <li>legs are deterministically ordered (straight first, then left, right, other; cosine as the
 * tie-break inside a kind) so branch0/branch1 keep their meaning and future authority layers can
 * address legs by index;</li>
 * <li>the node gets a {@link Form}: pass-through (single continuation), fork (2 legs incl. a
 * straight), tee (2 legs, no straight - the horizontal-line + vertical-join case: the train can
 * only go left or right), multi (3+ legs, e.g. an X crossing where the straight continuation is
 * leg 0 and the cross rails are listed but need a policy/route lock to be taken), dead end.</li>
 * </ul>
 * The MTR rail model has no explicit one-way direction bit (only directional speed limits), so
 * direction here is derived per approach; a future one-way provider can plug into candidate
 * filtering. Runtime fork decisions still use the legacy top-2 store until the authority layer
 * (P3) adopts this ordering.
 */
public final class MmtrPoint {

	public enum LegKind { STRAIGHT, LEFT, RIGHT, OTHER }

	public enum Form { PASS_THROUGH, FORK, TEE, MULTI, DEAD_END }

	public static final class MmtrPointLeg {
		public final String railHex;
		public final long endX, endY, endZ;
		public final LegKind kind;
		public final double cos;

		public MmtrPointLeg(String railHex, long endX, long endY, long endZ, LegKind kind, double cos) {
			this.railHex = railHex;
			this.endX = endX;
			this.endY = endY;
			this.endZ = endZ;
			this.kind = kind;
			this.cos = cos;
		}

		public String key() {
			return railHex + "/" + kind;
		}

		@Override
		public String toString() {
			return kind + " " + railHex + " (cos " + Math.round(cos * 100.0) / 100.0 + ")";
		}
	}

	private static final double COS_STRAIGHT = 0.9;
	private static final double COS_TURN = 0.5;

	public final long nodeX, nodeY, nodeZ;
	public final String viaRailHex;
	public final ObjectArrayList<MmtrPointLeg> legs = new ObjectArrayList<>();
	public final Form form;

	private MmtrPoint(long nodeX, long nodeY, long nodeZ, String viaRailHex, ObjectArrayList<MmtrPointLeg> legs) {
		this.nodeX = nodeX;
		this.nodeY = nodeY;
		this.nodeZ = nodeZ;
		this.viaRailHex = viaRailHex;
		this.legs.addAll(legs);
		if (legs.isEmpty()) {
			form = Form.DEAD_END;
		} else if (legs.size() == 1) {
			form = Form.PASS_THROUGH;
		} else if (legs.size() == 2) {
			boolean hasStraight = false;
			for (final MmtrPointLeg leg : legs) {
				hasStraight |= leg.kind == LegKind.STRAIGHT;
			}
			form = hasStraight ? Form.FORK : Form.TEE;
		} else {
			form = Form.MULTI;
		}
	}

	/** The first continuation (leg 0) - legacy branch0 semantics. */
	public String branch0Hex() {
		return legs.isEmpty() ? null : legs.get(0).railHex;
	}

	/** The second continuation (leg 1) - legacy branch1 semantics. */
	public String branch1Hex() {
		return legs.size() < 2 ? null : legs.get(1).railHex;
	}

	public String key() {
		return nodeX + "," + nodeY + "," + nodeZ + "|" + viaRailHex;
	}

	@Override
	public String toString() {
		return key() + " form=" + form + " legs=" + legs;
	}

	/**
	 * Direction-aware discovery over the whole rail graph: for every (node, approach rail) pair with
	 * two or more distinct continuations the point is classified with ordered, direction-classified
	 * legs. Returns every junction point including pass-throughs (form filtering is left to callers).
	 */
	public static ObjectArrayList<MmtrPoint> discoverDirectionAware(Simulator sim) {
		final ObjectArrayList<MmtrPoint> out = new ObjectArrayList<>();
		sim.positionsToRail.forEach((node, neighbors) -> {
			neighbors.forEach((entryEnd, viaRail) -> {
				final ObjectArrayList<MmtrPointLeg> legs = computeOrderedLegs(node, entryEnd, viaRail, neighbors);
				if (!legs.isEmpty()) {
					out.add(new MmtrPoint(node.getX(), node.getY(), node.getZ(), viaRail.getHexId(), legs));
				}
			});
		});
		return out;
	}

	/**
	 * Continuations of {@code viaRail} at {@code node} for a train arriving from {@code entryEnd}
	 * (the approach rail's far end): distinct neighbour rails excluding the approach rail, each
	 * classified by the angle between the approach direction and the candidate direction.
	 */
	/**
	 * Ordered continuations of {@code viaRail} at {@code node} arriving from {@code entryEnd}: used by
	 * the runtime walker and the run planner so every layer shares the SAME deterministic ordering.
	 *
	 * <p>Direction judgment (真实道岔=人字): a turnout's two arms never connect directly - from the
	 * left arm a train can only leave through the stem (上), never across to the right arm. Any
	 * candidate whose far end lies BEHIND the vehicle's heading (cos &lt; 0: turn-back rails and
	 * obtuse cross-arm reaches) is excluded; a right-angle crossing/turn (cos = 0) and every forward
	 * fan direction stay legal continuations.</p>
	 */
	public static ObjectArrayList<MmtrPointLeg> computeOrderedLegs(Position node, Position entryEnd, Rail viaRail, Object2ObjectOpenHashMap<Position, Rail> neighbors) {
		final ObjectArrayList<MmtrPointLeg> legs = new ObjectArrayList<>();
		final double dx = node.getX() - entryEnd.getX();
		final double dz = node.getZ() - entryEnd.getZ();
		final double la = Math.sqrt(dx * dx + dz * dz);
		neighbors.forEach((farEnd, rail) -> {
			if (rail == viaRail) {
				return; // the approach rail itself is never a continuation
			}
			final double cx = farEnd.getX() - node.getX();
			final double cz = farEnd.getZ() - node.getZ();
			final double lb = Math.sqrt(cx * cx + cz * cz);
			if (la == 0 || lb == 0) {
				return; // degenerate geometry - not a usable continuation
			}
			final double cos = (dx * cx + dz * cz) / (la * lb);
			if (cos < 0) {
				return; // 人字道岔方向判断: turn-back / obtuse cross-arm candidates are unreachable
			}
			final double cross = dx * cz - dz * cx;
			final LegKind kind;
			if (cos >= COS_STRAIGHT) {
				kind = LegKind.STRAIGHT;
			} else if (Math.abs(cos) < COS_TURN) {
				kind = cross >= 0 ? LegKind.LEFT : LegKind.RIGHT;
			} else {
				kind = LegKind.OTHER; // diagonal / shallow junction
			}
			legs.add(new MmtrPointLeg(rail.getHexId(), farEnd.getX(), farEnd.getY(), farEnd.getZ(), kind, cos));
		});
		// Deterministic ordering: straight first, then left, right, other; cosine descending inside
		// a kind; hex as the final tie-break so equal geometries cannot produce unstable orderings.
		legs.sort((a, b) -> {
			final int rank = Integer.compare(kindRank(a.kind), kindRank(b.kind));
			if (rank != 0) {
				return rank;
			}
			final int cosCmp = -Double.compare(a.cos, b.cos);
			if (cosCmp != 0) {
				return cosCmp;
			}
			return a.railHex.compareTo(b.railHex);
		});
		return legs;
	}

	private static int kindRank(LegKind kind) {
		return switch (kind) {
			case STRAIGHT -> 0;
			case LEFT -> 1;
			case RIGHT -> 2;
			case OTHER -> 3;
		};
	}
}
