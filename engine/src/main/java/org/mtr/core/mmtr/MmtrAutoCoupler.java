package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.MmtrCoupleSurgery;
import org.mtr.core.data.Position;
import org.mtr.core.data.Vehicle;
import org.mtr.core.mmtr.segment.MmtrMotionPosition;
import org.mtr.core.mmtr.signal.MmtrShuntAuthority;
import org.mtr.core.simulation.Simulator;

/**
 * C8 自动车钩: the automatic-coupler pass.
 *
 * <p>A real 动车组 or 调车机车 has automatic couplers - the driver draws up to a standing rake and the
 * couplers latch as soon as they touch. Nobody gets out and nobody presses a key. MMTR models this as
 * a POLICY layer on top of {@link MmtrCoupleSurgery}: every tick, a train that
 * <ul>
 *   <li>holds a live 调车授权 (the subsidiary aspect that authorises entering the occupied section -
 *       without it a chance encounter in the yard must NOT couple),</li>
 *   <li>has come to a stand,</li>
 *   <li>stands on the same rail, facing the same way, within {@link MmtrCoupleSurgery#COUPLER_CONTACT_M}
 *       of another standing train, and</li>
 *   <li>meets it with an AUTOMATIC coupler on both sides of the joint</li>
 * </ul>
 * couples itself. Everything else - placing the merged body, the car cap, the crew key's journey into
 * the formation - is the same surgery the manual path runs, so the two paths cannot drift apart.
 *
 * <p>Deliberately not automatic: uncoupling (a coupler cannot cut itself) and any joint with a manual
 * coupler or a legacy single-point walker (no car orientation) - the crew confirms those with the
 * coupler key.
 */
public final class MmtrAutoCoupler {

	private MmtrAutoCoupler() {
	}

	/**
	 * Run one auto-coupling pass. Called AFTER the vehicle simulation of the tick, never from inside it:
	 * the surgery unregisters the trailing train, and doing that while a siding iterates its vehicles
	 * would corrupt the iteration.
	 */
	public static void tick(Simulator simulator) {
		final ObjectArrayList<Vehicle> initiators = new ObjectArrayList<>();
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (isCandidate(simulator, vehicle)) {
				initiators.add(vehicle);
			}
		}));
		for (final Vehicle initiator : initiators) {
			// An earlier coupling in this same pass may already have swallowed this train.
			if (simulator.mmtrFindVehicle(initiator.getId()) == null) {
				continue;
			}
			final Vehicle target = findTarget(simulator, initiator);
			if (target != null) {
				final MmtrCoupleSurgery.Result result = MmtrCoupleSurgery.couple(simulator, initiator.getId(), target.getId());
				if (result.ok()) {
					System.out.println("[MMTR-COUP] 自动车钩挂上: " + initiator.getId() + " + " + target.getId() + " -> " + result.mergedCarCount() + " 节");
				}
			}
		}
	}

	/** The cheap per-train gate, evaluated before the (more expensive) target search. */
	private static boolean isCandidate(Simulator simulator, Vehicle vehicle) {
		if (vehicle.getSpeed() > 1e-9 || vehicle.getMmtrConsistWalker() == null) {
			return false;
		}
		final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
		if (walker == null) {
			return false;
		}
		final MmtrShuntAuthority authority = simulator.mmtrShuntAuthorities.active(vehicle.getId());
		return authority != null && authority.covers(walker.railHex());
	}

	/**
	 * The nearest standing train this train is closed up to with automatic couplers on both sides of the
	 * joint, or {@code null} when there is none. Every remaining gate (placement of the merged body, the
	 * car cap, the rail both trains stand on) is re-checked by the surgery itself.
	 */
	@Nullable
	private static Vehicle findTarget(Simulator simulator, Vehicle initiator) {
		final MmtrMotionPosition initiatorWalker = initiator.getMmtrMotionWalker();
		final Position initiatorEntry = initiatorWalker == null ? null : initiatorWalker.enteredFromPosition();
		if (initiatorWalker == null || initiatorEntry == null) {
			return null;
		}
		final String initiatorRail = initiatorWalker.railHex();
		final Vehicle[] best = {null};
		final double[] bestGap = {Double.MAX_VALUE};
		simulator.sidings.forEach(siding -> siding.iterateVehicles(vehicle -> {
			if (vehicle.getId() == initiator.getId() || vehicle.getSpeed() > 1e-9) {
				return;
			}
			final MmtrMotionPosition walker = vehicle.getMmtrMotionWalker();
			if (walker == null || !initiatorRail.equals(walker.railHex())) {
				return;
			}
			final Position entry = walker.enteredFromPosition();
			if (entry == null || !entry.equals(initiatorEntry)) {
				// Same rail but the other way round: the couplers do not face each other.
				return;
			}
			final double gap = MmtrCoupleSurgery.couplerGapM(initiator, vehicle);
			if (!Double.isFinite(gap) || gap > MmtrCoupleSurgery.COUPLER_CONTACT_M || gap < -0.05) {
				return;
			}
			if (gap < bestGap[0] && MmtrCoupleSurgery.autoCouplersAtJoint(initiator, vehicle)) {
				bestGap[0] = gap;
				best[0] = vehicle;
			}
		}));
		return best[0];
	}
}
