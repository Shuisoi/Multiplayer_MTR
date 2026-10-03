package org.mtr.core.mmtr.brake;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import java.util.List;

/**
 * **一列车的制动系统**（notes/270）：气压 → 分配阀 → 缸压 → 电空混合 → 逐车力，**与操纵方式无关**。
 *
 * <pre>
 *   BrakeCommand ──▶ 级位表插值 → 车头列车管目标(bar)
 *                                    │ 逐车传播（notes/268）
 *                                    ▼
 *                              每节车的管压 ── 该车自己的分配阀 ──▶ 该车缸压
 *                                    │（+ 上层补气诉求；电空混合只削动力车，notes/267）
 *                                    ▼
 *                             Σ 该车锚 × f(缸压) × κ(v) = 整列气制动力
 * </pre>
 *
 * <h2>可复用性（这一层为什么存在）</h2>
 *
 * <ul>
 *   <li><b>与操纵方式解耦</b>：三手柄 / 有级 / 无级 / 将来的 ATO 都只把它自己的手柄折成
 *       {@link BrakeCommand}，气路与力的逻辑一份；</li>
 *   <li><b>与编组形式解耦</b>：一列车就是一串 {@link BrakeCar}（单机 / 机车+客车 / 双机重联 / 货车），
 *       逐车锚与逐车口径都在那串数据里；</li>
 *   <li><b>与档位排布解耦</b>：档位表在 {@link PneumaticBrakeSpec#targetPipeBar} 里，长度随车底
 *       的 {@code brakeNotches} 走；</li>
 *   <li><b>连挂接口</b>：{@link #encodeState()} / {@link #applyState(String)} / {@link #seedAfterCoupling(int)}
 *       让"两列车合并/切分"时逐车管压与缸压能跟着走（与既有 {@code mmtrAirState} 同一格式）。</li>
 * </ul>
 *
 * <p>单车编组就是"只有一个元素的车列"—— 旧代码里"单车一条路、编组另一条路"的分叉在这里消失了
 * （稳态逐位一致，notes/268）。</p>
 */
public final class BrakeSystem {

	private final List<BrakeCar> cars;
	/** 编组级气压口径：车头那节车的（各车可用自己的覆盖）。 */
	private final PneumaticBrakeSpec spec;
	private final double[] pipeBar;
	private final double[] cylinderBar;
	private boolean initialized;

	/** 最近一拍的读数（HUD/日志/镜像/联锁用） */
	private double pneumaticForceN;
	private double emergencyForceN;
	private double blendedElectricN;
	private double maxCylinderBar;
	private boolean pneumaticHolding;
	/** 最近一拍**逐车黏着截断**掉的力（N）与"有没有被截"（notes/275 片 5，诊断用）。 */
	private double adhesionCappedN;
	private boolean perCarAdhesionLimited;

	public BrakeSystem(List<BrakeCar> cars, @Nullable PneumaticBrakeSpec spec) {
		this.cars = List.copyOf(cars);
		this.spec = spec == null ? PneumaticBrakeSpec.defaults() : spec;
		this.pipeBar = new double[this.cars.size()];
		this.cylinderBar = new double[this.cars.size()];
	}

	public int size() {
		return cars.size();
	}

	/** 管容积当量的**基准车长**（m）：notes/268 那条 5.0/s 的松弛率就是按 20 m 级车标定的。 */
	public static final double PIPE_VOLUME_REFERENCE_LENGTH_M = 20.0;

	/**
	 * **按车长的管容积当量**（notes/273 片 3，用户口径 2026-09-26「管容积当量用车长」）。
	 *
	 * <p>车越长，管子里要充/排的容积越大 ⇒ 同样的压差下追前面那节更慢。乘在松弛率上，
	 * 所以"20 m 的车"与片 3 之前逐位相同（缩放 1），16 m 的车略快、40 m 的车慢一倍。
	 * 车长未知（{@code <= 0}：单节等效车底、老夹具）时同样返回 1。</p>
	 *
	 * <p>夹在 0.25…4 之间：畸形车长不该把时间常数推到荒谬的量级。</p>
	 */
	public static double pipeVolumeScale(double carLengthM) {
		if (!(carLengthM > 0)) {
			return 1;
		}
		return Math.max(0.25, Math.min(4, PIPE_VOLUME_REFERENCE_LENGTH_M / carLengthM));
	}

