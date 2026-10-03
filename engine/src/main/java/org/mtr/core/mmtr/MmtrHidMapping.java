package org.mtr.core.mmtr;

/**
 * HID（手柄/摇杆）轴 → **三手柄位置**的换算：DirectInput 惯例 **X = 定速巡航、Y = 油门手柄、Z = 气制动手柄**。
 *
 * <h2>为什么放在引擎包里</h2>
 *
 * <p>它是一张"轴值 → 档位"的真值表（死区、量化、端点吸附），值得被单测钉住；而客户端的测试集要把整个
 * 客户端源码树编出来（需要引擎 jar），引擎的测试集不需要。纯函数、零依赖，放这儿两边都方便。</p>
 *
 * <h2>方向约定（可以逐轴反转）</h2>
 *
 * <ul>
 *   <li><b>X（AFB）</b>：−1 → 0（关闭），+1 → 车型上限（160），按步长（5 km/h）量化；
 *       这是"单向往前推"的杆，最左 = 关。</li>
 *   <li><b>Y（油门）</b>：−1 → 满电阻制动，0（含死区）→ 关闭，+1 → 满牵引。
 *       **中位 = 关闭**，正好对上双向手柄的中央位置 ✓</li>
 *   <li><b>Z（制动）</b>：−1 → 运行（缓解，含死区），+1 → EB，中间均匀分布（运行/1A/1B/2…8/EB 共 11 位）。</li>
 * </ul>
 *
 * <p>若推杆方向与实物相反（DirectInput 的"往前推"是负值，各家杆不一），用客户端的
 * {@code -Dmmtr.hid.invert.x/y/z=true} 逐轴翻转，不必改代码。</p>
 *
 * <p><b>一处有意的不对称</b>：模拟轴上**"最小"档（位置 ±1，2%）不可达** —— 它落在死区里，
 * 一出死区就是 5%。电噪声必须有地方消化，而 2% 这种档位本就属于离散手柄与键盘；
 * 要它就得用键盘单击 ↑/↓（单击正好一步）。</p>
 */
public final class MmtrHidMapping {

	/** 轴值死区（默认 0.05）：手抖与电位器噪声不该让手柄跳档。 */
	public static final double DEFAULT_DEADZONE = 0.05;

	private MmtrHidMapping() {
	}

	/**
	 * 定速巡航：轴 −1…+1 线性映射到 0…{@code maxKmh}，再按 {@code stepKmh} 量化。
	 *
	 * @param invert true = 把"推到底"换到另一端（实物方向相反时用）
	 */
	public static int cruiseKmhFromAxis(double axis, int maxKmh, int stepKmh, double deadzone, boolean invert) {
		final double value = clamp(invert ? -axis : axis, -1, 1);
		final int max = Math.max(1, maxKmh);
		final int step = Math.max(1, stepKmh);
		final int raw = (int) Math.round((value + 1) / 2 * max);
		final int snapped = Math.round((float) raw / step) * step;
		return clamp(snapped, 0, max);
	}

	/**
	 * 油门手柄（双向）：−1 = 满电阻制动，0（含死区）= 关闭，+1 = 满牵引；量程 ±{@code maxHandle}。
	 *
	 * <p>死区**只做中央吸附**（回到关闭位），不做缩放：出了死区就按真实比例给力，
	 * 否则"最小档"会被死区吃掉（最小档在 ±1，只有满量程的 1%）。</p>
	 */
	public static int driveHandleFromAxis(double axis, int maxHandle, double deadzone, boolean invert) {
		final double value = clamp(invert ? -axis : axis, -1, 1);
		final int max = Math.max(1, maxHandle);
		if (Math.abs(value) <= Math.max(0, deadzone)) {
			return ThreeHandleSpec.DRIVE_HANDLE_CLOSED;
		}
		// 按绝对值取整再补符号：`Math.round(-48.5)` 是 −48（半数向上），直接乘会让两侧差一格。
		// 这是一根**镜像**手柄（牵引/电阻制动对称），所以两侧必须严格对称。
		final int magnitude = (int) Math.round(Math.abs(value) * max);
		return clamp(value < 0 ? -magnitude : magnitude, -max, max);
	}

