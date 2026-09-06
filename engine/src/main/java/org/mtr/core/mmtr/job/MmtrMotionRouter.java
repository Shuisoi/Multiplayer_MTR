package org.mtr.core.mmtr.job;

import org.mtr.core.data.Depot;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

/**
 * M2-Core L0/L1: motion router skeleton for dynamic job planning. Today it answers reachability:
 * whether a consist standing on {@code fromSidingId} can be planned to terminate on
 * {@code toSidingId}. Same-depot pairs are reachable when the source has an outbound leg to the
 * main route and the destination has a return leg from it (the shared depot route supplies the
 * middle). Cross-depot / arbitrary-leg planning arrives with the Router (L1) stage.
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
			return false; // cross-depot legs arrive with the L1 Router
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
