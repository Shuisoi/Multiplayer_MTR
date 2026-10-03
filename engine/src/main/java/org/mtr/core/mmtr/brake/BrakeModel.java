package org.mtr.core.mmtr.brake;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.AirBrakeStateful;
import org.mtr.core.mmtr.physics.PneumaticBrakeSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * **制动模型宿主**（notes/270）：把一个 {@link BrakeSystem} 连同它的对外读数（归一化管压/缸压、
 * 整列气制动力、联锁、连挂状态串）打包成"任何一个控制器都能挂在身上"的一件东西。
 *
 * <h2>为什么要有这一层</h2>
 *
 * <p>可复用性的四个轴里，前三个（列车逻辑、操纵方式、手柄档位）已经分别落在
 * {@link PneumaticBrakeSpec} / {@code DriveController} / {@code ThreeHandleSpec} 上。剩下那个
 * ——<b>「这套制动模型谁都能用」</b>——需要一个共同的落点：以前三手柄控制器独占气路状态，
 * 有级/无级想用就得把 {@code ensureBrakeSystem} / {@code syncNormalized} / {@code encode/apply/seed}
 * 这一坨抄一遍（抄完就有三份"气路"要同步维护，正是 notes/270 要拆掉的东西）。</p>
 *
 * <p>现在是：控制器只持有 {@code new BrakeModel("三手柄")}，每拍把三根手柄折成 {@link BrakeCommand}
 * 丢进来，读数是同一套方法名。**没配气压口径的车底**（{@link #step} 收到 {@code air == null}）
 * 返回 {@code false}，控制器照旧走它原来的归一化模型 —— 老车底逐位不变。</p>
 *
 * <h2>连挂接口</h2>
 *
 * <p>{@link #setCars} 换车列，{@link #encodeState}/{@link #applyState}/{@link #seedAfterCoupling}
 * 让气压状态跨过"合并/切分"这件事（格式与 {@code MmtrComposition.encodeAirStates} 相同）。
 * 这就是"车体连挂形式"的接口：单机 / 机车+客车 / 双机重联 / 货车都只是不同的 {@link BrakeCar} 列表。</p>
 */
public final class BrakeModel implements AirBrakeStateful {

	/** 诊断用标签（哪个操纵方式持有的这份模型）。 */
	private final String label;
	/** 这一列车的逐车制动描述；空 = 单车（用等效锚造一节虚拟车）。 */
	private List<BrakeCar> cars = List.of();
	private @Nullable BrakeSystem system;
	private @Nullable PneumaticBrakeSpec spec;
	/**
	 * 还没建系统时收到的状态串（连挂手术发生在"新车上还没人开"的那一拍很常见）：
	 * 先存着，等 {@link #ensure} 造出系统来的那一拍灌进去 —— 否则那份切分好的气压会被静默丢掉。
	 */
	private String pendingState = "";

	// 归一化读数（对外/镜像/联锁约定：管压满格 = 充风压力，缸压满格 = 缸压上限）
	private double pipePressure = 1.0;
	private double brakeCylinderPressure;

	// 最近一拍的力读数（HUD/镜像/日志）
	private double pneumaticForceN;
	private double emergencyForceN;
	private double blendedElectricN;
	private boolean pneumaticHolding;

	public BrakeModel(String label) {
		this.label = label == null ? "" : label;
	}

	public String getLabel() {
		return label;
	}

	/**
	 * **连挂形式**：设置这一列车的逐车制动描述。
	 *
	 * <p>车列变了 ⇒ 制动系统按新车列重建（新车的管压/缸压从静止起）。<b>要保住气压状态</b>
	 * 就紧接着 {@link #applyState}(旧状态串)，或按连挂规则用 {@link #seedAfterCoupling}。</p>
	 */
	public void setCars(@Nullable List<BrakeCar> cars) {
		final List<BrakeCar> next = cars == null ? List.of() : List.copyOf(cars);
		if (next.equals(this.cars)) {
			return;
		}
		this.cars = next;
		this.system = null;
	}

	/** 这一拍是不是按 bar 口径在跑（车底配了气压参数）。 */
	public boolean isPneumatic() {
		return spec != null;
	}

	/**
	 * 这一列是不是**多节编组**（逐车管压的判据）。
	 *
	 * <p>看的是**连挂形式**（车列长度），不是"系统建起来没有"—— 装配完车列但还没跑第一拍时也该是 true
	 * （连挂手术/换端就发生在那一拍）。是否真的在跑 bar 口径另看 {@link #isPneumatic()}。</p>
	 */
	public boolean isPerCar() {
		return cars.size() > 1 || (system != null && system.size() > 1);
	}

	/** 这一拍制动系统里的车数（单车 = 1，未配气压口径 = 0）。 */
	public int size() {
		return system == null ? 0 : system.size();
	}

	/**
	 * 推进一拍。
	 *
	 * @param air                 这一列车的制动口径；{@code null} = 没配 ⇒ 不接管，返回 {@code false}
	 * @param equivalentCar       单车时用的等效车（整列锚）；多节编组时忽略
	 * @param command             制动诉求（{@link BrakeCommand}，与操纵方式无关）
	 * @param speedMetersPerSecond 当前速度
	 * @param availableElectricN  这一拍可用的电制动力（N，0 = 不混合）
	 * @param dtSeconds           步长
	 * @return {@code true} = 这一拍的气制动力已由本模型给出（调用方用 {@link #getPneumaticForceN()}）
	 */
	public boolean step(@Nullable PneumaticBrakeSpec air, @Nullable BrakeCar equivalentCar, BrakeCommand command,
			double speedMetersPerSecond, double availableElectricN, double dtSeconds) {
		if (air == null) {
			spec = null;
			system = null;
			pneumaticForceN = 0;
			emergencyForceN = 0;
			blendedElectricN = 0;
			pneumaticHolding = false;
			return false;
		}
		ensure(air, equivalentCar);
		final BrakeSystem brakes = system;
		if (brakes == null) {
			return false;
		}
		brakes.step(command, speedMetersPerSecond, availableElectricN, dtSeconds);
		pneumaticForceN = brakes.getPneumaticForceN();
		emergencyForceN = brakes.getEmergencyForceN();
		blendedElectricN = brakes.getBlendedElectricN();
		pneumaticHolding = brakes.isPneumaticHolding();
		// 对外读数按**车头**折算（驾驶室的表看的就是司机那一节，notes/268）
		pipePressure = clamp01(brakes.getHeadPipeBar() / Math.max(1e-9, air.getChargedBar()));
		brakeCylinderPressure = clamp01(brakes.getHeadCylinderBar() / Math.max(1e-9, air.getCylinderMaxBar()));
		return true;
	}

	/** 按需（重）建制动系统：口径换了 / 车列换了 / 还没建。 */
	private void ensure(PneumaticBrakeSpec air, @Nullable BrakeCar equivalentCar) {
		final int expectedSize = cars.isEmpty() ? 1 : cars.size();
		if (system == null || spec != air || system.size() != expectedSize) {
			final List<BrakeCar> effective;
			if (cars.isEmpty()) {
				// 单车：整列等效锚造一节虚拟车（动力车）——"单车一条路、编组另一条路"的分叉到此为止
				effective = List.of(equivalentCar == null ? BrakeCar.unbraked(false) : equivalentCar);
			} else {
				effective = new ArrayList<>(cars);
			}
			system = new BrakeSystem(effective, air);
			spec = air;
			if (!pendingState.isEmpty()) {
				system.applyState(pendingState);
				pendingState = "";
				pipePressure = clamp01(system.getHeadPipeBar() / Math.max(1e-9, air.getChargedBar()));
				brakeCylinderPressure = clamp01(system.getHeadCylinderBar() / Math.max(1e-9, air.getCylinderMaxBar()));
			}
		}
	}

	/** 这一拍**整列气制动力**（N，正值 = 在刹车）。 */
	public double getPneumaticForceN() {
		return pneumaticForceN;
	}

	/** 这一拍**整列紧急制动力**（N，按紧急力锚）。 */
	public double getEmergencyForceN() {
		return emergencyForceN;
	}

	/** 这一拍**被电制动替掉的气制动力**（N，已计入 {@link #getPneumaticForceN} 之外的那一份）。 */
	public double getBlendedElectricN() {
		return blendedElectricN;
	}

	/** 这一拍**还有车压着闸**（牵引联锁用；逐车判全列，notes/268）。 */
	public boolean isPneumaticHolding() {
		return pneumaticHolding;
	}

	// ---- 归一化读数（对外/镜像）--------------------------------------------------------------------

	@Override
	public double getPipePressure() {
		return pipePressure;
	}

	@Override
	public double getBrakeCylinderPressure() {
		return brakeCylinderPressure;
	}

	/** 镜像种子：权威端的归一化管压/缸压灌进这个模型（两端必须同一套 bar 语义）。 */
	@Override
	public void setState(double pipePressure, double brakeCylinderPressure) {
		this.pipePressure = clamp01(pipePressure);
		this.brakeCylinderPressure = clamp01(brakeCylinderPressure);
		if (system != null) {
			system.applyState(this.pipePressure + "," + this.brakeCylinderPressure);
		}
	}

	// ---- bar 读数（HUD/日志/测试）-------------------------------------------------------------------

	/** 车头**列车管压力**（bar）；未配气压口径 = 0（那一路读数走归一化字段）。 */
	public double getPipeBar() {
		return system == null ? 0 : system.getHeadPipeBar();
	}

	/** 车头**制动缸压力**（bar）；未配气压口径 = 0。 */
	public double getCylinderBar() {
		return system == null ? 0 : system.getHeadCylinderBar();
	}

	/** 第 {@code index} 节车的缸压（bar）。 */
	public double getCylinderBar(int index) {
		return system == null ? (index == 0 ? getCylinderBar() : 0) : system.getCylinderBar(index);
	}

	/** 第 {@code index} 节车的管压（bar）。 */
	public double getPipeBar(int index) {
		return system == null ? (index == 0 ? getPipeBar() : 0) : system.getPipeBar(index);
	}

	// ---- 连挂接口（notes/270）----------------------------------------------------------------------

	/** 逐车气路状态串（{@code 管压比例,缸压比例;…}）：与 {@code MmtrComposition.encodeAirStates} 同格式。 */
	public String encodeState() {
		return system == null ? pendingState : system.encodeState();
	}

	/** 从状态串恢复（解挂切分 / 镜像种子 / 连挂后保住原状态）。系统还没建就先存着（见 {@link #pendingState}）。 */
	public void applyState(@Nullable String airState) {
		if (airState == null || airState.isEmpty()) {
			return;
		}
		if (system == null) {
			pendingState = airState;
			return;
		}
		system.applyState(airState);
		final PneumaticBrakeSpec active = spec;
		if (active != null) {
			pipePressure = clamp01(system.getHeadPipeBar() / Math.max(1e-9, active.getChargedBar()));
			brakeCylinderPressure = clamp01(system.getHeadCylinderBar() / Math.max(1e-9, active.getCylinderMaxBar()));
		}
	}

	/** 连挂之后：新挂上来的无动力车按管压 0/缸压 0 起（随后自己充风），动力车自带风源。 */
	public void seedAfterCoupling(int firstAddedCarIndex) {
		if (system != null) {
			system.seedAfterCoupling(firstAddedCarIndex);
			applyState(system.encodeState());
		}
	}

	/** 复位（重连挂 / 重生 / 切换模式）：回到"满管、无缸压"。 */
	public void reset() {
		pipePressure = 1.0;
		brakeCylinderPressure = 0;
		pneumaticForceN = 0;
		emergencyForceN = 0;
		blendedElectricN = 0;
		pneumaticHolding = false;
		pendingState = "";
		if (system != null) {
			system.reset();
		}
	}

	private static double clamp01(double value) {
		return Math.max(0, Math.min(1, value));
	}
}
