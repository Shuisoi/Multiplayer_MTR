package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HID 轴 → 三手柄位置的真值表（DirectInput X=AFB / Y=油门 / Z=制动）。
 *
 * <p>钉住三件事：**中央关闭**（油门中位不能有残余力）、**两端吸附**（缓解位与 EB 位要能盲拉到）、
 * **逐轴可反转**（实物方向相反时不改代码）。</p>
 */
public final class MmtrHidMappingTests {

	private static final double DEADZONE = MmtrHidMapping.DEFAULT_DEADZONE;

	@Test
	public void throttleAxisIsCentredOnClosed() {
		// Y = −1 满电阻制动 … 0 关闭 … +1 满牵引
		assertEquals(0, MmtrHidMapping.driveHandleFromAxis(0, 97, DEADZONE, false), "中位 = 关闭");
		assertEquals(0, MmtrHidMapping.driveHandleFromAxis(0.04, 97, DEADZONE, false), "死区内 = 关闭");
		assertEquals(0, MmtrHidMapping.driveHandleFromAxis(-0.04, 97, DEADZONE, false), "死区内（负侧）= 关闭");
		assertEquals(97, MmtrHidMapping.driveHandleFromAxis(1, 97, DEADZONE, false), "+1 = 满牵引");
		assertEquals(-97, MmtrHidMapping.driveHandleFromAxis(-1, 97, DEADZONE, false), "−1 = 满电阻制动");
		assertEquals(49, MmtrHidMapping.driveHandleFromAxis(0.5, 97, DEADZONE, false));
		assertEquals(-49, MmtrHidMapping.driveHandleFromAxis(-0.5, 97, DEADZONE, false), "镜像必须严格对称（半数取整不能偏向一侧）");
		// 死区只做中央吸附：刚出死区就落到 **5%**（= 百分比网格的第一档）。
		// 注意：**"最小"档（2%，位置 ±1）在模拟轴上不可达** —— 它落在死区里，会被并进"关闭"。
		// 这是有意的：模拟杆的电噪声必须有地方消化，而"最小"这种 2% 的档位本就属于离散手柄/键盘。
		assertEquals(5, MmtrHidMapping.driveHandleFromAxis(DEADZONE + 0.005, 97, DEADZONE, false),
			"刚出死区 = 5%（最小档在模拟轴上不可达，见 MmtrHidMapping 类注释）");
		// 越界钳位
		assertEquals(97, MmtrHidMapping.driveHandleFromAxis(3, 97, DEADZONE, false));
		assertEquals(-97, MmtrHidMapping.driveHandleFromAxis(-3, 97, DEADZONE, false));
	}

	@Test
	public void throttleAxisCanBeInvertedPerAxis() {
		assertEquals(-97, MmtrHidMapping.driveHandleFromAxis(1, 97, DEADZONE, true), "反转后 +1 = 满电阻制动");
		assertEquals(97, MmtrHidMapping.driveHandleFromAxis(-1, 97, DEADZONE, true));
		assertEquals(0, MmtrHidMapping.driveHandleFromAxis(0.02, 97, DEADZONE, true), "死区不受反转影响");
	}

	@Test
	public void cruiseAxisRunsFromClosedToTheTypeCeilingOnTheStepGrid() {
		assertEquals(0, MmtrHidMapping.cruiseKmhFromAxis(-1, 160, 5, DEADZONE, false), "最左 = 关闭");
		assertEquals(80, MmtrHidMapping.cruiseKmhFromAxis(0, 160, 5, DEADZONE, false), "中位 = 80（对齐网格）");
		assertEquals(160, MmtrHidMapping.cruiseKmhFromAxis(1, 160, 5, DEADZONE, false), "最右 = 上限");
		// 轴值 → 原始 km/h 是 (a+1)/2*160；要落在 103 附近取 a = 103/160*2 − 1 = 0.2875
		assertEquals(105, MmtrHidMapping.cruiseKmhFromAxis(0.2875, 160, 5, DEADZONE, false), "量化到 5 km/h 网格");
		assertEquals(0, MmtrHidMapping.cruiseKmhFromAxis(-5, 160, 5, DEADZONE, false), "越界钳到 0");
		assertEquals(160, MmtrHidMapping.cruiseKmhFromAxis(2, 160, 5, DEADZONE, false), "越界钳到上限");
		assertEquals(160, MmtrHidMapping.cruiseKmhFromAxis(-1, 160, 5, DEADZONE, true), "反转后最左 = 上限");
	}

	@Test
	public void brakeAxisSnapsToReleaseAndEmergencyAtTheEnds() {
		final int positions = ThreeHandleSpec.DEFAULT_BRAKE_POSITIONS.length; // 11：运行/1A/1B/2…8/EB
		assertEquals(0, MmtrHidMapping.brakePositionFromAxis(-1, positions, DEADZONE, false), "拉到底 = 运行（缓解）");
		assertEquals(0, MmtrHidMapping.brakePositionFromAxis(-0.95, positions, DEADZONE, false), "缓解端吸附");
		assertEquals(10, MmtrHidMapping.brakePositionFromAxis(1, positions, DEADZONE, false), "推到顶 = EB");
		assertEquals(10, MmtrHidMapping.brakePositionFromAxis(0.95, positions, DEADZONE, false), "EB 端吸附");
		assertEquals(5, MmtrHidMapping.brakePositionFromAxis(0, positions, DEADZONE, false), "中位 ≈ 5 档（查表得 0.44）");
		// 单调不减：轴越推制动越强（这张表是"手柄位置"的语义，必须单调）
		int previous = -1;
		for (double axis = -1; axis <= 1.0001; axis += 0.05) {
			final int position = MmtrHidMapping.brakePositionFromAxis(axis, positions, DEADZONE, false);
			assertTrue(position >= previous, "制动力必须随轴不减：axis=" + axis);
			previous = position;
		}
	}

	@Test
	public void eachAxisHasItsOwnInvertFlag() {
		final int positions = ThreeHandleSpec.DEFAULT_BRAKE_POSITIONS.length;
		assertEquals(10, MmtrHidMapping.brakePositionFromAxis(-1, positions, DEADZONE, true), "反转后最左 = EB");
		assertEquals(0, MmtrHidMapping.brakePositionFromAxis(1, positions, DEADZONE, true));
		assertTrue(MmtrHidMapping.isOutsideDeadzone(0.2, DEADZONE));
		assertTrue(!MmtrHidMapping.isOutsideDeadzone(0.02, DEADZONE));
	}
}
