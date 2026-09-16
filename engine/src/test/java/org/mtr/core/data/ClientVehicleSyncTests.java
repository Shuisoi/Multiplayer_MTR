package org.mtr.core.data;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.operation.DynamicDataResponse;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Paths;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车辆同步消息的**组成**用例（notes/174 的回归）。
 *
 * <h2>它钉的是什么</h2>
 * <p>客户端那份镜像有一条硬规则：**"这一条消息里没出现的车，就删掉"**
 * （`PacketUpdateVehiclesLifts.updateVehiclesOrLifts` 里的
 * {@code dataSet.removeIf(data -> !keepIds.contains(data.getId()))}，删的时候还会
 * {@code VehicleExtension::dispose} 掉按节数缓存的渲染数据）。</p>
 *
 * <p>所以**补丁车必须同时出现在保活列表里** —— 只放进补丁列表的话，客户端每收到一次补丁就把那辆车
 * 删掉、dispose 渲染缓存，直到下一次整份快照（30 秒兜底）才回来：现场表现就是
 * **车一亮一灭、非常卡**（2026-09-16 实机就是这么报的）。</p>
 */
public final class ClientVehicleSyncTests {

	private static Simulator newSimulator() {
		return new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-client-sync"), false);
	}

	private static Client newClient(Simulator simulator) {
		final Client client = new Client(UUID.randomUUID());
		client.setPositionAndUpdateRadius(new Position(0, 0, 0), 10_000);
		simulator.clients.add(client);
		return client;
	}

	private static Vehicle newVehicle(Simulator simulator) {
		return new Vehicle(new VehicleExtraData(new JsonReader(new JsonObject())), null, new JsonReader(new JsonObject()), simulator);
	}

	/** 走一"拍"：先让这个客户端把这一拍该发的排出去（`sendUpdates` 就是 tick 里那一步），再取消息。 */
	private static ObjectArrayList<DynamicDataResponse> flush(Client client, Simulator simulator) {
		client.sendUpdates(simulator);
		final ObjectArrayList<DynamicDataResponse> out = new ObjectArrayList<>();
		simulator.processMessagesS2C(queueObject -> {
			if (queueObject.data instanceof final DynamicDataResponse response) {
				out.add(response);
			}
		});
		return out;
	}

	private static JsonObject patchOf(String stoppingPoint) {
		final JsonObject patch = new JsonObject();
		final JsonObject dataPatch = new JsonObject();
		dataPatch.addProperty("stoppingPoint", Double.parseDouble(stoppingPoint));
		patch.add("data", dataPatch);
		return patch;
	}

	/* 三张表都用**公开的 iterate 口**数（与 mod 侧读的是同一套 API），字段本身是 protected。 */

	private static int updates(DynamicDataResponse response) {
		final int[] count = {0};
		response.iterateVehiclesToUpdate(vehicleUpdate -> count[0]++);
		return count[0];
	}

	private static int patches(DynamicDataResponse response) {
		final int[] count = {0};
		response.iterateVehiclesToPatch(vehiclePatch -> count[0]++);
		return count[0];
	}

	private static int keeps(DynamicDataResponse response) {
		final int[] count = {0};
		response.iterateVehiclesToKeep(vehicleId -> count[0]++);
		return count[0];
	}

	private static long firstKeep(DynamicDataResponse response) {
		final long[] kept = {Long.MIN_VALUE};
		response.iterateVehiclesToKeep(vehicleId -> kept[0] = vehicleId);
		return kept[0];
	}

	/**
	 * **补丁车必须同时进保活列表**：客户端只看保活决定"删不删"。
	 *
	 * <p>少了这一条，客户端会在每次补丁时删车 → 车辆在画面上闪断、渲染缓存被反复重建。</p>
	 */
	@Test
	public void aPatchedVehicleIsAlsoKept() {
		final Simulator simulator = newSimulator();
		final Client client = newClient(simulator);
		final Vehicle vehicle = newVehicle(simulator);

		// 第一拍：客户端还没有这辆车 ⇒ 整份快照（建立镜像）
		client.update(vehicle, true, 0, null);
		final ObjectArrayList<DynamicDataResponse> first = flush(client, simulator);
		assertEquals(1, first.size(), "第一拍应当发一条消息（整份）");
		assertEquals(1, updates(first.getFirst()), "第一拍应当是整份快照");
		assertTrue(patches(first.getFirst()) == 0, "第一拍不该有补丁");

		// 第二拍：只有动态字段变了 ⇒ 补丁
		client.update(vehicle, true, 0, patchOf("123.5"));
		final ObjectArrayList<DynamicDataResponse> second = flush(client, simulator);
		assertEquals(1, second.size(), "第二拍应当发一条消息（补丁）");
		final DynamicDataResponse response = second.getFirst();

		assertEquals(1, patches(response), "第二拍应当是稀疏补丁");
		assertTrue(updates(response) == 0, "第二拍不该再发整份快照");
		assertEquals(1, keeps(response),
			"★ 补丁车必须同时在保活列表里：客户端只看保活决定要不要把这辆车删掉");
		assertEquals(vehicle.getId(), firstKeep(response), "保活的应当是同一辆车");

		/*
		 * 第三拍：什么也没变 ⇒ **一条消息都不发**（这是上游的省法，不是缺陷）。
		 *
		 * 为什么仍然安全：客户端那条"不在消息里就删车"的规则**只在收到消息时**才跑 ——
		 * 没收到消息就什么都不动。而将来一旦因为别的原因发了消息（别的车脏了），
		 * 那一拍的保活列表里必然包含这辆车（`Vehicle` 每 tick 都会对视野内的每辆车调一次 `update`），
		 * 所以它不会被误删。
		 */
		client.update(vehicle, false, 0, null);
		final ObjectArrayList<DynamicDataResponse> third = flush(client, simulator);
		assertTrue(third.isEmpty(), "没有变化就不该发消息（保活随下一条消息一起走）");
	}

	/**
	 * 车出视野：这一拍它不在任何一张表里 ⇒ 客户端据此把它删掉；再回来时**必须发整份**
	 * （客户端可能已经删了它，发补丁会静默丢更新）。
	 */
	@Test
	public void aVehicleThatLeftTheAreaIsDroppedAndComesBackWhole() {
		final Simulator simulator = newSimulator();
		final Client client = newClient(simulator);
		final Vehicle vehicle = newVehicle(simulator);

		client.update(vehicle, true, 0, null);
		flush(client, simulator);

		// 这一拍不再 push 它（= 出视野）：客户端会收到一条"什么都没有"的消息，据此删除
		final ObjectArrayList<DynamicDataResponse> removal = flush(client, simulator);
		assertEquals(1, removal.size(), "持有→不持有的那一拍必须发一条消息，否则客户端不会删");
		assertTrue(keeps(removal.getFirst()) == 0, "它不该再被保活");
		assertTrue(patches(removal.getFirst()) == 0, "它也不该有补丁");
		assertTrue(updates(removal.getFirst()) == 0, "它也不该有整份");

		// 重新进视野：引擎已经不再认为这个客户端持有它 ⇒ 整份（而不是补丁）
		client.update(vehicle, true, 0, patchOf("9.0"));
		final ObjectArrayList<DynamicDataResponse> reentry = flush(client, simulator);
		assertEquals(1, reentry.size());
		assertEquals(1, updates(reentry.getFirst()),
			"★ 重新进视野必须发整份：客户端可能已经把它删了，发补丁会静默丢更新");
	}
}
