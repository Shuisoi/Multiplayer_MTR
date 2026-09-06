package org.mtr.core.mmtr.job;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.core.data.Depot;
import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.core.simulation.Simulator;

/**
 * M2-Core L1: motion router. Given a parked consist and a target siding it builds the leg plan the
 * job executor will later feed the vehicle as a runtime MotionPlan (replacing the legacy baked
 * route). Today it assembles legs from the generated depot path caches:
 *   outbound(fromSiding) + main route + return(toSiding)
 * which is valid for same-depot pairs sharing the depot route (reachability = canReachSiding).
 */
public final class MmtrMotionRouter {

	private MmtrMotionRouter() {
	}

	/** A planned multi-leg move for one consist. */
	public static final class MmtrMotionPlan {
		public final long fromSidingId;
		public final long toSidingId;
		public final ObjectArrayList<PathData> legs = new ObjectArrayList<>();

		public MmtrMotionPlan(long fromSidingId, long toSidingId) {
			this.fromSidingId = fromSidingId;
			this.toSidingId = toSidingId;
		}

		public double totalLength() {
			double total = 0;
			for (final PathData leg : legs) {
				total += Math.max(0, leg.getEndDistance() - leg.getStartDistance());
			}
			return total;
		}
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

	/** Builds the leg plan; returns an empty plan when unreachable or paths are missing. */
	public static MmtrMotionPlan buildLegPlan(Simulator simulator, long fromSidingId, long toSidingId) {
		final MmtrMotionPlan plan = new MmtrMotionPlan(fromSidingId, toSidingId);
		if (!canReachSiding(simulator, fromSidingId, toSidingId)) {
			return plan;
		}
		final Siding from = findSiding(simulator, fromSidingId);
		final Siding to = findSiding(simulator, toSidingId);
		plan.legs.addAll(from.copyOutboundLegs());
		plan.legs.addAll(from.copyRouteLegs());
		if (fromSidingId != toSidingId) {
			plan.legs.addAll(to.copyReturnLegs());
		} else {
			plan.legs.addAll(from.copyReturnLegs());
		}
		return plan;
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
