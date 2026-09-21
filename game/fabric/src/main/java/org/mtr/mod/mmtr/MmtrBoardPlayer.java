package org.mtr.mod.mmtr;

import org.mtr.core.Main;
import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;
import org.mtr.mod.packet.PacketMmtrBoardPlayer;

import javax.annotation.Nullable;

/**
 * 「把某个玩家送到某辆车上并进入驾驶状态」——**服务端那一半**。
 *
 * <h2>为什么是两半</h2>
 *
 * <p>MTR 的乘车是客户端权威的：骑乘状态与车体局部坐标只存在于 {@code VehicleRidingMovement}，
 * 而且只有客户端的 {@code RenderVehicles → movePlayer} 每帧把玩家钉在车上。服务端**没法**替客户端
 * 建立骑乘状态，它能做的是两件事：</p>
 *
 * <ol>
 *   <li><b>把玩家挪到车旁/车上</b>（{@link ServerPlayerEntity#teleport}，权威位置）。这一步不只是
 *       "省得走路"：车辆的**客户端镜像**按玩家位置同步，人在几百格外时那辆车根本不在客户端的
 *       {@code MinecraftClientData.vehicles} 里，客户端也就算不出座位点。</li>
 *   <li><b>告诉那个客户端"进这辆车的驾驶室"</b>（{@link PacketMmtrBoardPlayer}）——剩下的由
 *       {@code MmtrBoardRequest} + {@code MmtrCabInteraction.enterCab} 在客户端完成（与按 G 同一条路）。</li>
 * </ol>
 *
 * <p>顺序不能反：先挪人、再发包。</p>
 *
 * <h2>落点为什么取转向架而不是座位</h2>
 *
 * <p>座位点是**车型资源包里的锚点**（{@code mmtr_cabdoor} / {@code MmtrVehicleAnchors.cabView}），
 * 由客户端算才与它自己那份模型一致（两个入口各推一遍必然分叉，notes/163）。服务端只把人放到
 * 那节车的真实位置上——差的那一两米由客户端进驾驶室时一次抹平。</p>
 */
public final class MmtrBoardPlayer {

	private MmtrBoardPlayer() {
	}

	/** 落点相对轨面的抬升：转向架位置在轨面上，抬一格免得人落在轨枕里。 */
	private static final double BOARD_Y_OFFSET_M = 1.0;

