package org.mtr.mod.client;

import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntObjectImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.Vector3d;
import org.mtr.mod.data.VehicleExtension;

import javax.annotation.Nullable;

/**
 * **我是不是坐在司机位上**，以及坐在哪辆车的哪个驾驶室 —— 驾驶输入与"谁是司机"上报共用的唯一判据。
 *
 * <h2>为什么不再看钥匙</h2>
 *
 * <p>用户口径（2026-09-19）：**操作手柄不需要手里握着钥匙**。原来客户端要先按 G 申领驾驶室、等引擎确认
 * （{@code mmtrIsCabConfirmed}）才允许发手柄状态，结果是"人明明坐在司机位上，按了没反应"。
 * 现在判据回到**位置**：骑在某辆车上、且沿车长的位置离某个驾驶室的玻璃最近，就是坐在那个驾驶室。</p>
 *
 * <h2>位置判据只有一份</h2>
 *
 * <p>真正做判断的是 {@link MmtrVehicleAnchors#nearestCab}，雨刷（哪把刀该动）用的是同一条规则 ——
 * 于是"雨刷会动的地方"和"能操作手柄的地方"永远指同一个驾驶室。这里只负责把骑行状态翻译成
 * 那三个入参（哪辆车、哪节车、沿车长的位置）。</p>
 *
 * <h2>⚠️ 光"离驾驶室最近"不够：还要真的站在操纵位附近（2026-10-01）</h2>
 *
 * <p>第一版只有 {@code nearestCab}：**车厢里任何地方都算"离某个驾驶室最近"**，于是一节带驾驶室的车
 * （SAF420 的 cab 车、BR101）全车都算"坐在司机位上"。后果不是"多算了几个司机"，而是
 * <b>乘客在车厢里按 A 走不动</b>（解耦把原版平移键摘了，见 {@link MmtrInputDecouple}），
 * 同时 A/D 还会去推手柄。用户口径是"坐在司机位上"，所以这里补一道<b>沿车长</b>的距离门槛
 * {@link #CONTROLS_RADIUS_M}，量的是乘坐点到该驾驶室座位点的距离（横断面不设限：司机室本来就只有
 * 2–3 m 宽，真正的分界是沿车长的那一头）。</p>
 *
 * <p>门槛的取值来自各车型锚点的实测（resourcepack 里的 {@code assets/mtr/mmtr_anchors_*.json}，
 * 乘坐空间 = 文件空间绕 Y 转 180°）：</p>
 *
 * <ul>
 *   <li><b>saf420cab_a/b</b>：座位 {@code |z| = 8.200}，司机门 7.502，风挡 9.725 ⇒ 整间司机室
 *       {@code |z| ∈ [7.3, 9.8]}（离座位最远 1.6 m）；车厢中部离座位 <b>8.2 m</b>。</li>
 *   <li><b>br101</b>：座位 {@code |z| = 7.707}，司机门 7.303，风挡 8.986 ⇒ 司机室
 *       {@code |z| ∈ [7.3, 9.0]}（离座位最远 1.3 m）；车厢中部离座位 <b>7.7 m</b>。</li>
 * </ul>
 *
 * <p>2.0 m 因此既装得下整间司机室（含站在风挡前那种站位），又把车厢走道排除在外（离座位最近的走道
 * 位置也有 3.7 m 以上）。判不出距离的车型（没有 {@code mmtr_seat_}／{@code mmtr_cabdoor_} 锚点）沿用
 * 旧口径 —— 那时也没有"坐在司机位上"这回事可言。</p>
 */
public final class MmtrDriverSeat {

	/**
	 * **算不算"坐在操纵位上"**：乘坐点到该驾驶室座位点的距离（**沿车长**，米）。
	 *
	 * <p>取值理由与实测见类注释。它同时决定三件事，而这三件事本来就该同进同出：能不能操作手柄、
	 * 要不要报"我是司机"（引擎据此授予操纵权与占用锁）、要不要把与 MMTR 撞键的原版操作摘下来。</p>
	 */
	public static final double CONTROLS_RADIUS_M = 2.0D;

	/** 已经说过"离操纵位太远"的车+端（每个组合一行，防止每 tick 刷屏）。 */
	private static final java.util.Set<String> reportedOutOfReach = new java.util.HashSet<>();

	private MmtrDriverSeat() {
	}

	/**
	 * @param vehicleId 正在骑的车
	 * @param carNumber 车节下标（0 起）
	 * @param cab       **模型自己的**驾驶室编号（{@code mmtr_cabdoor_<cab>}）
	 * @param engineEnd **引擎的端**（1 = A，2 = B）—— 由座位点的 Z 符号定，与 {@code cab} 不一定同号
	 *                  （见 {@link MmtrVehicleAnchors#engineEndOfSeat}）
	 */
	public record Seat(long vehicleId, int carNumber, int cab, int engineEnd) {

