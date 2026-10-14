package org.mtr.mod.render;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityRenderer;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mod.Blocks;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockCrowdMarker;
import org.mtr.mod.client.IDrawing;

/**
 * **客流标记的"手持现形"渲染器**：平时一格都不画，手里拿着任意一种客流标记方块时才把标记画出来。
 *
 * <h3>为什么平时不画</h3>
 * <p>标记方块是**勘测/施工工具**，不是建筑的一部分：铺一片站台要放几百个，如果平时可见，
 * 站台面上就会浮着一层有色玻璃。方块本身也不渲染（{@code BlockCrowdMarker.getRenderType2} 报
 * {@code ENTITYBLOCK_ANIMATED}，blockstate 指到空模型），所以世界里完全看不出来 —— 玩家看到的
 * 还是自己铺的那层地面。</p>
 *
 * <h3>为什么"拿任意一种"就全显</h3>
 * <p>用户口径（2026-10）："手持任意标记物显示三类全部"。勘测时最常见的动作是"对照着看这片标记
 * 到底画成什么样"——一级、二级、流动、归属锚四种颜色同时在场，一眼就能看出排队区、候车区、
 * 走道和归属锚有没有对上；分成四次拿不同方块看反而容易漏。</p>
 *
 * <h3>画什么</h3>
 * <ul>
 *   <li><b>贴地的一格</b>：这一格就是"人站的那一格"（半透明色块，按种类配色）；</li>
 *   <li><b>中间一根矮柱</b>（十字两片）：站台很长，贴地的色块会被地面上的杂物挡住，
 *       矮柱让远端也能一眼认出格位。</li>
 * </ul>
 * <p>图层用 {@link QueuedRenderLayer#EXTERIOR_TRANSLUCENT_DOUBLE}：真正的 alpha 混合**且不剔背面**
 * （十字两片一次画完两面）。用白贴图 + ARGB 颜色上色，省掉四张贴图。</p>
 *
 * <p>站定客流的**人**不在这里画：那一格上标了 {@code occupied=true}，由客户端网格渲染器
 * {@code MmtrCrowdStatues} 画一个村民（离线烘的网格）。矮柱会从雕像的腿里穿出来 ——
 * 勘测时看得见"这一格有人"比"柱子干净"重要，所以不改口径。</p>
 */
public class RenderCrowdMarker extends BlockEntityRenderer<BlockCrowdMarker.BlockEntity> {

	private static final Identifier WHITE_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/white.png");

	/** 可见距离（格）：220 格长的站台站在一端也能看清大半片标记。 */
	private static final int RENDER_DISTANCE = 128;

	public RenderCrowdMarker(Argument dispatcher) {
		super(dispatcher);
	}

	@Override
	public void render(BlockCrowdMarker.BlockEntity blockEntity, float tickDelta, GraphicsHolder graphicsHolder, int light, int overlay) {
		final MinecraftClient minecraftClient = MinecraftClient.getInstance();
		final ClientPlayerEntity clientPlayerEntity = minecraftClient.getPlayerMapped();
		if (clientPlayerEntity == null || minecraftClient.getCurrentScreenMapped() != null || !isHoldingMarker(clientPlayerEntity)) {
			return;
		}
		final BlockPos pos = blockEntity.getPos2();
		final int color = blockEntity.kind().color;
		final StoredMatrixTransformations storedMatrixTransformations = new StoredMatrixTransformations(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		MainRenderer.scheduleRender(WHITE_TEXTURE, false, QueuedRenderLayer.EXTERIOR_TRANSLUCENT_DOUBLE, (graphicsHolderNew, offset) -> {
			storedMatrixTransformations.transform(graphicsHolderNew, offset);
			// 贴地的一格（局部坐标：格子中心在原点、脚下那一面是 y=0）
			IDrawing.drawTexture(graphicsHolderNew, -0.5F, 0.02F, -0.5F, 0.5F, 0.02F, 0.5F, Direction.UP, color, GraphicsHolder.getDefaultLight());
			// 中间一根矮柱（十字两片，双面图层 ⇒ 四面都看得见）
			IDrawing.drawTexture(graphicsHolderNew, -0.5F, 0.02F, -0.06F, 0.5F, 0.62F, -0.06F, Direction.NORTH, color, GraphicsHolder.getDefaultLight());
			IDrawing.drawTexture(graphicsHolderNew, -0.06F, 0.02F, -0.5F, -0.06F, 0.62F, 0.5F, Direction.WEST, color, GraphicsHolder.getDefaultLight());
			graphicsHolderNew.pop();
		});
	}

	/** 主手或副手拿着任意一种客流标记方块（四种标记都算：拿一种就全显）。 */
	public static boolean isHoldingMarker(ClientPlayerEntity clientPlayerEntity) {
		return clientPlayerEntity.isHolding(Blocks.CROWD_MARK_1.get().asItem())
			|| clientPlayerEntity.isHolding(Blocks.CROWD_MARK_2.get().asItem())
			|| clientPlayerEntity.isHolding(Blocks.CROWD_FLOW.get().asItem())
			|| clientPlayerEntity.isHolding(Blocks.CROWD_ANCHOR.get().asItem());
	}

	@Override
	public int getRenderDistance2() {
		return RENDER_DISTANCE;
	}
}
