package org.mtr.mod;

import com.mojang.brigadier.arguments.StringArgumentType;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mtr.core.Main;
import org.mtr.core.data.Position;
import org.mtr.core.operation.SetTime;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.servlet.QueueObject;
import org.mtr.core.servlet.Webserver;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.GameRule;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.mapper.WorldHelper;
import org.mtr.mapping.registry.CommandBuilder;
import org.mtr.mapping.registry.Registry;
import org.mtr.mapping.tool.DummyClass;
import org.mtr.mixin.PlayerTeleportationStateAccessor;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.ArrivalsCacheServer;
import org.mtr.mod.data.RailActionModule;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.packet.*;
import org.mtr.mod.servlet.MinecraftOperationProcessor;
import org.mtr.mod.servlet.RequestHelper;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

public final class Init implements Utilities {

	private static Main main;
	private static int serverPort;
	private static Runnable sendWorldTimeUpdate;
	private static boolean canSendWorldTimeUpdate = true;
	private static boolean isDedicatedServer = true;
	private static int serverTick;
	private static long lastSavedMillis;
	private static Consumer<Webserver> webserverSetup;

	public static final String MOD_ID = "mtr";
	public static final String MOD_ID_NTE = "mtrsteamloco";
	public static final Logger LOGGER = LogManager.getLogger("MinecraftTransitRailway");
	public static final Registry REGISTRY = new Registry();
	public static final int SECONDS_PER_MC_HOUR = 50;
	public static final int AUTOSAVE_INTERVAL = 30000;
	public static final RequestHelper REQUEST_HELPER = new RequestHelper();

	private static final int MILLIS_PER_MC_DAY = SECONDS_PER_MC_HOUR * MILLIS_PER_SECOND * HOURS_PER_DAY;
	private static final Object2ObjectArrayMap<ServerWorld, RailActionModule> RAIL_ACTION_MODULES = new Object2ObjectArrayMap<>();
	private static final ObjectArrayList<String> WORLD_ID_LIST = new ObjectArrayList<>();
	private static final Object2ObjectAVLTreeMap<UUID, Runnable> RIDING_PLAYERS = new Object2ObjectAVLTreeMap<>();