	/**
	 * 把 {@code player} 送到 {@code vehicleId} 那辆车上，并让他的客户端进入驾驶室。
	 *
	 * @param cabSpec {@code <车厢序号><A|B>}（1 起，例如 {@code 1A}）；空串 = 客户端挑第一个可用的
	 * @return 是否找到了车并发出了请求（**不代表客户端已经坐进去** —— 那要看客户端的日志与引擎的驾驶室镜像）
	 */
	public static boolean board(MinecraftServer minecraftServer, ServerPlayerEntity player, long vehicleId, String cabSpec) {
		final Main main = Init.getMain();
		if (main == null || minecraftServer == null || player == null) {
			return false;
		}
		final boolean[] done = {false};
		MinecraftServerHelper.iterateWorlds(minecraftServer, mappedWorld -> {
			if (done[0]) {
				return;
			}
			final Simulator simulator = main.getSimulator(Init.getWorldId(new World(mappedWorld.data)));
			if (simulator == null) {
				return;
			}
			final Vehicle vehicle = simulator.mmtrFindVehicle(vehicleId);
			if (vehicle == null) {
				return;
			}
			/*
			 * **驾驶室由引擎挑，不由客户端挑**（2026-09-21 实机）。
			 *
			 * 方向是由被占用的驾驶室决定的（`towardB() = cabs.travelsToward(B) != travelReversed`），
			 * 而车场刷出来的车带着引擎的 SYSTEM 占位钥匙、方向已经定死 —— 客户端挑"第一个驾驶室"
			 * 会把人放进**车尾**（现场：被传送到 1A，而行进方向是另一端）。
			 * 引擎知道这列车接下来要往哪边走（与自臂同一条判据），所以问它一句。
			 */
			final String cab = cabSpec == null || cabSpec.isEmpty() ? simulator.mmtrPreferredCabSpec(vehicleId) : cabSpec;
			// 停稳时把换向器归位：接手的人要坐在车头（自动运行为了走得通可能是反着跑的）。
			vehicle.mmtrResetTravelDirectionForDriver();
			final double[] position = carWorldPosition(vehicle, cab);
			if (position == null) {
				Init.LOGGER.warn("[MMTR-BOARD] 车 {} 算不出落点（没有车辆编组？）", vehicleId);
				return;
			}
			player.teleport(mappedWorld, position[0], position[1], position[2], player.getYaw(0), player.getPitch(0));
			player.setFallDistanceMapped(0);
			Init.REGISTRY.sendPacketToClient(player, new PacketMmtrBoardPlayer(PacketMmtrBoardPlayer.contentOf(vehicleId, cab)));
			Init.LOGGER.info("[MMTR-BOARD] {} → 车 {}：落点 ({}, {}, {})，驾驶室 {}（{}），已发出上车请求",
					player.getName().getString(), vehicleId,
					Math.round(position[0] * 100) / 100.0, Math.round(position[1] * 100) / 100.0, Math.round(position[2] * 100) / 100.0,
					cab.isEmpty() ? "(自动)" : cab,
					cabSpec == null || cabSpec.isEmpty() ? "引擎按行进方向挑的前端驾驶室" : "调用方指定");
			done[0] = true;
		});
		return done[0];
	}

	/** 按名字找在线玩家（指令里可以点名别的玩家，不点名就是执行者自己）。 */
	@Nullable
	public static ServerPlayerEntity findPlayer(MinecraftServer minecraftServer, String name) {
		if (minecraftServer == null || name == null || name.isEmpty()) {
			return null;
		}
		final ServerPlayerEntity[] found = {null};
		MinecraftServerHelper.iteratePlayers(minecraftServer, player -> {
			if (found[0] == null && name.equalsIgnoreCase(player.getName().getString())) {
				found[0] = player;
			}
		});
		return found[0];
	}

	/**
	 * 这一节车当前所在的世界坐标（取第一个转向架的位置 = 车正下方的钢轨上）。
	 *
	 * <p>和 {@code MmtrInteractPrompt.carTransforms} 用的是同一个数据源
	 * （{@link Vehicle#getVehicleCarsAndPositions()}），只是服务端这边不需要朝向与倾斜。</p>
	 *
	 * @return {@code [x, y, z]}；编组为空时 {@code null}
	 */
	@Nullable
	private static double[] carWorldPosition(Vehicle vehicle, String cabSpec) {
		final ObjectArrayList<ObjectObjectImmutablePair<VehicleCar, ObjectArrayList<Vehicle.BogiePosition>>> cars = vehicle.getVehicleCarsAndPositions();
		if (cars.isEmpty()) {
			return null;
		}
		int carNumber = carIndexOf(cabSpec);
		if (carNumber < 0 || carNumber >= cars.size()) {
			carNumber = 0;
		}
		final ObjectArrayList<Vehicle.BogiePosition> bogies = cars.get(carNumber).right();
		if (bogies.isEmpty()) {
			return null;
		}
		final org.mtr.core.tool.Vector position = bogies.get(0).positionAndTiltAngle1().position();
		return new double[]{position.x(), position.y() + BOARD_Y_OFFSET_M, position.z()};
	}

	/** {@code "3B"} → 车节下标 {@code 2}（0 起）；空串或解析不了返回 {@code -1}（= 用第一节）。 */
	private static int carIndexOf(String cabSpec) {
		if (cabSpec == null || cabSpec.isEmpty()) {
			return -1;
		}
		try {
			return Integer.parseInt(cabSpec.replaceAll("[^0-9]", "")) - 1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}
}
