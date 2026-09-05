package org.mtr.core.mmtr;

import java.util.ArrayList;

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

		// 2) Pipe equalisation between neighbours (two passes: front->rear and rear->front).
		for (int pass = 0; pass < 2; pass++) {
			for (int i = 0; i < units.size() - 1; i++) {
				final Unit a = units.get(i);
				final Unit b = units.get(i + 1);
				final double difference = b.pipePressure - a.pipePressure;
				final double move = clamp(difference * PIPE_EQUALIZATION_PER_SECOND * dt / 2.0);
				a.pipePressure = clamp(a.pipePressure + move);
				b.pipePressure = clamp(b.pipePressure - move);
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

	private double averagePipePressure() {
		if (units.isEmpty()) {
			return 1;
		}
		double sum = 0;
		for (final Unit unit : units) {
			sum += unit.pipePressure;
		}
		return sum / units.size();
	}

	private double averageCylinderPressure() {
		if (units.isEmpty()) {
			return 0;
		}
		double sum = 0;
		for (final Unit unit : units) {
			sum += unit.brakeCylinderPressure;
		}
		return sum / units.size();
	}

	private static double clamp(double value) {
		return Math.max(0, Math.min(1, value));
	}
}
