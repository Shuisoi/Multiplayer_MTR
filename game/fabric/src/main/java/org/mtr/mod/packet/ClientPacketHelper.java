package org.mtr.mod.packet;

import org.mtr.core.data.Lift;
import org.mtr.core.data.TransportMode;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.ScreenExtension;
import org.mtr.mod.Init;
import org.mtr.mod.block.*;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.screen.*;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Since packets are registered serverside, they will fail if any client classes are used (e.g. screens).
 */
public final class ClientPacketHelper {

	public static void openBlockEntityScreen(BlockPos blockPos) {
		getBlockEntity(blockPos, blockEntity -> {
			if (blockEntity.data instanceof BlockTrainAnnouncer.BlockEntity) {
				openScreen(new TrainAnnouncerScreen(blockPos, (BlockTrainAnnouncer.BlockEntity) blockEntity.data), screenExtension -> screenExtension instanceof TrainAnnouncerScreen);
			} else if (blockEntity.data instanceof BlockTrainScheduleSensor.BlockEntity) {
				openScreen(new TrainScheduleSensorScreen(blockPos, (BlockTrainScheduleSensor.BlockEntity) blockEntity.data), screenExtension -> screenExtension instanceof TrainScheduleSensorScreen);
			} else if (blockEntity.data instanceof BlockTrainSensorBase.BlockEntityBase) {
				openScreen(new TrainBasicSensorScreen(blockPos), screenExtension -> screenExtension instanceof TrainBasicSensorScreen);
			} else if (blockEntity.data instanceof BlockRailwaySign.BlockEntity || blockEntity.data instanceof BlockRouteSignBase.BlockEntityBase) {
				openScreen(new RailwaySignScreen(blockPos), screenExtension -> screenExtension instanceof RailwaySignScreen);
			} else if (blockEntity.data instanceof BlockLiftTrackFloor.BlockEntity) {
				openScreen(new LiftTrackFloorScreen(blockPos, (BlockLiftTrackFloor.BlockEntity) blockEntity.data), screenExtension -> screenExtension instanceof LiftTrackFloorScreen);
			} else if (blockEntity.data instanceof BlockSignalBase.BlockEntityBase) {
				openScreen(new SignalColorScreen(blockPos, (BlockSignalBase.BlockEntityBase) blockEntity.data), screenExtension -> screenExtension instanceof SignalColorScreen);
			} else if (blockEntity.data instanceof BlockDriverKeyDispenser.BlockEntity) {
				openScreen(new DriverKeyDispenserScreen(blockPos, (BlockDriverKeyDispenser.BlockEntity) blockEntity.data), screenExtension -> screenExtension instanceof DriverKeyDispenserScreen);
			} else if (blockEntity.data instanceof BlockEyeCandy.BlockEntity) {
				openScreen(new EyeCandyScreen(blockPos, (BlockEyeCandy.BlockEntity) blockEntity.data), screenExtension -> screenExtension instanceof EyeCandyScreen);
			}
		});
	}

	public static void openDashboardScreen(TransportMode transportMode, PacketOpenDashboardScreen.ScreenType screenType, long id) {
		switch (screenType) {
			case STATION:
				openScreen(new EditStationScreen(MinecraftClientData.getDashboardInstance().stationIdMap.get(id), new DashboardScreen(transportMode)), screenExtension -> screenExtension instanceof EditStationScreen);
				break;
			case DEPOT:
				openScreen(new EditDepotScreen(MinecraftClientData.getDashboardInstance().depotIdMap.get(id), transportMode, new DashboardScreen(transportMode)), screenExtension -> screenExtension instanceof EditDepotScreen);
				break;
			case PLATFORM:
				openScreen(new PlatformScreen(MinecraftClientData.getDashboardInstance().platformIdMap.get(id), transportMode, new DashboardScreen(transportMode)), screenExtension -> screenExtension instanceof PlatformScreen);
				break;
			case SIDING:
				openScreen(new SidingScreen(MinecraftClientData.getDashboardInstance().sidingIdMap.get(id), transportMode, new DashboardScreen(transportMode)), screenExtension -> screenExtension instanceof SidingScreen);
				break;
			default:
				openScreen(new DashboardScreen(transportMode), screenExtension -> screenExtension instanceof DashboardScreen);
				break;
		}
	}

	public static void openLiftCustomizationScreen(BlockPos blockPos) {
		for (final Lift lift : MinecraftClientData.getInstance().lifts) {
			if (lift.getFloorIndex(Init.blockPosToPosition(blockPos)) >= 0) {
				MinecraftClient.getInstance().openScreen(new Screen(new LiftCustomizationScreen(lift)));
				break;
			}
		}
	}

	public static void openPIDSConfigScreen(BlockPos blockPos, int maxArrivals) {
		getBlockEntity(blockPos, blockEntity -> {
			if (blockEntity.data instanceof BlockPIDSBase.BlockEntityBase) {
				openScreen(new PIDSConfigScreen(blockPos, maxArrivals), screenExtension -> screenExtension instanceof PIDSConfigScreen);
			}
		});
	}

	public static void openRailShapeModifierScreen(String railId) {
		openScreen(new RailModifierScreen(railId), screenExtension -> screenExtension instanceof RailModifierScreen);
	}

	public static void openTicketMachineScreen(int balance) {
		openScreen(new TicketMachineScreen(balance), screenExtension -> screenExtension instanceof TicketMachineScreen);
	}

	/**
	 * 综合运转面板（notes/408 §3）：一条路给两个入口用 ——
	 * 物品右键（服务端发 {@code PacketMmtrPdaScreen} 到这）、驾驶中按 TAB（客户端直接调）。
	 *
	 * <p>{@code drivePage} 只是**开屏时的初始页**：面板自己有"车次 / 驾驶"的切换按钮，
	 * 而且驾驶页在没有"我在值守的车"时会自己退回列表页（{@code PdaScreen.init2}）。</p>
	 */
	public static void openPdaScreen(boolean drivePage) {
		openScreen(new PdaScreen(drivePage), screenExtension -> screenExtension instanceof PdaScreen);
	}

	private static void openScreen(ScreenExtension screenExtension, Predicate<ScreenExtension> isInstance) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final Screen screen = minecraftClient.getCurrentScreenMapped();
		if (screen == null || screen.data instanceof ScreenExtension && !isInstance.test((ScreenExtension) screen.data)) {
			minecraftClient.openScreen(new Screen(screenExtension));
		}
	}

	private static void getBlockEntity(BlockPos blockPos, Consumer<BlockEntity> consumer) {
		final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
		if (clientWorld != null) {
			final BlockEntity blockEntity = clientWorld.getBlockEntity(blockPos);
			if (blockEntity != null) {
				consumer.accept(blockEntity);
			}
		}
	}
}
