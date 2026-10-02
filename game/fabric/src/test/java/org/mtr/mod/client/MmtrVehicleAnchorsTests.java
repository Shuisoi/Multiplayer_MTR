package org.mtr.mod.client;

import org.junit.jupiter.api.Test;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.client.MmtrVehicleAnchors.Anchor;
import org.mtr.mod.client.MmtrVehicleAnchors.Kind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * notes/365：**一个模型挂多节**时的锚点查找规则。
 *
 * <p>现实背景：一条编组里同一个模型 id 会被挂很多次 —— SAF420 的 6M4T 是
 * {@code saf420cab_a ×1 + saf420car ×8 + saf420cab_b ×1}，而锚点 JSON 是**按模型一份**、
 * 里面每一个锚点的 {@code car} 都是 0（世界里存在的每一种包实测都是 {@code car=[0]}）。
 * 修之前 {@code anchor.car == modelCar} 只有"同车型第 0 节"匹配得上（{@code modelCarIndex} 算的是
 * 同车型的第几节），于是第 2..8 节一个锚点都查不到 —— 表现就是用户看到的
 * "水牌/动态面只有车头车尾有，中间没有"。</p>
 *
 * <p>规则（一条，所有 find* 共用）：**精确声明了就按精确的来，没声明就回退到第 0 节那一份**。</p>
 */
public final class MmtrVehicleAnchorsTests {

	@Test
	public void aSingleDeclaredSetServesEveryInstanceOfTheSameModel() {
		final ObjectArrayList<Anchor> anchors = new ObjectArrayList<>();
		anchors.add(anchor(Kind.PID, "pid_1_2", 0));
		anchors.add(anchor(Kind.PID, "pid_1_3", 0));
		anchors.add(anchor(Kind.PID, "pid_1_4", 0));
		anchors.add(anchor(Kind.PID, "pid_1_5", 0));

		for (int modelCar = 0; modelCar < 8; modelCar++) {
			assertEquals(4, MmtrVehicleAnchors.ofCar(anchors, modelCar).size(),
					"第 " + modelCar + " 节（同车型）应当拿到那一份锚点");
			assertEquals(4, MmtrVehicleAnchors.findPidBoards(anchors, modelCar).size(),
					"按种类查也一样（水牌就是走这条）");
		}
	}

	@Test
	public void anExplicitPerCarSetStillWins() {
		final ObjectArrayList<Anchor> anchors = new ObjectArrayList<>();
		anchors.add(anchor(Kind.PID, "carZero", 0));
		anchors.add(anchor(Kind.PID, "carOne", 1));

		assertEquals("carZero", MmtrVehicleAnchors.findPidBoards(anchors, 0).get(0).name);
		assertEquals("carOne", MmtrVehicleAnchors.findPidBoards(anchors, 1).get(0).name,
				"某一节真的声明了自己的锚点 ⇒ 精确匹配优先，回退不许抢");
		assertEquals(1, MmtrVehicleAnchors.ofCar(anchors, 1).size(), "精确匹配时不该把第 0 节那份也带进来");
		assertEquals("carZero", MmtrVehicleAnchors.findPidBoards(anchors, 2).get(0).name,
				"没声明的车节回退到第 0 节那一份");
	}

	@Test
	public void everyAnchorFamilyUsesTheSameRule() {
		final ObjectArrayList<Anchor> anchors = new ObjectArrayList<>();
		anchors.add(anchor(Kind.LIGHT, "light_1_1", 0));
		anchors.add(anchor(Kind.HUD, "hud_1", 0));
		anchors.add(anchor(Kind.WINDSHIELD, "windshield_1_1", 0));
		anchors.add(anchor(Kind.NEXT, "next_1", 0));

		assertEquals(1, MmtrVehicleAnchors.findLights(anchors, 5).size(), "车灯");
		assertEquals(1, MmtrVehicleAnchors.findHuds(anchors, 5).size(), "仪表");
		assertEquals(1, MmtrVehicleAnchors.findWindshields(anchors, 5).size(), "风挡（全部）");
		assertEquals(1, MmtrVehicleAnchors.findWindshields(anchors, 5, 1).size(), "风挡（按驾驶室）");
		assertEquals(1, MmtrVehicleAnchors.findNextBoards(anchors, 5).size(), "下一站牌");
		assertNotNull(MmtrVehicleAnchors.findWindshield(anchors, 5, 1, 1), "风挡（按驾驶室 + 玻璃序号）");
		assertNotNull(MmtrVehicleAnchors.findHud(anchors, 5, 1), "仪表（按驾驶室）");
	}

	@Test
	public void carZeroItselfIsUnchanged() {
		final ObjectArrayList<Anchor> anchors = new ObjectArrayList<>();
		anchors.add(anchor(Kind.PID, "carZero", 0));
		anchors.add(anchor(Kind.PID, "carOne", 1));

		assertEquals(1, MmtrVehicleAnchors.ofCar(anchors, 0).size(), "第 0 节仍然只拿自己的那一份");
		assertEquals("carZero", MmtrVehicleAnchors.ofCar(anchors, 0).get(0).name);
	}

	/** 造一个锚点：只关心 kind / car（位置之类的给常量，锚点是不可变值对象）。 */
	private static Anchor anchor(Kind kind, String name, int car) {
		return new Anchor(name, kind, 1, 1, car,
				new Vector(0, 0, 0), new Vector(0, 0, 1), new Vector(0, 1, 0), new Vector(1, 0, 0),
				1, 0.4, false, 0, false, new ObjectArrayList<>(), 0, 0, null, 0, 0, 0, 0, 1);
	}
}
