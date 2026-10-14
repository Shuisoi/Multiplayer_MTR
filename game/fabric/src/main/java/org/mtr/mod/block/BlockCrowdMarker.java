package org.mtr.mod.block;

import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.BlockEntityExtension;
import org.mtr.mapping.mapper.BlockExtension;
import org.mtr.mapping.mapper.BlockWithEntity;
import org.mtr.mapping.mapper.DirectionHelper;
import org.mtr.mapping.tool.HolderBase;
import org.mtr.mod.BlockEntityTypes;
import org.mtr.mod.Blocks;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * **客流标记方块**（MMTR）：在站台面上"画"出人流区，由 {@code MmtrCrowdModule} 读出来变成村民实体。
 *
 * <h3>为什么需要标记，而不全靠自动识别</h3>
 * <p>站台数据里只有两个轨道端点，没有"站台面在哪、多宽"。自动识别只能认出**站台方块那一排**
 * （MTR 站台方块，贴轨道、人不能站），再往站台里数几格（{@code platform rows}，默认 2）。
 * 十米宽的岛式站台、候车区、扶梯口的排队区，都得靠人手画出来 —— 这就是这四个方块。</p>
 *
 * <h3>四个方块（用户 2026-10 定的口径）</h3>
 * <ul>
 *   <li>{@link Kind#GRADE_1} <b>一级</b>：贴着站台边、朝站台方向排的队。客量没满时**七成**的人在这儿
 *       （"1 级方块朝站台方向排队，未满时比例为 7:3"）。</li>
 *   <li>{@link Kind#GRADE_2} <b>二级</b>：往里一层的候车格，未满时**三成**的人在这儿。
 *       客量 100% 时两级都铺满（"100% 时无论几级全站满"）。</li>
 *   <li>{@link Kind#FLOW} <b>流动</b>：人流走道。格里的人**真的会走** —— 沿 {@link #FACING} 指定的轴向
 *       来回走（放的时候朝哪边，人就沿那条轴走）。</li>
 *   <li>{@link Kind#ANCHOR} <b>归属锚</b>：放在**站台方块上**，声明"我所在的这一片标记区归这条站台"。
 *       几何上认不出来的人流区（站厅、扶梯口、离站台远的候车区）靠它挂到某条站台的客量上。</li>
 * </ul>
 *
 * <h3>为什么平时看不见（"手持现形"）</h3>
 * <ul>
 *   <li>方块本身**不渲染**：{@link #getRenderType2} 报 {@code ENTITYBLOCK_ANIMATED}（像眼糖方块那样
 *       整格交给方块实体渲染器），blockstate 指到一个空模型 —— 世界里不留任何痕迹，
 *       玩家看到的还是自己铺的站台面。</li>
 *   <li>**手里拿着任意一种标记方块**（主手或副手）时，{@code RenderCrowdMarker} 才把标记画出来
 *       （半透明色块 + 一根矮柱），颜色按种类分。这样勘测时一眼看清整片布局，放下方块就干净了。</li>
 *   <li>碰撞箱为空（{@code noCollision}）：标记格正好是"人站的那一格"，村民实体就站在方块里面 ——
 *       玩家自己也能穿过去，不会把站台堵住。</li>
 * </ul>
 */
public class BlockCrowdMarker extends BlockExtension implements BlockWithEntity, DirectionHelper {

	/** 标记种类。四种各注册成一个方块（id 见 {@link #path}），共用同一个方块实体类型。 */
	public enum Kind {

		/** 一级：贴站台边的排队格（未满时 7 成的人）。 */
		GRADE_1("crowd_mark_1", 0x99FF3B5C),
		/** 二级：往里一层的候车格（未满时 3 成的人）。 */
		GRADE_2("crowd_mark_2", 0x9937C8FF),
		/** 流动：人流走道（沿朝向来回走）。 */
		FLOW("crowd_flow", 0x9950E06E),
		/** 归属锚：放在站台方块上，声明这一片标记区归哪条站台。 */
		ANCHOR("crowd_anchor", 0x99FFB020);

		/** 注册用的路径（{@code mtr:crowd_mark_1} 等的后半段）。 */
		public final String path;
		/** 手持现形时画出来的 ARGB 颜色。 */
		public final int color;

		Kind(String path, int color) {
			this.path = path;
			this.color = color;
		}
	}

	public final Kind kind;

	/**
	 * 这一格现在**有没有人**（雕像）。生成器铺人时置 true、收人时置 false —— 方块本身仍然是标记。
	 *
	 * <p>为什么把"有没有人"做成方块状态而不是另放一个方块：标记格就是"人站的那一格"，
	 * 一格只能有一个方块。所以让**标记自己记这一格有没有人**：{@code occupied=false} 就是普通的标记格，
	 * {@code occupied=true} 由客户端网格渲染器（{@code MmtrCrowdStatues}）画成一个村民。</p>
	 *
	 * <p>标记的"原本逻辑"（不可见、手持现形、种类配色）与 {@code occupied} 无关：
	 * 手里拿着任意一种标记方块，一整片标记照旧现形 —— 那批人只是画在格子上，不占方块。</p>
	 */
	public static final net.minecraft.state.property.BooleanProperty OCCUPIED = net.minecraft.state.property.BooleanProperty.of("occupied");

	/**
	 * **头朝向**（水平四向），与身子朝向 {@link #FACING} 分开。
	 *
	 * <p>标记自己不带模型（{@code crowd_marker_empty}），头/身的姿势由座位方块与
	 * {@code MmtrCrowdStatueRenderer} 表达：渲染器把头档写成镜像村民的 {@code headYaw}，
	 * 于是"头转、身子不转"这种真实站姿是**实体自己**转出来的，不再需要预旋转模型。</p>
	 */
	public static final net.minecraft.state.property.EnumProperty<net.minecraft.util.math.Direction> HEAD =
		net.minecraft.state.property.EnumProperty.of("head", net.minecraft.util.math.Direction.class, net.minecraft.util.math.Direction.Type.HORIZONTAL);

	/** 雕像配色（7 套西装），按位置哈希分配，让人群看起来不是一色制服。 */
	public static final net.minecraft.state.property.IntProperty VARIANT = net.minecraft.state.property.IntProperty.of("variant", 0, 6);

	public BlockCrowdMarker(Kind kind) {
		super(Blocks.createDefaultBlockSettings(true).nonOpaque());
		this.kind = kind;
	}

	/**
	 * 拿起就放、敲一下就掉：标记是**勘测工具**，铺一片站台要放几百个，不能每个都挖半天。
	 *
	 * <p>注意这里没有用 {@code dropsNothing()}：标记要能捡回来复用（掉落物由
	 * {@code data/mtr/loot_tables/blocks/<id>.json} 给的"掉自己"）。</p>
	 */
	@Nonnull
	@Override
	public VoxelShape getOutlineShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		// 选中框只有贴着地面的一薄片：满站台铺满标记时不会连成一片，还能对着地面精准敲掉单格。
		return Block.createCuboidShape(0, 0, 0, 16, 1, 16);
	}

	@Nonnull
	@Override
	public VoxelShape getCollisionShape2(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		// 不挡路：标记格就是"人站的那一格"，实体（村民与玩家）要能进去。
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
	public BlockRenderType getRenderType2(BlockState state) {
		/*
		 * **整格一律交给方块实体渲染器**：平时什么都不画，只有手持标记时由 RenderCrowdMarker 现形。
		 *
		 * 站定的人（{@code occupied=true}）由**客户端网格渲染器** MmtrCrowdStatues 画成村民，
		 * 走的还是实体渲染管线（Human Era 的模型就是实体模型）：方块模型那条路拿不到 EMF 换的模型，
		 * 而且状态一旦指到没解析成功的模型就是紫黑格。所以这里永远报 ENTITYBLOCK_ANIMATED。
		 */
		return BlockRenderType.getEntityblockAnimatedMapped();
	}

	@Nonnull
	@Override
	public BlockState getPlacementState2(ItemPlacementContext itemPlacementContext) {
		// 只有「流动」用朝向（走道轴向）；其余三种存着朝向但不读，省得为四种方块各写一个类。
		// 头朝向跟着人放方块时的朝向走，之后由生成器按位置哈希改成"各看各的"。
		final net.minecraft.util.math.Direction facing = itemPlacementContext.getPlayerFacing().data;
		return new BlockState(getDefaultState2().data.with(FACING.data, facing).with(HEAD, facing));
	}

	@Nonnull
	@Override
	public BlockEntityExtension createBlockEntity(BlockPos blockPos, BlockState blockState) {
		return new BlockEntity(blockPos, blockState);
	}

	@Override
	public void addBlockProperties(List<HolderBase<?>> properties) {
		properties.add(FACING);
		properties.add(new Property<>(OCCUPIED));
		properties.add(new Property<>(HEAD));
		properties.add(new Property<>(VARIANT));
	}

	/**
	 * 这一格现在站着人没有（雕像）。
	 *
	 * <p>先问"这个方块有没有这个属性"再读 —— 过渡期的座位方块没有 {@code occupied}，
	 * 直接 {@code get} 会抛 {@code IllegalArgumentException}（踩过：服务端 tick 崩 + 客户端渲染线程崩）。</p>
	 */
	public static boolean isOccupied(BlockState state) {
		return state != null && state.data.contains(OCCUPIED) && state.data.get(OCCUPIED);
	}

	/**
	 * 把这一格标成"有人"或"没人" —— 生成器铺人/收人时只调这一个方法。
	 *
	 * <p>注意这里**不改方块种类**（一级/二级/流动/锚是另一个方块）、也不动其它属性，
	 * 所以标记的归属信息与摆放朝向一直在；姿势与外观由客户端按格子坐标哈希算（见
	 * {@code MmtrCrowdStatues}），不需要写进方块状态。</p>
	 */
	public static BlockState withOccupied(BlockState state, boolean occupied) {
		return new BlockState(state.data.with(OCCUPIED, occupied));
	}

	/** 这一种类的标记方块（{@code crowd_mark_1} / {@code crowd_mark_2}）。 */
	public static Block markerBlockOf(Kind kind) {
		return (kind == Kind.GRADE_2 ? Blocks.CROWD_MARK_2 : Blocks.CROWD_MARK_1).get();
	}

	/** 这一种类的空标记默认状态（收人 / 铺人时作为底板）。 */
	public static BlockState markerStateOf(Kind kind) {
		return markerBlockOf(kind).getDefaultState();
	}

	/**
	 * **过渡用**：世界里的老座位方块（{@link BlockCrowdSeat}，只存姿势、不带 {@code occupied}）。
	 *
	 * <p>座位方块是"站定客流 = 换成另一个方块"那条老路留下的；现在站定客流直接写在标记自己的
	 * {@code occupied} 上。老存档里已经铺下去的座位格要能认出来、下一拍迁回标记 ——
	 * 所以这里保留一个判据，等世界扫干净（{@code crowd dump} 里没有座位格了）再连
	 * {@code BlockCrowdSeat} 一起删。</p>
	 */
	public static boolean isLegacySeat(BlockState state) {
		return state != null && state.getBlock().data instanceof BlockCrowdSeat;
	}

	/**
	 * 这一格是什么标记；不是标记方块时返回 {@code null}。
	 *
	 * <p>为什么从方块反推种类、而不是让每种标记各占一个方块实体类型：世界侧要靠**方块实体**枚举
	 * 找出所有标记（{@code MmtrCrowdModule}），一个类型就够了；种类跟着方块走，
	 * 客户端方块实体也是用同一个 {@code state} 造出来的，所以两边都不用额外存 NBT。</p>
	 *
	 * <p><b>老座位方块（{@link BlockCrowdSeat}）也算标记</b>（过渡）：老存档里铺了人的格子被座位方块
	 * 顶替过（一格只能有一个方块），它顶替的正是它 {@link BlockCrowdSeat#kind} 记着的那种标记。
	 * 认出来之后下一拍就迁回"带 {@code occupied} 的标记"，见 {@link #isLegacySeat}。
	 * 于是"这一格是哪一级、归哪条站台、算不算站位"这套账，对标记格和座位格是同一份代码。</p>
	 */
	public static Kind kindOf(BlockState state) {
		if (state == null) {
			return null;
		}
		// 注意：这里要拿**原始** Block 来判（映射层的 Block 是个包装，包装类没有继承关系，
		// 真正注册出去的那个对象是本类的实例 —— 与 PlatformHelper 的判法一致）。
		final net.minecraft.block.Block block = state.getBlock().data;
		if (block instanceof BlockCrowdMarker) {
			return ((BlockCrowdMarker) block).kind;
		}
		return block instanceof BlockCrowdSeat ? ((BlockCrowdSeat) block).kind : null;
	}

	public static class BlockEntity extends BlockEntityExtension {

		public BlockEntity(BlockPos pos, BlockState state) {
			super(BlockEntityTypes.CROWD_MARKER.get(), pos, state);
		}

		/** 这一格的标记种类（方块被换掉时退回一级，渲染器不会因为 null 崩）。 */
		public Kind kind() {
			final Kind kind = kindOf(getCachedState2());
			return kind == null ? Kind.GRADE_1 : kind;
		}
	}
}
