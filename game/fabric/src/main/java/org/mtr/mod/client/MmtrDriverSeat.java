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
 * <p>已知的松紧度：车厢中部也算"离某个驾驶室最近"。BR101 这类机车两端各有驾驶室、车厢本身就短，
 * 实际影响是"站在机车中部也能开"；要收紧就在 {@code nearestCab} 里加一个距离门槛，箱位只有那一处。</p>
 */
public final class MmtrDriverSeat {

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
		final int cab = MmtrVehicleAnchors.nearestCab(MmtrVehicleAnchors.get(modelId), carNumber, playerOffset.getZMapped());
		if (cab <= 0) {
			return null;
		}
		// 引擎端由风挡锚点的 Z 符号定（+Z = 引擎的 B 端），不是锚点编号。
		final ObjectArrayList<MmtrVehicleAnchors.Anchor> windshields = MmtrVehicleAnchors.findWindshields(MmtrVehicleAnchors.get(modelId), carNumber, cab);
		final int engineEnd = windshields.isEmpty() ? cab : MmtrVehicleAnchors.engineEndOfSeat(windshields.get(0).position.z());
		return new Seat(ridingVehicleId, carNumber, cab, engineEnd);
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
		final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> cars = vehicle.getVehicleCarsAndPositions();
		return carNumber < 0 || carNumber >= cars.size() ? null : cars.get(carNumber).left().getVehicleId();
	}
}