	public static void init() {
		LOGGER.info("Starting Minecraft with arguments:\n{}", String.join("\n", ManagementFactory.getRuntimeMXBean().getInputArguments()));
		AsciiArt.print();
		Blocks.init();
		Items.init();
		BlockEntityTypes.init();
		EntityTypes.init();
		CreativeModeTabs.init();
		SoundEvents.init();
		DummyClass.enableLogging();

		// Register packets
		REGISTRY.setupPackets(new Identifier(MOD_ID, "packet"));
		REGISTRY.registerPacket(PacketAddBalance.class, PacketAddBalance::new);
		REGISTRY.registerPacket(PacketBlockRails.class, PacketBlockRails::new);
		REGISTRY.registerPacket(PacketBroadcastRailActions.class, PacketBroadcastRailActions::new);
		REGISTRY.registerPacket(PacketCheckRouteIdHasDisabledAnnouncements.class, PacketCheckRouteIdHasDisabledAnnouncements::new);
		REGISTRY.registerPacket(PacketDeleteData.class, PacketDeleteData::new);
		REGISTRY.registerPacket(PacketDeleteRailAction.class, PacketDeleteRailAction::new);
		REGISTRY.registerPacket(PacketDriveTrain.class, PacketDriveTrain::new);
		REGISTRY.registerPacket(PacketDriveControl.class, PacketDriveControl::new);
		REGISTRY.registerPacket(PacketMmtrCabOp.class, PacketMmtrCabOp::new);
		REGISTRY.registerPacket(PacketMmtrCoupleOp.class, PacketMmtrCoupleOp::new);
		REGISTRY.registerPacket(PacketMmtrRoutes.class, PacketMmtrRoutes::new);
		// 服务端 → 客户端：「把这位玩家放进那辆车的驾驶室」（/mtr mmtrboard、引擎指令栏的 train board）
		REGISTRY.registerPacket(PacketMmtrBoardPlayer.class, PacketMmtrBoardPlayer::new);
		REGISTRY.registerPacket(PacketFetchArrivals.class, PacketFetchArrivals::new);
		REGISTRY.registerPacket(PacketForwardClientRequest.class, PacketForwardClientRequest::new);
		REGISTRY.registerPacket(PacketUpdateKeyDispenserConfig.class, PacketUpdateKeyDispenserConfig::new);
		REGISTRY.registerPacket(PacketOpenBlockEntityScreen.class, PacketOpenBlockEntityScreen::new);
		REGISTRY.registerPacket(PacketOpenDashboardScreen.class, PacketOpenDashboardScreen::new);
		REGISTRY.registerPacket(PacketOpenLiftCustomizationScreen.class, PacketOpenLiftCustomizationScreen::new);
		REGISTRY.registerPacket(PacketOpenPIDSConfigScreen.class, PacketOpenPIDSConfigScreen::new);
		REGISTRY.registerPacket(PacketOpenTicketMachineScreen.class, PacketOpenTicketMachineScreen::new);
		REGISTRY.registerPacket(PacketPressLiftButton.class, PacketPressLiftButton::new);
		REGISTRY.registerPacket(PacketRequestData.class, PacketRequestData::new);
		REGISTRY.registerPacket(PacketSetRouteIdHasDisabledAnnouncements.class, PacketSetRouteIdHasDisabledAnnouncements::new);
		REGISTRY.registerPacket(PacketTurnOnBlockEntity.class, PacketTurnOnBlockEntity::new);
		REGISTRY.registerPacket(PacketUpdateData.class, PacketUpdateData::new);
		REGISTRY.registerPacket(PacketUpdateEyeCandyConfig.class, PacketUpdateEyeCandyConfig::new);
		REGISTRY.registerPacket(PacketUpdateLastRailStyles.class, PacketUpdateLastRailStyles::new);
		REGISTRY.registerPacket(PacketUpdateLiftTrackFloorConfig.class, PacketUpdateLiftTrackFloorConfig::new);
		REGISTRY.registerPacket(PacketUpdatePIDSConfig.class, PacketUpdatePIDSConfig::new);
		REGISTRY.registerPacket(PacketUpdateRailwaySignConfig.class, PacketUpdateRailwaySignConfig::new);
		REGISTRY.registerPacket(PacketUpdateSignalConfig.class, PacketUpdateSignalConfig::new);
		REGISTRY.registerPacket(PacketUpdateTrainAnnouncerConfig.class, PacketUpdateTrainAnnouncerConfig::new);
		REGISTRY.registerPacket(PacketUpdateTrainScheduleSensorConfig.class, PacketUpdateTrainScheduleSensorConfig::new);
		REGISTRY.registerPacket(PacketUpdateTrainSensorConfig.class, PacketUpdateTrainSensorConfig::new);
		REGISTRY.registerPacket(PacketUpdateVehiclesLifts.class, PacketUpdateVehiclesLifts::new);
		REGISTRY.registerPacket(PacketUpdateVehicleRidingEntities.class, PacketUpdateVehicleRidingEntities::new);

		// Register command
		REGISTRY.registerCommand("mtr", commandBuilderMtr -> {
			// Generate depot(s) by name
			// Clear depot(s) by name
			// Instant deploy depot(s) by name
			// Force copy a world backup from one folder another
			/*
			 * /mtr mmtrboard <vehicleId> [<车厢序号><A|B>] [玩家名]
			 *
			 * 把某位玩家送到某辆车的驾驶室里（"传送上车 + 进入驾驶状态"）。没有它就只能自己走到车门口
			 * 用准星对着司机门按 G —— 那条路要求人已经站在车旁边，而"作业表驱动的车停在车场深处"
			 * 恰恰是人不方便走过去的情形。
			 *
			 * 分工：服务端把玩家挪到那节车上（客户端镜像按位置同步，人不到车旁就算不出座位点），
			 * 再由 PacketMmtrBoardPlayer 让**那个客户端**执行进驾驶室（与按 G 同一段代码）。
			 * 玩家名省略 = 执行这条指令的人。权限 2（OP）—— 它挪的是别的玩家的位置。
			 */
			commandBuilderMtr.then("mmtrboard", commandBuilderBoard -> {
				commandBuilderBoard.permissionLevel(2);
				commandBuilderBoard.then("vehicleId", StringArgumentType.string(), commandBuilderVehicleId -> {
					commandBuilderVehicleId.executes(contextHandler -> mmtrBoard(contextHandler, "", ""));
					commandBuilderVehicleId.then("cab", StringArgumentType.string(), commandBuilderCab -> {
						commandBuilderCab.executes(contextHandler -> mmtrBoard(contextHandler, contextHandler.getString("cab"), ""));
						commandBuilderCab.then("player", StringArgumentType.string(), commandBuilderPlayer ->
							commandBuilderPlayer.executes(contextHandler -> mmtrBoard(contextHandler, contextHandler.getString("cab"), contextHandler.getString("player"))));
					});
				});
			});
			commandBuilderMtr.then("restoreWorld", commandBuilderRestoreWorld -> {
				commandBuilderRestoreWorld.permissionLevel(4);
				commandBuilderRestoreWorld.then("worldDirectory", StringArgumentType.string(), innerCommandBuilder1 -> innerCommandBuilder1.then("backupDirectory", StringArgumentType.string(), innerCommandBuilder2 -> innerCommandBuilder2.executes(contextHandler -> {
					final Path runPath = contextHandler.getServer().getRunDirectory().toPath();
					final Path worldDirectory = runPath.resolve(contextHandler.getString("worldDirectory"));
					final Path backupDirectory = runPath.resolve(contextHandler.getString("backupDirectory"));
					final boolean worldDirectoryExists = Files.isDirectory(worldDirectory);
					final boolean backupDirectoryExists = Files.isDirectory(backupDirectory);
					if (worldDirectoryExists && backupDirectoryExists) {
						try {
							if (main != null) {
								main.stop();
							}
							contextHandler.sendSuccess(String.format("Restoring world backup from %s to %s...", backupDirectory, worldDirectory), true);
							FileUtils.deleteDirectory(worldDirectory.toFile());
							contextHandler.sendSuccess("Deleting world complete", true);
							FileUtils.copyDirectory(backupDirectory.toFile(), worldDirectory.toFile());
							contextHandler.sendSuccess("Restoring world backup complete", true);
							System.exit(0);
							return 1;
						} catch (Exception e) {
							contextHandler.sendFailure("Restoring world backup failed");
							LOGGER.error("", e);
							return -1;
						}
					} else {
						if (backupDirectoryExists) {
							contextHandler.sendFailure("World directory not found");
						} else if (worldDirectoryExists) {
							contextHandler.sendFailure("Backup directory not found");
						} else {
							contextHandler.sendFailure("Directories not found");
						}
						return -1;
					}
				})));
			});
		}, "minecrafttransitrailway");

		// Register events
		REGISTRY.eventRegistry.registerServerStarted(minecraftServer -> {
			// Start up the backend
			RAIL_ACTION_MODULES.clear();
			WORLD_ID_LIST.clear();
			MinecraftServerHelper.iterateWorlds(minecraftServer, serverWorld -> {
				RAIL_ACTION_MODULES.put(serverWorld, new RailActionModule(serverWorld));
				WORLD_ID_LIST.add(getWorldId(new World(serverWorld.data)));
			});

			Config.init(minecraftServer.getRunDirectory());
			final int defaultPort = Config.getServer().getWebserverPort();
			serverPort = defaultPort <= 0 ? -1 : findFreePort(defaultPort);
			main = new Main(minecraftServer.getSavePath(WorldSavePath.getRootMapped()).resolve("mtr"), serverPort, Config.getServer().getUseThreadedSimulation(), Config.getServer().getUseThreadedFileLoading(), webserverSetup, WORLD_ID_LIST.toArray(new String[0]));

			serverTick = 0;
			lastSavedMillis = System.currentTimeMillis();
			sendWorldTimeUpdate = () -> {
				if (canSendWorldTimeUpdate) {
					canSendWorldTimeUpdate = false;
					sendMessageC2S(
							OperationProcessor.SET_TIME,
							minecraftServer,
							null,
							new SetTime(
									(WorldHelper.getTimeOfDay(minecraftServer.getOverworld()) + 6000) * SECONDS_PER_MC_HOUR,
									MILLIS_PER_MC_DAY,
									GameRule.DO_DAYLIGHT_CYCLE.getBooleanGameRule(minecraftServer)
							),
							response -> canSendWorldTimeUpdate = true,
							SerializedDataBase.class
					);
				} else {
					LOGGER.error("Transport Simulation Core not responding; stopping Minecraft server!");
					minecraftServer.stop(false);
					canSendWorldTimeUpdate = true; // In singleplayer, this gives the player opportunity to re-enter world.
				}
			};

			Init.LOGGER.info("Starting server as a {} server", isDedicatedServer ? "dedicated" : "non-dedicated");
			if (isDedicatedServer && Config.getServer().forceShutDownStrayThreads()) {
				StrayThreadManager.register(minecraftServer);
			}

			Main.CLIENT_NAME_RESOLVER = uuid -> {
				final ServerPlayerEntity serverPlayerEntity = minecraftServer.getPlayerManager().getPlayer(uuid);
				return serverPlayerEntity == null ? "" : serverPlayerEntity.getName().getString();
			};
		});

		REGISTRY.eventRegistry.registerServerStopping(minecraftServer -> {
			if (main != null) {
				main.stop();
			}
			serverPort = 0;
			RIDING_PLAYERS.clear();
		});

		REGISTRY.eventRegistry.registerStartServerTick(() -> {
			if (sendWorldTimeUpdate != null && serverTick % (SECONDS_PER_MC_HOUR * 10) == 0) {
				sendWorldTimeUpdate.run();
			}

			ArrivalsCacheServer.tickAll();
			serverTick++;

			if (main != null) {
				if (!Config.getServer().getUseThreadedSimulation()) {
					main.manualTick();
				}

				final long currentMillis = System.currentTimeMillis();
				if (currentMillis - lastSavedMillis > AUTOSAVE_INTERVAL) {
					main.save();
					lastSavedMillis = currentMillis;
				}
			}

			RIDING_PLAYERS.values().forEach(Runnable::run);
		});

		REGISTRY.eventRegistry.registerEndWorldTick(serverWorld -> {
			final RailActionModule railActionModule = RAIL_ACTION_MODULES.get(serverWorld);
			if (railActionModule != null) {
				railActionModule.tick();
			}

			if (main != null) {
				final String dimension = getWorldId(new World(serverWorld.data));
				main.processMessagesS2C(WORLD_ID_LIST.indexOf(dimension), queueObject -> MinecraftOperationProcessor.process(queueObject, serverWorld, dimension));
			}
		});

		REGISTRY.eventRegistry.registerPlayerJoin((minecraftServer, serverPlayerEntity) -> {
			updatePlayer(serverPlayerEntity, false);
			// 开发服自动 OP: the dev client re-randomises its offline name every launch, so a one-time
			// RCON op never sticks - every player who joins this dev server is made an operator.
			final net.minecraft.server.PlayerManager playerManager = minecraftServer.getPlayerManager().data;
			final com.mojang.authlib.GameProfile profile = serverPlayerEntity.getGameProfile();
			if (!playerManager.isOperator(profile)) {
				playerManager.addToOperators(profile);
			}
		});
		REGISTRY.eventRegistry.registerPlayerDisconnect((minecraftServer, serverPlayerEntity) -> RIDING_PLAYERS.remove(serverPlayerEntity.getUuid()));

		// Finish registration
		REGISTRY.init();
	}

