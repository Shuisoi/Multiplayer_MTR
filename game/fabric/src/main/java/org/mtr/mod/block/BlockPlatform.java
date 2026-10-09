package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.tool.HolderBase;

import javax.annotation.Nonnull;
import java.util.List;

public class BlockPlatform extends BlockExtension implements PlatformHelper {

	private final boolean isIndented;

	public BlockPlatform(BlockSettings blockSettings, boolean isIndented) {
		super(blockSettings);
		this.isIndented = isIndented;
	}

	/**
	 * 这是不是"内缩站台"（面高 13/16）。
	 *
	 * <p>公开出来是给客流生成器（{@code MmtrCrowdModule}）用的：内缩站台的边缘比满格站台低 3/16，
	 * 站在上面的人要跟着往下挪，否则会浮在砖面之上。</p>
	 */
	public boolean isIndented() {
		return isIndented;
	}

	@Nonnull
	@Override
	public BlockState getStateForNeighborUpdate2(BlockState state, Direction direction, BlockState neighborState, WorldAccess world, BlockPos pos, BlockPos neighborPos) {
		return PlatformHelper.getActualState(BlockView.cast(world), pos, state);
	}

	@Override
	public BlockState getPlacementState2(ItemPlacementContext ctx) {
		return getDefaultState2().with(new Property<>(FACING.data), ctx.getPlayerFacing().data);
	}

	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		if (isIndented) {
			final Direction facing = IBlock.getStatePropertySafe(state, FACING);
			return VoxelShapes.union(IBlock.getVoxelShapeByDirection(0, 0, 6, 16, 13, 16, facing), Block.createCuboidShape(0, 13, 0, 16, 16, 16));
		} else {
			return super.getOutlineShape2(state, world, pos, context);
		}
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(DOOR_TYPE);
		properties.add(SIDE);
	}
}
