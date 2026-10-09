package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.Blocks;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * **客流「村民」方块**（MMTR）：站在站台边缘表示客流的那一个个小人。
 *
 * <h3>为什么是方块而不是实体</h3>
 * <p>用户口径（2026-10）："我不想站台空旷……生成村民（非实体仅方块即可）"。
 * 一个 220 格长的站台在 100% 客量下就是 220 个人 —— 用实体（哪怕禁 AI 的村民）
 * 意味着 220 个实体参与每 tick 的 AI/碰撞/寻路，而客流只是**装饰**：人不走、不说话、
 * 不交互。做成**烘焙方块模型**（一个方块内部 4 个立方体，无方块实体、无 NBT）之后：
 * 渲染由原版区块渲染合批，服务端每 tick 成本为零，存档里就是一格方块。</p>
 *
 * <h3>三个状态属性</h3>
 * <ul>
 *   <li>{@link #FACING} —— 朝向。铺的时候让人**面朝轨道**（等车的人都看着车来的方向），
 *       所以这个属性由生成器按"从站位指向轨"算出来，不是玩家放置时的朝向。</li>
 *   <li>{@link #VARIANT} —— 衣服配色。同一排人里混三种颜色，避免一眼看出是复制粘贴。</li>
 *   <li>{@link #FOOT} —— 落脚高度。站台面不都是满格：内缩站台（indented）面高 13/16、
 *       半砖站台面高 8/16，模型整体要跟着往下挪，否则人会浮在砖面上方。</li>
 * </ul>
 *
 * <h3>通行性：不挡路</h3>
 * <p>碰撞箱为空 —— 客流是"空气里的一层人"，玩家可以直接穿过人群走站台；
 * 否则一个满员站台会把走道堵死（1 格一人）。选中框保留，方便手动敲掉或 /setblock 清理。</p>
 */
public class BlockCrowdVillager extends BlockExtension implements DirectionHelper {

	/** 配色变体数（{@link #VARIANT} 的取值个数）。 */
	public static final int VARIANT_COUNT = 3;

	public static final IntegerProperty VARIANT = IntegerProperty.of("variant", 0, VARIANT_COUNT - 1);

	/** 站台面是满格（16/16）。 */
	public static final int FOOT_FULL = 0;
	/** 站台面是内缩站台（13/16）。 */
	public static final int FOOT_INDENTED = 1;
	/** 站台面是半砖（8/16）。 */
	public static final int FOOT_SLAB = 2;

	public static final IntegerProperty FOOT = IntegerProperty.of("foot", FOOT_FULL, FOOT_SLAB);

	public BlockCrowdVillager() {
		super(Blocks.createDefaultBlockSettings(true).nonOpaque());
	}

	@Nonnull
	@Override
	public VoxelShape getCollisionShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return VoxelShapes.empty();
	}

	@Nonnull
	@Override
	public VoxelShape getCullingShape2(BlockState state, BlockView world, BlockPos pos) {
		// Prevents culling optimization mods from culling our fully transparent block
		return VoxelShapes.empty();
	}

	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		// 只有身体那一小段可选：满站台时选中框不会连成一片，还能手动敲掉单个小人。
		return Block.createCuboidShape(4, 0, 4, 12, 22, 12);
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(VARIANT);
		properties.add(FOOT);
	}
}
