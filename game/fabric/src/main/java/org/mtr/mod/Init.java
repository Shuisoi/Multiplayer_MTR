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
		// 服务端 → 客户端：**列车运动流**（notes/369 ①）——每 2 tick 一帧的定长原语运动包
		REGISTRY.registerPacket(PacketMmtrVehicleMotion.class, PacketMmtrVehicleMotion::new);
		// 服务端 → 客户端：「把这位玩家放进那辆车的驾驶室」（/mtr mmtrboard、引擎指令栏的 train board）
		REGISTRY.registerPacket(PacketMmtrBoardPlayer.class, PacketMmtrBoardPlayer::new);
		/*
		 * 综合运转面板（notes/408 §3）的三个包：
		 *   · PacketMmtrPdaScreen —— **服务端 → 客户端**的开屏请求（物品右键那条路；
		 *     驾驶中按 TAB 那条路是客户端直接开屏，不经服务端）；
		 *   · PacketMmtrDutyOp —— **客户端 → 服务端**的按钮（认领 / 马上退出 / 下一站退出）
		 *     **以及"要一份全部车次的列表"**（{@code Op.LIST}，vehicleId 传 0）；
		 *   · PacketMmtrDutyList —— **服务端 → 客户端**的"这就是全部车次"。
		 *
		 * 为什么名单要绕一圈问引擎：本地那份 `MinecraftClientData.vehicles` 是按玩家位置同步的镜像
		 * （见 PacketMmtrBoardPlayer 的类注释），站在几百格外时车根本不在里面 —— 面板原来自己遍历它，
		 * 于是"只有附近的车次"。引擎才是唯一真源。
		 */
		REGISTRY.registerPacket(PacketMmtrPdaScreen.class, PacketMmtrPdaScreen::new);
		REGISTRY.registerPacket(PacketMmtrDutyOp.class, PacketMmtrDutyOp::new);
		REGISTRY.registerPacket(PacketMmtrDutyList.class, PacketMmtrDutyList::new);
		/*
		 * **客户端 → 服务端：位置上行**（notes/409 §4）。
		 *
		 * <p>与 ① {@code PacketMmtrVehicleMotion} 是**一对**：① 是服务端把权威车辆的位置发下来
		 * （10 Hz），这一条是位置权威在客户端期间把那个位置传回去（同样 10 Hz）。它不新开中继通路
		 * —— ① 本来就是读"权威车辆"的 {@code railProgress} 打包的，引擎接受了上传之后，
		 * 观察者拿到的自然就是上传后的位置（notes/409 §4.2 第 5 条）。</p>
		 *
		 * <p>默认关（{@code -Dmmtr.upload=false}，两端都要显式打开）：关着时这一条路一个字节都不发。</p>
		 */
		REGISTRY.registerPacket(PacketMmtrUploadMotion.class, PacketMmtrUploadMotion::new);
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
			/*
			 * /mtr mmtrduty [status|take <车辆id>|wait <车辆id>|exit|next|cancel]
			 *
			 * **玩家自己敲的值守指令**（notes/408 §2）：查我现在是什么态、要哪趟车、什么时候退出。
			 * 用户口径（2026-10-09）："退出也是暂时使用命令进行退出，需要我自己能够输入命令"——
			 * 在它之前，接管/归还只能走网页指令栏（`job take` / `job release`），游戏里够不着，
			 * 于是每次都得有人在旁边代敲。
			 *
			 * <p>权限 0（**任何玩家**都能用）：它动的只有**自己**的值守 —— 强制把别人的车派给自己
			 * 那种事需要 OP，而那件事本来就要另一条指令（派车），不是这一条。</p>
			 *
			 * <p>动词**不在这里实现**：拼成一条引擎指令交给
			 * {@link org.mtr.core.mmtr.command.MmtrCommandDispatcher}（`duty …` 名词，S1 落的）。
			 * 这样"游戏里敲"与"网页指令栏敲"走的是同一套动词与同一份输出 —— 指令回显与 HUD
			 * 于是天然是同一份状态编码（notes/408 §3.4），不会出现两个地方各写一套词。</p>
			 */
			commandBuilderMtr.then("mmtrduty", commandBuilderDuty -> {
				commandBuilderDuty.permissionLevel(0);
				commandBuilderDuty.executes(contextHandler -> mmtrDuty(contextHandler, "status", ""));
				commandBuilderDuty.then("status", commandBuilderStatus ->
					commandBuilderStatus.executes(contextHandler -> mmtrDuty(contextHandler, "status", "")));
				commandBuilderDuty.then("take", commandBuilderTake -> {
					commandBuilderTake.executes(contextHandler -> mmtrDuty(contextHandler, "take", ""));
					commandBuilderTake.then("vehicleId", StringArgumentType.string(), commandBuilderVehicleId ->
						commandBuilderVehicleId.executes(contextHandler -> mmtrDuty(contextHandler, "take", contextHandler.getString("vehicleId"))));
				});
				commandBuilderDuty.then("wait", commandBuilderWait -> {
					commandBuilderWait.executes(contextHandler -> mmtrDuty(contextHandler, "wait", ""));
					commandBuilderWait.then("vehicleId", StringArgumentType.string(), commandBuilderVehicleId ->
						commandBuilderVehicleId.executes(contextHandler -> mmtrDuty(contextHandler, "wait", contextHandler.getString("vehicleId"))));
				});
				commandBuilderDuty.then("exit", commandBuilderExit ->
					commandBuilderExit.executes(contextHandler -> mmtrDuty(contextHandler, "exit", "")));
				commandBuilderDuty.then("next", commandBuilderNext ->
					commandBuilderNext.executes(contextHandler -> mmtrDuty(contextHandler, "next", "")));
				commandBuilderDuty.then("cancel", commandBuilderCancel ->
					commandBuilderCancel.executes(contextHandler -> mmtrDuty(contextHandler, "cancel", "")));
				/*
				 * /mtr mmtrduty assign <玩家名> <车次> <驾驶室> [wait]
				 *
				 * **给别的玩家派车**（notes/409 §0 的 ①，用户口径）：输入玩家 / 车次（作业单名）/
				 * 驾驶室编号 → 为他认领接下来要开的那辆车；末尾的 `wait` 是在下一停站车站站台等候、
				 * 不写 `wait` 就是直接传送。
				 *
				 * <p>权限 2（OP）：它挪的是**别的玩家**的位置 —— 上面那棵树里 `take` / `wait` / `exit`
				 * 权限 0 是因为它们只动自己的值守，而这一条是调度员的口径。</p>
				 *
				 * <p>与上面几条同一个约定：**动词的真源在引擎**。这里只做"名字 → uuid"、
				 * "名字 → 在哪台模拟器里"两件事，然后拼一条
				 * {@code duty assign <uuid> <车次> <驾驶室> [--wait] --name=<玩家名>} 交给
				 * {@link org.mtr.core.mmtr.command.MmtrCommandDispatcher}，把 {@code Result.lines}
				 * 原样回给执行者。</p>
				 */
				commandBuilderDuty.then("assign", commandBuilderAssign -> {
					commandBuilderAssign.permissionLevel(2);
					commandBuilderAssign.executes(contextHandler -> {
						contextHandler.sendFailure("用法：/mtr mmtrduty assign <玩家名> <车次> <驾驶室> [wait]"
							+ "（例如 /mtr mmtrduty assign Shuisoi 00103 1A wait）");
						return 0;
					});
					commandBuilderAssign.then("player", StringArgumentType.string(), commandBuilderAssignPlayer -> {
						commandBuilderAssignPlayer.executes(contextHandler -> {
							contextHandler.sendFailure("用法：/mtr mmtrduty assign <玩家名> <车次> <驾驶室> [wait]"
								+ "（例如 /mtr mmtrduty assign Shuisoi 00103 1A wait）");
							return 0;
						});
						commandBuilderAssignPlayer.then("jobId", StringArgumentType.string(), commandBuilderAssignJob -> {
							commandBuilderAssignJob.executes(contextHandler -> {
								contextHandler.sendFailure("用法：/mtr mmtrduty assign <玩家名> <车次> <驾驶室> [wait]"
									+ "（例如 /mtr mmtrduty assign Shuisoi 00103 1A wait）");
								return 0;
							});
							commandBuilderAssignJob.then("cab", StringArgumentType.string(), commandBuilderAssignCab -> {
								commandBuilderAssignCab.executes(contextHandler -> mmtrDutyAssign(contextHandler,
									contextHandler.getString("player"), contextHandler.getString("jobId"),
									contextHandler.getString("cab"), false));
								commandBuilderAssignCab.then("mode", StringArgumentType.string(), commandBuilderAssignMode ->
									commandBuilderAssignMode.executes(contextHandler -> mmtrDutyAssign(contextHandler,
										contextHandler.getString("player"), contextHandler.getString("jobId"),
										contextHandler.getString("cab"), true)));
							});
						});
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
			org.mtr.mod.mmtr.crowd.MmtrCrowdModule.clear();
			WORLD_ID_LIST.clear();
			MinecraftServerHelper.iterateWorlds(minecraftServer, serverWorld -> {
				RAIL_ACTION_MODULES.put(serverWorld, new RailActionModule(serverWorld));
				WORLD_ID_LIST.add(getWorldId(new World(serverWorld.data)));
			});

			Config.init(minecraftServer.getRunDirectory());
			final int defaultPort = Config.getServer().getWebserverPort();
			serverPort = defaultPort <= 0 ? -1 : findFreePort(defaultPort);
			main = new Main(minecraftServer.getSavePath(WorldSavePath.getRootMapped()).resolve("mtr"), serverPort, Config.getServer().getUseThreadedSimulation(), Config.getServer().getUseThreadedFileLoading(), webserverSetup, WORLD_ID_LIST.toArray(new String[0]));
			// 人数闸门（platform cap / platform radius）的镜像：引擎侧是权威值，这里把它从存档读回来。
			org.mtr.mod.mmtr.crowd.MmtrCrowdModule.loadConfig(minecraftServer.getSavePath(WorldSavePath.getRootMapped()).resolve("mtr"));

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
			org.mtr.mod.mmtr.crowd.MmtrCrowdModule.clear();
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

			// 站台客流：按各站台的有效客量在站台边缘铺/清「村民」方块（幂等、2ms/tick 预算）。
			org.mtr.mod.mmtr.crowd.MmtrCrowdModule.tick(serverWorld);

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

	/**
	 * {@code /mtr mmtrduty …} 的实际执行体（notes/408 §2）。
	 *
	 * <h2>为什么只做"解参数 + 找模拟器 + 转交"</h2>
	 * <p>动词的真源在引擎（{@code MmtrCommandDispatcher} 的 {@code duty} 名词）：网页指令栏、
	 * 这里的指令、面板的按钮三条路必须说同一套词、给同一份回话。这一层若自己判一遍业务
	 * （"车在动就不能接管"之类），就又多出一处会与引擎分叉的规则 —— 那正是 notes/215 的教训。</p>
	 *
	 * <h2>引擎按维度各有一份，选哪一份</h2>
	 * <p>先按"这趟车在哪"选（给了车辆 id 时），选不到再按"我的值守记录在哪"选 —— 后者让
	 * {@code exit} / {@code next} / {@code cancel} 不必再让人抄一遍车辆 id。</p>
	 *
	 * <h2>线程</h2>
	 * <p>这条指令在 MC 服务端主线程上跑，而本局的 {@code useThreadedSimulation=false} ⇒
	 * 模拟线程**就是**服务端主线程，所以这里直接改引擎状态是安全的（与 {@code /mtr mmtrboard}
	 * 那条路同一个前提，见 {@code Simulator} 自己的注释）。</p>
	 *
	 * @param verb          {@code status} / {@code take} / {@code wait} / {@code exit} / {@code next} / {@code cancel}
	 * @param rawVehicleId  车辆 id 原文（可空；{@code take} / {@code wait} 必需）
	 * @return brigadier 的返回码（&gt;0 = 成功）
	 */
	private static int mmtrDuty(CommandBuilder.ContextHandler contextHandler, String verb, String rawVehicleId) {
		final org.mtr.core.Main main = Init.getMain();
		if (main == null) {
			contextHandler.sendFailure("引擎还没起来（等世界加载完再敲）");
			return 0;
		}
		final org.mtr.mapping.holder.ServerPlayerEntity player = contextHandler.getServerPlayer();
		if (player == null) {
			contextHandler.sendFailure("这条指令要由玩家执行（控制台里没有执行者）");
			return 0;
		}
		final java.util.UUID uuid = player.getUuid();
		if (uuid == null) {
			contextHandler.sendFailure("拿不到你的 uuid，无法查值守");
			return 0;
		}

		long vehicleId = 0;
		if (rawVehicleId != null && !rawVehicleId.trim().isEmpty()) {
			try {
				vehicleId = Long.parseLong(rawVehicleId.trim());
			} catch (NumberFormatException e) {
				contextHandler.sendFailure("车辆 id 必须是数字：" + rawVehicleId);
				return 0;
			}
		}
		if ((verb.equals("take") || verb.equals("wait")) && vehicleId == 0) {
			contextHandler.sendFailure("要给出车辆 id：/mtr mmtrduty " + verb + " <车辆id>"
				+ "（先敲 /mtr mmtrduty 看你在哪趟车上；面板上车次那一行也写着车 id）");
			return 0;
		}

		final org.mtr.core.simulation.Simulator[] vehicleOwner = {null};
		final org.mtr.core.simulation.Simulator[] dutyOwner = {null};
		// 必须是 final：下面那个 lambda 要捕获它，而 vehicleId 本身被赋过两次（= 不是 effectively final）。
		final long targetVehicleId = vehicleId;
		org.mtr.mapping.mapper.MinecraftServerHelper.iterateWorlds(contextHandler.getServer(), world -> {
			final org.mtr.core.simulation.Simulator simulator = main.getSimulator(getWorldId(new World(world.data)));
			if (simulator == null) {
				return;
			}
			if (targetVehicleId != 0 && vehicleOwner[0] == null && simulator.mmtrFindVehicle(targetVehicleId) != null) {
				vehicleOwner[0] = simulator;
			}
			if (dutyOwner[0] == null && simulator.mmtrDuties.of(uuid) != null) {
				dutyOwner[0] = simulator;
			}
		});
		final org.mtr.core.simulation.Simulator simulator = vehicleOwner[0] != null ? vehicleOwner[0] : dutyOwner[0];
		if (simulator == null) {
			contextHandler.sendFailure("找不到你或这趟车所属的引擎（这局里没有模拟器？）");
			return 0;
		}

		final String playerName = player.getName() == null ? "" : player.getName().getString();
		// 玩家名字引擎不知道（它只认 uuid），所以顺手带上 —— 日志与面板上的"谁"才有可读的名字。
		final String command = switch (verb) {
			case "take" -> "duty claim " + uuid + " " + vehicleId + " --name=" + playerName;
			case "wait" -> "duty claim " + uuid + " " + vehicleId + " --wait --name=" + playerName;
			case "exit" -> "duty exit " + uuid;
			case "next" -> "duty exit " + uuid + " --next";
			case "cancel" -> "duty cancel " + uuid;
			default -> "duty status " + uuid;
		};
		final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result result =
			org.mtr.core.mmtr.command.MmtrCommandDispatcher.execute(simulator, command);
		for (final String line : result.lines) {
			if (result.ok) {
				contextHandler.sendSuccess(line, false);
			} else {
				contextHandler.sendFailure(line);
			}
		}
		return result.ok ? 1 : 0;
	}

	/**
	 * {@code /mtr mmtrduty assign <玩家名> <车次> <驾驶室> [wait]} 的实际执行体（notes/409 §0 的 ①）。
	 *
	 * <h2>为什么需要"先用引擎把车次名解析成车辆 id"这一步</h2>
	 * <p>引擎按维度各有一份模拟器，而这条指令拿到的是**车次名**，要选哪一份只能问引擎
	 * （{@code mmtrDuties.findVehicleIdByJobId} = 复用 {@code allVehicleRows()} 那份"场上有哪些车次"）。
	 * 于是这一次解析同时干两件事：**给出车辆 id**（游戏端自己能据此核对），以及**选定模拟器** ——
	 * 否则就只能瞎猜一个维度（现象是"明明有这趟车，指令却说找不到"）。</p>
	 *
	 * <p>解析不出来时**不拦**：照样把指令转交给第一条模拟器，让引擎给出那句能照着敲的拒绝
	 * （"场上没有正在跑的车次 00103" / "同名多辆，请改用车辆 id"）—— 拒绝理由只有一处实现。</p>
	 *
	 * <h2>线程与"动词只有一处实现"</h2>
	 * <p>与 {@link #mmtrDuty} 同一前提（模拟线程就是服务端主线程）；业务判据一条都不在这里，
	 * 拼出来的就是引擎那条 {@code duty assign …}。</p>
	 *
	 * @param playerName 玩家名（必须在线 —— 引擎只认 uuid，而 uuid 要从这里拿）
	 * @param jobId      车次名（作业单名，如 {@code 00103}）
	 * @param cabSpec    驾驶室编号（{@code <车节><A|B>}，1 起，如 {@code 1A} / {@code 10B}）
	 * @param waitAtPlatform {@code true} = 下一停站车站站台等候；{@code false} = 直接传送
	 * @return brigadier 的返回码（&gt;0 = 成功）
	 */
	private static int mmtrDutyAssign(CommandBuilder.ContextHandler contextHandler, String playerName, String jobId, String cabSpec, boolean waitAtPlatform) {
		final org.mtr.core.Main main = Init.getMain();
		if (main == null) {
			contextHandler.sendFailure("引擎还没起来（等世界加载完再敲）");
			return 0;
		}
		final org.mtr.mapping.holder.ServerPlayerEntity target =
			org.mtr.mod.mmtr.MmtrBoardPlayer.findPlayer(contextHandler.getServer(), playerName);
		if (target == null) {
			contextHandler.sendFailure("找不到在线玩家 " + playerName + " —— 用法：/mtr mmtrduty assign <玩家名> <车次> <驾驶室> [wait]");
			return 0;
		}
		final java.util.UUID uuid = target.getUuid();
		if (uuid == null) {
			contextHandler.sendFailure("拿不到 " + playerName + " 的 uuid，无法派车");
			return 0;
		}
		final String targetName = target.getName() == null ? playerName : target.getName().getString();
		final String wantedJobId = jobId == null ? "" : jobId.trim();
		final String wantedCab = cabSpec == null ? "" : cabSpec.trim();

		// 车次名 → 车辆 id（顺便选定是哪一份模拟器）；0 = 没有，-1 = 同名多辆。
		final org.mtr.core.simulation.Simulator[] jobOwner = {null};
		final long[] resolvedVehicleId = {0};
		final org.mtr.core.simulation.Simulator[] firstSimulator = {null};
		MinecraftServerHelper.iterateWorlds(contextHandler.getServer(), world -> {
			final org.mtr.core.simulation.Simulator simulator = main.getSimulator(getWorldId(new World(world.data)));
			if (simulator == null) {
				return;
			}
			if (firstSimulator[0] == null) {
				firstSimulator[0] = simulator;
			}
			if (jobOwner[0] == null) {
				final long found = simulator.mmtrDuties.findVehicleIdByJobId(wantedJobId);
				if (found != 0) {
					jobOwner[0] = simulator;
					resolvedVehicleId[0] = found;
				}
			}
		});
		final org.mtr.core.simulation.Simulator simulator = jobOwner[0] != null ? jobOwner[0] : firstSimulator[0];
		if (simulator == null) {
			contextHandler.sendFailure("找不到这局里的引擎（没有模拟器？）");
			return 0;
		}
		// 玩家名字引擎不知道（它只认 uuid），所以顺手带上 —— 日志与面板上的"谁"才有可读的名字。
		final String command = "duty assign " + uuid + " " + wantedJobId + " " + wantedCab
			+ (waitAtPlatform ? " --wait" : "") + " --name=" + targetName;
		// 现场最容易出的疑问是"到底转交了什么"（名字解成了哪个 uuid、车次名有没有被裁空白、wait 有没有带上），
		// 所以这一行是要看得见的 —— 与 mmtrClaimEngineCommand 那种"转交即留痕"同一个口径。
		LOGGER.info("[MMTR-DUTY] 派车转交（执行者 {}）：{}", contextHandler.getServerPlayer() == null
			? "控制台" : contextHandler.getServerPlayer().getName().getString(), command);
		final org.mtr.core.mmtr.command.MmtrCommandDispatcher.Result result =
			org.mtr.core.mmtr.command.MmtrCommandDispatcher.execute(simulator, command);
		for (final String line : result.lines) {
			if (result.ok) {
				contextHandler.sendSuccess(line, false);
			} else {
				contextHandler.sendFailure(line);
			}
		}
		return result.ok ? 1 : 0;
	}

}