	/** 编组级气压口径（车头那节车的）。 */
	public PneumaticBrakeSpec getSpec() {
		return spec;
	}

	/** 这一列车的逐车描述（连挂形式）。 */
	public List<BrakeCar> getCars() {
		return cars;
	}

	/** 某一节车的口径：它自己的，缺省沿用编组级。 */
	private PneumaticBrakeSpec specOf(int index) {
		final PneumaticBrakeSpec own = cars.get(index).spec();
		return own == null ? spec : own;
	}

	// ---- 每拍推进 ---------------------------------------------------------------------------------

	/**
	 * 推进一拍。
	 *
	 * @param command            制动诉求（与操纵方式无关，见 {@link BrakeCommand}）
	 * @param speedMetersPerSecond 当前速度（闸片衰减 ≥ 黏着曲线用）
	 * @param availableElectricN 这一拍**可用**的电制动力（N）：电空混合只用它替掉动力车自己的机械制动
	 * @param dtSeconds          步长
	 */
	public void step(BrakeCommand command, double speedMetersPerSecond, double availableElectricN, double dtSeconds) {
		if (!initialized) {
			reset();
		}
		final boolean emergency = command.emergency();
		// ① 车头：司机阀（或上层指令）作用在自己这一节上 —— 用**车头那节车自己的**口径（notes/273 片 3）：
		//    级位表是司机阀的表（编组级 spec），但充/排气速率与充风稳定值是这节车自己的管子。
		final PneumaticBrakeSpec headSpec = specOf(0);
		pipeBar[0] = emergency
			? headSpec.stepPipeEmergency(pipeBar[0], dtSeconds)
			: headSpec.stepPipe(pipeBar[0], pipeTargetBar(command.demandRatio()), dtSeconds);
		// ② 沿车列往后传（每节向它前面那节松弛）—— **逐车口径 + 按车长的管容积当量**（notes/273 片 3）：
		//    松弛率取该车自己的 pipePropagationPerSecond（以前整列共用"说话那节车"的），
		//    再按"基准车长 / 本车车长"缩放 —— 长车容积大 ⇒ 追得慢（用户口径：管容积当量用车长）。
		for (int i = 1; i < size(); i++) {
			final PneumaticBrakeSpec carSpec = specOf(i);
			final double gap = pipeBar[i - 1] - pipeBar[i];
			if (Math.abs(gap) > 1e-12) {
				final double relaxation = carSpec.getPipePropagationPerSecond() * pipeVolumeScale(cars.get(i).lengthM());
				pipeBar[i] = Math.max(0, Math.min(carSpec.getChargedBar(), pipeBar[i] + gap * relaxation * dtSeconds));
			}
		}
		// ③ 每节车自己的缸压目标：该车分配阀 + 上层补气（按该车缸压量程的比例）
		final double[] targetBar = new double[size()];
		for (int i = 0; i < size(); i++) {
			final PneumaticBrakeSpec carSpec = specOf(i);
			final double assistBar = command.assistRatio() <= 0 ? 0
				: carSpec.getCylinderSpringBar() + command.assistRatio() * (carSpec.getCylinderMaxBar() - carSpec.getCylinderSpringBar());
			targetBar[i] = Math.max(carSpec.distributorCylinderBar(pipeBar[i]), assistBar);
		}
		// ④ 电空混合：电制动替掉**动力车自己**那份机械制动（拖车的闸不动）
		double blended = 0;
		if (availableElectricN > 0 && !emergency) {
			double remaining = availableElectricN;
			for (int i = 0; i < size() && remaining > 1; i++) {
				if (!cars.get(i).powered()) {
					continue;
				}
				final double askN = forceN(i, targetBar[i], speedMetersPerSecond);
				final double coverN = Math.min(askN, remaining);
				if (coverN <= 0) {
					continue;
				}
				remaining -= coverN;
				blended += coverN;
				targetBar[i] = cylinderBarForForceN(i, askN - coverN, speedMetersPerSecond);
			}
		}
		blendedElectricN = blended;
		// ⑤ 缸压按建/缓解速率追目标
		for (int i = 0; i < size(); i++) {
			cylinderBar[i] = specOf(i).stepCylinder(cylinderBar[i], targetBar[i], emergency, dtSeconds);
		}
		// ⑥ 汇总读数 —— **逐车黏着截断**（notes/275 片 5）：每节车自己的闸传不过它自己的 μ_brake(v)·mᵢ·g
		double serviceN = 0;
		double emergencyN = 0;
		double maxBar = 0;
		double cappedN = 0;
		boolean limited = false;
		for (int i = 0; i < size(); i++) {
			final double rawServiceN = forceN(i, cylinderBar[i], speedMetersPerSecond);
			final double limitedServiceN = applyPerCarAdhesion(i, rawServiceN, speedMetersPerSecond);
			serviceN += limitedServiceN;
			cappedN += Math.max(0, rawServiceN - limitedServiceN);
			limited |= limitedServiceN < rawServiceN - 1e-9;
			emergencyN += applyPerCarAdhesion(i, emergencyForceN(i, cylinderBar[i], speedMetersPerSecond), speedMetersPerSecond);
			maxBar = Math.max(maxBar, cylinderBar[i]);
		}
		pneumaticForceN = serviceN;
		emergencyForceN = emergencyN;
		maxCylinderBar = maxBar;
		adhesionCappedN = cappedN;
		perCarAdhesionLimited = limited;
		// 牵引联锁：任何一节车还压着闸就不许牵引（按"该车自己量程的 1%"判，notes/268）
		boolean holding = false;
		for (int i = 0; i < size() && !holding; i++) {
			final PneumaticBrakeSpec carSpec = specOf(i);
			holding = cylinderBar[i] > carSpec.getCylinderSpringBar()
				+ 0.01 * (carSpec.getCylinderMaxBar() - carSpec.getCylinderSpringBar());
		}
		pneumaticHolding = holding;
	}

