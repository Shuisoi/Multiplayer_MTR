package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.VehicleCar;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered, couplable set of units that move together along one path ("一列由多个单元连挂的编组").
 *
 * <p>Preparatory model for coupling/uncoupling: a train is an ordered list of {@link Unit}s, each
 * with its own {@link ConsistType} (hence its own mass, traction, brake envelope and air rates).
 * The whole composition shares one longitudinal speed.</p>
 *
 * <p>Two response layers are offered:</p>
 * <ul>
 *   <li>{@link #aggregate(ControlState, double)} — the ideal, instantaneous response: mass-weighted
 *       traction from powered units, mass-weighted full service/emergency braking and resistance.
 *       Stateless, used as a quick reference and for legacy single-unit equivalence.</li>
 *   <li>{@link #stepAir(ControlState, double, long)} — the dynamic per-unit air-brake model: each
 *       unit owns its own train-pipe pressure and brake-cylinder pressure (0..1). The driver's
 *       handle acts on the leading unit (index 0) and pipe-pressure changes then propagate
 *       rearward by pairwise equalization between neighbouring units, mimicking an air-brake train
 *       pipe (Open Rails-style charging / venting / equalisation). Each unit's brake cylinder
 *       responds to its own pipe pressure. Returns the mass-weighted output for the whole train.</li>
 * </ul>
 *
 * <p>Assumptions (documented, refined when the world-layer coupling ops land):</p>
 * <ul>
 *   <li>unit 0 is the controlling (cab) unit that owns the driver input / notch scales;</li>
 *   <li>traction only comes from {@code powered} units; raw tractive acceleration is scaled by
 *       {@code massRatio / totalMass} so dead trailing mass reduces acceleration;</li>
 *   <li>running resistance is the mass-weighted sum of per-unit Davis terms;</li>
 *   <li>pipe pressures are normalised 0..1; air rates come from each unit's ConsistType; newly
 *       coupled units start charged unless the caller sets an empty pipe via
 *       {@link Unit#setAirState(double, double)} (a coupling op would simulate an empty, uncharged
 *       brake pipe);</li>
 *   <li>no coupler slack/spring dynamics yet (added with the coupling operations themselves).</li>
 * </ul>
 */
public final class MmtrComposition {

	/** How quickly neighbouring units' train-pipe pressures equalise (fraction of the gap per second). */
	public static final double PIPE_EQUALIZATION_PER_SECOND = 5.0;
	/** Emergency vents the leading pipe several times faster than a full service application. */
	private static final double EMERGENCY_VENT_FACTOR = 5.0;
	/** Pipe pressure above which the local brake is considered fully released. */
	private static final double RELEASED_PIPE = 0.999;

	/** One coupled unit: identity, ConsistType, traction capability and its own air-brake state. */
	public static final class Unit {

		private final String id;
		private final ConsistType type;
		private final boolean powered;
		private double pipePressure = 1.0;
		private double brakeCylinderPressure = 0.0;

		public Unit(String id, ConsistType type) {
			this(id, type, true);
		}

		public Unit(String id, ConsistType type, boolean powered) {
			this.id = id;
			this.type = type;
			this.powered = powered;
		}

		public String getId() { return id; }
		public ConsistType getType() { return type; }
		public boolean isPowered() { return powered; }

		public double getPipePressure() { return pipePressure; }
		public double getBrakeCylinderPressure() { return brakeCylinderPressure; }

		/** Sets this unit's air-brake state directly (used when coupling an uncharged unit, e.g. pipe 0). */
		public void setAirState(double pipePressure, double brakeCylinderPressure) {
			this.pipePressure = clamp(pipePressure);
			this.brakeCylinderPressure = clamp(brakeCylinderPressure);
		}
	}

	private final ArrayList<Unit> units = new ArrayList<>();

	public MmtrComposition() {
	}

	public MmtrComposition(Unit firstUnit) {
		couple(firstUnit);
	}

	public boolean isEmpty() { return units.isEmpty(); }
	public int size() { return units.size(); }

	public Unit unit(int index) {
		return units.get(index);
	}

	/** Couples a unit to the tail (keeps its current air state). */
	public void couple(Unit unit) {
		if (unit == null) {
			throw new IllegalArgumentException("unit must not be null");
		}
		units.add(unit);
	}

	/** Couples a whole composition (in order) to the tail. */
	public void couple(MmtrComposition tailComposition) {
		for (final Unit unit : tailComposition.units) {
			units.add(unit);
		}
	}

	/** Removes and returns the tail-most unit (carries its air state with it). */
	public Unit uncoupleLast() {
		if (units.isEmpty()) {
			throw new IllegalStateException("composition is empty");
		}
		return units.remove(units.size() - 1);
	}

	/**
	 * Uncouples the composition after {@code index}: this composition keeps units
	 * {@code 0..index} and a new composition holding {@code index+1..end} is returned.
	 * Each side keeps its own independent air-brake state.
	 */
	public MmtrComposition splitAfter(int index) {
		if (index < 0 || index >= units.size()) {
			throw new IndexOutOfBoundsException("split index " + index + " out of bounds (size " + units.size() + ")");
		}
		final MmtrComposition tail = new MmtrComposition();
		for (int i = index + 1; i < units.size(); i++) {
			tail.units.add(units.get(i));
		}
		units.subList(index + 1, units.size()).clear();
		return tail;
	}

	/**
	 * Builds the per-car composition from MTR's runtime car list (C2). Each car contributes one
	 * unit carrying its own {@code mmtrPowered} flag and its own ConsistType — resolved from
	 * {@code registry} by the car's {@code mmtrConsistTypeId}, then by the registry's
	 * {@code carTypeIds} model mapping, falling back to {@code fallbackType} (the consist default).
	 * A hauled wagon therefore adds mass and brake-pipe volume without
	 * adding traction, which is what makes "locomotive + wagons" physically correct.
	 *
	 * @return the composition, or {@code null} when the list is empty or no type can be resolved
	 * (the caller then behaves as before, i.e. without an MMTR composition)
	 */
	public static @Nullable MmtrComposition fromVehicleCars(List<VehicleCar> cars, @Nullable ConsistTypeRegistry registry, @Nullable ConsistType fallbackType) {
		if (cars == null || cars.isEmpty()) {
			return null;
		}
		final MmtrComposition composition = new MmtrComposition();
		for (int i = 0; i < cars.size(); i++) {
			final VehicleCar car = cars.get(i);
			final String consistTypeId = car.getMmtrConsistTypeId();
			ConsistType type = consistTypeId == null || consistTypeId.isEmpty() || registry == null ? null : registry.get(consistTypeId);
			if (type == null && registry != null) {
				// 车厢没声明车底 ⇒ 退回"车型 → 车底"的映射（与 MmtrCarTypeResolver 同一优先级）。
				final String mapped = registry.typeIdForCar(car.getVehicleId());
				type = mapped == null ? null : registry.get(mapped);
			}
			if (type == null) {
				type = fallbackType;
			}
			if (type == null) {
				return null;
			}
			composition.couple(new Unit("car" + i, type, car.getMmtrPowered()));
		}
		return composition;
	}

	/** Charges every unit's pipe to 1.0 and releases all cylinders (fresh train). */
	public void resetAirState() {
		for (final Unit unit : units) {
			unit.pipePressure = 1.0;
			unit.brakeCylinderPressure = 0.0;
		}
	}

	/** Sum of the coupled units' relative masses. */
	public double massTotal() {
		double total = 0;
		for (final Unit unit : units) {
			total += unit.type.getMassRatio();
		}
		return total;
	}

	/** Mass-weighted running resistance (m/s^2, positive = deceleration) at the given speed. */
	public double resistance(double speedMetersPerSecond) {
		final double totalMass = massTotal();
		if (totalMass <= 0) {
			return 0;
		}
		double sum = 0;
		for (final Unit unit : units) {
			sum += MmtrPhysics.resistance(unit.type, speedMetersPerSecond) * unit.type.getMassRatio();
		}
		return sum / totalMass;
	}

	/**
	 * Ideal, instantaneous mass-weighted response to the driver input (no air-pipe dynamics).
	 *
	 * @param control             driver input (notch scales come from the controlling unit, index 0)
	 * @param speedMetersPerSecond current shared speed
	 * @return aggregate {@link DriveOutput} for the whole composition
	 */
	public DriveOutput aggregate(ControlState control, double speedMetersPerSecond) {
		if (units.isEmpty()) {
			return new DriveOutput(0, false, false, 1, 0);
		}
		final double totalMass = massTotal();
		if (totalMass <= 0) {
			return new DriveOutput(0, false, false, 1, 0);
		}
		final ConsistType controlType = units.get(0).type;

		if (control.isEmergency()) {
			double decel = 0;
			for (final Unit unit : units) {
				decel += unit.type.getEmergencyDecelerationMps2() * unit.type.getMassRatio();
			}
			decel = decel / totalMass + resistance(speedMetersPerSecond);
			return new DriveOutput(-decel, true, true, 0, 1);
		}

		final int brake = Math.max(0, Math.min(control.getBrakeNotch(), controlType.getBrakeNotches()));
		if (brake > 0) {
			final double ratio = (double) brake / controlType.getBrakeNotches();
			double decel = 0;
			for (final Unit unit : units) {
				decel += unit.type.getServiceBrakeDecelerationMps2() * unit.type.getMassRatio();
			}
			decel = decel / totalMass * ratio + resistance(speedMetersPerSecond);
			return new DriveOutput(-decel, decel > 0.01, false, 1, ratio);
		}

		final int throttle = Math.max(0, Math.min(control.getThrottleNotch(), controlType.getPowerNotches()));
		if (throttle > 0) {
			final double ratio = (double) throttle / controlType.getPowerNotches();
			double traction = 0;
			for (final Unit unit : units) {
				if (unit.isPowered()) {
					traction += MmtrPhysics.tractionAcceleration(unit.type, ratio, speedMetersPerSecond) * unit.type.getMassRatio();
				}
			}
			return new DriveOutput(traction / totalMass, false, false, 1, 0);
		}

		// Coasting: decays with the mass-weighted running resistance.
		return new DriveOutput(-resistance(speedMetersPerSecond), false, false, 1, 0);
	}

	/**
	 * Advances the per-unit air-brake model by one step and returns the train's mass-weighted output.
	 *
	 * <ol>
	 *   <li>the driver's handle charges/vents the leading unit's pipe (emergency vents fast);</li>
	 *   <li>pipe-pressure differences between neighbouring units equalise (front toward rear);</li>
	 *   <li>each unit's brake cylinder approaches {@code 1 - pipe} when its pipe drops (or releases
	 *       toward 0 when its pipe is charged);</li>
	 *   <li>the resulting braking (or traction when the brake is released and throttle applied) is
	 *       mass-weighted across the whole composition.</li>
	 * </ol>
	 *
	 * @param control             driver input (notch scales come from the controlling unit, index 0)
	 * @param speedMetersPerSecond current shared speed
	 * @param dtMillis            step length
	 * @return aggregate {@link DriveOutput}; see per-unit state via {@link #unit(int)}
	 */
	public DriveOutput stepAir(ControlState control, double speedMetersPerSecond, long dtMillis) {
		if (units.isEmpty()) {
			return new DriveOutput(0, false, false, 1, 0);
		}
		final double dt = Math.max(1, dtMillis) / 1000.0;
		final double totalMass = massTotal();
		if (totalMass <= 0) {
			return new DriveOutput(0, false, false, 1, 0);
		}

		final ControlState safeControl = control == null ? ControlState.zero() : control;
		final ConsistType controlType = units.get(0).type;
		final Unit front = units.get(0);

		// 1) Driver handle acts on the leading unit's pipe.
		if (safeControl.isEmergency()) {
			front.pipePressure = Math.max(0, front.pipePressure - controlType.getAirPipeDischargeRatePerSecond() * EMERGENCY_VENT_FACTOR * dt);
		} else {
			final int brake = Math.max(0, Math.min(safeControl.getBrakeNotch(), controlType.getBrakeNotches()));
			if (brake > 0) {
				final double ratio = (double) brake / controlType.getBrakeNotches();
				front.pipePressure = Math.max(0, front.pipePressure - controlType.getAirPipeDischargeRatePerSecond() * ratio * dt);
			} else {
				front.pipePressure = Math.min(1, front.pipePressure + controlType.getAirPipeChargeRatePerSecond() * dt);
			}
		}

		// 2) Pipe pressure propagates from the head (control unit, driven by the handle) toward
		// the rear: each following unit relaxes toward its neighbour ahead. A single front-to-rear
		// pass keeps the head as the pressure source so charging and venting both travel down the
		// train without dragging the leading pipe off its commanded value.
		for (int i = 0; i < units.size() - 1; i++) {
			final Unit ahead = units.get(i);
			final Unit behind = units.get(i + 1);
			final double gap = ahead.pipePressure - behind.pipePressure;
			if (Math.abs(gap) > 1e-12) {
				final double move = gap * PIPE_EQUALIZATION_PER_SECOND * dt;
				behind.pipePressure = clamp(behind.pipePressure + move);
			}
		}

		// 3) Each unit's brake cylinder follows its own pipe pressure.
		for (final Unit unit : units) {
			if (unit.pipePressure < RELEASED_PIPE) {
				final double target = 1.0 - unit.pipePressure;
				unit.brakeCylinderPressure = clamp(unit.brakeCylinderPressure
					+ unit.type.getAirBrakeApplyRatePerSecond() * dt * (target - unit.brakeCylinderPressure));
			} else {
				unit.brakeCylinderPressure = Math.max(0, unit.brakeCylinderPressure
					- unit.type.getAirBrakeReleaseRatePerSecond() * dt);
			}
		}

		// 4) Mass-weighted aggregate output.
		final boolean emergency = safeControl.isEmergency();
		final boolean anyBrake = anyCylinderAbove(0.02) || safeControl.getBrakeNotch() > 0 || emergency;
		double decel = 0;
		for (final Unit unit : units) {
			final double magnitude = unit.brakeCylinderPressure
				* (emergency ? unit.type.getEmergencyDecelerationMps2() : unit.type.getServiceBrakeDecelerationMps2());
			decel += magnitude * unit.type.getMassRatio();
		}
		decel = decel / totalMass + resistance(speedMetersPerSecond);

		if (anyBrake || decel > 0.001) {
			return new DriveOutput(-decel, decel > 0.01, emergency, averagePipePressure(), averageCylinderPressure());
		}

		final int throttle = Math.max(0, Math.min(safeControl.getThrottleNotch(), controlType.getPowerNotches()));
		if (throttle > 0) {
			final double ratio = (double) throttle / controlType.getPowerNotches();
			double traction = 0;
			for (final Unit unit : units) {
				if (unit.isPowered()) {
					traction += MmtrPhysics.tractionAcceleration(unit.type, ratio, speedMetersPerSecond) * unit.type.getMassRatio();
				}
			}
			return new DriveOutput(traction / totalMass, false, false, averagePipePressure(), averageCylinderPressure());
		}

		return new DriveOutput(-resistance(speedMetersPerSecond), false, false, averagePipePressure(), averageCylinderPressure());
	}

	private boolean anyCylinderAbove(double threshold) {
		for (final Unit unit : units) {
			if (unit.brakeCylinderPressure > threshold) {
				return true;
			}
		}
		return false;
	}

	/** Average train-pipe pressure across all units (0..1). */
	public double averagePipePressure() {
		if (units.isEmpty()) {
			return 1;
		}
		double sum = 0;
		for (final Unit unit : units) {
			sum += unit.pipePressure;
		}
		return sum / units.size();
	}

	/** Average brake-cylinder pressure across all units (0..1). */
	public double averageCylinderPressure() {
		if (units.isEmpty()) {
			return 0;
		}
		double sum = 0;
		for (final Unit unit : units) {
			sum += unit.brakeCylinderPressure;
		}
		return sum / units.size();
	}


	/**
	 * Encodes every unit's (pipe, cylinder) air state into a compact snapshot string
	 * ({@code "pipe,cyl;pipe,cyl;..."}). Empty when there are no units.
	 */
	public static String encodeAirStates(MmtrComposition composition) {
		final StringBuilder builder = new StringBuilder();
		for (int i = 0; i < composition.size(); i++) {
			if (i > 0) {
				builder.append(';');
			}
			final Unit unit = composition.unit(i);
			builder.append(unit.pipePressure).append(',').append(unit.brakeCylinderPressure);
		}
		return builder.toString();
	}

	/**
	 * Seeds this composition's per-unit air state from a string produced by
	 * {@link #encodeAirStates(MmtrComposition)}. Units beyond the payload keep their state;
	 * extra payload entries are ignored.
	 */
	public void applyAirStateString(String airState) {
		if (airState == null || airState.isEmpty()) {
			return;
		}
		final String[] units = airState.split(";");
		for (int i = 0; i < units.length && i < this.units.size(); i++) {
			final String[] pair = units[i].split(",");
			if (pair.length == 2) {
				try {
					this.units.get(i).setAirState(Double.parseDouble(pair[0]), Double.parseDouble(pair[1]));
				} catch (NumberFormatException ignored) {
					// malformed seed: keep the unit's current state
				}
			}
		}
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(1, value));
	}
}