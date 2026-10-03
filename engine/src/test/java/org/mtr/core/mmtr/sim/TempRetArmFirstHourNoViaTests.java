package org.mtr.core.mmtr.sim;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * **前 1 小时**对照臂（用户口径 2026-09-27："为什么要跑全天的，只跑前 1h 就够了吧"）：
 * 作业单照**真实运营日**排（07:00 出库、180 s 错开、4 列），但只排 1 小时能装下的圈数（4/4/4/3 圈），
 * 然后回库（due = 08:00）。两臂除了那一步的经由点以外**逐字相同**。
 */
public final class TempRetArmFirstHourNoViaTests {

	@Test
	public void arm() throws IOException {
		TempRetArms.run("前 1 小时(07:00-08:00) 无经由点", "hour-novia", "ret-1h-novia.json", 4);
	}
}