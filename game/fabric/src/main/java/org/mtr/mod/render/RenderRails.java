package org.mtr.mod.render;

import com.logisticscraft.occlusionculling.OcclusionCullingInstance;
import com.logisticscraft.occlusionculling.util.Vec3d;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.tool.Angle;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.doubles.DoubleDoubleImmutablePair;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.*;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.Items;
import org.mtr.mod.block.BlockNode;
import org.mtr.mod.block.BlockSignalLightBase;
import org.mtr.mod.block.BlockSignalSemaphoreBase;
import org.mtr.mod.block.PlatformHelper;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.config.Config;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.RailType;
import org.mtr.mod.generated.lang.TranslationProvider;
import org.mtr.mod.item.ItemBlockClickingBase;
import org.mtr.mod.item.ItemBrush;
import org.mtr.mod.item.ItemNodeModifierBase;
import org.mtr.mod.item.ItemRailModifier;
import org.mtr.mod.model.ModelSmallCube;
import org.mtr.mod.packet.PacketUpdateLastRailStyles;
import org.mtr.mod.resource.RailResource;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

public class RenderRails implements IGui {

	private static final Identifier IRON_BLOCK_TEXTURE = new Identifier("textures/block/iron_block.png");
	private static final Identifier METAL_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/metal.png");
	private static final Identifier RAIL_PREVIEW_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/rail_preview.png");
	private static final Identifier RAIL_TEXTURE = new Identifier("textures/block/rail.png");
	private static final Identifier WOOL_TEXTURE = new Identifier("textures/block/white_wool.png");
	private static final Identifier ONE_WAY_RAIL_ARROW_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/one_way_rail_arrow.png");
	private static final int INVALID_NODE_CHECK_RADIUS = 16;
	private static final double LIGHT_REFERENCE_OFFSET = 0.1;
	private static final ModelSmallCube MODEL_SMALL_CUBE = new ModelSmallCube(new Identifier(Init.MOD_ID, "textures/block/white.png"));

	/**
	 * 本帧的投影对角 {@code {m00, m11}} —— 取自**游戏真正在用的**投影矩阵
	 * （{@link MmtrInteractPrompt#projectionScale}），逐实例视锥剔除要用。
	 * 在 {@link #render()} 开头取一次（每 pass 一次就够）；{@code null} = 取不到 ⇒ 只做前后剔除。
	 */
	@Nullable
	private static double[] frameProjectionScale;
	/** 视锥剔除的宽容系数：判得比真实视锥宽这么多，防画面边缘"钢轨闪掉"。 */
	private static final double CULL_MARGIN = 1.3;
	/** 允许"相机平面之后"这么多米内仍照画 —— 实例约 0.5 m 长，取点只是它的一个角。 */
	private static final double CULL_BEHIND_METRES = 2.0;
	/** 深度下限：实例贴近相机时视锥退化成一条线，用下限保证近处照画。 */
	private static final double CULL_NEAR_DEPTH = 1.0;

