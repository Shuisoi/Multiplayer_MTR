package org.mtr.core.operation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * 车辆同步的**稀疏补丁**（notes/174）。
 *
 * <h2>解决什么</h2>
 * <p>一份 {@code VehicleUpdate} 的 JSON 实测 **3,471 字符 / 3.5 KB**（单节车），而列车巡航时
 * 每拍真正变的只有一两个字段 —— 其余九成是**静态**的（三个 route 块的名称/终点/站名、
 * interchange 颜色、编组、path、加速/制动/限速那一整块 mmtr 物理参数）。
 * 64 人时这条线是下行带宽与 CPU 的主项，而且原来那份快照还在"每辆车 × 每个可见客户端"
 * 的循环里被深拷贝（{@code VehicleExtraData.copy()} 是 JSON 往返，60 µs/次）。</p>
 *
 * <h2>分层</h2>
 * <ul>
 *   <li><b>整份快照</b>（{@code vehiclesToUpdate}）：客户端**第一次看到**这辆车，或者**静态字段**变了
 *       （换交路、改编组、path 换了）—— 客户端据此重建镜像（`VehicleExtension` 的构造里会重建
 *       按节数缓存的渲染数据，所以"节数变了"必须走这条路）。</li>
 *   <li><b>稀疏补丁</b>（{@code vehiclesToPatch}）：只带这一拍变了的字段，客户端原地合并
 *       （{@code VehicleExtension.updateData(JsonObject)}，那个方法本来就是为部分更新留的）。</li>
 * </ul>
 *
 * <h2>判据的**方向**（重要）</h2>
 * <p>这里列的是"**允许走补丁的字段**"（动态白名单），**没列到的一律当静态** —— 于是"漏了一个字段"
 * 的后果是"多发一份快照"（只是慢一点），而不是"客户端那个字段永远不更新"（静默错）。</p>
 *
 * <p>再加一道保险：每辆车每 {@link #FULL_RESYNC_MILLIS} 毫秒强制发一次整份快照。
 * 于是即使这条白名单在某次改动里漏了东西，偏差最多存在这么久，而不是永久。</p>
 */
public final class VehicleSyncPatch {

	private VehicleSyncPatch() {
	}

	/** 每隔这么久无条件发一次整份快照（兜底，见类注释）。 */
	public static final long FULL_RESYNC_MILLIS = 30_000L;

	/**
	 * 允许走补丁的字段名（`vehicle` 段与 `data` 段**共用同一张表** —— 两段里同名字段本来就同义）。
	 *
	 * <p>分组说明：位置/速度与走行读数、门与人工标记、驾驶室与钥匙、司机控制与显示、空气制动读数、
	 * AWS/LZB/闭塞相关读数。</p>
	 */
	private static final java.util.Set<String> DYNAMIC_KEYS = java.util.Set.of(
		// 走行与位置（客户端本地也在积分，这些是权威校正值）
		"speed", "railProgress", "elapsedDwellTime", "nextStoppingIndexAto", "nextStoppingIndexManual",
		"reversed", "departureIndex", "sidingDepartureTime",
		// 停车与功率（engine 侧 checkForUpdate 认的就是这一组）
		"stoppingPoint", "speedTarget", "powerLevel", "doorTarget",
		"isCurrentlyManual", "mmtrDoorLeft", "mmtrDoorRight", "mmtrDoorManual",
		// 驾驶室 / 钥匙 / 任务身份
		"mmtrActive", "mmtrMode", "mmtrDriver", "mmtrActiveCab", "mmtrCabKeyHolder", "mmtrCabCrew",
		"mmtrCabCarIndex", "mmtrCabEnd", "mmtrCabArcM",
		// 调车授权
		"mmtrShuntAuthority", "mmtrShuntSpeedLimitKmh", "mmtrShuntRemainingS",
		// 司机控制与保护（三手柄：油门手柄位置、定速巡航设定值、手柄规格字符串）
		"mmtrThrottleNotch", "mmtrBrakeNotch", "mmtrReverser", "mmtrThrottleAxis", "mmtrBrakeAxis",
		"mmtrDriveHandle", "mmtrCruiseKmh", "mmtrHandleSpec", "mmtrHoldReason",
		"mmtrEmergency", "mmtrProtection",
		// 空气制动读数
		"mmtrPipePressure", "mmtrBrakeCylinderPressure", "mmtrAirState",
		// 任务/许可读数
		"mmtrMotionMirror", "mmtrRunTotalDistance", "mmtrRunStopTarget",
		"mmtrAwsWarningPending", "mmtrAwsWarningAcknowledged", "mmtrBlockHeld", "mmtrSpeedLimitKmh",
		"mmtrLzbSupervising", "mmtrLzbCeilingKmh", "mmtrLzbTargetKmh", "mmtrLzbTargetDistanceM",
		// 任务提示（作业号 / 这一步的人话说明 / 第几步 / mission 状态与执行者）—— 司机 HUD 读它。
		// 停在站台等发车时速度与门都不变，所以它靠引擎侧的"变了就标脏"推，不靠这些读数顺带带出去。
		"mmtrJobId", "mmtrTaskNote", "mmtrTaskStep", "mmtrTaskSteps", "mmtrMissionState", "mmtrMissionExecutor",
		// 站台作业子任务（到站停稳 / 开门 / 停够 / 关门）：清单 + "现在该做什么" + 版本号与确认数。
		// 同样靠"变了就标脏"推 —— 这些字变化的时刻（车稳稳停着、门开着）恰恰是读数全都不变的时候。
		"mmtrSubTasks", "mmtrSubTaskHint", "mmtrSubTaskRevision", "mmtrSubTaskAcks"
		/*
		 * 刻意**不**在白名单里的：`ridingEntities`。
		 *
		 * <p>它虽然属于"车变了"，但客户端收到上下车之后要做一件有副作用的事 —— 重建
		 * `EntityHelper.HIDDEN_PLAYERS` 与乘客插值（见 `PacketUpdateVehiclesLifts` 里那段）。
		 * 那条路只在"整份更新"时走；把它放进补丁就得上车时也走一遍重活，等于把省下的又花回去。
		 * 而上下车是"每站一次"量级，走整份完全划算。</p>
		 */
	);

	/**
	 * 对比"上一次发出去的整份快照"与"现在这一份"，给出这一拍该发的东西。
	 *
	 * @param lastSent 上一次发出的整份快照（{@code {"vehicle":…,"data":…}}）；{@code null} = 还没发过
	 * @param current  现在这一份（同样形状）
	 * @return **补丁**（只含变化的叶子，形状与快照一致，可直接喂给
	 * {@code VehicleExtension.updateData(JsonObject)}）；{@code null} = 应当发整份快照
	 * （没发过、有静态字段变了、或两段结构对不上）
	 */
	public static @Nullable JsonObject patchOf(@Nullable JsonObject lastSent, JsonObject current) {
		if (lastSent == null) {
			return null;
		}
		final JsonObject patch = new JsonObject();
		if (!diffSection(lastSent.getAsJsonObject("vehicle"), current.getAsJsonObject("vehicle"), patch, "vehicle")) {
			return null;
		}
		if (!diffSection(lastSent.getAsJsonObject("data"), current.getAsJsonObject("data"), patch, "data")) {
			return null;
		}
		if (patch.size() == 0) {
			// 一个字段都没变 ⇒ 空补丁（调用方按"无需推送"处理）
			return patch;
		}
		/*
		 * ★ **两段都必须出现**（2026-09-16 实机回归修的那条）。
		 *
		 * <p>客户端合并用的是 {@code VehicleExtension.updateData(JsonObject)}，里面写死了
		 * {@code new JsonReader(jsonObject.getAsJsonObject("vehicle"))} 与
		 * {@code new JsonReader(jsonObject.getAsJsonObject("data"))} —— 它假设两段都在
		 * （整份快照永远都在），而**只带一段的补丁会让 `getAsJsonObject(...)` 返回 null**，
		 * `new JsonReader(null)` 当场抛异常、**整条补丁作废**：现场表现是"有的补丁生效、有的炸"，
		 * 车与人的镜像停在旧值（用户报的"无法移动并抽搐"）。</p>
		 *
		 * <p>代价是两个字符（`{}`），换来客户端那条既有代码不必改 —— 也就不必要求所有客户端同时更新。</p>
		 */
		if (!patch.has("vehicle")) {
			patch.add("vehicle", new JsonObject());
		}
		if (!patch.has("data")) {
			patch.add("data", new JsonObject());
		}
		return patch;
	}

	/**
	 * 逐字段比对一段，把变化的叶子写进 {@code patch}。
	 *
	 * @return {@code false} = 出现了**静态字段**的变化（或结构对不上）⇒ 调用方必须发整份快照
	 */
	private static boolean diffSection(@Nullable JsonObject lastSent, @Nullable JsonObject current, JsonObject patch, String section) {
		if (lastSent == null || current == null) {
			return false;
		}
		final JsonObject sectionPatch = new JsonObject();
		boolean anyDynamic = false;

		for (final java.util.Map.Entry<String, JsonElement> entry : current.entrySet()) {
			final String key = entry.getKey();
			final JsonElement previous = lastSent.get(key);
			if (previous != null && previous.equals(entry.getValue())) {
				continue;
			}
			if (!DYNAMIC_KEYS.contains(key)) {
				// 静态字段变了：整份重发（客户端要重建镜像，例如节数变了）。
				return false;
			}
			sectionPatch.add(key, entry.getValue());
			anyDynamic = true;
		}

		// 上一次有、这一次没有的字段：同样当静态变化处理（不猜"删除"语义）。
		for (final String key : lastSent.keySet()) {
			if (!current.has(key)) {
				return false;
			}
		}

		if (anyDynamic) {
			patch.add(section, sectionPatch);
		}
		return true;
	}

	/** 白名单是否认这个字段名（用例用）。 */
	public static boolean isDynamicKey(String key) {
		return DYNAMIC_KEYS.contains(key);
	}
}
