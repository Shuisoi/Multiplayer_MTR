package org.mtr.core.mmtr;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.core.data.Siding;
import org.mtr.core.data.VehicleCar;

/**
 * World-layer coupling/uncoupling groundwork ("连挂/解挂原语").
 *
 * <p>MTR models a whole train as ONE {@code Vehicle} that follows a single railProgress and owns
 * an ordered {@code vehicleCars} list. Coupling is therefore reduced to <em>formation editing</em>
 * on that single vehicle: append the tail train's cars to the head train's car list (and free the
 * tail's siding), or cut the list at a unit boundary to spawn a second vehicle. Physics (mass
 * weighting, per-car air-brake equalisation) is handled by {@link MmtrComposition}.</p>
 *
 * <p>This class is the engine-side, headless-testable core of that operation: car-list merging /
 * splitting, total length bookkeeping, and the guard predicates the actual simulator/op must check
 * (both parties stopped at the depot / manual siding, same siding & mode, capacity).</p>
 */
public final class MmtrCoupling {

	/** Outcome of the coupling guard checks. */
	public static final class Check {
		public final boolean allowed;
		public final String reason;

		private Check(boolean allowed, String reason) {
			this.allowed = allowed;
			this.reason = reason;
		}

		public static Check ok() {
			return new Check(true, "");
		}

		public static Check fail(String reason) {
			return new Check(false, reason);
		}
	}

	private MmtrCoupling() {
	}

	/**
	 * Guard: may {@code tail} be coupled onto {@code head} in a depot/shunting context?
	 *
	 * @param headSidingId       siding id of the head train
	 * @param tailSidingId       siding id of the tail train (must match to share one track/path)
	 * @param headCloseToDepot   whether the head is at its depot track (not mid-route)
	 * @param tailCloseToDepot   whether the tail is at its depot track
	 * @param headSpeedInternal  head speed (m/ms)
	 * @param tailSpeedInternal  tail speed (m/ms)
	 * @param headCarCount       cars in the head
	 * @param tailCarCount       cars in the tail
	 * @param maxCarCount        transport-mode capacity cap
	 * @param headManual         whether the head siding is a manual (player-driven) siding
	 * @param tailManual         whether the tail siding is manual
	 */
	public static Check canCoupleAtDepot(long headSidingId, long tailSidingId, boolean headCloseToDepot, boolean tailCloseToDepot,
		double headSpeedInternal, double tailSpeedInternal, int headCarCount, int tailCarCount, int maxCarCount, boolean headManual, boolean tailManual) {
		if (headSidingId != tailSidingId) {
			return Check.fail("both trains must be on the same siding/track");
		}
		if (!headCloseToDepot || !tailCloseToDepot) {
			return Check.fail("coupling is only allowed while both trains are stopped at the depot/manual siding");
		}
		if (headSpeedInternal > 0 || tailSpeedInternal > 0) {
			return Check.fail("both trains must be stopped");
		}
		if (headCarCount + tailCarCount > maxCarCount) {
			return Check.fail("combined length exceeds the transport-mode car cap");
		}
		if (!headManual || !tailManual) {
			return Check.fail("coupling is only allowed on manual (player-driven) sidings");
		}
		return Check.ok();
	}

	/**
	 * Guard: may {@code head} be cut at {@code cutAfterCarIndex} (0-based index of the last car
	 * that stays in the head)?
	 */
	public static Check canUncouple(int carCount, int cutAfterCarIndex) {
		if (cutAfterCarIndex < 0 || cutAfterCarIndex >= carCount - 1) {
			return Check.fail("cut index must leave at least one car on each side");
		}
		return Check.ok();
	}

	/** Merges the head and tail car lists, preserving order. */
	public static ObjectArrayList<VehicleCar> mergeCars(ObjectArrayList<VehicleCar> headCars, ObjectArrayList<VehicleCar> tailCars) {
		final ObjectArrayList<VehicleCar> merged = new ObjectArrayList<>(headCars.size() + tailCars.size());
		merged.addAll(headCars);
		merged.addAll(tailCars);
		return merged;
	}

	/**
	 * Splits a car list after {@code cutAfterCarIndex}: index {@code 0..cutAfterCarIndex} stays in
	 * the returned head; the rest forms the returned tail. Both lists are new.
	 */
	public static ObjectObjectImmutablePair<ObjectArrayList<VehicleCar>, ObjectArrayList<VehicleCar>> splitCars(ObjectArrayList<VehicleCar> cars, int cutAfterCarIndex) {
		if (!canUncouple(cars.size(), cutAfterCarIndex).allowed) {
			throw new IllegalArgumentException("invalid cut index " + cutAfterCarIndex + " for " + cars.size() + " cars");
		}
		final ObjectArrayList<VehicleCar> head = new ObjectArrayList<>(cutAfterCarIndex + 1);
		final ObjectArrayList<VehicleCar> tail = new ObjectArrayList<>(cars.size() - cutAfterCarIndex - 1);
		for (int i = 0; i < cars.size(); i++) {
			(i <= cutAfterCarIndex ? head : tail).add(cars.get(i));
		}
		return new ObjectObjectImmutablePair<>(head, tail);
	}

	/** Total coupled length of the given cars (MTR convention, same as a spawned train). */
	public static double totalLength(ObjectArrayList<VehicleCar> cars) {
		return Siding.getTotalVehicleLength(cars);
	}
}