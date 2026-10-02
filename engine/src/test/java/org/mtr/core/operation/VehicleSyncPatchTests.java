package org.mtr.core.operation;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 车辆同步**稀疏补丁**的协议用例（notes/174）。
 *
 * <h2>为什么这几条必须有</h2>
 * <p>这一层把"每条更新 3.5 KB 的整份快照"换成"只发变了的字段"。它错了**不会报错**，
 * 只会表现为"客户端某个表永远停在旧值"——正是这个项目被咬过多次的那种静默破损。
 * 所以这里钉三件事：</p>
 * <ol>
 *   <li>**判据方向**：只有白名单里的字段走补丁，**没列到的一律当静态**（发整份）；
 *       于是"漏字段"的后果是"多发一份快照"，而不是"客户端永远不更新"；</li>
 *   <li>**等价**：把补丁合并进镜像，结果与直接吃整份快照**逐字段相同**；</li>
 *   <li>**收敛**：动态白名单覆盖引擎 {@code checkForUpdate()} 认的全部字段（那是"什么算变了"的权威口径）。</li>
 * </ol>
 */
public final class VehicleSyncPatchTests {

	/** 造一份"快照"：{@code {"vehicle":{…},"data":{…}}}。 */
	private static JsonObject snapshot(JsonObject vehicle, JsonObject data) {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.add("vehicle", vehicle);
		jsonObject.add("data", data);
		return jsonObject;
	}

	private static JsonObject vehicle(String speed, String railProgress) {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("speed", Double.parseDouble(speed));
		jsonObject.addProperty("railProgress", Double.parseDouble(railProgress));
		jsonObject.addProperty("mmtrThrottleNotch", 3);
		jsonObject.addProperty("mmtrMaxSpeedKmh", 120.0);
		return jsonObject;
	}

	private static JsonObject data(String stoppingPoint, String thisRouteName) {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("stoppingPoint", Double.parseDouble(stoppingPoint));
		jsonObject.addProperty("speedTarget", 12.5);
		jsonObject.addProperty("thisRouteName", thisRouteName);
		jsonObject.addProperty("totalVehicleLength", 132.0);
		return jsonObject;
	}

