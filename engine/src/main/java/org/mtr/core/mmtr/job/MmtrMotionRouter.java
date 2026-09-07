package org.mtr.core.mmtr.job;

import org.mtr.core.data.Siding;
import org.mtr.core.simulation.Simulator;

/**
 * Legacy reachability gate for the (soon-to-be-removed) path-cache relocation flow. It answers
 * whether two sidings of the same depot are connected by the pre-generated per-siding path caches
 * (outbound-to-main + main + return-to-siding).
 *
 * <p>This whole class is superseded by Motion Core (MmtrLiveRouter / MmtrMotionWalker), which routes
 * over the real rail graph by turnout authority instead of relying on generated path caches. Per the
 * "clean up old systems, no parallel" directive it is being removed slice by slice: the leg-plan
 * assembly (buildLegPlan / MmtrMotionPlan) has already been dropped here; canReachSiding remains only
 * because the legacy relocation gate still references it until that flow is retired.
 */
public final class MmtrMotionRouter {

	private MmtrMotionRouter() {
	}

	public static boolean canReachSiding(Simulator simulator, long fromSidingId, long toSidingId) {
		if (fromSidingId == toSidingId) {
			return true;
		}
		final Siding from = findSiding(simulator, fromSidingId);
		final Siding to = findSiding(simulator, toSidingId);
		if (from == null || to == null) {
			return false;
		}
		final long fromDepot = from.area == null ? 0 : from.area.getId();
		final long toDepot = to.area == null ? 0 : to.area.getId();
		if (fromDepot == 0 || fromDepot != toDepot) {
			return false; // cross-depot legs arrive with later Router stages
		}
		return from.hasPathToMainRoute() && to.hasReturnFromMainRoute();
	}

	private static Siding findSiding(Simulator simulator, long sidingId) {
		final Siding[] found = {null};
		simulator.sidings.forEach(siding -> {
			if (found[0] == null && siding.getId() == sidingId) {
				found[0] = siding;
			}
		});
		return found[0];
	}
}
