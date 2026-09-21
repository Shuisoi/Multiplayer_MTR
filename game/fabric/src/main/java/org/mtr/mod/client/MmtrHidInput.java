package org.mtr.mod.client;

import org.lwjgl.glfw.GLFW;
import org.mtr.core.mmtr.MmtrHidMapping;
import org.mtr.core.mmtr.ThreeHandleSpec;
import org.mtr.mod.Init;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;

/**
 * HID（手柄/摇杆）输入：把 **DirectInput 的 X / Y / Z** 三根轴读成三根手柄的位置。
 *
 * <table>
 *   <caption>轴映射（与用户口径一致）</caption>
 *   <tr><th>轴</th><th>手柄</th><th>−1 端</th><th>+1 端</th></tr>
 *   <tr><td>X</td><td>定速巡航 AFB</td><td>0（关闭）</td><td>车型上限（160 km/h，步长 5）</td></tr>
 *   <tr><td>Y</td><td>油门手柄（双向）</td><td>满电阻制动</td><td>满牵引（中位 = 关闭）</td></tr>
 *   <tr><td>Z</td><td>气制动手柄</td><td>运行（缓解）</td><td>EB</td></tr>
 * </table>
 *
 * <h2>只轮询，不解释</h2>
 *
 * <p>轴值 → 档位的换算全在引擎的 {@link MmtrHidMapping}（纯函数、有真值表）；这里只做三件环境相关的事：
 * 找设备、读轴、按系统属性校准。方向/轴号/死区**都不写死在代码里** —— 实物千差万别，
 * 让用户用启动参数校准，比让他等一次重新编译快得多：</p>
 *
 * <pre>
 *   -Dmmtr.hid.joystick=0            选 GLFW 手柄号（0 = GLFW_JOYSTICK_1）；默认扫第一个连着的手柄
 *   -Dmmtr.hid.axis.x/y/z=0,1,2     轴号（DirectInput 惯例 X/Y/Z）
 *   -Dmmtr.hid.invert.x/y/z=true     逐轴反转（推杆方向与预期相反时）
 *   -Dmmtr.hid.deadzone=0.05         死区
 * </pre>
 *
 * <p>设备选定与断开会各打一行日志（含生效的映射），否则"手柄没反应"又是一种只能靠猜的现场。</p>
 *
 * <p>只读不写：本类不碰手柄状态机（那是 {@link MmtrDriveInput} 的），也不发包 —— 它只回答
 * "按现在的杆位，三根手柄应该在哪"。</p>
 */
public final class MmtrHidInput {

	private MmtrHidInput() {
	}

	private static final int JOYSTICK_SCAN_LIMIT = GLFW.GLFW_JOYSTICK_LAST + 1;

	private static double deadzone = 0.05;
	private static int configuredJoystick = -1;
	private static int axisX = 0;
	private static int axisY = 1;
	private static int axisZ = 2;
	private static boolean invertX;
	private static boolean invertY;
	private static boolean invertZ;

	/** 当前选中的 GLFW 手柄号（−1 = 没有）。 */
	private static int joystickId = -1;
	/** 上一次打日志时描述的设备（掉线/换设备时重新打）。 */
	private static String loggedDevice = "";

	static {
		readProperties();
	}

	/**
	 * 按当前杆位算出三根手柄应当在哪。
	 *
	 * @return {@code null} = 没有可用手柄（没插 / 轴数不够）——调用方按"纯键盘"处理
	 */
	public static @Nullable State poll(ThreeHandleSpec spec) {
		if (!ensureJoystick(spec)) {
			return null;
		}
		final FloatBuffer axes = GLFW.glfwGetJoystickAxes(joystickId);
		if (axes == null || axes.capacity() <= Math.max(axisX, Math.max(axisY, axisZ)) || axes.capacity() <= 0) {
			return null;
		}
		return new State(
			MmtrHidMapping.cruiseKmhFromAxis(axes.get(axisX), spec.getCruiseMaxKmh(), spec.getCruiseStepKmh(), deadzone, invertX),
			MmtrHidMapping.driveHandleFromAxis(axes.get(axisY), ThreeHandleSpec.DRIVE_HANDLE_MAX, deadzone, invertY),
			MmtrHidMapping.brakePositionFromAxis(axes.get(axisZ), spec.getBrakePositionCount(), deadzone, invertZ)
		);
	}