	/** 级位表插值：归一化诉求 → **车头**列车管目标（bar）。档位正好落在表项上（有级/三手柄与旧口径逐位一致）。 */
	public double pipeTargetBar(double demandRatio) {
		final int count = spec.getPositionCount();
		if (count <= 1) {
			return demandRatio > 0 ? spec.targetPipeBar(0) : spec.getChargedBar();
		}
		final double position = Math.max(0, Math.min(1, demandRatio)) * (count - 1);
		final int low = (int) Math.floor(position);
		final int high = Math.min(count - 1, low + 1);
		final double fraction = position - low;
		return spec.targetPipeBar(low) + (spec.targetPipeBar(high) - spec.targetPipeBar(low)) * fraction;
	}

	/** 单节车的常用制动力（N）：锚 × 缸簧死区与饱和 × 闸片衰减。 */
	private double forceN(int index, double cylinderBar, double speedMetersPerSecond) {
		final BrakeCar car = cars.get(index);
		final PneumaticBrakeSpec carSpec = specOf(index);
		final double usable = Math.max(0, Math.min(1,
			(cylinderBar - carSpec.getCylinderSpringBar()) / (carSpec.getCylinderMaxBar() - carSpec.getCylinderSpringBar())));
		return car.serviceForceN() * usable * carSpec.frictionFactor(speedMetersPerSecond);
	}