	/**
	 * 气制动手柄：−1 = 运行（缓解），+1 = EB，中间均匀分布 {@code positionCount} 个位置（含两端）。
	 *
	 * <p>两端各留一个死区：拉到底稳稳地是"运行"（缓解位要能盲拉到位），推到顶稳稳地是 EB
	 * （紧急位必须能盲推到）。</p>
	 */
	public static int brakePositionFromAxis(double axis, int positionCount, double deadzone, boolean invert) {
		final int last = Math.max(1, positionCount) - 1;
		final double value = clamp(invert ? -axis : axis, -1, 1);
		final double band = Math.max(0, deadzone) * 2;
		if (value <= -1 + band) {
			return ThreeHandleSpec.runningPosition();
		}
		if (value >= 1 - band) {
			return last;
		}
		return clamp((int) Math.round((value + 1) / 2 * last), 0, last);
	}

	/**
	 * **单手柄**：同一根 Y 轴映射到那唯一一根杆（用户口径 2026-09-25「控制器肯定是 Y 轴直接分配到单手柄上就行了呗」）。
	 *
	 * <p>为什么单开一条而不是复用 {@link #driveHandleFromAxis}：两者量程的**含义**不同。
	 * 三手柄的油门是「牵引 ↔ 电阻制动」的**对称镜像**（±97，0 = 关闭），制动在另一根杆上；
	 * 而单手柄（SAF420 的 P5+B8+EB）是**一根杆走完全程**：正 = 牵引 1..P、0 = 关闭、
	 * 负 = 制动 1..B，**再往外一格 = 紧急位**。所以档位是按各自侧的实际档数**分段**映射的，不能按比例硬套。</p>
	 *
	 * <p>分段边界（{@code axis} 为 −1…+1，且假定调用方已按 {@code invertY} 归一到"推到底 = 满牵引"）：</p>
	 * <ul>
	 *   <li>{@code |axis| ≤ deadzone} → {@code 0}（关闭位）—— 与三手柄同样的中央吸附；</li>
	 *   <li>{@code axis > 0} → {@code 1..P} 按 {@code axis} 分段（推到底 = P 最大牵引）；</li>
	 *   <li>{@code axis < 0} → 中间 {@code B} 格按行程比例给 {@code −B..−1}，而**最后一段留给紧急位**
	 *       {@code −(B+1)}，与 {@link #brakePositionFromAxis} 把两端留给"运行/EB"是同一个道理
	 *       （紧急位必须能盲推到底）。</li>
	 * </ul>
	 *
	 * <p>结果与客户端 {@code clampSingle} 的值域**逐位一致**（{@code [-(B+1), +P]}）。</p>
	 *
	 * @param axis         −1…+1（已按 invert 归一：+1 = 满牵引，−1 = 紧急）
	 * @param powerNotches 牵引档数 P（≥1）
	 * @param brakeNotches 制动档数 B（≥1）
	 */
	public static int singleHandleFromAxis(double axis, int powerNotches, int brakeNotches, double deadzone) {
		final double value = clamp(axis, -1, 1);
		if (Math.abs(value) <= Math.max(0, deadzone)) {
			return 0;
		}
		final int highest = Math.max(1, powerNotches);
		final int service = Math.max(1, brakeNotches);
		if (value > 0) {
			return clamp((int) Math.round(value * highest), 1, highest);
		}
		final double magnitude = -value;
		if (magnitude >= 1 - Math.max(0, deadzone)) {
			return -(service + 1);
		}
		return -clamp((int) Math.round(magnitude * service), 1, service);
	}

	/** 轴值是否已经离开死区（用于"这根轴在动"的判据）。 */
	public static boolean isOutsideDeadzone(double axis, double deadzone) {
		return Math.abs(axis) > Math.max(0, deadzone);
	}

	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }

	private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
}