	public static void getRailActionModule(ServerWorld serverWorld, Consumer<RailActionModule> consumer) {
		final RailActionModule railActionModule = RAIL_ACTION_MODULES.get(serverWorld);
		if (railActionModule != null) {
			consumer.accept(railActionModule);
		}
	}

	/**
	 * @return the port of the webserver started by Transport Simulation Core, not the clientside webserver.
	 * <br>{@code 0} means the integrated server is not running
	 * <br>{@code -1} means the webserver is disabled
	 */
	public static int getServerPort() {
		return serverPort;
	}

	/** MMTR: engine access for the game-side command executor. */
	public static Main getMain() {
		return main;
	}

	public static <T extends SerializedDataBase> void sendMessageC2S(String key, @Nullable MinecraftServer minecraftServer, @Nullable World world, SerializedDataBase data, @Nullable Consumer<T> consumer, @Nullable Class<T> responseDataClass) {
		if (main != null) {
			main.sendMessageC2S(world == null ? null : WORLD_ID_LIST.indexOf(getWorldId(world)), new QueueObject(key, data, consumer == null || minecraftServer == null ? null : responseData -> minecraftServer.execute(() -> consumer.accept(responseData)), responseDataClass));
		}
	}

	public static BlockPos positionToBlockPos(Position position) {
		return new BlockPos((int) position.getX(), (int) position.getY(), (int) position.getZ());
	}

