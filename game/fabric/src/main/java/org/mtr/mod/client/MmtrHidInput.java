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
		final State state = new State(
			MmtrHidMapping.cruiseKmhFromAxis(axes.get(axisX), spec.getCruiseMaxKmh(), spec.getCruiseStepKmh(), deadzone, invertX),
			MmtrHidMapping.driveHandleFromAxis(axes.get(axisY), ThreeHandleSpec.DRIVE_HANDLE_MAX, deadzone, invertY),
			MmtrHidMapping.brakePositionFromAxis(axes.get(axisZ), spec.getBrakePositionCount(), deadzone, invertZ)
		);
		logAxesIfTracing(axes, state);
		return state;
	}

	/**
	 * **单手柄车底**：只取 Y 轴（归一化）—— 一根杆走完全程，不需要三手柄规格。
	 *
	 * <p>为什么不能复用 {@link #poll}：它按 {@link ThreeHandleSpec} 解释 {@code X/Z} 且**没有规格就返回 null**，
	 * 而 NOTCHED 车底（SAF420 的 P5+B8+EB）在引擎里本来就没有规格 —— 于是"有手柄也读不出杆位"。
	 * 这里把 Y 轴单独取出来（与 {@code poll} **同一套**轴号/反转/设备选定），换算交给
	 * {@link MmtrHidMapping#singleHandleFromAxis}（纯函数、有真值表）。X/Z 在单手柄上无意义，故不读。</p>
	 *
	 * @return {@code null} = 没有可用手柄（没插 / 轴数不够）——调用方回退到键盘
	 */
	public static @Nullable SingleHandleAxis pollSingleHandleAxis() {
		if (!ensureJoystick(null)) {
			return null;
		}
		final FloatBuffer axes = GLFW.glfwGetJoystickAxes(joystickId);
		if (axes == null || axes.capacity() <= axisY || axes.capacity() <= 0) {
			return null;
		}
		final float raw = axes.get(axisY);
		// 反转在这里归一（与三手柄那条路一致）：调用方拿到的永远是"正值 = 牵引侧"。
		return new SingleHandleAxis(invertY ? -raw : raw, deadzone);
	}

	/** 单手柄输入：Y 轴原值（已按 {@code invert.y} 归一）+ 当时生效的死区。 */
	public record SingleHandleAxis(double axis, double deadzone) {
	}

	/** 上一次打"原始轴值"的时间（1 s 节流）。 */
	private static long lastAxisLogMillis;

	/**
	 * **原始轴值日志**（notes/254）：用户口径 2026-09-23「在油门设置在 50% 时也是 300kn / 现在有 20%」——
	 * 到底是"我推到了 50% 而游戏只认 19%"（摇杆行程/校准问题）还是"游戏认对了但力不对"，
	 * 光看手柄百分比分不出来。这一行把**轴的原值**与**换算出来的手柄位**并排打出来：
	 *
	 * <pre>[MMTR-HID] 轴原值：X=0.000 Y=0.190 Z=-1.000 → 定速=0 油门=18 制动=0（死区 0.05）</pre>
	 *
	 * 只在**真的动了杆**（任一轴出死区）且 `-Dmmtr.trace=true` 时打，1 s 一次。
	 */
	private static void logAxesIfTracing(FloatBuffer axes, State state) {
		if (!org.mtr.core.mmtr.MmtrTrace.isEnabled()) {
			return;
		}
		final float x = axes.get(axisX);
		final float y = axes.get(axisY);
		final float z = axes.get(axisZ);
		if (Math.abs(x) <= deadzone && Math.abs(y) <= deadzone && Math.abs(z) <= deadzone) {
			return;
		}
		final long now = System.currentTimeMillis();
		if (now - lastAxisLogMillis < 1000) {
			return;
		}
		lastAxisLogMillis = now;
		org.mtr.core.mmtr.MmtrTrace.log("[MMTR-HID] 轴原值：X=" + Math.round(x * 1000) / 1000.0
			+ " Y=" + Math.round(y * 1000) / 1000.0 + " Z=" + Math.round(z * 1000) / 1000.0
			+ " → 定速=" + state.cruiseKmh() + " 油门=" + state.driveHandle() + " 制动=" + state.brakePosition()
			+ "（死区 " + deadzone + "）");
	}

	/** 有没有手柄接着（HUD/诊断用）。 */
	public static boolean isConnected() {
		return joystickId >= 0;
	}

	/** 三根手柄的绝对位置（轴值即杆位）。 */
	public record State(int cruiseKmh, int driveHandle, int brakePosition) {
	}

	/** 找设备：属性指定优先，否则扫第一个连着的；掉线时清空并允许下次重新找。 */
	private static boolean ensureJoystick(@Nullable ThreeHandleSpec spec) {
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
	private static void logDevice(@Nullable ThreeHandleSpec spec) {
		final String name = GLFW.glfwGetJoystickName(joystickId);
		final FloatBuffer axes = GLFW.glfwGetJoystickAxes(joystickId);
		final String description = (name == null ? "未知手柄" : name) + " (GLFW id " + joystickId + ", 轴 "
			+ (axes == null ? 0 : axes.capacity()) + ")";
		if (description.equals(loggedDevice)) {
			return;
		}
		loggedDevice = description;
		if (spec == null) {
			/*
			 * 单手柄车底：只有 Y 轴有意义（X 定速 / Z 制动在这类车上不存在，不读）。
			 * 日志必须说清"生效的是哪一种映射"——同一只手柄在三手柄车上读 X/Y/Z、在单手柄车上只读 Y，
			 * 不写清楚的话，"推了 Z 轴没反应"看起来就像手柄坏了。
			 */
			Init.LOGGER.info("[MMTR-HID] 手柄 {}：**单手柄车底** —— Y({})→那唯一一根杆"
					+ "（正=牵引 · 中位=关闭 · 负=制动 · 推到底=紧急）；死区 {}；反转 Y={}（X/Z 不读）",
				description, axisY, deadzone, invertY);
			return;
		}
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