	/** 一个字段都没变 ⇒ 空补丁（调用方据此什么也不发）。 */
	@Test
	public void anUnchangedSnapshotProducesAnEmptyPatch() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		final JsonObject patch = VehicleSyncPatch.patchOf(lastSent, snapshot(vehicle("1.0", "100.0"), data("400.0", "A线")));
		assertNotNull(patch, "没变不等于要发整份");
		assertEquals(0, patch.size(), "没变就该是空补丁");
	}

	/** 只有动态字段变了 ⇒ 补丁里**只有那一个字段**。 */
	@Test
	public void aDynamicChangeProducesAPatchWithOnlyThatField() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		final JsonObject patch = VehicleSyncPatch.patchOf(lastSent, snapshot(vehicle("1.0", "100.0"), data("380.0", "A线")));
		assertNotNull(patch, "动态变化应当走补丁");
		assertEquals(2, patch.size(), "两段都要出现（另一段是空对象，见下一条用例）");
		final JsonObject dataPatch = patch.getAsJsonObject("data");
		assertNotNull(dataPatch);
		assertEquals(1, dataPatch.size(), "只带变了的那个字段");
		assertEquals(380.0, dataPatch.get("stoppingPoint").getAsDouble());
		assertFalse(dataPatch.has("thisRouteName"), "没变的静态字段不该出现在补丁里");
		assertFalse(dataPatch.has("speedTarget"), "没变的动态字段也不该出现");
	}

	/**
	 * ★ **补丁必须两段都在**（2026-09-16 实机回归）：客户端合并在
	 * `VehicleExtension.updateData(JsonObject)` 里对两段各 `getAsJsonObject(...)` 再 `new JsonReader(...)`，
	 * 缺了一段就是 **null → 抛异常 → 整条补丁作废**（"有的补丁生效、有的炸" = 抽搐）。
	 */
	@Test
	public void aPatchAlwaysCarriesBothSectionsEvenWhenOnlyOneChanged() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));

		final JsonObject dataOnly = VehicleSyncPatch.patchOf(lastSent, snapshot(vehicle("1.0", "100.0"), data("380.0", "A线")));
		assertNotNull(dataOnly);
		assertTrue(dataOnly.has("vehicle"), "只改了 data 时，vehicle 段也必须出现（空对象）");
		assertTrue(dataOnly.getAsJsonObject("vehicle").isEmpty(), "它应当是空的");
		assertEquals(1, dataOnly.getAsJsonObject("data").size(), "data 段只带变了的字段");

		final JsonObject vehicleOnly = VehicleSyncPatch.patchOf(lastSent, snapshot(vehicle("2.5", "100.0"), data("400.0", "A线")));
		assertNotNull(vehicleOnly);
		assertTrue(vehicleOnly.has("data"), "只改了 vehicle 时，data 段也必须出现（空对象）");
		assertTrue(vehicleOnly.getAsJsonObject("data").isEmpty(), "它应当是空的");
		assertEquals(1, vehicleOnly.getAsJsonObject("vehicle").size(), "vehicle 段只带变了的字段");
	}
	/** 两个段同时有动态变化 ⇒ 两段都带，且各自只带变了的字段。 */
	@Test
	public void bothSectionsCanCarryAPatchAtOnce() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		final JsonObject current = snapshot(vehicle("2.5", "103.0"), data("380.0", "A线"));
		final JsonObject patch = VehicleSyncPatch.patchOf(lastSent, current);
		assertNotNull(patch);
		assertEquals(2, patch.size(), "vehicle 与 data 两段各有一条");
		final JsonObject vehiclePatch = patch.getAsJsonObject("vehicle");
		assertEquals(2, vehiclePatch.size(), "vehicle 段只带变了的两个读数");
		assertEquals(2.5, vehiclePatch.get("speed").getAsDouble());
		assertEquals(103.0, vehiclePatch.get("railProgress").getAsDouble());
		assertEquals(380.0, patch.getAsJsonObject("data").get("stoppingPoint").getAsDouble());
		assertFalse(patch.getAsJsonObject("vehicle").has("mmtrMaxSpeedKmh"), "静态字段不该出现");
	}

	/** **静态**字段变了 ⇒ 必须发整份（客户端要重建镜像；节数变了尤其是）。 */
	@Test
	public void aStaticChangeForcesAFullSnapshot() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		assertNull(VehicleSyncPatch.patchOf(lastSent, snapshot(vehicle("1.0", "100.0"), data("400.0", "B线"))),
			"路线名变了 ⇒ 整份（客户端镜像要重建）");
		assertNull(VehicleSyncPatch.patchOf(lastSent, snapshot(vehicle("1.0", "100.0"), dataTail())),
			"车列长度这类静态字段变了 ⇒ 整份");
		assertNull(VehicleSyncPatch.patchOf(lastSent, snapshot(vehicleStatic(), data("400.0", "A线"))),
			"车辆静态参数（限速等）变了 ⇒ 整份");
		assertNull(VehicleSyncPatch.patchOf(null, snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"))),
			"从没发过 ⇒ 整份");
	}

	/** 静态参数变了一个（限速）。 */
	private static JsonObject vehicleStatic() {
		final JsonObject jsonObject = vehicle("1.0", "100.0");
		jsonObject.addProperty("mmtrMaxSpeedKmh", 160.0);
		return jsonObject;
	}

	private static JsonObject dataTail() {
		final JsonObject jsonObject = data("400.0", "A线");
		jsonObject.addProperty("totalVehicleLength", 200.0);
		return jsonObject;
	}

	/** 上一次有、这一次没有的字段：不猜"删除"语义，直接发整份。 */
	@Test
	public void aDisappearingFieldForcesAFullSnapshot() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		final JsonObject current = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		current.getAsJsonObject("vehicle").remove("mmtrThrottleNotch");
		assertNull(VehicleSyncPatch.patchOf(lastSent, current), "字段消失 ⇒ 整份");
	}

	/**
	 * **等价**：把补丁合并进镜像，必须与"直接吃整份快照"逐字段相同。
	 *
	 * <p>这就是客户端实际做的两步（{@code Vehicle.updateData} + {@code VehicleExtraData.updateData}），
	 * 只不过这里在纯 JSON 上做 —— 不需要世界、也不需要游戏客户端。</p>
	 */
	@Test
	public void applyingThePatchEqualsApplyingTheFullSnapshot() {
		final JsonObject lastSent = snapshot(vehicle("1.0", "100.0"), data("400.0", "A线"));
		final JsonObject current = snapshot(vehicle("2.5", "103.0"), data("380.0", "A线"));

		final JsonObject patch = VehicleSyncPatch.patchOf(lastSent, current);
		assertNotNull(patch, "这一步应当走补丁");

		final JsonObject mirror = lastSent.deepCopy();
		merge(mirror, patch);
		assertEquals(current, mirror, "补丁合并出来的状态必须与整份快照一致");
	}

	/** 引擎 {@code checkForUpdate()} 认的字段大多必须在动态白名单里；`ridingEntities` 是**刻意**的例外。 */
	@Test
	public void everyFieldTheEngineTreatsAsDirtyIsInTheWhitelist() {
		for (final String key : new String[]{
			"stoppingPoint", "doorTarget", "powerLevel", "speedTarget", "isCurrentlyManual",
			"mmtrDoorLeft", "mmtrDoorRight", "mmtrDoorManual"
		}) {
			assertTrue(VehicleSyncPatch.isDynamicKey(key), key + " 是引擎认的「变了」的字段，必须能走补丁");
		}
		// 上下车要在客户端重建 HIDDEN_PLAYERS / 乘客插值（只在整份更新那条路上做），所以它走整份。
		assertFalse(VehicleSyncPatch.isDynamicKey("ridingEntities"), "上下车走整份快照（客户端那条路有副作用要跑）");
	}

	/** 反方向：明显静态的字段**不许**走补丁（否则客户端镜像不会重建）。 */
	@Test
	public void staticFieldsAreNotInTheWhitelist() {
		for (final String key : new String[]{
			"thisRouteName", "thisRouteDestination", "thisStationName", "vehicleCars", "path",
			"totalVehicleLength", "mmtrMaxSpeedKmh", "mmtrPowerNotches", "interchangeColorsForStationNameList"
		}) {
			assertFalse(VehicleSyncPatch.isDynamicKey(key), key + " 是静态字段，必须走整份快照");
		}
	}

	/**
	 * ★ **车一开动就变的那两个物理读数**（notes/368 §2(2)）：`acceleration` / `deceleration`
	 * 由服务端每 tick 按当前速度写进镜像（客户端拿它们做信号预留足迹与电机音调）。
	 *
	 * <p>它们**不在**白名单里时，`diffSection` 会判成"静态字段变了" ⇒ 返回 {@code null} ⇒ **整份快照**；
	 * 而这两项每 tick 都在变 ⇒ 车一动就退化成"每秒一份 7.4 KB"。现场实测正是如此
	 * （notes/368 §1：1191/1711 个包是整份、平均 7313 B）。</p>
	 */
	@Test
	public void aSpeedDependentPhysicsChangeProducesAPatchNotAFullSnapshot() {
		final JsonObject lastSent = snapshot(vehicle("0.0", "100.0"), dataWithPhysics("1.05", "0.35"));
		final JsonObject now = snapshot(vehicle("0.005", "100.4"), dataWithPhysics("1.12", "0.36"));

		final JsonObject patch = VehicleSyncPatch.patchOf(lastSent, now);
		assertNotNull(patch, "★ 速度一变必须是补丁：这两项不在白名单里时这里会返回 null（= 整份 7.4 KB）");
		final JsonObject dataPatch = patch.getAsJsonObject("data");
		assertEquals(2, dataPatch.size(), "只带这两个物理读数（速度与位置在 vehicle 段）");
		assertEquals(1.12, dataPatch.get("acceleration").getAsDouble(), "牵引加速度能力");
		assertEquals(0.36, dataPatch.get("deceleration").getAsDouble(), "常用制动减速度");
	}

	private static JsonObject dataWithPhysics(String acceleration, String deceleration) {
		final JsonObject jsonObject = new JsonObject();
		jsonObject.addProperty("stoppingPoint", 400.0);
		jsonObject.addProperty("speedTarget", 12.5);
		jsonObject.addProperty("totalVehicleLength", 132.0);
		jsonObject.addProperty("acceleration", Double.parseDouble(acceleration));
		jsonObject.addProperty("deceleration", Double.parseDouble(deceleration));
		return jsonObject;
	}

	/** 把补丁按段合并进目标（与客户端两步 updateData 同语义：只覆盖出现的字段）。 */
	private static void merge(JsonObject target, JsonObject patch) {
		for (final String section : new String[]{"vehicle", "data"}) {
			final JsonObject sectionPatch = patch.getAsJsonObject(section);
			if (sectionPatch != null) {
				final JsonObject targetSection = target.getAsJsonObject(section);
				sectionPatch.entrySet().forEach(entry -> targetSection.add(entry.getKey(), entry.getValue()));
			}
		}
	}
}