	public static void render() {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientWorld clientWorld = minecraftClient.getWorldMapped();
		final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();

		if (clientWorld == null || clientPlayerEntity == null) {
			return;
		}

		// 本帧的投影对角，逐实例视锥剔除要用（见 renderWithinRenderDistance / insideViewFrustum）。
		frameProjectionScale = MmtrInteractPrompt.projectionScale(minecraftClient);
		// 合并烘焙缓存：逐 pass 重置预算，主 pass 递增帧号（见 MmtrRailMeshCache.beginPass）。
		MmtrRailMeshCache.beginPass(OptimizedRenderer.renderingShadows());

		final ObjectArrayList<Function<OcclusionCullingInstance, Runnable>> cullingTasks = new ObjectArrayList<>();
		final Vector3d cameraPosition = minecraftClient.getGameRendererMapped().getCamera().getPos();
		final Vec3d camera = new Vec3d(cameraPosition.getXMapped(), cameraPosition.getYMapped(), cameraPosition.getZMapped());
		final boolean holdingRailRelated = isHoldingRailRelated(clientPlayerEntity);
		/*
		 * 区间叠加层（用户 2026-09-25）：**手持信号灯**时，轨面不再刷"轨道类型"的颜色，改成按区间上色
		 * （相连两段异色）+ 方向三角形。见 MmtrSectionBands。
		 *
		 * <p>为什么只认信号灯：用户的原话是「在手持信号灯时不显示轨道类型而显示区间」—— 建轨、放灯时
		 * 手里拿的正是信号灯；其余铁轨工具（连接器/节点/刷子）保持原来的轨道类型配色不动。</p>
		 */
		final boolean holdingSignalLight = isHoldingSignalLight(clientPlayerEntity);

		// Finding visible rails
		final ObjectArrayList<Rail> railsToRender = new ObjectArrayList<>();
		MinecraftClientData.getInstance().railWrapperList.values().forEach(railWrapper -> {
			cullingTasks.add(occlusionCullingInstance -> {
				final boolean shouldRender = occlusionCullingInstance.isAABBVisible(railWrapper.startVector, railWrapper.endVector, camera);
				return () -> railWrapper.shouldRender = shouldRender;
			});
			if (railWrapper.shouldRender) {
				railsToRender.add(railWrapper.getRail());
			}
		});

		// Ghost rails (when holding brush)
		final ObjectArraySet<Rail> hoverRails = new ObjectArraySet<>();
		if (clientPlayerEntity.isHolding(Items.BRUSH.get())) {			final ObjectObjectImmutablePair<Rail, BlockPos> railAndBlockPos = MinecraftClientData.getInstance().getFacingRailAndBlockPos(false);
			if (railAndBlockPos != null) {
				final Rail rail = railAndBlockPos.left();
				final BlockPos blockPos = railAndBlockPos.right();
				if (clientPlayerEntity.isSneaking()) {
					if (PacketUpdateLastRailStyles.CLIENT_CACHE.canApplyStylesToRail(clientPlayerEntity.getUuid(), rail, false)) {
						final Rail newRail = PacketUpdateLastRailStyles.CLIENT_CACHE.getRailWithLastStyles(clientPlayerEntity.getUuid(), rail);
						hoverRails.add(newRail);
						railsToRender.remove(rail);
						railsToRender.add(newRail);
						final DoubleDoubleImmutablePair railRadii = newRail.railMath.getHorizontalRadii();
						renderRailStats(blockPos, null, newRail.railMath.getLength(), railRadii.leftDouble(), railRadii.rightDouble());
					}
				} else {
					hoverRails.add(rail);
					final DoubleDoubleImmutablePair railRadii = rail.railMath.getHorizontalRadii();
					renderRailStats(blockPos, null, rail.railMath.getLength(), railRadii.leftDouble(), railRadii.rightDouble());
				}
			}
		}

		// Ghost rail (when building rail)
		final ItemStack itemStack = getStackInHand();
		final Item item = itemStack.getItem();
		if (item.data instanceof ItemRailModifier) {
			final HitResult hitResult = minecraftClient.getCrosshairTargetMapped();
			if (hitResult != null) {
				final Vector3d hitPos = hitResult.getPos();
				final BlockPos posStart = Init.newBlockPos(hitPos.getXMapped(), hitPos.getYMapped(), hitPos.getZMapped());
				final CompoundTag compoundTag = itemStack.getOrCreateTag();

				if (compoundTag.contains(ItemBlockClickingBase.TAG_POS)) {
					final BlockPos posEnd = BlockPos.fromLong(compoundTag.getLong(ItemBlockClickingBase.TAG_POS));
					final BlockState blockStateEnd = clientWorld.getBlockState(posEnd);

					if (blockStateEnd.getBlock().data instanceof BlockNode) {
						final BlockState blockStateStart = clientWorld.getBlockState(posStart);
						final float angleEnd = BlockNode.getAngle(blockStateEnd);
						final ObjectObjectImmutablePair<Angle, Angle> angles = Rail.getAngles(
								Init.blockPosToPosition(posStart), blockStateStart.getBlock().data instanceof BlockNode ? BlockNode.getAngle(blockStateStart) : blockStateEnd.getBlock().data instanceof BlockNode.BlockContinuousMovementNode ? angleEnd : EntityHelper.getYaw(new Entity(clientPlayerEntity.data)) + 90,
								Init.blockPosToPosition(posEnd), angleEnd
						);

						final Rail rail = ((ItemRailModifier) item.data).createRail(clientPlayerEntity.getUuid(), ItemNodeModifierBase.getTransportMode(compoundTag), blockStateStart, blockStateEnd, posStart, posEnd, angles.left(), angles.right());
						if (rail != null) {
							final Rail newRail = PacketUpdateLastRailStyles.CLIENT_CACHE.getRailWithLastStyles(clientPlayerEntity.getUuid(), rail);
							railsToRender.add(newRail);
							hoverRails.add(newRail);
							final double railLength = newRail.railMath.getLength();
							final DoubleDoubleImmutablePair railRadii = newRail.railMath.getHorizontalRadii();
							renderRailStats(posStart, posEnd, railLength, railRadii.leftDouble(), railRadii.rightDouble());
							renderRailStats(posEnd, posStart, railLength, railRadii.rightDouble(), railRadii.leftDouble());
						}
					}
				}
			}
		}

		railsToRender.forEach(rail -> {
			final RenderState renderState = holdingRailRelated ? hoverRails.contains(rail) ? RenderState.FLASHING : RenderState.COLORED : RenderState.NORMAL;
			// 区间模式：轨面按正常画（不上类型色），单向箭头与轨上信号色照旧（那是信号层，不是类型色）。
			final RenderState railPaint = holdingSignalLight ? RenderState.NORMAL : renderState;
			switch (rail.getTransportMode()) {
				case TRAIN:
					renderRailStandard(clientWorld, rail, railPaint, 1);
					if (renderState.hasColor) {
						renderRailOneWayArrows(rail, 0.25F);
						renderSignalsStandard(clientWorld, rail);
					}
					break;
				case BOAT:
					renderRailStandard(clientWorld, rail, railPaint, 0.5F);
					if (renderState.hasColor) {
						renderRailOneWayArrows(rail, 0.25F);
						renderSignalsStandard(clientWorld, rail);
					}
					break;
				case CABLE_CAR:
					if (rail.isPlatform() || rail.isSiding() || rail.getSpeedLimitKilometersPerHour(false) == RailType.CABLE_CAR_STATION.speedLimit || rail.getSpeedLimitKilometersPerHour(true) == RailType.CABLE_CAR_STATION.speedLimit) {
						renderRailStandard(clientWorld, rail, 0.25F + SMALL_OFFSET, railPaint, 0.25F, METAL_TEXTURE, 0.25F, 0, 0.75F, 1);
					}
					if (renderState.hasColor && !rail.isPlatform() && !rail.isSiding()) {
						renderRailOneWayArrows(rail, 0.5F + SMALL_OFFSET);
					}
					// 索道没有闭塞区间：区间模式下不画它的"类型色中线"（那是轨道类型那一套）。
					if (!holdingSignalLight) {
						MainRenderer.scheduleRender(QueuedRenderLayer.LINES, (graphicsHolder, offset) -> renderWithinRenderDistance(rail, (blockPos, x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> graphicsHolder.drawLineInWorld(
								(float) (x1 - offset.getXMapped()),
								(float) (y1 - offset.getYMapped() + 0.5),
								(float) (z1 - offset.getZMapped()),
								(float) (x3 - offset.getXMapped()),
								(float) (y2 - offset.getYMapped() + 0.5),
								(float) (z3 - offset.getZMapped()),
								holdingRailRelated ? RailType.getRailColor(rail) : ARGB_BLACK
						), 0.5, 0, 0));
					}
					break;
				case AIRPLANE:
					renderRailStandard(clientWorld, rail, 0.0625F + SMALL_OFFSET, railPaint, 0.25F, IRON_BLOCK_TEXTURE, 0.25F, 0, 0.75F, 1);
					if (renderState.hasColor) {
						renderRailOneWayArrows(rail, 0.25F);
						renderSignalsStandard(clientWorld, rail);
					}
					break;
			}
			if (holdingSignalLight) {
				// 区间带：按弧窗 + 行车方向画在这根轨上（引擎给的结论，见 MmtrSectionBands）
				MmtrSectionBands.render(rail);
			}
		});

		if (holdingRailRelated) {
			// Render nodes
			MinecraftClientData.getInstance().positionsToRail.keySet().forEach(position -> {
				final BlockPos blockPos = Init.positionToBlockPos(position);
				renderNode(clientWorld.getBlockState(blockPos), blockPos, () -> true, GraphicsHolder.getDefaultLight());
			});

			/*
			 * ★ 这一段是"手持轨道工具时每帧一个 33³ = 35937 次的循环"，每次迭代都做一次
			 * {@code getBlockState}（跨 chunk section 的调色板查询）并**分配一个捕获 lambda**
			 * （下面 renderNode 的那个 BooleanSupplier 捕获了 blockState/blockPos，不是能被缓存的无捕获 lambda）
			 * ⇒ 120 FPS 下约 430 万短命对象/秒。它只在手持轨道类工具时发生，所以单独立一个段：
			 * 读数里 {@code nodes} 的次数 ÷ 帧数 = 1 就意味着"这一帧手里拿着轨道工具"。
			 */
			final long probeNodes = MmtrFrameProbe.begin();
			// Render nodes with the connected block state but isn't actually connected
			for (int x = -INVALID_NODE_CHECK_RADIUS; x <= INVALID_NODE_CHECK_RADIUS; x++) {
				for (int y = -INVALID_NODE_CHECK_RADIUS; y <= INVALID_NODE_CHECK_RADIUS; y++) {
					for (int z = -INVALID_NODE_CHECK_RADIUS; z <= INVALID_NODE_CHECK_RADIUS; z++) {
						final BlockPos blockPos = clientPlayerEntity.getBlockPos().add(x, y, z);
						final BlockState blockState = clientWorld.getBlockState(blockPos);
						renderNode(blockState, blockPos, () -> blockState.get(new Property<>(BlockNode.IS_CONNECTED.data)) && !MinecraftClientData.getInstance().positionsToRail.containsKey(Init.blockPosToPosition(blockPos)), MainRenderer.getFlashingLight());
					}
				}
			}
			MmtrFrameProbe.end("nodes", probeNodes);

			// 信号绑定工具（铲子=绑节点、木斧=分轨）：把"这盏灯守哪几根轨"画进世界里（见 MmtrSignalBindingOverlay）
			MmtrSignalBindingOverlay.render(clientPlayerEntity);
		}

		if (!OptimizedRenderer.renderingShadows()) {
			MainRenderer.WORKER_THREAD.scheduleMTRRails(occlusionCullingInstance -> {
				final ObjectArrayList<Runnable> tasks = new ObjectArrayList<>();
				cullingTasks.forEach(occlusionCullingInstanceRunnableFunction -> tasks.add(occlusionCullingInstanceRunnableFunction.apply(occlusionCullingInstance)));
				minecraftClient.execute(() -> tasks.forEach(Runnable::run));
			});
		}
	}

	public static boolean isHoldingRailRelated(ClientPlayerEntity clientPlayerEntity) {
		return PlayerHelper.isHolding(new PlayerEntity(clientPlayerEntity.data),
				item -> item.data instanceof ItemNodeModifierBase || item.data instanceof ItemBrush ||
						/*
						 * 两把绑定工具都算"轨道相关"：拿起来要像拿铁轨工具一样看到实体的叠加层
						 * （每根轨按它的信号色画出来 + 单向箭头 + 轨上的信号灯）。
						 * 没有它，绑定就是盲操作 —— 看不见自己点的到底是哪根轨。
						 *
						 * 铲子（signal_binder）= 把灯绑到**节点**上；木斧（rail_binder）= 把灯分到**具体某条轨**上。
						 */
						item.data instanceof org.mtr.mod.item.ItemMmtrSignalBinder ||
						item.data instanceof org.mtr.mod.item.ItemMmtrRailBindingTool ||
						Block.getBlockFromItem(item).data instanceof BlockSignalLightBase ||
						Block.getBlockFromItem(item).data instanceof BlockNode ||
						Block.getBlockFromItem(item).data instanceof BlockSignalSemaphoreBase ||
						Block.getBlockFromItem(item).data instanceof PlatformHelper
		);
	}

	/**
	 * 手上是不是**信号灯**（含臂板信号机）—— 区间叠加层的开关（用户 2026-09-25）。
	 *
	 * <p>与 {@link #isHoldingRailRelated} 分开是有意的：那个决定"要不要显示轨道叠加层"，
	 * 这个决定"轨面刷类型色还是刷区间色"。两者都在 {@code isHoldingRailRelated} 里判的话，
	 * 下次想给别的工具加区间模式就没地方下手了。</p>
	 */
	public static boolean isHoldingSignalLight(ClientPlayerEntity clientPlayerEntity) {
		return PlayerHelper.isHolding(new PlayerEntity(clientPlayerEntity.data),
				item -> Block.getBlockFromItem(item).data instanceof BlockSignalLightBase ||
						Block.getBlockFromItem(item).data instanceof BlockSignalSemaphoreBase
		);
	}

	private static void renderRailOneWayArrows(Rail rail, float yOffset) {
		final long speedLimit1 = rail.getSpeedLimitKilometersPerHour(false);
		final long speedLimit2 = rail.getSpeedLimitKilometersPerHour(true);

		// Render one-way rail arrows
		if (speedLimit1 == 0 || speedLimit2 == 0) {
			renderWithinRenderDistance(rail, (blockPos, x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> MainRenderer.scheduleRender(ONE_WAY_RAIL_ARROW_TEXTURE, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, offset) -> {
				IDrawing.drawTexture(graphicsHolder, x1, y1 + yOffset + 0.125, z1, x2, y1 + yOffset + 0.125 + SMALL_OFFSET, z2, x3, y2 + yOffset + 0.125, z3, x4, y2 + yOffset + 0.125 + SMALL_OFFSET, z4, offset, 0, speedLimit1 == 0 ? 0.25F : 0.75F, 1, speedLimit1 == 0 ? 0.75F : 0.25F, Direction.UP, ARGB_WHITE, GraphicsHolder.getDefaultLight());
				IDrawing.drawTexture(graphicsHolder, x2, y1 + yOffset + 0.125 + SMALL_OFFSET, z2, x1, y1 + yOffset + 0.125, z1, x4, y2 + yOffset + 0.125 + SMALL_OFFSET, z4, x3, y2 + yOffset + 0.125, z3, offset, 0, speedLimit1 == 0 ? 0.25F : 0.75F, 1, speedLimit1 == 0 ? 0.75F : 0.25F, Direction.UP, ARGB_WHITE, GraphicsHolder.getDefaultLight());
			}), 1, -1, 1);
		}
	}

	private static void renderRailStandard(ClientWorld clientWorld, Rail rail, RenderState renderState, float railWidth) {
		renderRailStandard(clientWorld, rail, 0.065625F, renderState, railWidth, renderState.hasColor ? RAIL_PREVIEW_TEXTURE : RAIL_TEXTURE, -1, -1, -1, -1);
	}

	private static void renderRailStandard(ClientWorld clientWorld, Rail rail, float yOffset, RenderState renderState, float railWidth, Identifier defaultTexture, float u1, float v1, float u2, float v2) {
		// Render rail models
		final boolean[] renderType = {false, false}; // render default rail, rendered something
		for (final String style : rail.getStyles()) {
			final String newStyle;
			if (OptimizedRenderer.hasOptimizedRendering() && Config.getClient().getDefaultRail3D() && rail.getTransportMode() == TransportMode.TRAIN) {
				newStyle = style.equals(CustomResourceLoader.DEFAULT_RAIL_ID) ? rail.isSiding() ? CustomResourceLoader.DEFAULT_RAIL_3D_SIDING_ID : CustomResourceLoader.DEFAULT_RAIL_3D_ID : style;
			} else {
				newStyle = style;
			}

			if (newStyle.equals(CustomResourceLoader.DEFAULT_RAIL_ID)) {
				renderType[0] = true;
			} else {
				final boolean flip = newStyle.endsWith("_2");
				CustomResourceLoader.getRailById(RailResource.getIdWithoutDirection(newStyle), railResource -> {
					/*
					 * ★ 合并烘焙路径（notes/398）：这一根轨的**全部实例**早就烘成一个模型了，
					 * 这里一帧只排一次队 ⇒ 一个材质一次 draw。
					 *
					 * <p>为什么能"建一次就一直用"：钢轨是引擎数据、只有玩家编辑线路时才会变，
					 * 而光照是逐顶点烘在 VBO 里的 —— 所以失效只有两个来源：键（几何）与光照哈希。
					 * 二者都由 MmtrRailMeshCache 管，这里不用管。</p>
					 *
					 * <p>视锥剔除**故意不在这里做**：烘进去的是整个轨的所有实例，
					 * 相机一动就重建等于白干。粗一级的剔除由 railWrapper 的整轨 AABB 承担
					 * （见 render() 里的 occlusionCulling），细一级交给 GPU 裁剪。</p>
					 */
					final MmtrRailMeshCache.Baked baked = MmtrRailMeshCache.get(clientWorld, rail, railResource, newStyle, flip);
					if (baked == null) {
						/*
						 * 回退：烘不出来（`.bbmodel`、映射库改了字段名、或本 pass 的烘焙预算用完了）。
						 * 逐实例渲染原样保留 —— 这条路径必须永远能画，否则就是"钢轨没了"。
						 */
						renderWithinRenderDistance(rail, (blockPos, x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> {
							final int light = LightmapTextureManager.pack(clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos), clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
							final double differenceX = x3 - x1;
							final double differenceZ = z3 - z1;
							final double yaw = Math.atan2(differenceZ, differenceX);
							final double pitch = Math.atan2(y2 - y1, Math.sqrt(differenceX * differenceX + differenceZ * differenceZ));
							final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations((x1 + x3) / 2, (y1 + y2) / 2 + railResource.getModelYOffset(), (z1 + z3) / 2);
							storedMatrixTransformations.add(graphicsHolder -> {
								graphicsHolder.rotateYRadians((float) (Math.PI / 2 - yaw + (flip ? Math.PI : 0)));
								graphicsHolder.rotateXRadians((float) (Math.PI - pitch * (flip ? -1 : 1)));
								graphicsHolder.rotateZDegrees((float) ((x1 * z1) % 10) / 100);
							});
							railResource.render(storedMatrixTransformations, light);
							renderType[1] = true;
						}, railResource.getRepeatInterval(), 0, 0);
					} else {
						renderType[1] = true;
						MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
							graphicsHolder.push();
							// 顶点已经烘成"本轨局部坐标"，所以这里只需要把本轨原点搬到相机相对位置。
							// 减 offset 在 double 上做完才交给 translate ⇒ 精度不受世界坐标大小影响。
							graphicsHolder.translate(baked.originX - offset.getXMapped(), baked.originY - offset.getYMapped(), baked.originZ - offset.getZMapped());
							/*
							 * 光照已经逐顶点烘在 VBO 里（UV_LIGHTMAP → VERTEX_BUFFER），这里的 light 只是占位。
							 * 故意传"默认（全亮）"：万一那条顶点映射没生效，整根轨会明显发白 —— 失效要一眼可见，
							 * 而不是悄悄退化成"整根轨一个光照值"看着还行。判定方法见运行手册。
							 */
							CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(baked.model, graphicsHolder, GraphicsHolder.getDefaultLight());
							graphicsHolder.pop();
						});
					}
				});
			}
		}

		// Render default rail or coloured rail
		if (renderType[0] || renderState.hasColor) {
			final int color = renderState.hasColor ? renderState == RenderState.FLASHING ? MainRenderer.getFlashingColor(RailType.getRailColor(rail), 1) : RailType.getRailColor(rail) : ARGB_WHITE;

			final Identifier texture = renderType[1] && !renderType[0] ? IRON_BLOCK_TEXTURE : defaultTexture;
			renderWithinRenderDistance(rail, (blockPos, x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> {
				final float textureOffset = (((int) (x1 + z1)) % 4) * 0.25F;
				final int light = renderState == RenderState.FLASHING || renderState == RenderState.COLORED ? GraphicsHolder.getDefaultLight() : LightmapTextureManager.pack(clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos), clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
				MainRenderer.scheduleRender(texture, false, QueuedRenderLayer.EXTERIOR, (graphicsHolder, offset) -> {
					IDrawing.drawTexture(graphicsHolder, x1, y1 + yOffset, z1, x2, y1 + yOffset + SMALL_OFFSET, z2, x3, y2 + yOffset, z3, x4, y2 + yOffset + SMALL_OFFSET, z4, offset, u1 < 0 ? 0 : u1, v1 < 0 ? 0.1875F + textureOffset : v1, u2 < 0 ? 1 : u2, v2 < 0 ? 0.3125F + textureOffset : v2, Direction.UP, color, light);
					IDrawing.drawTexture(graphicsHolder, x2, y1 + yOffset + SMALL_OFFSET, z2, x1, y1 + yOffset, z1, x4, y2 + yOffset + SMALL_OFFSET, z4, x3, y2 + yOffset, z3, offset, u1 < 0 ? 0 : u1, v1 < 0 ? 0.1875F + textureOffset : v1, u2 < 0 ? 1 : u2, v2 < 0 ? 0.3125F + textureOffset : v2, Direction.UP, color, light);
				});
			}, 0.5, -railWidth, railWidth);
		}
	}

	private static void renderSignalsStandard(ClientWorld clientWorld, Rail rail) {
		final IntArrayList colors = new IntArrayList(rail.getSignalColors());
		Collections.sort(colors);
		final float width = 1F / 16;
		final LongArrayList preBlockedSignalColors = MinecraftClientData.getInstance().railIdToPreBlockedSignalColors.getOrDefault(rail.getHexId(), new LongArrayList());
		final LongArrayList currentlyBlockedSignalColors = MinecraftClientData.getInstance().railIdToCurrentlyBlockedSignalColors.getOrDefault(rail.getHexId(), new LongArrayList());

		for (int i = 0; i < colors.size(); i++) {
			final int rawColor = colors.getInt(i);
			final boolean preBlocked = preBlockedSignalColors.contains(rawColor);
			final boolean currentlyBlocked = currentlyBlockedSignalColors.contains(rawColor);
			final boolean shouldFlash = preBlocked || currentlyBlocked;
			final int color = shouldFlash ? MainRenderer.getFlashingColor(rawColor, currentlyBlocked ? 1 : 4) : ARGB_BLACK | rawColor;
			final float u1 = width * i + 1 - width * colors.size() / 2;
			final float u2 = u1 + width;

			renderWithinRenderDistance(rail, (blockPos, x1, z1, x2, z2, x3, z3, x4, z4, y1, y2) -> {
				final int light = shouldFlash ? GraphicsHolder.getDefaultLight() : LightmapTextureManager.pack(clientWorld.getLightLevel(LightType.getBlockMapped(), blockPos), clientWorld.getLightLevel(LightType.getSkyMapped(), blockPos));
				MainRenderer.scheduleRender(WOOL_TEXTURE, false, shouldFlash ? QueuedRenderLayer.EXTERIOR : QueuedRenderLayer.LIGHT, (graphicsHolder, offset) -> {
					IDrawing.drawTexture(graphicsHolder, x1, y1 + 0.125, z1, x2, y1 + 0.125 + SMALL_OFFSET, z2, x3, y2 + 0.125, z3, x4, y2 + 0.125 + SMALL_OFFSET, z4, offset, u1, 0, u2, 1, Direction.UP, color, light);
					IDrawing.drawTexture(graphicsHolder, x4, y2 + 0.125 + SMALL_OFFSET, z4, x3, y2 + 0.125, z3, x2, y1 + 0.125 + SMALL_OFFSET, z2, x1, y1 + 0.125, z1, offset, u1, 0, u2, 1, Direction.UP, color, light);
				});
			}, 1, u1 - 1, u2 - 1);
		}
	}

	private static void renderWithinRenderDistance(Rail rail, RenderRailWithBlockPos callback, double interval, float offsetRadius1, float offsetRadius2) {
		final Camera camera = MinecraftClient.getInstance().getGameRendererMapped().getCamera();
		final Vector3d cameraPosition = camera.getPos();
		final int renderDistance = MinecraftClientHelper.getRenderDistance() * 16;
		/*
		 * 相机基底（世界空间）：与交互提示**同一套**，见 {@link MmtrInteractPrompt#cameraBasis}。
		 * 只在这里取一次，逐实例复用。
		 *
		 * <p>2026-10-09 换掉原来的 `new Vector3d(x1,y1,z1).subtract(cameraPosition)
		 * .rotateY(toRadians(yaw)).rotateX(toRadians(pitch))`：那一串依赖第三方
		 * `com.logisticscraft.occlusionculling.util.Vec3d` 的旋转约定，而那个类**既不在本仓源码里、
		 * 也不在依赖 jar 里**（无从查证），实测症状是"抬头/低头时成片钢轨消失"（深度里混进了
		 * `2·dy·sin(pitch)` 这一项：俯仰 30° 时视线正前方 10 m 的目标会被算出 z=5、|y|=8.7，
		 * 判成画面外）。改用被 `mmtr/tools/projection-check` 单测钉死的基底，三个符号都是有据的。</p>
		 */
		final double[] cameraBasis = MmtrInteractPrompt.cameraBasis(camera);

		rail.railMath.render((cx1, cy1, cz1, cx2, cy2, cz2, cx3, cy3, cz3, cx4, cy4, cz4, tiltAngle) -> {
			// MMTR port: map the modern 13-arg corner+height callback back onto the legacy 10-arg layout.
			final double x1 = cx1, y1 = cy1, z1 = cz1;
			final double x2 = cx2, y2 = cy3, z2 = cz2;
			final double x3 = cx3, z3 = cz3;
			final double x4 = cx4, z4 = cz4;
			final BlockPos blockPos = Init.newBlockPos(x1, y1 + LIGHT_REFERENCE_OFFSET, z1);
			final double distanceToCamera = new Vector3d(x1, 0, z1).distanceTo(new Vector3d(cameraPosition.getXMapped(), 0, cameraPosition.getZMapped())); // Minecraft does not have vertical render distance, no need to compare the Y-axis.
			if (distanceToCamera <= renderDistance) {
				/*
				 * ★ 逐实例视锥剔除（2026-10-05）。
				 *
				 * <p>为什么必须做到实例级：上一级剔除是**整根轨一个 AABB**
				 * （{@code railWrapper.startVector → endVector}），而一根轨最长 100 m（节点段）、
				 * 每 {@code repeatInterval} 米一个实例 ⇒ **一根轨约 200 个实例**。只要那一角 AABB
				 * 落在视锥里，这 200 个实例就全画。实测 {@code draws/frame≈767} ⇒ 约 4 根长轨全画。</p>
				 *
				 * <p>原来的写法有两个洞：**32 格以内完全不剔除**（背后的轨道也画）、
				 * **32 格以外只判前后、不判左右**。这里两带用同一套判据一次补齐。
				 * 相机空间的 {@code z} 就是前向深度（与原 {@code z > 0} 同一套语义）。</p>
				 */
				if (cameraBasis == null || insideViewFrustum(
						(x1 - cameraPosition.getXMapped()) * cameraBasis[0] + (y1 - cameraPosition.getYMapped()) * cameraBasis[1] + (z1 - cameraPosition.getZMapped()) * cameraBasis[2],
						(x1 - cameraPosition.getXMapped()) * cameraBasis[3] + (z1 - cameraPosition.getZMapped()) * cameraBasis[4],
						(x1 - cameraPosition.getXMapped()) * cameraBasis[5] + (y1 - cameraPosition.getYMapped()) * cameraBasis[6] + (z1 - cameraPosition.getZMapped()) * cameraBasis[7])) {
					callback.renderRail(blockPos, x1, z1, x2, z2, x3, z3, x4, z4, y1, y2);
				}
			}
		}, interval, offsetRadius1, offsetRadius2);
	}

	/**
	 * 一个轨道实例在不在画面里。三个入参都是**相机空间**的分量：{@code depth} 沿相机前向（+ = 在前方），
	 * {@code cameraX} 沿相机右向，{@code cameraY} 沿相机上向。
	 *
	 * <p>判据直接来自投影矩阵对角：{@code |x| * m00 ≤ z} 且 {@code |y| * m11 ≤ z} 即在视锥内
	 * （{@code m00 = m11/aspect}、{@code m11 = 1/tan(fov/2)}，正是
	 * {@link MmtrInteractPrompt#projectionScale} 读出来的那两项）。<b>不手推 FOV 语义</b> ——
	 * 那个方法的注释记着上一次靠猜造成的 bug。</p>
	 *
	 * <p>两处刻意的宽容：{@link #CULL_MARGIN} 把视锥放宽；{@link #CULL_BEHIND_METRES} 让相机平面
	 * 之后一小段仍照画 —— 取点只是实例的一个角，不留余量会在画面边缘看到"钢轨闪掉"。
	 * 取不到投影矩阵时退化成"只剔背后的"，比改前更保守也更正确。</p>
	 */
	private static boolean insideViewFrustum(double depth, double cameraX, double cameraY) {
		if (depth <= -CULL_BEHIND_METRES) {
			return false;
		}
		final double[] scale = frameProjectionScale;
		if (scale == null) {
			return true;
		}
		final double safeDepth = Math.max(depth, CULL_NEAR_DEPTH);
		return Math.abs(cameraX) * scale[0] <= safeDepth * CULL_MARGIN
				&& Math.abs(cameraY) * scale[1] <= safeDepth * CULL_MARGIN;
	}

	private static void renderNode(BlockState blockState, BlockPos blockPos, BooleanSupplier shouldRender, int light) {
		if (blockState.getBlock().data instanceof BlockNode && shouldRender.getAsBoolean()) {
			final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(blockPos.getX() + 0.5, blockPos.getY(), blockPos.getZ() + 0.5);
			storedMatrixTransformations.add(graphicsHolder -> {
				graphicsHolder.rotateYDegrees((blockState.get(new Property<>(BlockNode.FACING.data)) ? -90 : 0) + (blockState.get(new Property<>(BlockNode.IS_45.data)) ? -45 : 0) + (blockState.get(new Property<>(BlockNode.IS_22_5.data)) ? -22.5F : 0));
				graphicsHolder.scale(4, 0.5F, 0.5F);
				graphicsHolder.translate(-0.5, 0, -0.5);
			});
			MODEL_SMALL_CUBE.render(storedMatrixTransformations, light);
		}
	}

	private static void renderRailStats(BlockPos renderPos, @Nullable BlockPos otherPos, double railLength, double closerRadius, double otherRadius) {
		if (railLength > 0) {
			final String textXYZOffsetLabel = otherPos == null ? null : TranslationProvider.GUI_MTR_RAIL_XYZ_OFFSET.getString();
			final String textXYZOffset = otherPos == null ? null : String.format("(%s, %s, %s)", renderPos.getX() - otherPos.getX(), renderPos.getY() - otherPos.getY(), renderPos.getZ() - otherPos.getZ());

			final String textXZRadiusLabel;
			final String textXZRadius;
			final double roundedCloserRadius = Utilities.round(closerRadius, 3);
			final double roundedOtherRadius = Utilities.round(otherRadius, 3);
			if (roundedCloserRadius == 0 || roundedOtherRadius == 0 || roundedCloserRadius == roundedOtherRadius) {
				if (roundedCloserRadius == 0 && roundedOtherRadius == 0) {
					textXZRadiusLabel = null;
					textXZRadius = null;
				} else {
					textXZRadiusLabel = TranslationProvider.GUI_MTR_RAIL_XZ_RADIUS.getString();
					textXZRadius = String.valueOf(roundedCloserRadius == 0 ? roundedOtherRadius : roundedCloserRadius);
				}
			} else {
				textXZRadiusLabel = TranslationProvider.GUI_MTR_RAIL_XZ_RADII.getString();
				textXZRadius = String.format("%s, %s", roundedCloserRadius, roundedOtherRadius);
			}

			final String textLengthLabel = TranslationProvider.GUI_MTR_RAIL_XZ_LENGTH.getString();
			final String textLength = String.valueOf(Utilities.round(railLength, 3));

			final double textOffset = otherPos == null ? 0.5 : 1;

			MainRenderer.scheduleRender(QueuedRenderLayer.TEXT, (graphicsHolder, offset) -> {
				graphicsHolder.push();
				graphicsHolder.translate(renderPos.getX() - offset.getXMapped() + 0.5, renderPos.getY() - offset.getYMapped() + textOffset, renderPos.getZ() - offset.getZMapped() + 0.5);
				InitClient.transformToFacePlayer(graphicsHolder, renderPos.getX() + 0.5, renderPos.getY() + textOffset, renderPos.getZ() + 0.5);
				graphicsHolder.rotateZDegrees(180);
				graphicsHolder.scale(1 / 32F, 1 / 32F, -1 / 32F);
				int line = 0;
				if (otherPos != null) {
					line = renderRailStat(graphicsHolder, textXYZOffsetLabel, textXYZOffset, line);
				}
				if (textXZRadius != null) {
					line = renderRailStat(graphicsHolder, textXZRadiusLabel, textXZRadius, line);
				}
				renderRailStat(graphicsHolder, textLengthLabel, textLength, line);
				graphicsHolder.pop();
			});
		}
	}

	private static int renderRailStat(GraphicsHolder graphicsHolder, String title, String data, int line) {
		int newLine = line - 9;
		graphicsHolder.drawText(data, -GraphicsHolder.getTextWidth(data) / 2, newLine, ARGB_WHITE, true, GraphicsHolder.getDefaultLight());
		graphicsHolder.push();
		graphicsHolder.scale(0.5F, 0.5F, 0.5F);
		newLine -= 5;
		graphicsHolder.drawText(title, -GraphicsHolder.getTextWidth(title) / 2, newLine * 2, ARGB_WHITE, true, GraphicsHolder.getDefaultLight());
		graphicsHolder.pop();
		return newLine - 1;
	}

	private static ItemStack getStackInHand() {
		final ClientPlayerEntity clientPlayerEntity = MinecraftClient.getInstance().getPlayerMapped();
		if (clientPlayerEntity != null) {
			try {
				return clientPlayerEntity.getStackInHand(clientPlayerEntity.getActiveHand());
			} catch (Exception ignored) {
			}
		}
		return ItemStack.getEmptyMapped();
	}

	private enum RenderState {
		NORMAL(false), COLORED(true), FLASHING(true);

		private final boolean hasColor;

		RenderState(boolean hasColor) {
			this.hasColor = hasColor;
		}
	}

	@FunctionalInterface
	private interface RenderRailWithBlockPos {
		void renderRail(BlockPos blockPos, double x1, double z1, double x2, double z2, double x3, double z3, double x4, double z4, double y1, double y2);
	}
}