	/** 单节车的紧急制动力（N）。 */
	private double emergencyForceN(int index, double cylinderBar, double speedMetersPerSecond) {
		final BrakeCar car = cars.get(index);
		final PneumaticBrakeSpec carSpec = specOf(index);
		final double usable = Math.max(0, Math.min(1,
			(cylinderBar - carSpec.getCylinderSpringBar()) / (carSpec.getCylinderMaxBar() - carSpec.getCylinderSpringBar())));
		return car.emergencyForceN() * usable * carSpec.frictionFactor(speedMetersPerSecond);
	}

	/**
	 * **逐车黏着截断**（notes/275 片 5，规格模块四）：一节车的闸传不到轨面上的那部分不算数。
	 *
	 * <p>为什么必须在**逐车**这一层：{@code TrainPhysics} 的编组级截断用的是整列 {@code m·g} 与
	 * "说话那节车"的 μ —— 于是空车那点轴重被重车的轴重"担保"了。分客车/货车两族之后更明显：
	 * 客车的盘型闸与货车的闸瓦 μ 不同（片 5 的口径：**黏着档是车底属性**），空车或落叶轨上
	 * 真正被截住的往往是**轻的那一节**。</p>
	 *
	 * <p>WSP 是**简化**口径（决定 2）：编组级开关 + 逐车截断，不做逐车 WSP 档、不做减转矩脉冲。
	 * 开着 ⇒ 钳到上限（真车点刹保持峰值微滑）；关掉 ⇒ 断崖到动摩擦（抱死，力反而更小）。</p>
	 *
	 * <p>没有逐车黏着数据（载重或黏着档缺一个：单节等效车底、老夹具）时原样返回 ——
	 * 由控制器的编组级截断兜底，与片 5 之前逐位相同。</p>
	 */
	private double applyPerCarAdhesion(int index, double forceN, double speedMetersPerSecond) {
		final BrakeCar car = cars.get(index);
		if (!car.hasPerCarAdhesion()) {
			return forceN;
		}
		final double limitN = car.brakingAdhesionLimitN(speedMetersPerSecond);
		if (forceN <= limitN) {
			return forceN;
		}
		final PneumaticBrakeSpec carSpec = specOf(index);
		return carSpec.isWspEnabled() ? limitN : Math.min(limitN, carSpec.getWheelSlipMu() * car.normalForceN());
	}

	/** 该车"要出多少力"反推缸压（bar）—— 混合削力时用。 */
	private double cylinderBarForForceN(int index, double forceN, double speedMetersPerSecond) {
		if (forceN <= 0) {
			return 0;
		}
		final BrakeCar car = cars.get(index);
		final PneumaticBrakeSpec carSpec = specOf(index);
		final double fullForceN = car.serviceForceN() * carSpec.frictionFactor(speedMetersPerSecond);
		final double ratio = fullForceN <= 0 ? 1 : Math.max(0, Math.min(1, forceN / fullForceN));
		return carSpec.getCylinderSpringBar() + ratio * (carSpec.getCylinderMaxBar() - carSpec.getCylinderSpringBar());
	}

	// ---- 读数 -------------------------------------------------------------------------------------

	/** 整列**气制动力**（N，正值 = 在刹车）：逐车求和（notes/269：不是"车头缸压折算"）。 */
	public double getPneumaticForceN() { return pneumaticForceN; }

	/** 整列**紧急**气制动力（N）：逐车紧急锚。 */
	public double getEmergencyForceN() { return emergencyForceN; }

	/** 这一拍电空混合**替掉**的机械力（N）：与司机的电制动手柄**取大不相加**（notes/267）。 */
	public double getBlendedElectricN() { return blendedElectricN; }

	/** 全列最大缸压（bar）。 */
	public double getMaxCylinderBar() { return maxCylinderBar; }

	/** 车头（司机那一节）的管压/缸压（bar）—— HUD 与镜像按它显示。 */
	public double getHeadPipeBar() { return size() == 0 ? 0 : pipeBar[0]; }

	public double getHeadCylinderBar() { return size() == 0 ? 0 : cylinderBar[0]; }

	/** 第 {@code index} 节车的管压/缸压（bar）。 */
	public double getPipeBar(int index) { return index >= 0 && index < size() ? pipeBar[index] : 0; }

