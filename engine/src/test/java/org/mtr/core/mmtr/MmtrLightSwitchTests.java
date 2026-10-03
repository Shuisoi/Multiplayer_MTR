package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 灯光开关的真值表（用户口径 2026-09-29；灯种口径 2026-10-03 收紧成"一档 = 一种灯"）。
 *
 * <p>钉住四件事：**循环顺序**（机车四档 / 动车组三档）、**越界与不可用档位的钳法**（动车组收到"关闭"
 * 必须退回尾灯，不能变成"全灭"）、**换向器 N 固定红**（无视档位）、以及**"关闭"压过 N**
 * （机车挂车时尾灯要真的灭得掉）。</p>
 *
 * <p>2026-10-03 起档位名就是**灯的种类**：尾灯（红）/ 近光（白、低）/ 远光（白、高）。
 * 数值没动（2 / 3），所以这条测试同时也是"旧协议不会因为改名而错位"的看门人。</p>
 */
public final class MmtrLightSwitchTests {

	@Test
	public void muCyclesThroughThreePositions() {
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.cycle(MmtrLightSwitch.TAIL, false), "尾灯 → 近光");
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.cycle(MmtrLightSwitch.LOW, false), "近光 → 远光");
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.cycle(MmtrLightSwitch.HIGH, false), "远光 → 尾灯（三档闭环）");
		// 动车组没有"关闭"：即使手里是 0（旧存档 / 别的车底留下的），按一下也必须回到三档闭环里。
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.cycle(MmtrLightSwitch.OFF, false), "关闭（不可用）→ 近光");
	}

	@Test
	public void locomotiveCyclesThroughFourPositions() {
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.cycle(MmtrLightSwitch.OFF, true), "关闭 → 尾灯");
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.cycle(MmtrLightSwitch.TAIL, true), "尾灯 → 近光");
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.cycle(MmtrLightSwitch.LOW, true), "近光 → 远光");
		assertEquals(MmtrLightSwitch.OFF, MmtrLightSwitch.cycle(MmtrLightSwitch.HIGH, true), "远光 → 关闭（四档闭环）");
	}

	@Test
	public void sanitizeNeverInventsAnOffPosition() {
		assertEquals(MmtrLightSwitch.OFF, MmtrLightSwitch.sanitize(MmtrLightSwitch.OFF, true), "机车保留关闭");
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.sanitize(MmtrLightSwitch.OFF, false), "动车组的关闭 ⇒ 尾灯");
		// 越界（旧客户端推上来的、或者被别的车底用过的值）一律退回默认，绝不落到"没灯"。
		assertEquals(MmtrLightSwitch.DEFAULT, MmtrLightSwitch.sanitize(-1, true));
		assertEquals(MmtrLightSwitch.DEFAULT, MmtrLightSwitch.sanitize(4, true));
		assertEquals(MmtrLightSwitch.DEFAULT, MmtrLightSwitch.sanitize(999, false));
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.sanitize(MmtrLightSwitch.HIGH, false), "合法档位原样保留");
	}

	@Test
	public void reverserNeutralIsAlwaysRed() {
		// 换向器 N：两端固定红，**无视**档位（含"近光/远光"这两个白灯档）。
		for (final int state : new int[]{MmtrLightSwitch.TAIL, MmtrLightSwitch.LOW, MmtrLightSwitch.HIGH}) {
			assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.lampState(state, 0),
				MmtrLightSwitch.label(state) + " + 换向N ⇒ 固定红");
		}
		// 非 N：按档位。
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.lampState(MmtrLightSwitch.TAIL, 1));
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.lampState(MmtrLightSwitch.LOW, 1));
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.lampState(MmtrLightSwitch.HIGH, -1), "后退（-1）同样遵循档位");
	}

	@Test
	public void offWinsOverNeutral() {
		// 这一条是"机车连挂时尾灯灭掉"的接口：挂车时换向器多半就是 N，
		// 若 N 压过关闭，那个需求永远无法实现（见 MmtrLightSwitch 类注释的判据顺序）。
		assertEquals(MmtrLightSwitch.OFF, MmtrLightSwitch.lampState(MmtrLightSwitch.OFF, 0), "关闭 + 换向N ⇒ 仍然关");
		assertEquals(MmtrLightSwitch.OFF, MmtrLightSwitch.lampState(MmtrLightSwitch.OFF, 1));
	}

	@Test
	public void eachEndKeepsItsOwnSwitch() {
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.switchOfEnd(MmtrLightSwitch.HIGH, MmtrLightSwitch.TAIL, MmtrLightSwitch.END_A));
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.switchOfEnd(MmtrLightSwitch.HIGH, MmtrLightSwitch.TAIL, MmtrLightSwitch.END_B), "两端互不影响");
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.switchOfEnd(MmtrLightSwitch.LOW, MmtrLightSwitch.TAIL, 0), "端越界按 A 端");
	}

	@Test
	public void headlightFlagMatchesTheTwoWhiteLamps() {
		assertTrue(MmtrLightSwitch.isHeadlight(MmtrLightSwitch.LOW));
		assertTrue(MmtrLightSwitch.isHeadlight(MmtrLightSwitch.HIGH));
		assertFalse(MmtrLightSwitch.isHeadlight(MmtrLightSwitch.TAIL), "尾灯不是前照灯（渲染侧据此把灯罩画红）");
		assertFalse(MmtrLightSwitch.isHeadlight(MmtrLightSwitch.OFF));
	}

	@Test
	public void configValueParsingIsStrictAboutNames() {
		assertTrue(MmtrLightSwitch.parseOffPosition("LOCO"));
		assertTrue(MmtrLightSwitch.parseOffPosition(" loco "));
		assertFalse(MmtrLightSwitch.parseOffPosition("MU"));
		assertFalse(MmtrLightSwitch.parseOffPosition(""), "没写 ⇒ 动车组口径（零回归）");
		assertFalse(MmtrLightSwitch.parseOffPosition(null));
		assertTrue(MmtrLightSwitch.isKnownConfigValue("LOCO"));
		assertTrue(MmtrLightSwitch.isKnownConfigValue("mu"));
		assertFalse(MmtrLightSwitch.isKnownConfigValue("机车"), "写错的值要被认出是错的并点名");
	}

	@Test
	public void describeReportsWhatIsActuallyShown() {
		assertEquals("A端=远光 B端=尾灯", MmtrLightSwitch.describe(MmtrLightSwitch.HIGH, MmtrLightSwitch.TAIL, 1, true));
		assertEquals("A端=尾灯 B端=尾灯（换向N：固定红）", MmtrLightSwitch.describe(MmtrLightSwitch.HIGH, MmtrLightSwitch.TAIL, 0, true),
			"换向N 时报的是**生效后**的结果，不是开关本身");
	}

	/**
	 * 自动运行（"AI 驾驶员"）那一档：世界时白天 = 近光、其余 = 远光（用户口径 2026-10-03）。
	 *
	 * <p>边界必须钉死：{@code [06:00, 18:00)} 是 MC 自己的白天（raw 0..12000），灯的档位与它同口径。
	 * 负值（引擎还不知道世界时）按**远光**：那一档最亮，比"看不见的前照灯"安全。
	 */
	@Test
	public void autoHeadlightFollowsTheInGameClock() {
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.autoHeadlightState(0), "00:00 深夜 ⇒ 远光");
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.autoHeadlightState(5), "05:59 还是夜 ⇒ 远光");
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.autoHeadlightState(6), "06:00 天亮 ⇒ 近光");
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.autoHeadlightState(12), "正午 ⇒ 近光");
		assertEquals(MmtrLightSwitch.LOW, MmtrLightSwitch.autoHeadlightState(17), "17:59 还是白天 ⇒ 近光");
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.autoHeadlightState(18), "18:00 天黑 ⇒ 远光");
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.autoHeadlightState(23));
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.autoHeadlightState(-1), "还不知道世界时 ⇒ 按最亮的远光");
	}

	/** 自动运行的"车头/车尾/换向器"三件套：车头跟**真实行驶方向**，尾在前时换向器报 −1。 */
	@Test
	public void autoRunFrontRearAndReverser() {
		assertEquals(MmtrLightSwitch.END_A, MmtrLightSwitch.autoLeadingEnd(false), "朝 A 端跑 ⇒ A 端是车头");
		assertEquals(MmtrLightSwitch.END_B, MmtrLightSwitch.autoLeadingEnd(true), "朝 B 端跑 ⇒ B 端是车头");
		assertEquals(MmtrLightSwitch.END_B, MmtrLightSwitch.otherEnd(MmtrLightSwitch.END_A));
		assertEquals(MmtrLightSwitch.END_A, MmtrLightSwitch.otherEnd(MmtrLightSwitch.END_B));
		assertEquals(1, MmtrLightSwitch.autoReverser(false), "自动运行在档（前进）");
		assertEquals(-1, MmtrLightSwitch.autoReverser(true), "尾在前（REV）⇒ −1；只要不是 0 就不会被判成固定红");
		// 换向器 N 会把两端都判成红的（lampState 第 2 条）—— 所以自动运行**绝不能**留 0。
		assertEquals(MmtrLightSwitch.TAIL, MmtrLightSwitch.lampState(MmtrLightSwitch.HIGH, 0));
		assertEquals(MmtrLightSwitch.HIGH, MmtrLightSwitch.lampState(MmtrLightSwitch.HIGH, MmtrLightSwitch.autoReverser(true)));
	}
}