		/** 引擎侧的驾驶室名（{@code MmtrCommandExecutor} 的 {@code <car><A|B>}，车节号从 1 起）。 */
		public String cabSpec() {
			return (carNumber + 1) + (engineEnd == 2 ? "B" : "A");
		}
	}

	/** 当前坐着的司机位；人不在车上 / 这节车没有驾驶室 / 位置还没算出来时为 {@code null}。 */
	public static @Nullable Seat current() {
		final VehicleExtension vehicle = ridingVehicle();
		if (vehicle == null) {
			return null;
		}
		final long ridingVehicleId = vehicle.getId();
		final IntObjectImmutablePair<ObjectObjectImmutablePair<Vector3d, Double>> ridingCar = VehicleRidingMovement.getRidingVehicleCarNumberAndOffset(ridingVehicleId);
		if (ridingCar == null) {
			return null;
		}
		final int carNumber = ridingCar.leftInt();
		final Vector3d playerOffset = ridingCar.right().left();
		// 位置是"上一次 movePlayer 的结果"，刚上车那一两拍还是 null —— 那时答案就是"还没坐下"，不能抛 NPE
		// （notes/198 记过：这个 NPE 从 render 里抛出去会把整条车辆渲染循环打断）。
		if (playerOffset == null) {
			return null;
		}
		final String modelId = modelIdFor(vehicle, carNumber);
		if (modelId == null) {
			return null;
		}
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> anchors = MmtrVehicleAnchors.get(modelId);
		final int cab = MmtrVehicleAnchors.nearestCab(anchors, carNumber, playerOffset.getZMapped());
		if (cab <= 0) {
			return null;
		}
		// 引擎端由风挡锚点的 Z 符号定（+Z = 引擎的 B 端），不是锚点编号。
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> windshields = MmtrVehicleAnchors.findWindshields(anchors, carNumber, cab);
		final int engineEnd = windshields.isEmpty() ? cab : MmtrVehicleAnchors.engineEndOfSeat(windshields.get(0).position.z());
		/*
		 * **还要真的站在操纵位附近**：只有"离某个驾驶室最近"是不够的 —— 车厢里任何地方都满足它，
		 * 于是乘客在车厢里走动也会被当成司机（解耦摘掉原版平移键 ⇒ 走不动；A/D 还会去推手柄）。
		 * 量的是沿车长到座位点的距离，见 CONTROLS_RADIUS_M 与类注释里的实测表。
		 */
		final MmtrVehicleAnchors.CabView cabView = MmtrVehicleAnchors.cabView(anchors, cab);
		final double controlsZ = cabView != null ? cabView.z : (windshields.isEmpty() ? Double.NaN : windshields.get(0).position.z());
		if (!Double.isNaN(controlsZ) && Math.abs(playerOffset.getZMapped() - controlsZ) > CONTROLS_RADIUS_M) {
			reportOutOfReach(ridingVehicleId, carNumber, cab, playerOffset.getZMapped(), controlsZ);
			return null;
		}
		return new Seat(ridingVehicleId, carNumber, cab, engineEnd);
	}

	/** 说一次"人还在车厢里，但离操纵位太远"（每辆车每一端一行；解耦与手柄都由它决定，值得留痕）。 */
	private static void reportOutOfReach(long vehicleId, int carNumber, int cab, double playerZ, double controlsZ) {
		final String key = vehicleId + "/" + carNumber + "/" + cab;
		if (reportedOutOfReach.size() >= 16 || !reportedOutOfReach.add(key)) {
			return;
		}
		org.mtr.mod.Init.LOGGER.info("[MMTR-SEAT] 离操纵位 {} m（门槛 {} m）⇒ 不算坐在司机位上：车={} 车节={} 驾驶室={}",
				Math.round(Math.abs(playerZ - controlsZ) * 100) / 100.0, CONTROLS_RADIUS_M, vehicleId, carNumber, cab);
	}

	/** 简写：我现在是不是坐在司机位上（= 能不能操作手柄 / 要不要告诉引擎"我是司机"）。 */
	public static boolean isAtControls() {
		return current() != null;
	}

	/** 正在骑的车（HUD 要读它的镜像速度）；没骑任何车时为 {@code null}。 */
	public static @Nullable VehicleExtension ridingVehicle() {
		final long ridingVehicleId = VehicleRidingMovement.getRidingVehicleId();
		if (ridingVehicleId == 0) {
			return null;
		}
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == ridingVehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	/** The model ID of one car of a consist - the same lookup {@code ModelPropertiesPart} uses. */
	@Nullable
	private static String modelIdFor(VehicleExtension vehicle, int carNumber) {
		return vehicle.mmtrCarResourceId(carNumber);
	}
}
