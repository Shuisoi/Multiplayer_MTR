package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.Blocks;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * **客流「雕像座位」方块**（MMTR）：站定客流的**一个人 = 一格座位状态**。
 *
 * <h3>画什么、谁画的（2026-10 改口径）</h3>
 * <p>方块本身**不画**（{@link #getRenderType2} 指向的 {@code mtr:block/crowd_statue_empty}
 * 一个 element 都没有），一格座位就是一组**数据**：身向、头向、外观档。真正出画面的是
 * {@code org.mtr.mod.mmtr.crowd.MmtrCrowdStatueRenderer} —— 它在 {@code AFTER_ENTITIES}
 * 里给每格造一只**镜像村民**，交给 {@code EntityRenderDispatcher}（也就是实体渲染路径本身）去画。</p>
 *
 * <p>为什么不再烘一套我们自己的方块模型/贴图（旧口径，已删除）：运行期实测那只村民用的是
 * EMF 从资源包里随机挑的 CEM 变体（{@code currentModelVariant} 在不同村民身上是 2 / 5，而
 * {@code villager.jem} 与 {@code villager3.jem} 根本是两份不同的几何）＋ ETF 按 UUID 从 70 张
 * **128×128** 随机人皮里挑的底色（连 {@code type/}、{@code profession/} 层也被 ETF 换成
 * {@code plains3.png} / {@code librarian3.png} 这种带后缀的变体）。这些都不是常量，
 * **手写一套资源包在数学上就不可能 1:1**；让加载器自己画，几何/贴图/动画就自动一致。</p>
 *
 * <h3>四个属性 = 1024 个状态（身向 16 × 头向 16 × 外观 4）</h3>
 * <ul>
 *   <li>{@link #FACING} —— 身子 16 向的**象限**（一个象限 90°）；</li>
 *   <li>{@link #BODYSUB} —— 象限内的 4 个 22.5° 细分档。</li>
 * </ul>
 * <p>于是身子绝对朝向 = 22.5° × ({@code 4 × quadrantIndex(FACING) + BODYSUB})，档 0 = 北，
 * 沿**北 → 西 → 南 → 东**增加 → 渲染成实体 yaw 时是 {@code 180 − 22.5 × 身档}
 * （MC 里 180 = 北，见 {@code MmtrCrowdStatueRenderer.pose}）。{@link #HEAD} 存**绝对**头档
 * 0..15。4 象限 × 4 身档 × 16 头档 × 4 外观档 = <b>1024 个状态</b>。</p>
 *
 * <h3>为什么是**新方块**而不是给标记加属性</h3>
 * <p>区块调色板里存的是**全局 block state id**：给中段的 {@link BlockCrowdMarker} 改状态数，
 * 它后面所有方块的状态 id 全部平移 ⇒ 老存档里的方块会变成别人。所以雕像另开两个方块
 * （{@code crowd_seat_1} / {@code crowd_seat_2}），声明在 {@code Blocks} 的**最末尾**（只追加）。</p>
 *
 * <h3>和标记的关系：同一格的两种样子</h3>
 * <p>标记格就是"人站的那一格"，一格只能有一个方块 —— 所以是**替换**：铺人时把
 * {@code crowd_mark_*} 换成对应的 {@code crowd_seat_*}（{@link #kind} 记着顶替的是哪一种），
 * 收人时换回标记（{@code MmtrCrowdModule} 用同一个预算化的方块操作队列做）。
 * 方块实体类型仍是 {@code BlockEntityTypes.CROWD_MARKER}（同一个），于是生成器的
 * "扫标记 → 归属 → 站位"整条链路一个字都不用改。</p>
 */
public class BlockCrowdSeat extends BlockExtension implements BlockWithEntity, DirectionHelper {

	/** 身子的 4 个 22.5° 细分档（一个 {@link #FACING} 象限内）。 */
	public static final IntegerProperty BODYSUB = IntegerProperty.of("bodysub", 0, 3);
	/** 头的**绝对**朝向档（22.5° 一档，0..15；0 = 北）。 */
	public static final IntegerProperty HEAD = IntegerProperty.of("head", 0, 15);
	/**
	 * 外观档（{@code 0..3}）：渲染器用它去 {@code MmtrCrowdVillagers.typeFor/professionFor} 取
	 * **与走动人流同一张表**的群系/职业（{@code typeIndex = variant}），再让 ETF/EMF 自己决定
	 * 具体哪张随机人皮、哪个 CEM 变体 —— 我们不再自己合成贴图。
	 *
	 * <p><b>为什么只有 4 档</b>：本模组的实体是 {@code TYPES[typeIndex % 7]} 的 7 种群系，而这一格的槽位被
	 * "身向 16 × 头向 16" 占满了 —— 4 象限 × 4 身档 × 16 头档 = 256 个姿势，再乘 4 个外观档 = 1024 个状态。
	 * 7 与 4 互素 ⇒ 同一个 variant 会摊上全部 7 个群系，所以雕像保证是"**某个**真实村民的 1:1 样子"，
	 * 但不保证与它顶替的那一只同群系。</p>
	 *
	 * <p><b>要逐格对齐得开 7 档（状态数 1792），但那不安全</b>：{@code crowd_seat_1} 一旦扩容，
	 * 紧跟在它后面的 {@code crowd_seat_2} 的全局状态 id 会被挤位 —— 老存档里二级座位会有一大半读成一级座位
	 * （收人时还原成 {@code crowd_mark_1}，把勘测的等级改掉）。正确做法是**追加两个新方块**
	 * （{@code crowd_seat_3/4}）承载剩下的 3 个群系档，而不是给老方块扩容。</p>
	 */
	public static final IntegerProperty VARIANT = IntegerProperty.of("variant", 0, 3);

	/** 这一格雕像顶替的是哪一种标记格（只有 {@link BlockCrowdMarker.Kind#GRADE_1} 与 {@code GRADE_2} 会注册成座位）。 */
	public final BlockCrowdMarker.Kind kind;

	public BlockCrowdSeat(BlockCrowdMarker.Kind kind) {
		super(Blocks.createDefaultBlockSettings(true).nonOpaque());
		this.kind = kind;
	}

	/**
	 * 走方块模型那条路（{@code MODEL}），但模型是**空的**（{@code mtr:block/crowd_statue_empty}）：
	 * 这一格在区块网格里不占任何面，雕像由 {@code MmtrCrowdStatueRenderer} 交给实体渲染路径画。
	 *
	 * <p>对照：{@link BlockCrowdMarker} 报 {@code ENTITYBLOCK_ANIMATED}（整格交给方块实体渲染器），
	 * 因为它平时什么都不画、只在自己手里拿着标记时现形。</p>
	 */
	@Nonnull
	@Override
	public BlockRenderType getRenderType2(BlockState state) {
		return BlockRenderType.getModelMapped();
	}

	/**
	 * 选中框和标记一样只有贴着地面的一薄片：满站台铺满雕像时不会连成一大片，
	 * 还能对着地面精准敲掉单格（雕像本体挡视线，选中框就不该再糊一层）。
	 */
	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return Block.createCuboidShape(0, 0, 0, 16, 1, 16);
	}

	@Nonnull
	@Override
	public VoxelShape getCollisionShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		// 不挡路：人站的那一格必须能走过去（玩家、以及走动人流的实体都要能穿过雕像格）。
		return VoxelShapes.empty();
	}

	@Nonnull
	@Override
	public VoxelShape getCullingShape2(BlockState state, BlockView world, BlockPos pos) {
		// Prevents culling optimization mods from culling our fully transparent block
		return VoxelShapes.empty();
	}

	/** 玩家自己放一个时朝向自己（生成器铺的时候是直接写状态，不走这里）。 */
	@Nonnull
	@Override
	public BlockState getPlacementState2(ItemPlacementContext itemPlacementContext) {
		final int quadrant = 4 * quadrantIndex(itemPlacementContext.getPlayerFacing());
		return withPose(getDefaultState2(), quadrant, quadrant, 0);
	}

	/** 方块实体与标记**共用同一个类型**（{@code CROWD_MARKER}）：生成器只用它枚举标记格。 */
	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new BlockCrowdMarker.BlockEntity(blockPos, blockState);
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(BODYSUB);
		properties.add(HEAD);
		properties.add(VARIANT);
	}

	// ------------------------------------------------------------------ 16 向的算术（与资源生成器同一套口径）

	/** 象限序号：北 0、西 1、南 2、东 3（身子档 0 = 北，沿**北→西→南→东**增加）。 */
	public static int quadrantIndex(Direction facing) {
		if (facing == Direction.NORTH) {
			return 0;
		}
		if (facing == Direction.WEST) {
			return 1;
		}
		if (facing == Direction.SOUTH) {
			return 2;
		}
		return 3;
	}

	/** {@link #quadrantIndex} 的逆。 */
	public static Direction facingForQuadrant(int quadrant) {
		switch (Math.floorMod(quadrant, 4)) {
			case 0:
				return Direction.NORTH;
			case 1:
				return Direction.WEST;
			case 2:
				return Direction.SOUTH;
			default:
				return Direction.EAST;
		}
	}

	/** 这一格的身档：{@code 4 × 象限 + BODYSUB}（22.5° 一步，0 = 北）。 */
	public static int bodyStep(BlockState state) {
		// 走 IBlock 的读法：FACING 是**方向**属性（DirectionProperty），映射层的 get 拿回来的
		// 是原始 net.minecraft.util.math.Direction，得由 Direction.convert 那一层转成映射类型；
		// IBlock.getStatePropertySafe 正好把这层转换包了（而且方块没这个属性时退回默认值，不抛）。
		return 4 * quadrantIndex(IBlock.getStatePropertySafe(state, FACING)) + IBlock.getStatePropertySafe(state, BODYSUB);
	}

	/** 身档 → 象限（{@code 0..3}）。 */
	public static int quadrantOf(int bodyStep16) {
		return Math.floorMod(Math.floorDiv(bodyStep16, 4), 4);
	}

	/** 身档 → 象限内的细分档（{@code 0..3}）。 */
	public static int bodySubOf(int bodyStep16) {
		return Math.floorMod(bodyStep16, 4);
	}

	/** 这一格的**外观档**（{@code 0..3}）：客户端雕像渲染器用它去 {@code MmtrCrowdVillagers.typeFor} 取群系。 */
	public static int variantOf(BlockState state) {
		return IBlock.getStatePropertySafe(state, VARIANT);
	}

	/** 这一格的**头档**（{@code 0..15}，绝对朝向，0 = 北）。 */
	public static int headStepOf(BlockState state) {
		return IBlock.getStatePropertySafe(state, HEAD);
	}

	/**
	 * 摆一个姿势：{@code bodyStep16} 拆成 {@link #FACING} + {@link #BODYSUB}，
	 * {@code headStep16} 进 {@link #HEAD}，{@code variant} 进 {@link #VARIANT}。
	 *
	 * <p>两个档都先 {@code floorMod 16} 再写：调用方（生成器）拿到的是实体的 yaw，
	 * 四舍五入后可能落到 16 或 −1 上，别让一次取整把状态写成一个不存在的组合。</p>
	 */
	public static BlockState withPose(BlockState state, int bodyStep16, int headStep16, int variant) {
		final int body = Math.floorMod(bodyStep16, 16);
		final int head = Math.floorMod(headStep16, 16);
		return state
			.with(new Property<>(FACING.data), facingForQuadrant(quadrantOf(body)).data)
			.with(new Property<>(BODYSUB.data), bodySubOf(body))
			.with(new Property<>(HEAD.data), head)
			.with(new Property<>(VARIANT.data), Math.max(0, Math.min(3, variant)));
	}

	// ------------------------------------------------------------------ 标记 ⇄ 座位

	/** 这一种类的座位方块（{@code crowd_seat_1} / {@code crowd_seat_2}）。 */
	public static Block seatBlockOf(BlockCrowdMarker.Kind kind) {
		return (kind == BlockCrowdMarker.Kind.GRADE_2 ? Blocks.CROWD_SEAT_2 : Blocks.CROWD_SEAT_1).get();
	}

	/** 这一种类的标记方块（{@code crowd_mark_1} / {@code crowd_mark_2}），收人时还原成它。 */
	public static Block markerBlockOf(BlockCrowdMarker.Kind kind) {
		return (kind == BlockCrowdMarker.Kind.GRADE_2 ? Blocks.CROWD_MARK_2 : Blocks.CROWD_MARK_1).get();
	}

	/**
	 * 收人：这一格还原成它顶替的那种**空标记**。
	 *
	 * <p>为什么给默认状态而不是把身向/头向留下来：标记格上那三个属性只在"标记自己画雕像"
	 * 那条老路上有用（现在站定的人不再走那条路），留着反而让 {@code /setblock} 之类的读数看着像有人。
	 * 归属信息（是哪种标记 = 一级还是二级）由**方块种类**决定，一点都不会丢。</p>
	 */
	public static BlockState markerStateOf(BlockCrowdMarker.Kind kind) {
		return markerBlockOf(kind).getDefaultState();
	}
}
