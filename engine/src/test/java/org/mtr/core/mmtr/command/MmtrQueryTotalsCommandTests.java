package org.mtr.core.mmtr.command;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Vector;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code query totals}（notes/168）：总区间的规模与概况 —— **从指令通道就能看到**。
 *
 * <p>为什么值得一条用例：网页指令栏走的是**引擎分发器**（`mmtr-command` → `MmtrCommandDispatcher`），
 * 而 `tracks` / `lamps` 那几条住在**游戏端**的执行器里（只有被 push 过去才跑）。加总区间时先只加了
 * 游戏端那条，于是"网页上敲 totals"会得到"不认识的名词" —— 真机实测就是这个结果。这条用例钉住
 * 引擎这一侧的入口与它的三行结论。</p>
 *
 * <p>夹具与 {@code MmtrTotalSectionTests} 同一个：200 m 轨、东行灯在弧 50、西行灯在弧 150 ⇒
 * **3 段位置**（弧 50/150 两个切点），其中**只有中间那段是错开的**（两个方向都照到、却不是同一段路）。</p>
 */
public final class MmtrQueryTotalsCommandTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();
	private static final float EAST = 270;
	private static final float WEST = 90;

	private static Rail rail(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return Rail.newRail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	private static void addLamp(Simulator simulator, Rail rail, double arcM, float angle) {
		final Vector position = rail.railMath.getPosition(arcM, false);
		simulator.mmtrSignals.put((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()), angle, 4, "AUTO", "");
	}

	@Test
	public void queryTotalsReportsCountStaggeredAndDirectionSpread() {
		final Rail r = rail(new Position(0, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-query-totals"), false);
		simulator.rails.add(r);
		simulator.sync();
		addLamp(simulator, r, 50, EAST);
		addLamp(simulator, r, 150, WEST);

		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(simulator, "query totals");
		assertTrue(result.ok, "query totals 应当被受理");
		assertEquals("query", result.namespace);
		assertEquals("totals", result.verb);

		final String text = String.join("\n", result.lines);
		assertTrue(text.contains("总区间 3 条"), text);
		assertTrue(text.contains("错开 1 条"), text);
		assertTrue(text.contains("覆盖它的方向数 = 1：2 条"), text);
		assertTrue(text.contains("覆盖它的方向数 = 2：1 条"), text);
	}

	@Test
	public void queryTotalsOnALampLessWorldIsAllUnsignalledAndNotStaggered() {
		final Rail a = rail(new Position(0, 0, 0), new Position(100, 0, 0));
		final Rail b = rail(new Position(100, 0, 0), new Position(200, 0, 0));
		final Simulator simulator = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-query-totals-plain"), false);
		simulator.rails.add(a);
		simulator.rails.add(b);
		simulator.sync();

		final MmtrCommandDispatcher.Result result = MmtrCommandDispatcher.execute(simulator, "query totals");
		assertTrue(result.ok);
		final String text = String.join("\n", result.lines);
		// 无灯走廊：一条带、两个方向各补一段（同一段路）⇒ 方向数 2，但**不是错开**
		assertTrue(text.contains("总区间 1 条"), text);
		assertTrue(text.contains("错开 0 条"), text);
		assertTrue(text.contains("覆盖它的方向数 = 2：1 条"), text);
	}
}
