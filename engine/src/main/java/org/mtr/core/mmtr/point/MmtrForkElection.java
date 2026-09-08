package org.mtr.core.mmtr.point;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Data;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.mmtr.point.MmtrPoint.MmtrPointLeg;
import org.mtr.core.mmtr.point.MmtrPointRegistry.BranchStore;
import org.mtr.core.simulation.Simulator;

/**
 * The single source of truth for "which rail do I continue onto at this node?".
 *
 * <p>Extracted verbatim from {@code MmtrMotionWalker.electAtFork} during the consist-body rewrite
 * (B3) so the new double-ended walker and the legacy single-point walker cannot drift apart. The
 * decision order is unchanged and is the design R2 rule:</p>
 *
 * <ol>
 *   <li>manual operator branch (point-op / legacy preset) — never auto;</li>
 *   <li>an explicit auto grant held by {@code owner} at its ordered-leg index;</li>
 *   <li>the live task target rail, when it is one of the ordered legs;</li>
 *   <li>a single continuation (no authority question);</li>
 *   <li>otherwise {@code null} — the train waits at the fork.</li>
 * </ol>
 *
 * <p>Continuations are ordered deterministically per approach direction by {@link MmtrPoint}
 * (straight &gt; left &gt; right &gt; other), so operator indices and planner presets agree; an
 * authoritative 进向表 entry for {@code (node, viaRail)} overrides the geometry entirely.</p>
 */
public final class MmtrForkElection {

	private MmtrForkElection() {
	}

	/**
	 * Elect the continuation rail when the train stands at {@code node} having arrived on
	 * {@code viaRail} from {@code enteredFrom}.
	 *
	 * @return the elected rail, or {@code null} when the train must halt (unset fork / no
	 * continuation at all)
	 */
	public static @Nullable Rail elect(Data data, BranchStore branches, @Nullable MmtrPointAuthority pointAuthority, @Nullable String pointAuthorityOwner, @Nullable String targetRailHex, Position node, Position enteredFrom, Rail viaRail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
		if (neighbors == null) {
			return null;
		}
		final ObjectArrayList<Rail> forwardRails = new ObjectArrayList<>();
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() != viaRail) {
				forwardRails.add(e.getValue());
			}
		}
		if (forwardRails.isEmpty()) {
			return null;
		}
		final ObjectArrayList<String> declared = data instanceof final Simulator simulator
			? simulator.mmtrJunctionLegs.get(node.getX(), node.getY(), node.getZ(), viaRail.getHexId()) : null;
		final ObjectArrayList<MmtrPointLeg> legs = MmtrPoint.computeOrderedLegs(node, enteredFrom, viaRail, neighbors, declared);
		if (legs.isEmpty()) {
			return null;
		}
		Rail chosen = null;
		final long px = node.getX();
		final long py = node.getY();
		final long pz = node.getZ();
		final String viaHex = viaRail.getHexId();
		if (branches != null && branches.contains(px, py, pz, viaHex)) {
			final int operator = branches.get(px, py, pz, viaHex);
			if (operator >= 0 && operator < legs.size()) {
				chosen = findRailByHex(forwardRails, legs.get(operator).railHex);
			}
		}
		if (chosen == null && pointAuthority != null && pointAuthorityOwner != null) {
			if (pointAuthority.isGrantedTo(px, py, pz, viaHex, pointAuthorityOwner)) {
				final int granted = pointAuthority.grantedLeg(px, py, pz, viaHex);
				if (granted >= 0 && granted < legs.size()) {
					chosen = findRailByHex(forwardRails, legs.get(granted).railHex);
				}
			}
		}
		if (chosen == null && targetRailHex != null) {
			for (final MmtrPointLeg leg : legs) {
				if (leg.railHex.equals(targetRailHex)) {
					chosen = findRailByHex(forwardRails, leg.railHex);
					break;
				}
			}
		}
		if (chosen == null && legs.size() == 1) {
			chosen = findRailByHex(forwardRails, legs.get(0).railHex);
		}
		return chosen;
	}

	/** Whether the node has any continuation other than {@code viaRail} at all (used to tell a halt from an end of line). */
	public static boolean hasContinuation(Data data, Position node, Rail viaRail) {
		final Object2ObjectOpenHashMap<Position, Rail> neighbors = data.positionsToRail.get(node);
		if (neighbors == null) {
			return false;
		}
		for (final Object2ObjectOpenHashMap.Entry<Position, Rail> e : neighbors.object2ObjectEntrySet()) {
			if (e.getValue() != viaRail) {
				return true;
			}
		}
		return false;
	}

	private static @Nullable Rail findRailByHex(ObjectArrayList<Rail> forwardRails, String hex) {
		for (final Rail forwardRail : forwardRails) {
			if (forwardRail.getHexId().equals(hex)) {
				return forwardRail;
			}
		}
		return null;
	}
}
