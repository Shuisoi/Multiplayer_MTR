package org.mtr.mod.render;

import org.mtr.core.data.InterchangeColorsForStationName;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.EntityRenderer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.InitClient;
import org.mtr.mod.KeyBindings;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.client.VehicleRidingMovement;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.ArrivalsCacheClient;
import org.mtr.mod.data.IGui;
import org.mtr.mod.entity.EntityRendering;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.render.light.MmtrLightField;
import org.mtr.mod.render.panel.MmtrFaceRuntime;
import org.mtr.mod.render.panel.MmtrPanelTexture;
import org.mtr.mod.render.panel.MmtrWindshield;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.awt.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class MainRenderer extends EntityRenderer<EntityRendering> implements IGui {

	private static long timerMillis;
	private static long lastRenderedMillis;

	public static final WorkerThread WORKER_THREAD = new WorkerThread();

	private static final int FLASHING_INTERVAL = 1000;
	private static final int TOTAL_RENDER_STAGES = 2;
	private static final ObjectArrayList<ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>>> RENDERS = new ObjectArrayList<>(TOTAL_RENDER_STAGES);
	private static final ObjectArrayList<ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>>> CURRENT_RENDERS = new ObjectArrayList<>(TOTAL_RENDER_STAGES);

	static {
		for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
			final int renderStageCount = QueuedRenderLayer.values().length;
			final ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>> rendersList = new ObjectArrayList<>(renderStageCount);
			final ObjectArrayList<Object2ObjectArrayMap<Identifier, ObjectArrayList<BiConsumer<GraphicsHolder, Vector3d>>>> currentRendersList = new ObjectArrayList<>(renderStageCount);

			for (int j = 0; j < renderStageCount; j++) {
				rendersList.add(j, new Object2ObjectArrayMap<>());
				currentRendersList.add(j, new Object2ObjectArrayMap<>());
			}

			RENDERS.add(i, rendersList);
			CURRENT_RENDERS.add(i, currentRendersList);
		}
	}

	public MainRenderer(Argument argument) {
		super(argument);
	}

	@Override
	public void render(EntityRendering entityRendering, float yaw, float tickDelta, GraphicsHolder graphicsHolder, int i) {
		render(graphicsHolder, entityRendering.getCameraPosVec2(tickDelta));
	}

	@Override
	public boolean shouldRender2(EntityRendering entity, Frustum frustum, double x, double y, double z) {
		return true;
	}

	@Nonnull
	@Override
	public Identifier getTexture2(EntityRendering entityRendering) {
		return new Identifier("");
	}

	public static void render(GraphicsHolder graphicsHolder, Vector3d offset) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientWorld clientWorld = minecraftClient.getWorldMapped();
		final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();

		if (clientWorld == null || clientPlayerEntity == null) {
			return;
		}

		final long millisElapsed;
		if (OptimizedRenderer.renderingShadows()) {
			if (Config.getClient().getDisableShadowsForShaders()) {
				return;
			}
			millisElapsed = 0;
		} else {
			millisElapsed = getMillisElapsed();
			timerMillis += millisElapsed;

			MinecraftClientData.getInstance().blockedRailIds.clear();
			MinecraftClientData.getInstance().vehicles.forEach(vehicle -> vehicle.simulate(millisElapsed));
			MinecraftClientData.getInstance().lifts.forEach(lift -> {
				lift.tick(millisElapsed);
				if (VehicleRidingMovement.isRiding(lift.getId()) && VehicleRidingMovement.showShiftProgressBar()) {
					clientPlayerEntity.sendMessage(TranslationProvider.GUI_MTR_PRESS_TO_SELECT_FLOOR.getText(KeyBindings.LIFT_MENU.getBoundKeyLocalizedText().getString()), true);
				}
			});
			lastRenderedMillis = InitClient.getGameMillis();
			WORKER_THREAD.start();
			DynamicTextureCache.instance.tick();
			// B7.6e: release cab panel textures that stopped being drawn
			MmtrPanelTexture.tick();
			// notes/359: 动态面的数据快照按帧失效（这一帧里同一台车的所有面共用同一份数据）
			MmtrFaceRuntime.tick();
			// Tick the riding cool down (dismount player if they are no longer riding a vehicle) and store the player offset cache
			VehicleRidingMovement.tick();
			// C7: the "aim at a train and press K to couple/uncouple" interaction.
			org.mtr.mod.client.MmtrCoupleInteraction.tick();
			// B2: the "aim at a driver's door and press G to take/give back that cab" interaction.
			org.mtr.mod.client.MmtrCabInteraction.tick();
			// 服务端请求的"把这位玩家放进驾驶室"（/mtr mmtrboard、引擎指令栏的 train board）：
			// 车镜像还没到位时在这里逐拍重试。
			org.mtr.mod.client.MmtrBoardRequest.tick();
			/*
			 * ★ **界面开着时不读按键**（notes/352）。
			 *
			 * <p>这套交互键（K/G/B/Y/U/N/H/J）全是**字母**，而"在聊天框里打一句话"就是在按它们 ——
			 * 不挡的话打一个 K 就把车挂了、打一个 H 就重拉一次引擎数据。原版自己也是这个口径
			 * （{@code MinecraftClient.handleInputEvents} 只在没有界面时跑），这里照抄它。</p>
			 *
			 * <p>驾驶输入（{@code MmtrDriveInput}）**不在这个括号里**：它还要靠每拍 tick 维持手柄状态与
			 * 每秒补发（引擎侧的占用锁靠"人还在司机位上"维持），所以它自己内部只挡"读键"那一半。</p>
			 */
			if (MinecraftClient.getInstance().getCurrentScreenMapped() == null) {
				// 计划内接管：坐在司机位上按 B 把本车的作业单接过来 / 还回去。
				org.mtr.mod.client.MmtrTaskInteraction.tick();
				// 司机的车门键（Y 两侧 / U 右侧）：站台作业的"按键开门 … 关门"子任务靠它达成。
				org.mtr.mod.client.MmtrDoorInteraction.tick();
				// 手动重拉引擎数据（H）：外部新建的轨道（rail add）不会自动到客户端，
				// 因为客户端只在区块加载时才拉新数据 —— 站着不动就永远不拉。见 MmtrDataResync。
				org.mtr.mod.client.MmtrDataResync.tick();
				// Windshield wiper stalk (关 / 慢 / 快): a driver input, so it is ticked with the other keys.
				MmtrWindshield.tick();
			}
			// 三手柄机车的驾驶输入（油门/制动/定速/换向）：只在握着驾驶室钥匙时才产生控制意图。
			org.mtr.mod.client.MmtrDriveInput.tick();
			/*
			 * 临时挪走"别的模组的撞键绑定"（notes/353）。
			 *
			 * <p>它必须**每拍都跑**，而且必须在界面开着时也跑：它的另一半职责是"把挪走的键位放回来" ——
			 * 一开界面（聊天/选项）就得恢复，否则打一句话里的 J 就再也打不出来了。</p>
			 */
			org.mtr.mod.client.MmtrKeyNeutraliser.tick();
			ArrivalsCacheClient.INSTANCE.tick();
		}

		final Vector3d cameraShakeOffset = clientPlayerEntity.getPos().subtract(offset);
		RenderVehicles.render(millisElapsed, cameraShakeOffset);
		RenderLifts.render(millisElapsed, cameraShakeOffset);
		RenderRails.render();

		for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
			for (int j = 0; j < QueuedRenderLayer.values().length; j++) {
				CURRENT_RENDERS.get(i).get(j).clear();
				CURRENT_RENDERS.get(i).get(j).putAll(RENDERS.get(i).get(j));
				RENDERS.get(i).get(j).clear();
			}
		}

		for (int i = 0; i < TOTAL_RENDER_STAGES; i++) {
			for (int j = 0; j < QueuedRenderLayer.values().length; j++) {
				final QueuedRenderLayer queuedRenderLayer = QueuedRenderLayer.values()[j];
				CURRENT_RENDERS.get(i).get(j).forEach((key, value) -> {
					final RenderLayer renderLayer;
					switch (queuedRenderLayer) {
						case LIGHT:
							renderLayer = MoreRenderLayers.getLight(key, false);
							break;
						case LIGHT_TRANSLUCENT:
							renderLayer = MoreRenderLayers.getLight(key, true);
							break;
						case LIGHT_2:
							renderLayer = MoreRenderLayers.getLight2(key);
							break;
						case INTERIOR:
							renderLayer = MoreRenderLayers.getInterior(key);
							break;
						case INTERIOR_TRANSLUCENT:
							renderLayer = MoreRenderLayers.getInteriorTranslucent(key);
							break;
						case EXTERIOR:
							renderLayer = MoreRenderLayers.getExterior(key);
							break;
						case EXTERIOR_TRANSLUCENT:
							renderLayer = MoreRenderLayers.getExteriorTranslucent(key);
							break;
						case EXTERIOR_TRANSLUCENT_DOUBLE:
							renderLayer = MoreRenderLayers.getExteriorTranslucentDoubleSided(key);
							break;
						case LINES:
							renderLayer = RenderLayer.getLines();
							break;
						default:
							renderLayer = null;
							break;
					}
					if (renderLayer != null) {
						graphicsHolder.createVertexConsumer(renderLayer);
					}
					value.forEach(renderer -> renderer.accept(graphicsHolder, offset));
				});
			}
		}

		// MMTR 光场：把车辆所在 section 的世界光照采好、传上 GPU，并绑到 Sampler3/4。
		// 必须在这里——优化渲染器真正 draw 之前的最后一步；`offset` 用的就是下面那些变换减掉的同一个相机位置。
		MmtrLightField.getInstance().beginFrame(offset);

		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.render(!Config.getClient().getHideTranslucentParts());

		// 诊断用自动截图（-Dmmtr.lightfield.screenshot=N）：放在优化渲染器之后，截到的是含车厢的这一帧。
		MmtrLightField.getInstance().maybeCaptureScreenshot();
	}

	public static void scheduleRender(@Nullable Identifier identifier, boolean priority, QueuedRenderLayer queuedRenderLayer, BiConsumer<GraphicsHolder, Vector3d> callback) {
		if (identifier != null) {
			RENDERS.get(priority ? 1 : 0).get(queuedRenderLayer.ordinal()).computeIfAbsent(identifier, key -> new ObjectArrayList<>()).add(callback);
		}
	}

	public static void scheduleRender(QueuedRenderLayer queuedRenderLayer, BiConsumer<GraphicsHolder, Vector3d> callback) {
		scheduleRender(new Identifier(""), false, queuedRenderLayer, callback);
	}

	public static void cancelRender(Identifier identifier) {
		RENDERS.forEach(renderForPriority -> renderForPriority.forEach(renderForPriorityAndQueuedRenderLayer -> renderForPriorityAndQueuedRenderLayer.remove(identifier)));
		CURRENT_RENDERS.forEach(renderForPriority -> renderForPriority.forEach(renderForPriorityAndQueuedRenderLayer -> renderForPriorityAndQueuedRenderLayer.remove(identifier)));
	}

	/**
	 * Get a continuously ticking timer for rendering, suitable for animations.
	 *
	 * @return a value in milliseconds representing the time elapsed, incremented when {@link MainRenderer#render(GraphicsHolder, Vector3d)} gets invoked
	 */
	public static long getTimerMillis() {
		return timerMillis;
	}

	public static String getInterchangeRouteNames(Consumer<BiConsumer<String, InterchangeColorsForStationName>> getInterchanges) {
		final ObjectArrayList<String> interchangeRouteNames = new ObjectArrayList<>();
		getInterchanges.accept((connectingStationName, interchangeColorsForStationName) -> interchangeColorsForStationName.forEach((color, interchangeRouteNamesForColor) -> interchangeRouteNamesForColor.forEach(interchangeRouteNames::add)));
		return IGui.mergeStationsWithCommas(interchangeRouteNames);
	}

	public static int getFlashingLight() {
		final int light = (int) Math.round(((Math.sin(Math.PI * 2 * (getTimerMillis() % FLASHING_INTERVAL) / FLASHING_INTERVAL) + 1) / 2) * 0xF);
		return LightmapTextureManager.pack(light, light);
	}

	public static int getFlashingColor(int color, int multiplier) {
		final double flashingProgress = ((Math.sin(Math.PI * 2 * (getTimerMillis() % FLASHING_INTERVAL) / FLASHING_INTERVAL) + 1) / 2);
		final Color oldColor = new Color(color);
		return new Color(
				(int) (oldColor.getRed() * Math.min(1, flashingProgress * multiplier)),
				(int) (oldColor.getGreen() * Math.min(1, flashingProgress * multiplier)),
				(int) (oldColor.getBlue() * Math.min(1, flashingProgress * multiplier))
		).getRGB();
	}

	private static long getMillisElapsed() {
		final long millisElapsed = InitClient.getGameMillis() - lastRenderedMillis;
		final long gameMillisElapsed = (long) (MinecraftClient.getInstance().getLastFrameDuration() * 50);
		return Math.abs(gameMillisElapsed - millisElapsed) < 50 ? gameMillisElapsed : millisElapsed;
	}
}
