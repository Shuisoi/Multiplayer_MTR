package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.mmtr.physics.BrakeSpec;

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
		/**
		 * 三态（notes/271 片 2）：{@code powered} 是**显式声明**的，还是只有默认值。
		 *
		 * <p>{@code false} = "作者没表态" ⇒ 允许走"最多借一节牵引"的老兜底；
		 * {@code true} 且 {@code powered == false} = 显式无动力 ⇒ **永不贡献牵引**（挂车、{@code --unpowered}、
		 * 世界文件里的 {@code powered:false}）。老代码把这两件事混成一个 {@code boolean}，
		 * 于是"一列全挂车"和"没表态的车列"只能共用一套兜底。</p>
		 */
		private final boolean poweredDeclared;
		/** 这节车的车长（m，notes/273 片 3）：管容积当量的输入；{@code 0} = 未知。 */
		private final double lengthM;
		/** 载重比例 0..1（notes/274 片 4）：逐车质量 = 整备 + 比例 × 车底的载重能力。 */
		private final double loadRatio;
		private double pipePressure = 1.0;
		private double brakeCylinderPressure = 0.0;

		public Unit(String id, ConsistType type) {
			this(id, type, true);
		}

		public Unit(String id, ConsistType type, boolean powered) {
			this(id, type, powered, false);
		}

		public Unit(String id, ConsistType type, boolean powered, boolean poweredDeclared) {
			this(id, type, powered, poweredDeclared, 0);
		}

		public Unit(String id, ConsistType type, boolean powered, boolean poweredDeclared, double lengthM) {
			this(id, type, powered, poweredDeclared, lengthM, 0);
		}

		public Unit(String id, ConsistType type, boolean powered, boolean poweredDeclared, double lengthM, double loadRatio) {
			this.id = id;
			this.type = type;
			this.powered = powered;
			this.poweredDeclared = poweredDeclared;
			this.lengthM = lengthM;
			this.loadRatio = Math.max(0, Math.min(1, loadRatio));
		}

		public String getId() { return id; }
		public ConsistType getType() { return type; }
		public boolean isPowered() { return powered; }
		public double getLengthM() { return lengthM; }
		public double getLoadRatio() { return loadRatio; }

		/** 这节车**按自己的载重**算出来的车底（载重折进质量；比例 0 时就是原对象）。 */
		public ConsistType loadedType() { return type.withLoad(loadRatio); }

		/** 这一节的动力位是不是**显式说出来的**（见字段注释）。 */
		public boolean isPoweredDeclared() { return poweredDeclared; }

		/** 显式无动力：永不贡献牵引，也不该被选作"说话的车"。 */
		public boolean isDeclaredUnpowered() { return poweredDeclared && !powered; }

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
		/*
		 * notes/247：**借来的车底不给牵引**。
		 *
		 * <p>"说话那节车"的车底会被拿来给**所有解析不出车底的车厢**兜底，而兜底时连牵引一起借走 ——
		 * 现场 BR101 + 2×p1（p1 没配映射）于是成了三台机车：起步约 3 m/s²、常用制动也是三倍。
		 * 规则（两遍扫描）：</p>
		 *
		 * <ul>
		 *   <li>车列里**有**任何一节显式解析出车底（车厢声明 / {@code carTypeIds} 映射）⇒ 借来的车一律按拖车（无牵引）；</li>
		 *   <li>一节都没有（整个维度只有一个缺省车底的老情形）⇒ 只有**第一节**车借牵引，其余按拖车 ——
		 *       这样"整列同型车"的质量照旧进物理，牵引却不会随节数翻倍（老行为是 N 倍功率）。</li>
		 * </ul>
		 */
		final String[] explicitIds = new String[cars.size()];
		boolean anyExplicit = false;
		for (int i = 0; i < cars.size(); i++) {
			explicitIds[i] = explicitTypeIdOf(cars.get(i), registry);
			anyExplicit |= explicitIds[i] != null;
		}
		/*
		 * notes/338（2026-09-27 现场）：**"借牵引的那一节"必须是第一节能出力的车，不是死认第 0 节**。
		 *
		 * <p>发现它的现场：世界里没装 {@code mmtr-consist-types.json}（引擎按
		 * {@code <dimension>/mmtr-consist-types.json} 找；dev 存档里就没有这份文件）⇒ 每节车都解析不出
		 * 车底、全列借缺省车底。缺省车底只有**一节**保留牵引，修前那一节写死 index 0 —— 而真实车列的头车
		 * 是**无动力的控制车**（Tc–M–M–T…，作业单里 {@code powered=false}），于是那一节在
		 * {@link #toConsistType} 求和时被 {@code unit.isPowered()} 挡掉：整列牵引 = 0。</p>
		 *
		 * <p>表现（单列 6 节编组在库里实测）：牵引为 0 ⇒ 只剩阻力 ⇒ 自动巡航那一支把速度积成
		 * **负值**（{@code speed = speed + a·dt}，a &lt; 0，没有下限），而 {@code integratedDistance = speed·dt < 0}
		 * ⇒ 走行体一步都不前进（累计里程恒为 0.0 m）。所有闸门（进路/闭塞/停车点/权威）都说"没人拦它"，
		 * 全链路**一句日志都没有** —— 车就这么一直在库里"倒着加速"。</p>
		 */
		int borrowedTractionIndex = 0;
		if (!anyExplicit) {
			for (int i = 0; i < cars.size(); i++) {
				if (cars.get(i).getMmtrPowered()) {
					borrowedTractionIndex = i;
					break;
				}
			}
		}
		final MmtrComposition composition = new MmtrComposition();
		for (int i = 0; i < cars.size(); i++) {
			final VehicleCar car = cars.get(i);
			ConsistType type = explicitIds[i] == null ? null : registry == null ? null : registry.get(explicitIds[i]);
			final boolean borrowed = type == null;
			if (borrowed) {
				type = fallbackType;
			}
			if (type == null) {
				return null;
			}
			if (borrowed && (anyExplicit || i != borrowedTractionIndex)) {
				type = type.asHauledTrailer();
				warnAboutBorrowedType(car.getVehicleId(), anyExplicit);
			}
			composition.couple(new Unit("car" + i, type, car.getMmtrPowered(), car.isMmtrPoweredDeclared(), car.getLength(), car.getMmtrLoadRatio()));
		}
		return composition;
	}

	/** 车厢显式声明的车底，其次是注册表里按车型的映射；都没有时返回 null（= 要借"说话那节车"的车底）。 */
	private static @Nullable String explicitTypeIdOf(VehicleCar car, @Nullable ConsistTypeRegistry registry) {
		if (registry == null) {
			return null;
		}
		final String declared = car.getMmtrConsistTypeId();
		if (declared != null && !declared.isEmpty() && registry.contains(declared)) {
			return declared;
		}
		final String mapped = registry.typeIdForCar(car.getVehicleId());
		return mapped != null && registry.contains(mapped) ? mapped : null;
	}

	/** 已经喊过的"借了别人的车底"的车型（一种车型只喊一次，免得每 tick 刷屏）。 */
	private static final java.util.Set<String> BORROWED_TYPE_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * 车厢没有自己的车底、只能借"说话那节车"的时候**必须说出来**：这正是"加速度莫名其妙不对"的现场，
	 * 而且修法就一句话（在 {@code carTypeIds} 里给它配个车底）。
	 */
	private static void warnAboutBorrowedType(@Nullable String vehicleId, boolean anyExplicit) {
		if (vehicleId != null && BORROWED_TYPE_WARNED.add(vehicleId + (anyExplicit ? "#" : "# alone"))) {
			System.out.println("[MMTR-CFG] 车厢 " + vehicleId + " 既没声明车底、carTypeIds 里也没有它 —— 按"
				+ (anyExplicit ? "「说话那节车」的质量/制动/阻力兜底，**不给牵引**" : "缺省车底兜底（只有第一节借牵引）")
				+ "。请在 carTypeIds 里给它配一份车底，否则它的牵引/质量都是借来的（notes/247）。");
		}
	}

	/** Charges every unit's pipe to 1.0 and releases all cylinders (fresh train). */
	public void resetAirState() {
		for (final Unit unit : units) {
			unit.pipePressure = 1.0;
			unit.brakeCylinderPressure = 0.0;
		}
	}

	/**
	 * 整列车的**惯性质量** {@code Σ λᵢ·mᵢ}（kg）—— 牛顿力 → 加速度时除的就是它。
	 *
	 * <p>旧口径这里是"相对质量之和"（每节 1.0），乘出来的东西量纲不清；换成真实质量之后，
	 * "多挂一节车"这件事才真的改变加速度（notes/235）。</p>
	 */
	public double totalEffectiveMassKg() {
		double total = 0;
		for (final Unit unit : units) {
			// notes/274 片 4：**载重进惯性**（车厢自己的比例 × 它自己车底的载重能力）。
			total += unit.type.effectiveMassForLoadKg(unit.loadRatio);
		}
		return total;
	}

	/**
	 * 整列车的运行阻力（牛顿）：逐节相加（每节自己的 Davis 曲线）。
	 *
	 * <p>注：Davis 的 A 项在真车里与轴重相关，这里**不随载重缩放**（片 4 的口径是"载重只影响质量"）——
	 * 重车因此少了那一点滚阻增量。要改的话是 {@code RunningResistanceSpec} 的事，与本片的载重通路无关。</p>
	 */
	public double resistanceForceN(double speedMetersPerSecond) {
		double sum = 0;
		for (final Unit unit : units) {
			sum += unit.type.getPhysics().resistanceForceN(speedMetersPerSecond);
		}
		return sum;
	}

	/**
	 * **整列车的等效车底**（notes/243 S1）：操纵语义沿用"说话的那节车"（index 0 的
	 * {@link ConsistType.ControlMode} 与三手柄规格），但**物理量按车求和** ——
	 * 质量、λ、牵引力/功率（只算 powered 单元）、制动力、阻力。
	 *
	 * <p>为什么需要它：三手柄/有级/无级三个控制器都只拿一个 {@link ConsistType} 去算，
	 * 而"说话的车"只是编组里的一节 —— BR101 + 2×p1 于是按 82 t 算（真实 162 t），
	 * 起步/制动都灵一倍、AFB 的补气量也偏小。把整列折成一个等效车底交给同一套控制器，
	 * 是最小改动下让"挂车质量真的进物理"的办法（多体/车钩那些留给后面的片）。</p>
	 *
	 * @param id 给这份等效车底一个可辨认的 id（日志/镜像用），例如 {@code "consist:br101+p1+p1"}
	 * @return 等效车底；单节编组时语义与那节车底等价（数值上也是同一套求和）
	 */
	public ConsistType toConsistType(String id) {
		if (units.isEmpty()) {
			return ConsistType.FALLBACK;
		}
		final ConsistType lead = units.get(0).type;
		final Unit leadUnit = units.get(0);
		ConsistType slowAirUnit = null;
		double totalMassKg = 0;
		double totalEffectiveMassKg = 0;
		double totalTractiveEffortN = 0;
		double totalPowerW = 0;
		double totalServiceForceN = 0;
		double totalEmergencyForceN = 0;
		double totalResistanceAN = 0;
		double totalResistanceBN = 0;
		double totalResistanceCN = 0;
		double airPipeCharge = 0;
		double airPipeDischarge = 0;
		double airApply = 0;
		double airRelease = 0;
		/*
		 * notes/271 片 2：**牵引的兜底改成逐车判据**。
		 *
		 * <p>旧写法是"整列一节都没声明动力 ⇒ 第一节借牵引"，而"声明"这件事在 boolean 上分不出来 ——
		 * 于是"一列全无动力的挂车"与"作者没表态的车列"共用同一套兜底（notes/247 的现场就出在这一族）。现在分三态：</p>
		 *
		 * <ul>
		 *   <li>显式无动力（世界文件 {@code powered:false} / {@code --unpowered}）⇒ **永不贡献牵引**
		 *       —— 这就是"挂车不能开"，也是 `--unpowered` 语义的收紧；</li>
		 *   <li>声明有动力 ⇒ 按它自己的车底算；</li>
		 *   <li>没表态 ⇒ 老兜底照旧，但**整列最多一节**，而且那一节的车底得真的能出力
		 *       （用例里用 VehicleCar 拼的车列基本都不写 powered，实机车列才写）。</li>
		 * </ul>
		 */
		boolean anyDeclaredPowered = false;
		for (final Unit unit : units) {
			anyDeclaredPowered |= unit.isPowered() && unit.isPoweredDeclared();
		}
		Unit borrowedTraction = null;
		if (!anyDeclaredPowered) {
			for (final Unit unit : units) {
				if (!unit.isPoweredDeclared() && unit.type.canPull()) {
					borrowedTraction = unit;
					break;
				}
			}
		}
		for (final Unit unit : units) {
			// notes/274 片 4：**载重折进质量**再进物理（比例 0 时就是原车底，零开销）。
			final ConsistType type = unit.loadedType();
			final org.mtr.core.mmtr.physics.TrainPhysics physics = type.getPhysics();
			totalMassKg += physics.getMassKg();
			totalEffectiveMassKg += physics.effectiveMassKg();
			if (unit.isPowered() || unit == borrowedTraction) {
				totalTractiveEffortN += physics.getTraction().getMaxTractiveEffortN();
				totalPowerW += physics.getTraction().getMaxPowerW();
			}
			totalServiceForceN += physics.getBrake().getServiceForceN();
			totalEmergencyForceN += physics.getBrake().getEmergencyForceN();
			totalResistanceAN += physics.getResistance().getAN();
			totalResistanceBN += physics.getResistance().getBN();
			totalResistanceCN += physics.getResistance().getCN();
			// 气路速率取**最慢的一节**（编组里管径/容积不同，全列充气由最慢那节决定）
			airPipeCharge = airPipeCharge <= 0 ? type.getAirPipeChargeRatePerSecond() : Math.min(airPipeCharge, type.getAirPipeChargeRatePerSecond());
			airPipeDischarge = airPipeDischarge <= 0 ? type.getAirPipeDischargeRatePerSecond() : Math.min(airPipeDischarge, type.getAirPipeDischargeRatePerSecond());
			airApply = airApply <= 0 ? type.getAirBrakeApplyRatePerSecond() : Math.min(airApply, type.getAirBrakeApplyRatePerSecond());
			airRelease = airRelease <= 0 ? type.getAirBrakeReleaseRatePerSecond() : Math.min(airRelease, type.getAirBrakeReleaseRatePerSecond());
			// 谁把整列拖慢了：挂车漏写气路字段会落回缺省 0.1/s 缓解 ⇒ 松闸后十几秒没有牵引（notes/248）。
			if (unit != leadUnit && type.getAirBrakeReleaseRatePerSecond() < lead.getAirBrakeReleaseRatePerSecond()
				&& type.getAirBrakeReleaseRatePerSecond() <= airRelease) {
				slowAirUnit = type;
			}
		}
		if (slowAirUnit != null) {
			warnAboutSlowAirUnit(slowAirUnit, lead, airRelease);
		}
		// λ_eq = Σ(λᵢmᵢ) / Σmᵢ（构造器会再做一次 max(1, ·)）
		final double rotatingMassFactor = totalMassKg <= 0 ? 1 : totalEffectiveMassKg / totalMassKg;
		return new ConsistType(
			id, lead.getName() + "（编组）", lead.getControlMode(), lead.getPowerNotches(), lead.getBrakeNotches(),
			lead.getMaxSpeedKmh(), totalMassKg, rotatingMassFactor,
			totalTractiveEffortN, totalPowerW, totalServiceForceN, totalEmergencyForceN,
			totalResistanceAN, totalResistanceBN, totalResistanceCN,
			// 黏着：编组沿用"说话那节车"的轨面条件（撒砂增益已经折进 usableMuMax）
			lead.getAdhesion().usableMuMax(), false,
			airPipeCharge, airPipeDischarge, airApply, airRelease,
			lead.getManualMaxSpeedMetersPerSecond() * 3.6, lead.getHandles(),
			// notes/352：灯光开关档数沿用"说话那节车"（编组的灯由驾驶室的开关决定）。
			lead.hasMmtrLightOffPosition()
		);
	}

	/**
	 * 挂车把整列的**气路速率**拖慢时点名（notes/248）。
	 *
	 * <p>为什么要有：编组的气路速率取"最慢的一节"，而缺省值是 0.1/s 缓解（机车配的是 0.25/s）。
	 * 挂车漏写气路字段 ⇒ 整列缓解慢 2.5 倍 ⇒ 司机松闸后缸压要十来秒才排空，牵引联锁一直按住牵引，
	 * 现场表现是"松了闸、油门推到 96，车十几二十秒不动"。这一条只报"真的被拖慢"的组合，不报普通缺省。</p>
	 */
	private static final java.util.Set<String> SLOW_AIR_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static void warnAboutSlowAirUnit(ConsistType slowUnit, ConsistType lead, double aggregateReleaseRate) {
		if (SLOW_AIR_WARNED.add(slowUnit.getId() + "→" + lead.getId())) {
			System.out.println("[MMTR-CFG] 编组里的 " + slowUnit.getId() + "（缓解 " + slowUnit.getAirBrakeReleaseRatePerSecond()
				+ "/s）比说话的车 " + lead.getId() + "（缓解 " + lead.getAirBrakeReleaseRatePerSecond()
				+ "/s）慢 —— 整列按最慢的一节走，实际缓解 " + Math.round(aggregateReleaseRate * 1000) / 1000.0
				+ "/s：松闸后要等缸压排空才有牵引（缺省 0.1/s 会让它多等好几倍时间）。给这节车显式写上气路速率即可（notes/248）。");
		}
	}

	/** 运行阻力折成的减速度（m/s²，正数 = 减速）。 */
	public double resistance(double speedMetersPerSecond) {
		final double totalInertia = totalEffectiveMassKg();
		return totalInertia <= 0 ? 0 : resistanceForceN(speedMetersPerSecond) / totalInertia;
	}

	/**
	 * **这一列车的逐车制动描述**（notes/270，"连挂形式 → 制动系统"的适配口）：每节车自己的制动力锚
	 * （来自它自己的车底，随包配置的 UIC 反推）、是不是动力车（只有动力车吃电空混合）、
	 * 以及它自己的气压口径（{@code null} = 沿用编组级）。
	 *
	 * <p>单机、机车+客车、双机重联、混编货车都只是**不同的列表**；连挂/解挂就是列表的拼接与切分 ——
	 * {@link org.mtr.core.mmtr.brake.BrakeSystem} 的状态按车序同步拼接/切分（见它的连挂接口）。
	 * 这一层**不碰操纵方式**：谁在开车、有几根手柄，与"车列由哪些车组成"是两件事。</p>
	 */
	public java.util.List<org.mtr.core.mmtr.brake.BrakeCar> brakeCars() {
		final java.util.ArrayList<org.mtr.core.mmtr.brake.BrakeCar> cars = new java.util.ArrayList<>(units.size());
		for (final Unit unit : units) {
			final org.mtr.core.mmtr.physics.BrakeSpec brake = unit.type.getBrake();
			// notes/274/275 片 4/5：**含载重的质量**与**这节车自己的黏着档**一起交给制动系统，
			// 逐车黏着截断才有法向力可用（片 5 之前这两样都没有 ⇒ 只能整列一次截断）。
			cars.add(new org.mtr.core.mmtr.brake.BrakeCar(brake.getServiceForceN(), brake.getEmergencyForceN(),
				unit.isPowered(), unit.type.getBrakes(), unit.getLengthM(),
				unit.type.loadedMassKg(unit.getLoadRatio()), unit.type.getAdhesion()));
		}
		return cars;
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
		final double totalInertia = totalEffectiveMassKg();
		if (totalInertia <= 0) {
			return new DriveOutput(0, false, false, 1, 0);
		}
		final ConsistType controlType = units.get(0).type;
		final double resistanceDecel = resistance(speedMetersPerSecond);

		if (control.isEmergency()) {
			double forceN = 0;
			for (final Unit unit : units) {
				forceN += unit.type.getPhysics().getBrake().emergencyForceN();
			}
			return new DriveOutput(-(forceN / totalInertia + resistanceDecel), true, true, 0, 1);
		}

		final int brake = Math.max(0, Math.min(control.getBrakeNotch(), controlType.getBrakeNotches()));
		if (brake > 0) {
			final double ratio = (double) brake / controlType.getBrakeNotches();
			double forceN = 0;
			for (final Unit unit : units) {
				forceN += unit.type.getPhysics().getBrake().serviceForceN(ratio);
			}
			final double decel = forceN / totalInertia + resistanceDecel;
			return new DriveOutput(-decel, decel > 0.01, false, 1, ratio);
		}

		final int throttle = Math.max(0, Math.min(control.getThrottleNotch(), controlType.getPowerNotches()));
		if (throttle > 0) {
			final double ratio = (double) throttle / controlType.getPowerNotches();
			final double traction = tractionAccelerationMps2(ratio, speedMetersPerSecond, totalInertia, resistanceDecel);
			return new DriveOutput(traction, false, false, 1, 0);
		}

		// Coasting: decays with the running resistance of the whole train.
		return new DriveOutput(-resistanceDecel, false, false, 1, 0);
	}

	/**
	 * 整列车的牵引净加速度（m/s²）：**只有动力车出牵引力**，力逐节相加，再减去整列车的运行阻力
	 * 除以整列车的惯性质量。阻力只减一次（它是整车的事，不是每节各减一次）——旧口径把阻力塞在
	 * 每节的"加速度"里再加权，量纲不清（notes/235）。
	 */
	private double tractionAccelerationMps2(double ratio, double speedMetersPerSecond, double totalInertia, double resistanceDecel) {
		double forceN = 0;
		for (final Unit unit : units) {
			if (unit.isPowered()) {
				forceN += unit.type.getPhysics().tractiveEffortN(ratio, speedMetersPerSecond);
			}
		}
		return forceN / totalInertia - resistanceDecel;
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
		final double totalInertia = totalEffectiveMassKg();
		if (totalInertia <= 0) {
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

		// 4) Aggregate output: 逐节的**力**相加，除以整列车的惯性质量（notes/235）。
		final boolean emergency = safeControl.isEmergency();
		final boolean anyBrake = anyCylinderAbove(0.02) || safeControl.getBrakeNotch() > 0 || emergency;
		final double resistanceDecel = resistance(speedMetersPerSecond);
		double brakeForceN = 0;
		for (final Unit unit : units) {
			final BrakeSpec brake = unit.type.getPhysics().getBrake();
			brakeForceN += unit.brakeCylinderPressure * (emergency ? brake.emergencyForceN() : brake.serviceForceN(1));
		}
		final double decel = brakeForceN / totalInertia + resistanceDecel;

		if (anyBrake || decel > 0.001) {
			return new DriveOutput(-decel, decel > 0.01, emergency, averagePipePressure(), averageCylinderPressure());
		}

		final int throttle = Math.max(0, Math.min(safeControl.getThrottleNotch(), controlType.getPowerNotches()));
		if (throttle > 0) {
			final double ratio = (double) throttle / controlType.getPowerNotches();
			return new DriveOutput(tractionAccelerationMps2(ratio, speedMetersPerSecond, totalInertia, resistanceDecel), false, false,
				averagePipePressure(), averageCylinderPressure());
		}

		return new DriveOutput(-resistanceDecel, false, false, averagePipePressure(), averageCylinderPressure());
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