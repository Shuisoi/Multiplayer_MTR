package org.mtr.core.servlet;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.data.VehiclePosition;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web 三个"单一图层"接口的**契约**（notes/167）：
 * {@code /mmtr-track-sections}（L1 轨道区间）、{@code /mmtr-block-sections}（L2 行车区间）、
 * {@code /mmtr-lamps}（信号灯绑定）。
 *
 * <p>为什么要钉 JSON 形状：网页只读引擎的结论，字段一旦改名/缺字段，前端就是"静默画不出来"——
 * 这类破损在服务端看不出来（实测吃过一次：控制台一直读引擎**已经不发的** `node.block` 字段）。</p>
 */
public final class SystemMapSectionApiTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
		return Rail.newRail(p1, Angle.fromAngle((float) bearing), p2, Angle.fromAngle((float) (bearing + 180)),
			Rail.Shape.QUADRATIC, 0, NO_STYLES, 80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	/** 一条 200 m 轨 + 起点一盏朝东的灯（守整根轨）。 */
	private static Simulator world() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-api-sections"), false);
		final Rail r = through(new Position(0, 0, 0), new Position(200, 0, 0));
		sim.rails.add(r);
		sim.sync();
		sim.mmtrSignals.put(0, 0, 0, 270, 4, "AUTO", "");
		return sim;
	}

	@Test
	public void trackSectionsEndpointCarriesTheLevelOneLayer() {
		final Simulator sim = world();
		final JsonObject payload = SystemMapServlet.getMmtrTrackSections(sim);
		assertNotNull(payload.get("trackSections"), "要有 trackSections 数组");
		assertTrue(payload.get("count").getAsInt() >= 1, "至少一段轨道区间");
		assertEquals(sim.rails.size(), payload.get("railCount").getAsInt(), "railCount 要对得上");
		final JsonObject first = payload.getAsJsonArray("trackSections").get(0).getAsJsonObject();
		for (final String field : new String[]{"id", "length", "occupied", "spans"}) {
			assertTrue(first.has(field), "每条要有 " + field);
		}
		final JsonObject span = first.getAsJsonArray("spans").get(0).getAsJsonObject();
		for (final String field : new String[]{"hex", "from", "to", "points"}) {
			assertTrue(span.has(field), "每个 span 要有 " + field);
		}
		assertEquals(0, first.getAsJsonArray("spans").get(0).getAsJsonObject().getAsJsonArray("points").size() % 2,
			"采样点是 x,z 成对");
	}

	@Test
	public void blockSectionsEndpointCarriesDirectionLampsAndMemberTrackSections() {
		final Simulator sim = world();
		final JsonObject payload = SystemMapServlet.getMmtrBlockSections(sim);
		assertTrue(payload.get("count").getAsInt() >= 1, "至少一段行车区间");
		final JsonObject first = payload.getAsJsonArray("blockSections").get(0).getAsJsonObject();
		for (final String field : new String[]{"id", "entrySignal", "exitSignal", "next", "aspect", "occupied",
			"length", "direction", "uncovered", "members", "memberCount", "spans"}) {
			assertTrue(first.has(field), "每条要有 " + field);
		}
		assertTrue(first.getAsJsonObject("direction").has("label"), "方向要带中文名（网页画方向带）");
		assertTrue(first.getAsJsonArray("members").size() >= 1, "members 要指出它由哪些 L1 段拼成");
		final JsonObject span = first.getAsJsonArray("spans").get(0).getAsJsonObject();
		for (final String field : new String[]{"hex", "from", "to", "dirOfTravel", "reachable", "points"}) {
			assertTrue(span.has(field), "每个 span 要有 " + field);
		}
	}

	/**
	 * **总区间**（`/mmtr-total-sections`，notes/168）：地图上一条带 = 一个位置 ＋ 覆盖它的各方向区间。
	 *
	 * <p>形状必须稳：前端只读这里画图，缺字段就是"静默画不出来"。</p>
	 */
	@Test
	public void totalSectionsEndpointCarriesGeometryAndDirectionalCovers() {
		final Simulator sim = world();
		final JsonObject payload = SystemMapServlet.getMmtrTotalSections(sim);
		assertNotNull(payload.get("totalSections"), "要有 totalSections 数组");
		assertTrue(payload.has("staggeredCount"), "要能一眼看出有多少处是错开的");
		assertEquals(SystemMapServlet.getMmtrTrackSections(sim).get("count").getAsInt(), payload.get("count").getAsInt(),
			"总区间与轨道区间一一对应（几何就是 L1）");
		final JsonObject first = payload.getAsJsonArray("totalSections").get(0).getAsJsonObject();
		for (final String field : new String[]{"id", "length", "occupied", "directions", "staggered", "covers", "spans"}) {
			assertTrue(first.has(field), "每条要有 " + field);
		}
		final JsonObject cover = first.getAsJsonArray("covers").get(0).getAsJsonObject();
		for (final String field : new String[]{"section", "entrySignal", "exitSignal", "next", "aspect", "occupied",
			"uncovered", "length", "direction"}) {
			assertTrue(cover.has(field), "每个 cover 要有 " + field);
		}
		assertTrue(cover.getAsJsonObject("direction").has("label"), "方向要带中文名（网页画方向带）");
		final JsonObject span = first.getAsJsonArray("spans").get(0).getAsJsonObject();
		for (final String field : new String[]{"hex", "from", "to", "points"}) {
			assertTrue(span.has(field), "每个 span 要有 " + field);
		}
	}

	@Test
	public void lampsEndpointBindsEachLampToTheSectionItOpens() {
		final Simulator sim = world();
		final JsonObject payload = SystemMapServlet.getMmtrLamps(sim);
		assertEquals(sim.mmtrSignals.signals.size(), payload.get("count").getAsInt(), "每盏登记灯一条");
		final JsonObject lamp = payload.getAsJsonArray("lamps").get(0).getAsJsonObject();
		for (final String field : new String[]{"key", "x", "y", "z", "angle", "aspect", "occupied", "unbound",
			"section", "sections", "nextSections", "protectedRails"}) {
			assertTrue(lamp.has(field), "每盏灯要有 " + field);
		}
		assertTrue(!lamp.get("unbound").getAsBoolean(), "这盏灯接入了闭塞");
		assertEquals(1, lamp.getAsJsonArray("sections").size(), "它开出一段");
		assertEquals("GREEN", lamp.get("aspect").getAsString(), "空线 ⇒ 绿");
	}

	/*
	 * 说明：占用 → 显示/绑定 的**语义**不在这里测 —— 铺一棵"停着车"的占用树需要 `VehiclePosition.addSegment`，
	 * 那个入口是 data 包内的（本文件在 servlet 包）。语义已由 `MmtrTrackSectionTests`
	 * （`lampStateFollowsTheSectionItOpens…` / `aMultiLegLampReports…`）与
	 * `MmtrSectionServiceTests` 的占用用例覆盖；本文件只钉**对外 JSON 的形状**（缺字段前端就静默画不出来）。
	 */
}