	public static Position blockPosToPosition(BlockPos blockPos) {
		return new Position(blockPos.getX(), blockPos.getY(), blockPos.getZ());
	}

	public static BlockPos newBlockPos(double x, double y, double z) {
		return new BlockPos(MathHelper.floor(x), MathHelper.floor(y), MathHelper.floor(z));
	}

	public static boolean isChunkLoaded(World world, BlockPos blockPos) {
		return world.getChunkManager().getWorldChunk(blockPos.getX() / 16, blockPos.getZ() / 16) != null && world.isRegionLoaded(blockPos, blockPos);
	}

	public static void updateRidingEntity(ServerPlayerEntity serverPlayerEntity, boolean dismount) {
		if (dismount) {
			RIDING_PLAYERS.remove(serverPlayerEntity.getUuid());
			updatePlayer(serverPlayerEntity, false);
		} else {
			RIDING_PLAYERS.put(serverPlayerEntity.getUuid(), () -> updatePlayer(serverPlayerEntity, true));
		}
	}

	public static String getWorldId(World world) {
		final Identifier identifier = MinecraftServerHelper.getWorldId(world);
		return String.format("%s/%s", identifier.getNamespace(), identifier.getPath());
	}

	public static int findFreePort(int startingPort) {
		for (int i = Math.max(1024, startingPort); i <= 65535; i++) {
			// Start with port 80, then search from 1025 onwards
			try (final ServerSocket serverSocket = new ServerSocket(i == 1024 ? 80 : i)) {
				final int port = serverSocket.getLocalPort();
				LOGGER.info("Found available port: {}", port);
				return port;
			} catch (Exception ignored) {
			}
		}
		return 0;
	}

