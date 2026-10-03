package org.mtr.mod.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 client mirror store: the contract the in-game signal renderer relies on. The value is a LIST per
 * rail because a route may traverse the same rail twice (牵出—推进 / 尽头换向) - the renderer picks the
 * candidate that continues from the node it is leaving, exactly like the engine's MmtrSignalAspect.
 */
public final class MmtrClientRoutesTests {

	@AfterEach
	public void clearMirror() {
		MmtrClientRoutes.clear();
	}

	@Test
	public void emptyMirrorMeansNoNarrowing() {
		MmtrClientRoutes.clear();
		assertTrue(MmtrClientRoutes.nextRails("railA").isEmpty(), "no route -> no candidate");
		assertTrue(MmtrClientRoutes.nextRails(null).isEmpty(), "null lookups are safe");
		assertFalse(MmtrClientRoutes.isPendingEntry("railA"));
		assertFalse(MmtrClientRoutes.isPendingEntry(null));
		assertEquals(0, MmtrClientRoutes.nextRailCount());
		assertEquals(0, MmtrClientRoutes.pendingEntryCount());
	}

	@Test
	public void aDoubledRailKeepsEveryCandidateInRouteOrder() {
		final Map<String, List<String>> next = new HashMap<>();
		next.put("railA", List.of("railB"));
		next.put("railB", List.of("railC", "railA"));
		MmtrClientRoutes.update(next, Set.of("railA"));

		assertEquals(List.of("railB"), MmtrClientRoutes.nextRails("railA"));
		assertEquals(List.of("railC", "railA"), MmtrClientRoutes.nextRails("railB"),
			"both occurrences of the doubled rail are available, outbound first");
		assertTrue(MmtrClientRoutes.isPendingEntry("railA"), "the pending entry rail is flagged");
		assertFalse(MmtrClientRoutes.isPendingEntry("railB"));
		assertEquals(2, MmtrClientRoutes.nextRailCount());
		assertEquals(1, MmtrClientRoutes.pendingEntryCount());
	}

	@Test
	public void anUpdateReplacesTheWholeMirrorAndIsDefensivelyCopied() {
		final Map<String, List<String>> first = new HashMap<>();
		first.put("railA", List.of("railB"));
		final Set<String> pending = new java.util.HashSet<>();
		pending.add("railA");
		MmtrClientRoutes.update(first, pending);

		// Mutating the caller's collections afterwards must not leak into the mirror.
		first.put("railX", List.of("railY"));
		pending.clear();
		assertTrue(MmtrClientRoutes.nextRails("railX").isEmpty(), "the store copied the map");
		assertTrue(MmtrClientRoutes.isPendingEntry("railA"), "the store copied the pending set");

		MmtrClientRoutes.update(Map.of("railM", List.of("railN")), Set.of());
		assertTrue(MmtrClientRoutes.nextRails("railA").isEmpty(), "a new mirror replaces the previous one");
		assertEquals(List.of("railN"), MmtrClientRoutes.nextRails("railM"));
	}

	/**
	 * S4: the engine's 闭塞区间 v2 conclusion per LAMP. The renderer works per rail block, so it looks its
	 * own block position up by the lamp key the engine uses.
	 */
	@Test
	public void theEnginesPerLampAspectIsMirroredAndLookedUpByBlockPosition() {
		MmtrClientRoutes.clear();
		assertEquals(null, MmtrClientRoutes.lampAspect(-170, -60, -122), "no mirror -> the renderer keeps its local chain");
		assertEquals(0, MmtrClientRoutes.lampAspectCount());

		final Map<String, String> lamps = new HashMap<>();
		lamps.put("-170,-60,-122", "RED");
		lamps.put("-149,-60,-169", "GREEN");
		MmtrClientRoutes.update(Map.of(), Set.of(), Set.of(), lamps);

		assertEquals("RED", MmtrClientRoutes.lampAspect(-170, -60, -122), "the lamp's own block position finds it");
		assertEquals("GREEN", MmtrClientRoutes.lampAspect(-149, -60, -169));
		assertEquals(null, MmtrClientRoutes.lampAspect(0, 0, 0), "a lamp the engine did not send stays local");
		assertEquals(2, MmtrClientRoutes.lampAspectCount());

		// The store copies: mutating the caller's map afterwards must not leak in.
		lamps.put("1,2,3", "DOUBLE_YELLOW");
		assertEquals(null, MmtrClientRoutes.lampAspect(1, 2, 3), "the store copied the lamp map");

		MmtrClientRoutes.clear();
		assertEquals(null, MmtrClientRoutes.lampAspect(-170, -60, -122), "clear() drops the lamp aspects too");
	}