	/** 有没有手柄接着（HUD/诊断用）。 */
	public static boolean isConnected() {
		return joystickId >= 0;
	}

	/** 三根手柄的绝对位置（轴值即杆位）。 */
	public record State(int cruiseKmh, int driveHandle, int brakePosition) {
	}

	/** 找设备：属性指定优先，否则扫第一个连着的；掉线时清空并允许下次重新找。 */
	private static boolean ensureJoystick(ThreeHandleSpec spec) {
		if (joystickId >= 0 && GLFW.glfwJoystickPresent(joystickId)) {
			return true;
		}
		if (joystickId >= 0) {
			Init.LOGGER.info("[MMTR-HID] 手柄断开（GLFW id {}），回退到键盘", joystickId);
			joystickId = -1;
			loggedDevice = "";
		}
		final int found = configuredJoystick >= 0
			? (GLFW.glfwJoystickPresent(configuredJoystick) ? configuredJoystick : -1)
			: scanJoystick();
		if (found < 0) {
			return false;
		}
		joystickId = found;
		logDevice(spec);
		return true;
	}

	private static int scanJoystick() {
		for (int id = 0; id < JOYSTICK_SCAN_LIMIT; id++) {
			if (GLFW.glfwJoystickPresent(id)) {
				return id;
			}
		}
		return -1;
	}

	/** 设备选定/变化时打一行"生效的映射"：否则"手柄没反应"又是一种只能靠猜的现场。 */
	private static void logDevice(ThreeHandleSpec spec) {
		final String name = GLFW.glfwGetJoystickName(joystickId);
		final FloatBuffer axes = GLFW.glfwGetJoystickAxes(joystickId);
		final String description = (name == null ? "未知手柄" : name) + " (GLFW id " + joystickId + ", 轴 "
			+ (axes == null ? 0 : axes.capacity()) + ")";
		if (description.equals(loggedDevice)) {
			return;
		}
		loggedDevice = description;
		Init.LOGGER.info("[MMTR-HID] 手柄 {}：X({})→定速巡航 0..{} 步{} · Y({})→油门手柄 ±{}（{}） · Z({})→制动 运行..EB；"
				+ "死区 {}；反转 X={} Y={} Z={}（用 -Dmmtr.hid.* 校准）",
			description, axisX, spec.getCruiseMaxKmh(), spec.getCruiseStepKmh(), axisY, ThreeHandleSpec.DRIVE_HANDLE_MAX,
			invertY ? "已反转：正=电阻制动" : "正=牵引", axisZ, deadzone, invertX, invertY, invertZ);
	}

	private static void readProperties() {
		deadzone = clampDeadzone(doubleProperty("mmtr.hid.deadzone", MmtrHidMapping.DEFAULT_DEADZONE));
		configuredJoystick = intProperty("mmtr.hid.joystick", -1);
		axisX = intProperty("mmtr.hid.axis.x", 0);
		axisY = intProperty("mmtr.hid.axis.y", 1);
		axisZ = intProperty("mmtr.hid.axis.z", 2);
		invertX = Boolean.getBoolean("mmtr.hid.invert.x");
		invertY = Boolean.getBoolean("mmtr.hid.invert.y");
		invertZ = Boolean.getBoolean("mmtr.hid.invert.z");
	}

	private static double clampDeadzone(double value) {
		return Math.max(0, Math.min(0.5, value));
	}

	private static int intProperty(String key, int fallback) {
		try {
			return Integer.parseInt(System.getProperty(key, String.valueOf(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private static double doubleProperty(String key, double fallback) {
		try {
			return Double.parseDouble(System.getProperty(key, String.valueOf(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