	public static void openConnectionSafe(String url, Consumer<InputStream> callback, String... requestProperties) {
		try {
			final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
			connection.setUseCaches(false);
			// MMTR: Wikimedia and other APIs reject default Java User-Agent (HTTP 403).
			connection.setRequestProperty("User-Agent", "MMTR/4.0.5 (Minecraft Transit Railway fork; https://github.com/Minecraft-Transit-Railway/Minecraft-Transit-Railway)");
			connection.setRequestProperty("Accept", "application/json");

			for (int i = 0; i < requestProperties.length / 2; i++) {
				connection.setRequestProperty(requestProperties[2 * i], requestProperties[2 * i + 1]);
			}

			try (final InputStream inputStream = connection.getInputStream()) {
				callback.accept(inputStream);
			} catch (Exception e) {
				Init.LOGGER.warn("HTTP request failed for {}: {}", url, e.getMessage());
			}
		} catch (Exception e) {
			Init.LOGGER.warn("HTTP request failed for {}: {}", url, e.getMessage());
		}
	}

	public static void openConnectionSafeJson(String url, Consumer<JsonElement> callback, String... requestProperties) {
		openConnectionSafe(url, inputStream -> {
			try (final InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
				callback.accept(JsonParser.parseReader(inputStreamReader));
			} catch (Exception e) {
				Init.LOGGER.error("", e);
			}
		}, requestProperties);
	}