	public double getCylinderBar(int index) { return index >= 0 && index < size() ? cylinderBar[index] : 0; }

	/** 牵引联锁：还有哪节车压着闸（notes/238/248/268）。 */
	public boolean isPneumaticHolding() { return pneumaticHolding; }

	/** 这一拍有车被**它自己**的黏着上限截住了（notes/275 片 5；诊断/HUD 用）。 */
	public boolean isPerCarAdhesionLimited() { return perCarAdhesionLimited; }

	/** 这一拍被逐车黏着截掉的力（N）—— 与"整列锚之和"的差就是它。 */
	public double getAdhesionCappedN() { return adhesionCappedN; }

	// ---- 连挂接口（notes/270）----------------------------------------------------------------------

	/** 管压回到充风稳定值、缸压归零（新造/彻底重解时用）。 */
	public void reset() {
		for (int i = 0; i < size(); i++) {
			final PneumaticBrakeSpec carSpec = specOf(i);
			pipeBar[i] = carSpec.getChargedBar();
			cylinderBar[i] = 0;
		}
		initialized = true;
		pneumaticForceN = 0;
		emergencyForceN = 0;
		blendedElectricN = 0;
		maxCylinderBar = 0;
		pneumaticHolding = false;
		adhesionCappedN = 0;
		perCarAdhesionLimited = false;
	}

	/**
	 * **逐车气路状态**（{@code 管压比例,缸压比例;…}）—— 与 {@code MmtrComposition.encodeAirStates} 同一格式，
	 * 所以连挂手术那条现成通道（`mmtrAirState`）不用改就能带上它。
	 */
	public String encodeState() {
		final StringBuilder builder = new StringBuilder();
		for (int i = 0; i < size(); i++) {
			if (i > 0) {
				builder.append(';');
			}
			final PneumaticBrakeSpec carSpec = specOf(i);
			builder.append(round(pipeBar[i] / Math.max(1e-9, carSpec.getChargedBar())))
				.append(',').append(round(cylinderBar[i] / Math.max(1e-9, carSpec.getCylinderMaxBar())));
		}
		return builder.toString();
	}

	/** 从 {@link #encodeState()} 的串恢复（**解挂切分/镜像种子**用）；坏串忽略、多余的项忽略、缺的项保持原状。 */
	public void applyState(@Nullable String state) {
		if (state == null || state.isEmpty()) {
			return;
		}
		if (!initialized) {
			reset();
		}
		final String[] units = state.split(";");
		for (int i = 0; i < units.length && i < size(); i++) {
			final String[] pair = units[i].split(",");
			if (pair.length != 2) {
				continue;
			}
			try {
				final PneumaticBrakeSpec carSpec = specOf(i);
				pipeBar[i] = Math.max(0, Math.min(carSpec.getChargedBar(), Double.parseDouble(pair[0]) * carSpec.getChargedBar()));
				cylinderBar[i] = Math.max(0, Math.min(carSpec.getCylinderEmergencyBar(), Double.parseDouble(pair[1]) * carSpec.getCylinderMaxBar()));
			} catch (NumberFormatException ignored) {
				// 坏项：保持该车原状
			}
		}
	}

	/**
	 * **连挂之后**：新挂上来的车与已编组的车"不是一根已经充好风的管子"——
	 * 无动力车按**管压 0 / 缸压 0**起（随后按充风速率自己充），有动力车自带风源、保留原状态。
	 *
	 * @param firstAddedIndex 第一个来自被连挂那一列的车（下标）
	 */
	public void seedAfterCoupling(int firstAddedIndex) {
		for (int i = Math.max(0, firstAddedIndex); i < size(); i++) {
			if (!cars.get(i).powered()) {
				pipeBar[i] = 0;
				cylinderBar[i] = 0;
			}
		}
	}

	private static String round(double value) {
		return String.valueOf(Math.round(value * 1e6) / 1e6);
	}
}
