package org.mtr.mod.packet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.mtr.mod.client.MmtrClientRoutes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区间叠加层载荷的**编解码闭环**（notes/291）：这个包里的 {@link PacketMmtrRoutes#contentOf} 负责写，
 * {@code runClientInbound} 负责读（Gson 层面只做搬运），真正的解码在 {@link MmtrClientRoutes#update} 里。
 * 写与读分居两个类 —— 步长对不上时**不会报错、只会画错**，所以这里把"引擎侧拍平 → JSON → 客户端解成带"
 * 整条链路走一遍。
 */
public final class PacketMmtrRoutesSectionBandsTests {

	/** 真实格式的轨 hex：6 个 16 位十六进制字段（负坐标也在里面，所以字段之间只有一个 `-`）。 */
	private static final String RAIL_HEX = "0000000000000005-0000000000000000-0000000000000000-0000000000000064-0000000000000000-0000000000000000";

	@AfterEach
	public void clearMirror() {
		MmtrClientRoutes.clear();
	}

	@Test
	public void theSectionBandsSurviveTheJSONRoundTrip() {
		final ObjectArrayList<String> wire = new ObjectArrayList<>();
		// 两条带，字段顺序：[轨hex, 区间id, 色号, 方向x‰, 方向z‰, 弧起cm, 弧止cm]
		wire.add(RAIL_HEX);
		wire.add("-149,-60,-169");
		wire.add("5");
		wire.add("-1000");
		wire.add("0");
		wire.add("1234");
		wire.add("5678");
		wire.add(RAIL_HEX);
		wire.add("-149,-60,-169#2");
		wire.add("0");
		wire.add("1000");
		wire.add("0");
		wire.add("5678");
		wire.add("9000");

		final String content = PacketMmtrRoutes.contentOf(
			new Object2ObjectOpenHashMap<>(),
			new ObjectOpenHashSet<>(),
			new ObjectOpenHashSet<>(),
			new Object2ObjectOpenHashMap<>(),
			new Object2ObjectOpenHashMap<>(),
			wire
		);

		// 服务端写出来的 JSON：客户端那一侧读到的就是这个数组（runClientInbound 只做 iterateStringArray）
		final JsonArray encoded = JsonParser.parseString(content).getAsJsonObject().getAsJsonArray("sectionBands");
		assertEquals(wire.size(), encoded.size(), "写出去的字段数必须原样到达");
		assertEquals(0, encoded.size() % PacketMmtrRoutes.SECTION_BAND_STRIDE, "步长必须整除 —— 否则客户端会错位解码");
		final List<String> decoded = new ArrayList<>();
		encoded.forEach(element -> decoded.add(element.getAsString()));
		assertEquals(new ArrayList<>(wire), decoded, "顺序也不能变（步长解错的第一个症状就是错位）");

		// 再走一遍客户端解码
		MmtrClientRoutes.update(new HashMap<>(), new HashSet<>(), new HashSet<>(), new HashMap<>(), new HashMap<>(), decoded);

		assertEquals(2, MmtrClientRoutes.sectionBandCount());
		assertEquals(1, MmtrClientRoutes.sectionRailCount(), "两条带在同一根轨上");
		final MmtrClientRoutes.SectionBand first = MmtrClientRoutes.sectionBands(RAIL_HEX).get(0);
		assertEquals("-149,-60,-169", first.sectionId);
		assertEquals(5, first.colorIndex);
		assertEquals(-1.0, first.headingX, 1e-9);
		assertEquals(0.0, first.headingZ, 1e-9);
		assertEquals(12.34, first.arcFromM, 1e-9);
		assertEquals(56.78, first.arcToM, 1e-9);
		assertTrue(MmtrClientRoutes.sectionBands(RAIL_HEX).get(1).arcToM > first.arcToM, "第二条带在更远的弧上");
	}
}