	public static void writeFromClient() {
		isDedicatedServer = false;
	}

	public static void createWebserverSetup(Consumer<Webserver> webserverSetup) {
		Init.webserverSetup = webserverSetup;
	}

	public static String randomString() {
		return Integer.toHexString(new Random().nextInt());
	}





	private static void updatePlayer(ServerPlayerEntity serverPlayerEntity, boolean isRiding) {
		serverPlayerEntity.setFallDistanceMapped(0);
		serverPlayerEntity.setNoGravity(isRiding);
		serverPlayerEntity.setNoClipMapped(isRiding);
		((PlayerTeleportationStateAccessor) serverPlayerEntity.data).setInTeleportationState(isRiding);
	}

	/**
	 * {@code /mtr mmtrboard <vehicleId> [<车厢序号><A|B>] [玩家名]} 的实际执行体。
	 *
	 * <p>真正的活在 {@link org.mtr.mod.mmtr.MmtrBoardPlayer#board}，这里只负责把指令参数解成人/车、
	 * 并把失败原因说清楚 —— 玩家看不到服务端日志，只回一句"没成功"是最难排查的那种反馈。</p>
	 *
	 * @return brigadier 的返回码（>0 = 成功）
	 */
	private static int mmtrBoard(CommandBuilder.ContextHandler contextHandler, String cabSpec, String playerName) {
		final String rawVehicleId = contextHandler.getString("vehicleId").trim();
		final long vehicleId;
		try {
			vehicleId = Long.parseLong(rawVehicleId);
		} catch (NumberFormatException e) {
			contextHandler.sendFailure("vehicleId 必须是数字：" + rawVehicleId);
			return 0;
		}
		final org.mtr.mapping.holder.ServerPlayerEntity target = playerName == null || playerName.isEmpty()
				? contextHandler.getServerPlayer()
				: org.mtr.mod.mmtr.MmtrBoardPlayer.findPlayer(contextHandler.getServer(), playerName);
		if (target == null) {
			contextHandler.sendFailure(playerName == null || playerName.isEmpty()
					? "这条指令要由玩家执行，或显式给出 <玩家名>（控制台里没有执行者）"
					: "找不到在线玩家 " + playerName);
			return 0;
		}
		if (!org.mtr.mod.mmtr.MmtrBoardPlayer.board(contextHandler.getServer(), target, vehicleId, cabSpec)) {
			contextHandler.sendFailure("找不到车辆 " + vehicleId + "（用 vehicle list 看看场上有哪些车）");
			return 0;
		}
		contextHandler.sendSuccess("已把 " + target.getName().getString() + " 送到车 " + vehicleId + " 的驾驶室"
				+ (cabSpec == null || cabSpec.isEmpty() ? "（自动挑第一个）" : " " + cabSpec), true);
		return 1;
	}


}