	/**
	 * 区间叠加层（notes/291）：引擎把每条区间带拍成扁平字符串（步长 7），客户端解成"轨 → 带"。
	 *
	 * <p>这里钉三件事：<b>①</b> 数值走整数（千分位方向 / 厘米弧长）—— 区域设置若用逗号作小数点，
	 * {@code Double.parseDouble} 会静默解析失败，而弧长算错的表现是"带子画到别的轨上"；
	 * <b>②</b> 按**规范 hex** 入索引 —— 一根实体轨的 hex 有两种互为逆序的写法，引擎发规范写法，
	 * 而客户端手里那条轨可能是逆序的，不规范化就会"有些轨有带、有些没有"；
	 * <b>③</b> 坏字段不许把整张叠加层丢掉（退回默认值，与网页读接口同一条规矩）。</p>
	 */
	@Test
	public void theSectionBandsAreDecodedPerRailAndKeyedByCanonicalHex() {
		MmtrClientRoutes.clear();
		assertTrue(MmtrClientRoutes.sectionBands("anything").isEmpty(), "no mirror -> no band");
		assertEquals(0, MmtrClientRoutes.sectionBandCount());

		// 引擎侧的一条区间带：轨 hex（规范写法）/ 区间 id / 色号 / 方向 x‰ / 方向 z‰ / 弧起 cm / 弧止 cm
		final String forward = "0000000000000000-0000000000000000-0000000000000000-0000000000000064-0000000000000000-0000000000000000";
		final String backward = "0000000000000064-0000000000000000-0000000000000000-0000000000000000-0000000000000000-0000000000000000";
		MmtrClientRoutes.update(Map.of(), Set.of(), Set.of(), Map.of(), Map.of(), List.of(
			forward, "-149,-60,-169", "3", "-1000", "0", "1250", "8750",
			forward, "-149,-60,-169#2", "坏色号", "0", "1000", "坏", "500"
		));

		assertEquals(2, MmtrClientRoutes.sectionBandCount());
		assertEquals(1, MmtrClientRoutes.sectionRailCount(), "两条带都挂在这一根轨上");

		final MmtrClientRoutes.SectionBand band = MmtrClientRoutes.sectionBands(forward).get(0);
		assertEquals("-149,-60,-169", band.sectionId);
		assertEquals(3, band.colorIndex);
		assertEquals(-1.0, band.headingX, 1e-9, "方向走千分位整数：-1000 ⇒ -1.0");
		assertEquals(0.0, band.headingZ, 1e-9);
		assertEquals(12.5, band.arcFromM, 1e-9, "弧长走厘米整数：1250 ⇒ 12.5 m");
		assertEquals(87.5, band.arcToM, 1e-9);
		assertEquals(75.0, band.lengthM(), 1e-9);

		// ② 逆序写法必须命中同一条带（规范化在入索引时做）
		assertEquals(2, MmtrClientRoutes.sectionBands(backward).size(), "两种端点写法是同一根轨");

		// ③ 坏字段只退化成默认值，其余字段照常（"坏" ⇒ 弧起 0、弧止 5 m、色号 0）—— 不抛、不空表
		final MmtrClientRoutes.SectionBand second = MmtrClientRoutes.sectionBands(backward).get(1);
		assertEquals(0, second.colorIndex);
		assertEquals(0.0, second.arcFromM, 1e-9);
		assertEquals(5.0, second.arcToM, 1e-9);
		assertEquals("-149,-60,-169#2", second.sectionId, "区间 id 是字符串，原样带过来（诊断要用）");

		MmtrClientRoutes.clear();
		assertTrue(MmtrClientRoutes.sectionBands(forward).isEmpty(), "clear() drops the bands too");
	}

	/** 调色板与引擎的 {@code COLOR_COUNT} 必须一致：客户端按下标取色，越界就是画不出来。 */
	@Test
	public void theClientPaletteMatchesTheEngineColorCount() {
		assertEquals(org.mtr.core.mmtr.signal.MmtrSectionOverlay.COLOR_COUNT,
			org.mtr.mod.render.MmtrSectionBands.COLORS.length,
			"引擎发的是色号（取模 COLOR_COUNT），客户端的调色板必须一样长");
	}
}
