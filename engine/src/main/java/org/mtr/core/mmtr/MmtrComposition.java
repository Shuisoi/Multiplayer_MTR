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
 * <p><b>它只负责"这一列由哪些车组成"</b>（notes/376）：质量的求和、逐车 {@link org.mtr.core.mmtr.brake.BrakeCar}
 * 列表（{@link #brakeCars()}）与等效车底（{@link #toConsistType(String)}）。**制动/牵引的推进不在这里** ——
 * 一根手柄 → {@code BrakeCommand} → {@link org.mtr.core.mmtr.brake.BrakeModel}（逐车管压/缸压/力）
 * 只有一条路；以前那套"编组自己的归一化逐车气路"（{@code stepAir} / {@code aggregate} /
 * {@code encodeAirStates}）已整段删除。</p>
 *
 * <p>Assumptions (documented, refined when the world-layer coupling ops land):</p>
 * <ul>
 *   <li>unit 0 is the controlling (cab) unit that owns the driver input / notch scales;</li>
 *   <li>traction only comes from {@code powered} units;</li>
 *   <li>running resistance is the mass-weighted sum of per-unit Davis terms;</li>
 *   <li>气压状态由制动模型逐车持有（管压/缸压，bar 口径），连挂/解挂通过它的
 *       {@code encodeState}/{@code applyState}/{@code seedAfterCoupling} 接口跨过；</li>
 *   <li>no coupler slack/spring dynamics yet (added with the coupling operations themselves).</li>
 * </ul>
 */
public final class MmtrComposition {

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
		double totalMassKg = 0;
		double totalEffectiveMassKg = 0;
		double totalTractiveEffortN = 0;
		double totalPowerW = 0;
		double totalServiceForceN = 0;
		double totalEmergencyForceN = 0;
		double totalResistanceAN = 0;
		double totalResistanceBN = 0;
		double totalResistanceCN = 0;
		// notes/379：整列的**电制动（回生）**能力 = 各动力车自己的能力之和（有几台电机算几台）。
		org.mtr.core.mmtr.physics.ElectricBrakeSpec totalElectric = null;
		/**
		 * notes/379：编组级的"电空混合"开关 = **有没有一节动力车带得了电制动**。
		 *
		 * <p>为什么不能在编组级直接沿用说话那节车：SAF420 这种编组的头车是**无动力的控制拖车**
		 * （`blendingEnabled:false`，它本来就没有电机），照抄它会把整列的回生关掉 ——
		 * 现场表现就是"B1/B2 明明配了电制动，缸压却照旧按纯空气建"。</p>
		 */
		boolean anyPoweredBlending = false;
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
				// notes/379：电制动只算**动力车**（拖车没有电机可回馈）。
				if (type.getElectricBrake() != null) {
					totalElectric = totalElectric == null ? type.getElectricBrake() : totalElectric.plus(type.getElectricBrake());
					anyPoweredBlending |= type.getBrakes().isBlendingEnabled();
				}
			}
			totalServiceForceN += physics.getBrake().getServiceForceN();
			totalEmergencyForceN += physics.getBrake().getEmergencyForceN();
			totalResistanceAN += physics.getResistance().getAN();
			totalResistanceBN += physics.getResistance().getBN();
			totalResistanceCN += physics.getResistance().getCN();
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
			lead.getManualMaxSpeedMetersPerSecond() * 3.6, lead.getHandles(),
			/*
			 * notes/376：**气压口径同样沿用"说话那节车"**（它决定级位表、缸压上限与闸片口径）——
			 * 逐车的气路状态不走这里，而是由 `MmtrComposition.brakeCars()` 交给制动模型的 `setCars()`。
			 * 以前这一格是 `handles == null ? null : handles.getBrakes()`，于是有级/无级编组（SAF420 就是）
			 * 整列丢掉气压口径、退回旧比例制动力 —— 那条路已删除。
			 */
			lead.getBrakes().withBlendingEnabled(anyPoweredBlending || lead.getBrakes().isBlendingEnabled()),
			// notes/379：整列的电制动能力（各动力车之和）。
			totalElectric, 0, null,
			// notes/352：灯光开关档数沿用"说话的那节车"（编组的灯由驾驶室的开关决定）。
			lead.hasMmtrLightOffPosition()
		);
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
}
