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

		/*
		 * ★ 阴影 pass 与主 pass 是**两次调用**（见 MmtrFrameProbe 的类注释）。
		 *
		 * <p>下面那个早期返回只挡掉了 else 里的"模拟 + 每帧钩子"，而这一行之后的
		 * RenderVehicles / RenderLifts / RenderRails / 队列派发 / MmtrLightField.beginFrame /
		 * 优化批次提交**照样各跑一遍** —— 开光影时一个视觉帧就是两遍。</p>
		 */
		final boolean shadowPass = OptimizedRenderer.renderingShadows();
		if (shadowPass && Config.getClient().getDisableShadowsForShaders()) {
			return;
		}

		/*
		 * ★ pass 从这里就开始了 —— **必须在 simulate/tick 的探针之前**。
		 *
		 * <p>否则那两个段会记到"上一个 pass"头上：阴影 pass 先跑，于是主 pass 里的 simulate/tick
		 * 会被算成 {@code shadow.simulate} / {@code shadow.tick}。2026-10-05 在日志里实测到这个错误读数
		 * （一个只有 4 个主 pass 的窗口出现 {@code shadow.simulate=136ms/4}），notes/395 记。</p>
		 */
		MmtrFrameProbe.passBegin(shadowPass);
		final long probePass = MmtrFrameProbe.begin();

		final long millisElapsed;
		if (shadowPass) {
			millisElapsed = 0;
		} else {
			// 一次视觉帧的边界：主 pass 恰好一次（阴影 pass 不算新的一帧），否则"帧间隔"只有半个视觉帧。
			MmtrFrameProbe.frameBoundary();
			millisElapsed = getMillisElapsed();
			timerMillis += millisElapsed;

			final long probeSimulate = MmtrFrameProbe.begin();
			MinecraftClientData.getInstance().blockedRailIds.clear();
			MinecraftClientData.getInstance().vehicles.forEach(vehicle -> vehicle.simulate(millisElapsed));
			MinecraftClientData.getInstance().lifts.forEach(lift -> {
				lift.tick(millisElapsed);
				if (VehicleRidingMovement.isRiding(lift.getId()) && VehicleRidingMovement.showShiftProgressBar()) {
					clientPlayerEntity.sendMessage(TranslationProvider.GUI_MTR_PRESS_TO_SELECT_FLOOR.getText(KeyBindings.LIFT_MENU.getBoundKeyLocalizedText().getString()), true);
				}
			});
			lastRenderedMillis = InitClient.getGameMillis();
			MmtrFrameProbe.end("simulate", probeSimulate);

			final long probeTick = MmtrFrameProbe.begin();
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
			// 服务端请求的"把这位玩家放进驾驶室"（/mtr mmtrboard、引擎指令栏的 train board）：
			// 车镜像还没到位时在这里逐拍重试。
			org.mtr.mod.client.MmtrBoardRequest.tick();
			/*
			 * ★ **界面开着时不读按键**（notes/352）。
			 *
			 * <p>这套交互键（K/G/B/Y/U/N/H/J）全是**字母**，而"在聊天框里打一句话"就是在按它们 ——
			 * 不挡的话打一个 K 就把车挂了、打一个 H 就重拉一次引擎数据。原版自己也是这个口径
			 * （{@code MinecraftClient.handleInputEvents} 只在没有界面时跑），这里照抄它。
			 * **G 原本漏在外面，2026-10-05 补进来**（见下面那一行）。</p>
			 *
			 * <p>驾驶输入（{@code MmtrDriveInput}）**不在这个括号里**：它还要靠每拍 tick 维持手柄状态与
			 * 每秒补发（引擎侧的占用锁靠"人还在司机位上"维持），所以它自己内部只挡"读键"那一半。</p>
			 */
			if (MinecraftClient.getInstance().getCurrentScreenMapped() == null) {
				/*
				 * B2: the "aim at a driver's door and press G to take that cab / step off the train" interaction.
				 *
				 * <p>★ 2026-10-05：它从括号**外面**挪了进来（原本在 {@code MmtrCoupleInteraction.tick()} 旁边）。
				 * G 和 Y/U/K 一样是**字母键** —— 开着聊天框打一句话里的 g 就是"按了 G"，
				 * 于是打字会拔钥匙 / 上下车。原版自己就是这个口径（{@code MinecraftClient.handleInputEvents}
				 * 只在没有界面时跑），这里照抄它，并让 G 与门键、作业键完全对齐：**同一个界面挡掉全部交互键**。</p>
				 *
				 * <p>代价（刻意接受）：界面开着时不跑 {@code confirm}/{@code refreshConfirmation} ——
				 * 上车结果那一行提示会晚到，直到关掉界面。那段逻辑**不是按键**，它的另一半职责
				 * （把 {@code mmtrCabConfirmed} 跟上引擎镜像）只在人已经坐在驾驶室里时才有意义，
				 * 而界面开着的时候玩家既走不动也开不了车 —— 所以"晚一拍"没有可观察的后果。
				 * 反过来说，把它拆成"镜像那半留外面、读键那半挪进来"会让这个类多出一个只有时序意义的
				 * 状态，不值得：开界面本身就是把人按在原地的操作。</p>
				 */
				org.mtr.mod.client.MmtrCabInteraction.tick();
				// 计划内接管：坐在司机位上按 B 把本车的作业单接过来 / 还回去。
				org.mtr.mod.client.MmtrTaskInteraction.tick();
				// 司机的车门键（Y 两侧 / U 右侧）：站台作业的"按键开门 … 关门"子任务靠它达成。
				org.mtr.mod.client.MmtrDoorInteraction.tick();
				// 手动重拉引擎数据（H）：外部新建的轨道（rail add）不会自动到客户端，
				// 因为客户端只在区块加载时才拉新数据 —— 站着不动就永远不拉。见 MmtrDataResync。
				org.mtr.mod.client.MmtrDataResync.tick();
				// Windshield wiper stalk (关 / 慢 / 快): a driver input, so it is ticked with the other keys.
				MmtrWindshield.tick();
				/*
				 * 综合运转面板（notes/408 §3）：驾驶中按 TAB 调出。
				 *
				 * <p>放在这个括号里与别的字母键一致，但它的**必要性更强**：TAB 原本是原版的
				 * "按住看玩家列表"，如果界面开着时也读它，玩家在聊天框里打不了字 —— 每按一次 TAB
				 * 就会弹一次面板。读取端自己也只把 TAB 用在"手里拿着 PDA 或人在驾驶室里"，
				 * 其余情况让给原版。</p>
				 */
				org.mtr.mod.client.MmtrPdaInteraction.tick();
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
			MmtrFrameProbe.end("tick", probeTick);
		}

		final Vector3d cameraShakeOffset = clientPlayerEntity.getPos().subtract(offset);
		final long probeVehicles = MmtrFrameProbe.begin();
		RenderVehicles.render(millisElapsed, cameraShakeOffset);
		MmtrFrameProbe.end("vehicles", probeVehicles);
		final long probeLifts = MmtrFrameProbe.begin();
		RenderLifts.render(millisElapsed, cameraShakeOffset);
		MmtrFrameProbe.end("lifts", probeLifts);
		final long probeRails = MmtrFrameProbe.begin();
		RenderRails.render();
		MmtrFrameProbe.end("rails", probeRails);

		final long probeQueue = MmtrFrameProbe.begin();
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

		MmtrFrameProbe.end("queue", probeQueue);

		// MMTR 光场：把车辆所在 section 的世界光照采好、传上 GPU，并绑到 Sampler3/4。
		// 必须在这里——优化渲染器真正 draw 之前的最后一步；`offset` 用的就是下面那些变换减掉的同一个相机位置。
		final long probeLightField = MmtrFrameProbe.begin();
		MmtrLightField.getInstance().beginFrame(offset);
		MmtrFrameProbe.end("lightfield", probeLightField);

		final long probeSubmit = MmtrFrameProbe.begin();
		CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.render(!Config.getClient().getHideTranslucentParts());
		MmtrFrameProbe.end("submit", probeSubmit);

		// 优化渲染器已退出：批次级 program 缓存到此为止。它只在"批次内 program 恒定"这个前提下成立，
		// 而此刻后面还会有原版实体、别的渲染器绑 program —— 缓存留着就会被逐 draw 钩子误用。
		MmtrLightField.getInstance().invalidateBatchProgram();

		// 诊断用自动截图（-Dmmtr.lightfield.screenshot=N）：放在优化渲染器之后，截到的是含车厢的这一帧。
		MmtrLightField.getInstance().maybeCaptureScreenshot();

		MmtrFrameProbe.passEnd(probePass);
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
