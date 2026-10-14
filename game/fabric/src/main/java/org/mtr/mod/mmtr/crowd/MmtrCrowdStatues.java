package org.mtr.mod.mmtr.crowd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mapping.render.batch.MaterialProperties;
import org.mtr.mapping.render.model.Face;
import org.mtr.mapping.render.model.RawMesh;
import org.mtr.mapping.render.model.RawModel;
import org.mtr.mapping.render.object.VertexArray;
import org.mtr.mapping.render.tool.GlStateTracker;
import org.mtr.mapping.render.vertex.Vertex;
import org.mtr.mapping.render.vertex.VertexAttributeMapping;
import org.mtr.mapping.render.vertex.VertexAttributeSource;
import org.mtr.mapping.render.vertex.VertexAttributeType;
import org.mtr.mod.Init;
import org.mtr.mod.block.BlockCrowdMarker;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.render.MainRenderer;
import org.mtr.mod.render.QueuedRenderLayer;
import org.mtr.mod.resource.OptimizedModelWrapper;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * **客流雕像：把离线烘好的网格画出来**（见 `mmtr/tools/crowd-bake/bake_crowd.py`）。
 *
 * <h3>三份产物（都在模组资源里，运行期只读不写）</h3>
 * <ul>
 *   <li>{@code mtr:crowd/crowd_mesh.json} —— 32 → 28 个成年盒子（脚在 y=0、x/z 居中、含 sizeAdd 膨胀）；</li>
 *   <li>{@code mtr:crowd/crowd_tiles.json} —— 图集尺寸 + 99 块贴图的落点 + 四个组别的下标表；</li>
 *   <li>{@code mtr:crowd/crowd_atlas} —— 图集（底皮 73 / 群系 7 / 职业 14 / 等级 5）。</li>
 * </ul>
 *
 * <h3>谁站哪、朝哪（方案 A：客量驱动，见 `notes/416-客流方案A-客量驱动-合同.md`）</h3>
 * <p>跨端只有**一个**数值：站台客量 {@code Platform.crowdLevel}（0–100%），客户端从
 * {@link MmtrCrowdPlatformLevels#levelAt(int, int, int)} 问"这一格所在站台有多少人"。
 * 渲染端**不读** {@link BlockCrowdMarker#OCCUPIED}（它只是老存档兼容的方块属性）：</p>
 * <ul>
 *   <li>**候选格 = 扫到的所有标记格**（{@code GRADE_1 / GRADE_2 / FLOW / ANCHOR}）；
 *       **落点 = 候选格里去掉锚点格**（ANCHOR 是归属锚、铺在站台方块上，见下面"每人四个细节"）；</li>
 *   <li>候选格按**归属站台**分组（{@link MmtrCrowdPlatformLevels#platformAt} 的 {@code getHexId()}；
 *       取不到站台的格单独成组，客量按 0，见 {@link #groupKeyAt}）；</li>
 *   <li>每组的落点顺序 = **跨块轮流取**（同一组内的块按 key 排序后轮流各取一格）→ **均衡序**
 *       （{@code 下标 = i × STRIDE mod 格数}，STRIDE 取 φ 那一档并调到与格数互质）⇒ 任意前缀都均匀
 *       散布**整条站台**；站定格排在前、流动格排在后；</li>
 *   <li>每 {@link #BATCH_SPOTS} 格一批（**一批可以跨块**），一批一个 {@code OptimizedModel}，
 *       世界坐标按**这一批自己的原点**（该批格子的最小角）记；</li>
 *   <li>每帧每组的批数 = {@code ceil(n / 32)}，{@code n = nStatic + nFlow}，
 *       {@code nStatic = round(S × L/100)}（{@code S} = **非锚**站定格数）、
 *       {@code nFlow = L<=0 ? 0 : min(F/10, max(2, round(F×L/100)))}。</li>
 * </ul>
 * <p>⇒ **客量变化 = 只改"画前几批"：0 重建、0 上传、0 网络**。**姿势**（朝向 + 头档 + 抖动 + 外观）分两处给，
 * 两处都是"坐标（+ 静态数据）的纯函数"，所以都不进候选格签名、方块状态里也不用存姿势：</p>
 * <ul>
 *   <li><b>站定格</b>（{@code GRADE_1 / GRADE_2}）的身体朝向由**站台自己的中轴**解出 —— 背着中轴站
 *       = 面向轨道（{@link PlatformAxis#yawBucketAt}）。**不读方块 {@code facing}**：那个属性
 *       "只有流动用"（{@code BlockCrowdMarker.java:146}），它记的是铺标记时玩家的朝向，与"轨道在哪边"
 *       无关，拿它当判据等于把当年那位玩家站的方向当轨道方向；</li>
 *   <li><b>流动格</b>（{@code FLOW}）的身体朝向、**所有人的头档**（正前/左/右/低头四选一）、水平抖动、
 *       外观（哪张皮/哪个群系/哪个职业）仍由**格子坐标哈希**决定：73×7×14×5 种组合全都用得上，
 *       而且"人是不是整齐"这件事只在站定的人身上成立（走动的各走各的才像人流）。</li>
 * </ul>
 *
 * <h3>每人四个细节（也全是"烘焙期算一次"的：2026-10-13 加）</h3>
 * <ol>
 *   <li><b>锚点格不站人</b>：{@link BlockCrowdMarker.Kind#ANCHOR} 是"归属锚"（放在站台方块上，声明这一片
 *       标记区归这条站台），它**照扫、照算进读数**（{@link PlatformGroup#anchorCount}），但**不是落点** ——
 *       {@link PlatformGroup#spots} 里没有它，所以 {@link #personCount} 的站定格数也用"非锚站定格"算，
 *       两边的口径始终自洽（否则那一格的配额会算进 n、前缀又多不出人来）；</li>
 *   <li><b>光按标记格烘进顶点</b>：{@link #WORLD_LIGHT} 默认开，逐顶点用
 *       {@code LightmapTextureManager.pack(world.getLightLevel(BLOCK/SKY, 标记格))}（见 {@link #buildSegment}）；</li>
 *   <li><b>水平抖动</b>：落点中心是 {@code (x+0.5, z+0.5)}，再按格子哈希各加一个 ±0.28 格的偏移
 *       （{@link #JITTER_SALT_X} / {@link #JITTER_SALT_Z}）—— 一排人不再像格子点阵，抖动后仍在这一格内；</li>
 *   <li><b>头四选一</b>：头 = {@code chain} 含 {@code head2} 的那 4 个盒，绕脖子枢轴（模型
 *       {@code (0,24,0)} = 1.5 格高）取四种姿势之一 —— **正前 / 左 30° / 右 30° / 低头**
 *       （低头那一档的角度由 {@link #HEAD_PITCH_DEGREES} 给，默认 −30° = 朝下），由该格哈希选
 *       （{@link #HEAD_SALT}）；身体/双臂/双腿一格不动（建表时**只**对这 4 个盒换用头部表，
 *       见 {@link #buildTables}）。</li>
 * </ol>
 * <p><b>为什么这四条都必须由"格子哈希"给、且只在烘焙期算</b>：人是烘在 VBO 里的静态顶点，一旦
 * 同一个格子两次烘出来不一样，画面上就是"人跳了一下"（而且重建的触发完全不可预测）。所以这里一个
 * 随机源都不用（没有 {@code Math.random()}），一律 {@code mix(x,y,z 的哈希 ^ 各自独立的盐)}；
 * 也正因为它们是**坐标的纯函数**，它们**不进候选格签名**（见 {@link PlatformGroup#signature}）——
 * 挖/放/换 kind 才重建，客量、玩家走位、光变了都不重建（下面"光进顶点就要接受不刷新"那一段）。</p>
 *
 * <h3>为什么批必须按"整条站台"排，不能按 32×32 块各排自己的批（2026-10-12 修正）</h3>
 * <p>一个 32×32 块的候选格约 91 个，客量 10% ⇒ 那块想要 9 人，但"批"的粒度是 32 ⇒ 每块都画满一整批
 * 32 人；14 块 ⇒ 全站台画 448 人（≈35%），**低客量失真 3–4 倍**。跨块按站台排批之后，误差上限是
 * 整条站台 ≤ 16 人（1280 格 / 32 = 40 批 ⇒ 粒度 ≈ 2.5%），而且低客量时 draw 数也跟着掉。</p>
 *
 * <h3>为什么不是"方块模型"</h3>
 * <p>方块模型必须走方块图集（实体贴图不在图集里 ⇒ 紫黑）、旋转只认 0/±22.5/±45、
 * 且客量一变就要重建区块网格。网格：任意角度、任意贴图、改客量不动区块。</p>
 *
 * <h3>怎么画：烘进 VBO 批次（主路）</h3>
 * <p>逐帧现写顶点成本 ≈ 60 µs/人，8 区块内一千多人 = 64 ms/帧，做不到"全显示"。所以主路是
 * **按站台把整片雕像烘进 MTR 优化渲染器的 VBO 批次**（与钢轨同一条路）：每帧只剩几十个 draw call，
 * 人数与帧时间脱钩。旧的逐帧路径保留为回退（优化渲染器不可用 / 建块失败时才走）。</p>
 *
 * <h3>什么时候重建（只剩两种 + 一条补偿）</h3>
 * <ol>
 *   <li>这条站台**第一次被采纳**（见 {@link #scan}：这一拍收到的格不为 0，且这一份通过了
 *       {@link #scanAcceptedCells} 的判据 —— **候选集只许长；要缩必须逐格证实**；判不过的那一拍
 *       **沿用上一拍的组、不改签名**）；</li>
 *   <li>这一组的**候选格列表（位置 + kind）真的变了**（挖/放/换 kind）。</li>
 * </ol>
 * <p>另外有一条**不是几何变化**的补建：客量读到 0 / `?` 孤儿的组**不烘**（烘了也没人画得出来，
 * 见 {@link #worthBuilding}），所以"客量 0 → 升上来、而这一组还没有批次"时要补建一次
 * （见 {@link #refreshAllLevels} 的补偿触发）。**已经有批次的组**客量再怎么变也不重建。</p>
 * <p>**玩家走动、客量变化（已有批次时）都不重建**：签名只由候选格本身算（见 {@link PlatformGroup}），
 * 与"谁在看、站在哪、画几批"无关；扫描也以**站台**为单位（发现一条就把整条扫完，见 {@link #scan}）。
 * 这是 415 里"40 ms/段建块抖动 + 待烘块 4–6"的根因修法。</p>
 * <p>★ 2026-10-13 又补了**同一族的三处**漏洞（都是"候选集必须与玩家位置无关"这条硬约束的违例）：</p>
 * <ol>
 *   <li><b>视距外的区块是 {@code EmptyChunk} 哨兵</b>（不是 {@code null}），旧代码把它当"扫全"，
 *       于是候选集跟着玩家的视距走 ⇒ 2088 ↔ 1846 每拍重烘；</li>
 *   <li><b>"看清了（{@code loaded}）⇒ 这一份权威、多少都采纳"这条判据仍然不够</b>：{@code loaded} 只说明
 *       "包围盒里的区块对象都在"，它**证不出**"少了的那几格真的不再是标记方块" —— 实机
 *       （2026-10-13 18:44，玩家在两个位置之间每 3 秒来回传送）同一个站台读出 1713 / 1447 两份、
 *       两边都自称 {@code 扫全 1/1}，小的一份把大的一份替换掉 ⇒ 每拍重烘。现在**变小一律逐格证实**
 *       （{@link #scanAcceptedCells} 第 3 条 + {@link #shrinkProven}）：证不出就**原样沿用上一拍的
 *       {@link PlatformGroup}**（{@code carried}），并在汇总行记一笔 {@code 拒缩}；</li>
 *   <li>{@link #scanOrphans} 的格**一律进孤儿 key**：那里原来是"玩家窗口里、恰好离某条站台中轴 6 格内
 *       ⇒ 塞给那条真站台"，而那个窗口**跟着玩家位置走**，是同一族的位置相关来源（孤儿组本来就永不建块，
 *       所以代价只是这些格不画）。</li>
 * </ol>
 */
public final class MmtrCrowdStatues {

	private static final Identifier MESH_ID = new Identifier(Init.MOD_ID, "crowd/crowd_mesh.json");
	private static final Identifier TILES_ID = new Identifier(Init.MOD_ID, "crowd/crowd_tiles.json");
	/** 图集的**贴图路径**：`assets/<ns>/textures/...` + `.png`。
	 *  缺了前缀/后缀 {@code TextureManager} 会报 `Failed to load texture` 并画出一层垃圾
	 *  （踩过：雕像"一个都看不见"就是这个）。 */
	private static final String ATLAS_TEXTURE_PATH = "textures/crowd/crowd_atlas.png";
	/** 给 MTR 的标识符（它的 API 用映射层的 Identifier；路径含义与上面那个字符串完全一样）。 */
	private static final org.mtr.mapping.holder.Identifier ATLAS_ID =
		new org.mtr.mapping.holder.Identifier(Init.MOD_ID, ATLAS_TEXTURE_PATH);

	/** 扫描半径（区块）：**发现**站台用的粗扫范围（发现一条之后整条由 {@link #scanPlatform} 扫完）。 */
	private static final int CHUNK_RADIUS = 8;
	/**
	 * 两次**几何扫描**之间隔多少 tick（标记布局变化很慢；5 秒一次足够）。
	 *
	 * <p>几何扫描（{@link #scan}）回答"候选格 + 归属站台有没有变" —— 它是**建块的唯一来源**，
	 * 所以与玩家走位无关地按拍跑；而客量读数另有 {@link #GROUP_RESYNC_TICKS} 的快拍。</p>
	 */
	private static final int RESCAN_TICKS = 100;
	/**
	 * 两次**客量快照**之间隔多少 tick（1 秒）。
	 *
	 * <p>为什么与几何扫描分开：契约要求"客量改了 ≤ 2 s 画面跟着变"，而几何扫描是 5 秒一拍。
	 * 这里只重读每条站台的客量（不碰候选格、不碰签名、不建块）。</p>
	 */
	private static final int GROUP_RESYNC_TICKS = 20;
	/** 站台包围盒的水平放宽（格）：必须盖住服务端扫标记的横向窗口（{@code MARKER_WINDOW = 12}）。 */
	private static final int PLATFORM_PAD_HORIZONTAL = 16;
	/** 站台包围盒的垂直放宽（格）：站台有坡度 + 标记放在面上一层/两层。 */
	private static final int PLATFORM_PAD_VERTICAL = 6;
	/** 「取不到站台」的格这一拍额外粗扫的半径（区块）：镜像还没同步、或标记铺在没有站台的轨道旁时用。 */
	private static final int ORPHAN_SCAN_RADIUS = 4;

	private static final boolean ENABLED = !"off".equalsIgnoreCase(env("MMTR_STATUES", "on"));
	/** 全亮（0xF000F0 = block 15 / sky 15）：MTR 自己的渲染都用这个；{@link #WORLD_LIGHT} 关时也用它。 */
	private static final int FULL_BRIGHT = 0xF000F0;
	/**
	 * 烘焙时用**标记格的世界光**（默认**开**），{@code MMTR_STATUES_WORLDLIGHT=off} = 强制全亮。
	 *
	 * <p>语义（2026-10-13 反转，原来是"默认关、要显式打开"，默认值从 {@code false} 改成 {@code on}）：
	 * 默认就按该格标记方块坐标读
	 * {@code pack(world.getLightLevel(BLOCK, pos), world.getLightLevel(SKY, pos))} 烘进顶点 ——
	 * 这样有顶棚的站台在白天不会被外面照成一片死白，夜里/棚下也不会亮得不真实。
	 * {@code off} 只留给截图与排查光照问题时用（它把整片雕像拉平到全亮，看起来都一样）。</p>
	 *
	 * <p>★ **光烘进顶点 ⇒ 世界光变了不会自己刷新**（这是静态烘焙的既定代价，不是 bug）：
	 * 顶上盖了块玻璃、太阳落山，画面里的雕像**不会**跟着变暗，直到这一组因为"候选格列表变了"
	 * （挖/放/换 kind）或"换世界"重烘一次为止。三条理由不做"光变了就重建"：① 重建是 40–1800 ms
	 * 一级的代价（见 {@link #BATCH_SPOTS} 与类注释），光每时每刻都在随太阳变，那会变成"按帧重建"，
	 * 正好是方案 A 要根除的东西；② 光不参与候选格签名（{@link PlatformGroup#signature}），
	 * 加进去就等于让签名跟着时间走；③ 光影包/平滑光照本来就会在画的时候再乘一遍，视觉上过得去。
	 * 想让它随世界光走，就临时用 {@code off} 排查对照，**不要**在这里加重建触发。</p>
	 */
	private static final boolean WORLD_LIGHT = !"off".equalsIgnoreCase(env("MMTR_STATUES_WORLDLIGHT", "on"));

	/**
	 * 头部**低头**角度（度，绕模型 +x 轴）。默认 {@code -30} = 朝下看。
	 *
	 * <p>正负号的依据（2026-10-13 查实）：模型底面在 y=0、正面朝向 **−z**
	 * （{@code tools/crowd-bake/bake_crowd.py:78} 的 {@code "north": (0, 0, -1)}，
	 * 以及 {@code :334} 预览相机"沿 +z 看、z 越小越近"时被剔的是背面）。
	 * 正面法线 {@code (0,0,-1)} 绕 +x 转 θ 得 {@code (0, sinθ, -cosθ)}：
	 * θ = **−30°** 时 y 分量为负 ⇒ 正面朝下（低头）；θ = +30° 会变成仰头。</p>
	 *
	 * <p>为什么做成可调：这一条依赖"模型正面在 +z 还是 −z"的建模约定，而**实机一眼可辨**。
	 * 万一看着是仰头，用 {@code -Dmmtr.statues.headpitch=30} 翻过来即可，不必改代码重编。</p>
	 */
	private static final double HEAD_PITCH_DEGREES = parseDouble(env("MMTR_STATUES_HEADPITCH", "-30"), -30.0);
	/**
	 * **同时渲染人数的硬上限**（{@code -Dmmtr.statues.max=N}，默认 4096）。
	 *
	 * <p>语义（2026-10-12 起）：这一帧**真的排进渲染队列的人数**不许超过它 —— 主路（批次）按
	 * "近的组先排"逐组累加，累计到了就停（远端那些组这一帧不画）；回退路（逐帧写顶点）同样受它夹住。
	 * 客量给的是"这个站台该有多少人"，这条是"画面最多认多少人"，两者独立：客量 100% 但上限 256
	 * 时只画得下 256 个。</p>
	 *
	 * <p>默认值从 256 提到 4096 的原因：256 是旧的**逐帧写顶点**那代的靶子（那时每个人 ≈ 60 µs，
	 * 256 个就是 15 ms/帧）。现在主路是 VBO 批次，多画一个人只多几个 draw call（每帧排队 0.02–0.07 ms），
	 * 而"客量 50% → 100% 画面要跟着变"要求上限**别先于客量**截住画面 —— 一条站台满员就有上千个候选格，
	 * 256 会让 50% 和 100% 看起来一样多。要回到旧行为就显式传 {@code -Dmmtr.statues.max=256}。</p>
	 */
	private static final int HARD_MAX_PER_FRAME = Integer.parseInt(env("MMTR_STATUES_MAX", "4096"));
	/**
	 * 回退路（逐帧写顶点）每帧写顶点最多花多少毫秒（自调的靶子）：{@code MMTR_STATUES_BUDGET_MS}。
	 *
	 * <p>只作用于回退路 —— 主路（批次）每帧只排队、不写顶点，与它无关（这一点与改动前一致）。</p>
	 */
	private static final double TIME_BUDGET_MILLIS = parseDouble(env("MMTR_STATUES_BUDGET_MS", "8"), 8.0);
	/** 回退路自调的下限：再慢也不少于这么多（否则站台上一会儿有、一会儿没有，比少画更难看）。 */
	private static final int MIN_PER_FRAME = 24;
	private static final boolean DEBUG = "true".equalsIgnoreCase(env("MMTR_STATUES_DEBUG", "false"));

	private static final List<Box> BOXES = new ArrayList<>();
	private static final List<Tile> TILES = new ArrayList<>();
	private static int[] skinTiles = new int[0];
	private static int[] typeTiles = new int[0];
	private static int[] professionTiles = new int[0];
	private static int[] levelTiles = new int[0];
	private static int atlasWidth = 1;
	private static int atlasHeight = 1;
	private static int tileSize = 128;
	private static boolean loaded = false;
	private static boolean failed = false;

	/** 上一次扫描看清的候选格：**站台组 key** → 这一组的候选格（跨块轮取 + 均衡序，见 {@link PlatformGroup}）。 */
	private static Map<String, PlatformGroup> groups = new LinkedHashMap<>();
	/** 上一次扫描一共看到多少格候选（读数 + "附近没有标记"那一句的判据）。 */
	private static int candidateCount = 0;
	/** 其中**画得到**的格数（= Σ (站定格 + ⌊流动格/10⌋)）：永远画不到的流动格不烘，也不该算进来。 */
	private static int drawableCount = 0;
	/**
	 * 其中**锚点格**（{@code ANCHOR}）的格数：**照扫照算、但一格都不站人**（见 {@link PlatformGroup}）。
	 *
	 * <p>它不影响绘制（{@link #personCount} 与 {@link PlatformGroup#spots} 都不含它），
	 * 只是让"候选格 − 可画"这个差能对得上账：差里一大块就是这个数 + 画不到的流动格。</p>
	 */
	private static int anchorCount = 0;
	/** 上一次扫描花了多少毫秒（5 秒一条的汇总里打出来）。 */
	private static double lastScanMillis = 0.0;
	/** 从上次汇总到现在发布了几组（"走动 60 s 应该 0 次"这条读数的分子）。 */
	private static int buildsSinceSummary = 0;
	/**
	 * 从上次汇总到现在**丢弃**了几组 —— 因为"不值得建"而没烘（`?` 孤儿 / 客量读到 0，见
	 * {@link #worthBuilding}）。这是这次省下多少工作量的读数，清零时机与 {@link #buildsSinceSummary} 一致。
	 */
	private static int skippedSinceSummary = 0;
	/**
	 * 从上次汇总到现在，**因为"变小"被拒收**的扫描次数（汇总行的 `拒缩` 读数）。
	 *
	 * <p>它回答的是："这一拍有没有一份扫描因为证不出'那些格真没了'而被当成半份挡回去"。
	 * 玩家在两个位置之间来回传送（视距一变、能看到的格就少一截）时它**会涨、而 `建块` 不涨** ——
	 * 那正是"候选集与玩家位置无关"这条硬约束生效的样子；玩家真的挖掉几格标记时它**不该涨**
	 * （缺的格逐格证得出来 ⇒ 采纳 ⇒ 照旧建块一次）。清零时机与 {@link #buildsSinceSummary} 一致。</p>
	 */
	private static int shrinkRejectedSinceSummary = 0;
	private static int rescanCountdown = 0;
	private static long lastDebugAt = 0L;
	private static int lastDrawn = 0;
	/** 上一次绘制真的排进队列的批数 / 意图人数 / 实画格数 / 看到的客量（汇总那一条读它们）。 */
	private static int lastDrawnBatches = 0;
	private static int lastDrawnIntent = 0;
	private static int lastDrawnCells = 0;
	private static int lastSeenLevel = -1;
	private static boolean warnedNoSpots = false;
	private static boolean warnedNothingDrawn = false;
	private static boolean warnedFirstDraw = false;
	private static boolean warnedLevelLookup = false;
	/** "第一份被拒收的缩小"那条诊断只打一次（与上面几个 warnedXxx 同一个用法）；累计次数看汇总行的 `拒缩`。 */
	private static boolean warnedShrinkRejected = false;
	private static boolean probed = false;
	/** 回退路自调的每帧人数上限（上一帧实测写顶点耗时 → 这一帧画几个）。 */
	private static int frameCap = 64;
	private static double lastDrawMillis = 0.0;
	/**
	 * 头部姿势的档数（编译期常量，要用来开 {@link #YAW_POSITIONS} 的第二维）：**四选一** ——
	 * {@code 0 = 正前}、{@code 1 = 左 30°}、{@code 2 = 右 30°}、{@code 3 = 低头}，角度见
	 * {@link #HEAD_YAW_DEGREES} / {@link #HEAD_PITCH_DEGREES_PER_VARIANT}。
	 *
	 * <p>★ 改这个数就要**同步改下面三张表**（{@link #HEAD_VARIANT_NAMES} / {@link #HEAD_YAW_DEGREES} /
	 * {@link #HEAD_PITCH_DEGREES_PER_VARIANT}）：它们都是手写的字面量，长度不一致会在
	 * {@link #buildTables} 里越界（整个雕像资源载入失败，比"头不转"更糟）。</p>
	 */
	private static final int HEAD_VARIANTS = 4;
	/** 四档头姿势的**名字**（只用于建表那条日志：让人一眼看出这一局四档各是什么角度与方向）。 */
	private static final String[] HEAD_VARIANT_NAMES = {"正前", "左", "右", "低头"};
	/** 四档头姿势的**偏转**（度，绕 y，正 = 转向身体右边）：正前 / 左 30° / 右 30° / 低头（不偏转）。 */
	private static final double[] HEAD_YAW_DEGREES = {0.0, -30.0, 30.0, 0.0};
	/**
	 * 四档头姿势的**俯仰**（度，绕 x，正 = 仰头）：只有**低头**那一档非 0，另外三档平视。
	 *
	 * <p>低头那一档取的还是可调的 {@link #HEAD_PITCH_DEGREES}（默认 −30 ⇒ 朝下），所以"模型正面到底在
	 * −z 还是 +z"万一判错，用 {@code -Dmmtr.statues.headpitch=30} 就能整片翻过来；
	 * 建表日志会把**这一局实际用的角度与方向**打出来（{@link #headPoseSummary}），不必靠猜。</p>
	 */
	private static final double[] HEAD_PITCH_DEGREES_PER_VARIANT = {0.0, 0.0, 0.0, HEAD_PITCH_DEGREES};
	/**
	 * "头不额外偏转、也不低头"那一档的下标（= 0：正前）。
	 *
	 * <p>回退路（{@link #draw}）逐四边形写顶点，**身体/双臂/双腿**那些盒用的就是这一档；主路逐格查的是
	 * 格子的 {@code headVariant}。两处都从这里取，免得"正前是第几档"散落在两处。</p>
	 *
	 * <p>为什么身体那些盒可以随便取一档、这里却要点名第 0 档：建表时**只有**头那 4 个盒走头档的旋转
	 * （见 {@link #buildTables}），所以 16×4 张表里身体盒的顶点逐位相同 —— 取哪一行画出来都一样。
	 * 点名 0 只是让"身体用正前那一档"这句话在代码里也是真的（将来换网格、换盒序时不会突然抓到一个
	 * 低头/偏头的行）。</p>
	 */
	private static final int HEAD_VARIANT_STRAIGHT = 0;
	/**
	 * 预算好的顶点表：**身体档 × 头部档**（22.5° 一档 × {@link #HEAD_VARIANTS} 种头部姿势）的位置/法线，
	 * 外加每个面近似的法线方向（只需身体档）与一份共用的 uv（贴图块内像素）。
	 *
	 * <p>第二维就是"头相对身体"的正前 / 左 30° / 右 30° / 低头那四档：建表时**只有** {@code chain}
	 * 含 {@code head2} 的那 4 个盒走额外旋转（见 {@link #buildTables}），身体/双臂/双腿仍是身体档本身，
	 * 所以"转的是脑袋不是整条人"在这里就定死了 —— 逐格只查表，不做任何三角函数。</p>
	 */
	private static final float[][][] YAW_POSITIONS = new float[16][HEAD_VARIANTS][];
	private static final float[][][] YAW_NORMALS = new float[16][HEAD_VARIANTS][];
	private static final org.mtr.mapping.holder.Direction[][] YAW_FACE_DIRECTIONS = new org.mtr.mapping.holder.Direction[16][];
	private static float[] VERTEX_U = new float[0];
	private static float[] VERTEX_V = new float[0];
	/** 顶点表里每个盒摊出几个顶点（6 面 × 4 角 = 24）：从顶点下标反查"这是哪个盒的哪个面"就靠它。 */
	private static final int VERTICES_PER_BOX = 6 * 4;
	private static int VERTEX_COUNT = 0;
	/** 每个盒的 {@code chain}（建表时从 {@link #BOXES} 抄一份，只读；认"头"用）。 */
	private static String[] BOX_CHAIN = new String[0];
	/** 每个盒是不是"头"（{@link #isHeadBox}）：只有它为真的盒在建表时走头部的偏转 + 低头。 */
	private static boolean[] HEAD_VERTEX = new boolean[0];
	private static float INV_ATLAS_WIDTH = 1.0F;
	private static float INV_ATLAS_HEIGHT = 1.0F;

	private MmtrCrowdStatues() {
	}

	private static String env(String name, String fallback) {
		final String value = System.getProperty(name.toLowerCase().replace('_', '.'), System.getenv(name));
		return value == null || value.isEmpty() ? fallback : value.trim();
	}

	private static double parseDouble(String value, double fallback) {
		try {
			return Double.parseDouble(value);
		} catch (NumberFormatException exception) {
			return fallback;
		}
	}

	// ------------------------------------------------------------------ 数据

	/**
	 * 一个盒子：AABB（脚在 y=0、x/z 居中、单位 = 1/16 格）+ 六面 uv 矩形（单位 = 贴图像素）
	 * + {@code chain}（原版模型里这个盒挂在哪根骨头上，用来认"头"：见 {@link #isHeadBox}）。
	 */
	private static final class Box {

		final float[] low = new float[3];
		final float[] high = new float[3];
		final float[][] uv = new float[6][4];
		/** {@code crowd_mesh.json} 里这个盒的 {@code chain}（如 {@code bodywear/player/head2}）；缺失为 null。 */
		String chain;
	}

	private static final class Tile {

		final int x;
		final int y;

		Tile(int x, int y) {
			this.x = x;
			this.y = y;
		}
	}

	/**
	 * 一个候选落点（= 标记格）：位置 + 身档（22.5° 一档，0 = 北）+ 头档（见 {@link #HEAD_VARIANTS}）
	 * + 外观下标。
	 *
	 * <p>{@code yaw} 是**身体**那一档（顶点表的第一维）：站定格由**站台中轴**给（面向轨道），
	 * 流动格与"取不到站台"的格由格子哈希给（见 {@link #spot}）；{@code headVariant} 是**头相对身体**
	 * 的四种姿势，永远由格子哈希给。</p>
	 *
	 * <p>{@code x/z} 已经是**抖动后**的位置（格中心 + 该格的确定性水平偏移，见 {@link #spot}）；
	 * 抖动幅度 ±0.28 < 半个模型宽 0.25 之外的那点余量，所以"这个人还在这一格里"。</p>
	 */
	private static final class Spot {

		final double x;
		final double y;
		final double z;
		final float yaw;
		/** 头部档（0 … {@link #HEAD_VARIANTS}−1）：只用来查 {@link #YAW_POSITIONS} 的第二维，不进候选格签名。 */
		final int headVariant;
		final int skin;
		final int type;
		final int profession;
		final int level;
		/** 标记种类：只有用来分"站定组 / 流动组"，以及算候选签名（位置 + kind）。 */
		final BlockCrowdMarker.Kind kind;

		Spot(double x, double y, double z, float yaw, int headVariant, int skin, int type, int profession, int level, BlockCrowdMarker.Kind kind) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.headVariant = headVariant;
			this.skin = skin;
			this.type = type;
			this.profession = profession;
			this.level = level;
			this.kind = kind;
		}
	}

	/**
	 * 扫描中途的一格候选（还没变成 {@link Spot}）：只要"归属谁 + 在哪 + 什么 kind"。
	 *
	 * <p>用轻量对象而不是直接造 {@link Spot}：分组要在几千个格子上来回几遍（跨块轮取要按块 key 排序），
	 * 而朝向/外观是**坐标的纯函数**，建块那一刻再算就够 —— 免得每 5 秒为整片站台做几千次哈希。</p>
	 */
	private static final class Cell {

		final int x;
		final int y;
		final int z;
		final BlockCrowdMarker.Kind kind;

		Cell(int x, int y, int z, BlockCrowdMarker.Kind kind) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.kind = kind;
		}
	}

	// ------------------------------------------------------------------ 载入

	private static void loadIfNeeded() {
		if (loaded || failed) {
			return;
		}
		final MinecraftClient client = MinecraftClient.getInstance();
		try {
			final JsonObject mesh;
			final JsonObject tiles;
			try (InputStream stream = open(client, MESH_ID)) {
				mesh = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			}
			try (InputStream stream = open(client, TILES_ID)) {
				tiles = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			}
			BOXES.clear();
			// 面序以 mesh 里的 "faces" 数组为准；`uv` 是"面名 → 四角 uv 矩形"的对象
			final String[] faceNames = new String[6];
			for (int i = 0; i < 6; i++) {
				faceNames[i] = mesh.getAsJsonArray("faces").get(i).getAsString();
			}
			for (final var element : mesh.getAsJsonArray("boxes")) {
				final JsonObject box = element.getAsJsonObject();
				final Box parsed = new Box();
				for (int i = 0; i < 3; i++) {
					parsed.low[i] = box.getAsJsonArray("from").get(i).getAsFloat();
					parsed.high[i] = box.getAsJsonArray("to").get(i).getAsFloat();
				}
				// chain 只用来认"哪几个盒是头"（见 isHeadBox）；老/手工网格里没有这一项也不能炸
				final com.google.gson.JsonElement chain = box.get("chain");
				parsed.chain = chain == null || chain.isJsonNull() ? null : chain.getAsString();
				final JsonObject uv = box.getAsJsonObject("uv");
				for (int face = 0; face < 6; face++) {
					final var rect = uv.getAsJsonArray(faceNames[face]);
					for (int corner = 0; corner < 4; corner++) {
						parsed.uv[face][corner] = rect.get(corner).getAsFloat();
					}
				}
				BOXES.add(parsed);
			}
			final JsonObject atlas = tiles.getAsJsonObject("atlas");
			atlasWidth = atlas.get("width").getAsInt();
			atlasHeight = atlas.get("height").getAsInt();
			INV_ATLAS_WIDTH = 1.0F / atlasWidth;
			INV_ATLAS_HEIGHT = 1.0F / atlasHeight;
			tileSize = atlas.get("tile").getAsInt();
			TILES.clear();
			for (final var element : tiles.getAsJsonArray("tiles")) {
				final JsonObject tile = element.getAsJsonObject();
				TILES.add(new Tile(tile.get("x").getAsInt(), tile.get("y").getAsInt()));
			}
			final JsonObject groupTiles = tiles.getAsJsonObject("groups");
			skinTiles = readIndices(groupTiles, "skin");
			typeTiles = readIndices(groupTiles, "type");
			professionTiles = readIndices(groupTiles, "profession");
			levelTiles = readIndices(groupTiles, "level");
			loaded = !BOXES.isEmpty() && skinTiles.length > 0;
			if (loaded) {
				buildTables();
			}
			System.out.println("[MMTR-CROWD] 雕像资源已载入：" + BOXES.size() + " 盒 ｜ 图集 " + atlasWidth + "×"
				+ atlasHeight + " ｜ 底皮 " + skinTiles.length + " / 群系 " + typeTiles.length + " / 职业 "
				+ professionTiles.length + " / 等级 " + levelTiles.length);
		} catch (Throwable throwable) {
			failed = true;
			System.out.println("[MMTR-CROWD] 雕像资源载入失败（本局不画雕像）：" + throwable);
		}
	}

	private static InputStream open(MinecraftClient client, Identifier id) throws Exception {
		final Resource resource = client.getResourceManager().getResource(id)
			.orElseThrow(() -> new IllegalStateException("缺资源 " + id));
		return resource.getInputStream();
	}

	private static int[] readIndices(JsonObject groups, String key) {
		final var array = groups.getAsJsonArray(key);
		final int[] out = new int[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = array.get(i).getAsInt();
		}
		return out;
	}

	/** 资源重载（换包）后重新载入。 */
	public static void invalidate() {
		loaded = false;
		failed = false;
		BOXES.clear();
		TILES.clear();
		groups.clear();
		DISCOVERED_PLATFORMS.clear();
		PLATFORM_BOXES.clear();
		LAST_PLATFORMS.clear();
		ACCEPTED_CELLS.clear();
		LEVEL_GROUPS.clear();
		lastLevelLookupAt = 0L;
		firstScanDone = false;
		candidateCount = 0;
		drawableCount = 0;
		anchorCount = 0;
		disposeBatches();
		warnedNoSpots = false;
		warnedNothingDrawn = false;
		warnedFirstDraw = false;
		warnedLevelLookup = false;
	}

	// ------------------------------------------------------------------ 扫描

	/** 上一次 tick 看到的世界对象（判"换世界"用）。 */
	private static ClientWorld lastWorld;

	/** 每 tick：按节拍扫出附近的**候选格 + 归属站台**（客量只决定画前几批，见类注释）。 */
	public static void clientTick(MinecraftClient client) {
		if (!ENABLED) {
			return;
		}
		loadIfNeeded();
		if (!loaded || client.world == null || client.player == null) {
			return;
		}
		/*
		 * ★ 换世界（退出到标题再进、换存档、换维度）：**旧批次必须整批丢掉重建**。
		 *
		 * <p>2026-10-11 实机踩到：退出世界再进之后，日志里还在"排 14 块 / 1280 人"，画面上一个人都没有
		 * —— 批次持有的 VAO/VBO 是上一个世界那套 GL 状态下的产物，重进之后不能再用，
		 * 而我们的账本还以为它们是好的。判据就是"world 对象换了"。</p>
		 */
		if (client.world != lastWorld) {
			lastWorld = client.world;
			disposeBatches();
			groups = new LinkedHashMap<>();
			DISCOVERED_PLATFORMS.clear();
			PLATFORM_BOXES.clear();
			LAST_PLATFORMS.clear();
			ACCEPTED_CELLS.clear();
			LEVEL_GROUPS.clear();
			lastLevelLookupAt = 0L;
			firstScanDone = false;
			candidateCount = 0;
			drawableCount = 0;
			anchorCount = 0;
			rescanCountdown = 0;
			System.out.println("[MMTR-CROWD] 换了世界 ⇒ 批次整批作废，重新扫、重新烘");
		}
		// 客量快照：1 秒一拍，与几何扫描解耦（契约：客量改了 ≤ 2 s 画面跟着变）
		refreshLevels();
		if (rescanCountdown-- > 0) {
			return;
		}
		rescanCountdown = RESCAN_TICKS;
		scan(client.world, client.player.getBlockPos());
	}

	/**
	 * 扫候选格并按**归属站台**分组（见类注释的"方案 A"）。
	 *
	 * <h3>为什么以站台为单位，不以"半径"为单位</h3>
	 * <p>候选格列表就是**建块的签名**：列表一变就要重建。如果列表跟着玩家走（站台只在半径里露出一半、
	 * 纵向按玩家裁剪），玩家一走列表就变 —— 这正是 415 里"走动时 40 ms/段建块抖动、待烘块 4–6"的根因。
	 * 一条 200 格长的站台站在一端时，半径只覆盖半条，走两步"又多出半条"也是同一种病。所以这里：</p>
	 * <ol>
	 *   <li>{@link #discoverPlatforms} 只负责"发现了哪几条站台"（半径内的粗扫）；</li>
	 *   <li>{@link #scanPlatform} 把每条站台**整条扫完**（按它自己的包围盒，不管玩家在哪），
	 *       **只有整个包围盒覆盖到的区块都加载好才算扫全** —— 没扫全就下一拍继续，绝不发布半条；</li>
	 *   <li>{@link #scanOrphans} 兜住"取不到站台"的格（镜像还没同步时会短暂处在这个状态）。</li>
	 * </ol>
	 *
	 * <h3>一份扫描能不能发布：只许长，要缩必须逐格证实（2026-10-13 定稿）</h3>
	 * <p>玩家在两个位置之间来回传送时，读数在两个值（实机：1713 / 1447 格候选）之间抖，两拍都在重烘。
	 * 两个来源，都是"候选集跟着玩家走"：</p>
	 * <ol>
	 *   <li>客户端 {@code ClientChunkManager.getChunk(x,z,status,true)} 对**超出视距**的区块返回
	 *       {@code EmptyChunk}（哨兵对象）而**不是 {@code null}**、也不抛异常 ⇒ 少了视距外那几段的格子；</li>
	 *   <li>上一版把判据写成"{@code loaded} 为真 ⇒ 这一份权威，多少都采纳"，而 {@code loaded} 只说明
	 *       "包围盒里的区块**对象**都在"，**证不出**"少了的那几格真的不再是标记方块"
	 *       （2026-10-13 还查到 {@link #scanPlatform} 当时是 {@code return true;} 常量，
	 *       连这条判据都从来没生效过 —— 日志里每一拍 `扫全 1/1` 就是这么来的）。</li>
	 * </ol>
	 * <p>现在判据变成一条**可证实**的规则（见 {@link #scanAcceptedCells}）：这一份**不少于上次已采纳的
	 * 那一份** ⇒ 采纳（并更新基准）；**变小 ⇒ 逐格证实**上一次有、这一次没有的每一格
	 * （① 所在区块真在 ② 那里已经不是客流标记），有一格证不出就是半份、**拒收**（记一笔 `拒缩`）。
	 * 拒收时**沿用上一拍那个 {@link PlatformGroup} 对象**（签名原样保留 ⇒ {@code prepareGroups} 不标脏
	 * ⇒ 0 建块）。发现记录、孤儿窗口、续扫逻辑照旧，见各自的注释。</p>
	 */
	private static void scan(ClientWorld world, BlockPos center) {
		final long scanStarted = System.nanoTime();
		/*
		 * 上一拍的组（按 key 查）。这一拍"没扫全"的站台**原样沿用**这些对象（见下面的 ★）。
		 *
		 * <p>沿用**对象本身**而不是重新造一份：{@link PlatformGroup} 的签名是构造时算好、不再变的，
		 * 同一个对象进 {@link #groups} ⇒ 与 {@link GroupBatch#signature} 相等 ⇒ {@link #prepareGroups}
		 * 不标脏 ⇒ 这一拍 0 建块。要是重新造一份、只把"上一拍的格子"塞进去，只要有一丁点不同
		 * （客量探针顺序、排序稳定性）它就又变成"签名变了"。</p>
		 */
		final Map<String, PlatformGroup> previousGroups = groups;
		// ① 发现：半径内粗扫，给出"这一拍要整条扫的站台包围盒"
		final List<PlatformBox> boxes = discoverPlatforms(world, center);
		/*
		 * ★ 这些包围盒覆盖到的区块**现在就算被认领**（不是等某条扫完才算）。
		 *
		 * <p>为什么必须先认领：{@link #scanPlatform} 没扫全时这一条可能只收到一部分格（下一拍继续），可它的
		 * 标记格还是躺在那儿。若"认领"只认扫完的站台，那些格就会掉进 {@link #scanOrphans} 的
		 * {@code "?…"} 孤儿组 —— 于是**同一批格子随"这一条这一拍扫没扫全"在站台组与孤儿组之间来回跳**，
		 * 两家都跟着抖。认领全部包围盒之后，孤儿路径永远碰不到站台包围盒里的格，站台组也因此只由
		 * "它自己包围盒里扫到的东西"决定（合同 §2 第一条）。</p>
		 */
		final Set<Long> claimedChunks = new LinkedHashSet<>();
		for (final PlatformBox box : boxes) {
			claimBoxChunks(box, claimedChunks);
		}
		// ② 整条扫：每条站台按自己的包围盒扫完（跨块的格在这里被收齐）
		final List<ScannedGroup> scanned = new ArrayList<>();
		final Map<String, List<Cell>> byGroup = new LinkedHashMap<>();
		int scannedPlatforms = 0;
		for (final PlatformBox box : boxes) {
			// 这一拍重新判"能不能发布"（box.complete / box.loaded）：上一拍的结论不许留到这一拍
			box.complete = false;
			final List<Cell> cells = new ArrayList<>();
			scannedPlatforms++;
			final boolean loaded = scanPlatform(world, box, cells);
			scanned.add(new ScannedGroup(box, cells, loaded));
		}
		// ③ 兜底：镜像还没同步 ⇒ 半径内的标记格先挂到"？"组（客量 0，一格都不画，但也不丢）
		scanOrphans(world, center, byGroup, claimedChunks);
		// ④ 每一组：跨块轮取 + 均衡序 + 站定组在前，然后算签名
		final Map<String, PlatformGroup> next = new LinkedHashMap<>();
		final Map<String, PlatformGroup> carried = new LinkedHashMap<>();
		int candidates = 0;
		int drawable = 0;
		// 锚点格（ANCHOR）总数：照扫照算、但不站人（见 PlatformGroup 的类注释），只用于读数对账
		int anchors = 0;
		int completePlatforms = 0;
		for (final ScannedGroup scan : scanned) {
			final PlatformBox box = scan.box;
			/*
			 * ★ 两份扫描结果都并进 byGroup（键是格子的归属站台，两条站台不会算出同一个键）。
			 *
			 * <p>孤儿路径只收"包围盒外"的格，所以这份合并**不可能**把一个格同时算进两条站台；
			 * 去重只是防"同一个键被收两遍"。</p>
			 */
			final List<Cell> cells = dedupe(scan.cells);
			byGroup.computeIfAbsent(box.key, ignored -> new ArrayList<>()).addAll(cells);
			/*
			 * ★ 这里**同时**是"半份扫描不许进签名"的闸门（与 {@link #scanPlatform} 的"看没看清"配套）。
			 * 判据：
			 * <ol>
			 *   <li>{@code !cells.isEmpty()}：一格都没收的不许当"扫过"发布 —— 那会造出一个空组，
			 *       下一拍有格了又变一次 ⇒ 白重建；也正因为这一条短路在前，**空的一份永远不会改写基准**
			 *       （见 {@link #scanAcceptedCells}）；</li>
			 *   <li>{@link #scanAcceptedCells}：**这一份不少于上次已采纳的那一份 ⇒ 采纳**（候选集只许长，
			 *       多出来的格一定来自"这一拍多看清了那些区块"，是逐格从真区块里读出来的，不需要证实）；
			 *       **比上次小 ⇒ 逐格证实**（上次有、这次没有的每一格都要"区块真在 + 那里已经不是标记方块"），
			 *       有一格证不出就判这份是半份、**拒收**。</li>
			 * </ol>
			 * 不满足就**把这一条整组留到下一拍**：{@code carried} 里的组最后由上一拍的对象原样填回
			 * {@code next}，签名不动 ⇒ 0 建块、画面也不动。**不做任何部分更新**：把半份格子写进
			 * {@link PlatformGroup} 就等于改签名，那是本条要根除的病。
			 *
			 * <p>为什么不能退回"{@code loaded} 为真就多少都采纳"（上一版的写法）：{@code loaded} 只回答
			 * "包围盒里的区块**对象**在不在"，它**不等于"这一份数据齐全"** —— 实机 2026-10-13 18:44，
			 * 玩家在两个位置之间每 3 秒来回传送，同一个站台读出 1713 / 1447 两份、两边都自称
			 * {@code 扫全 1/1}，小的一份把大的一份替换掉 ⇒ 签名一拍拍地变 ⇒ 每拍重烘。判据必须**可证实**：
			 * "少了的格"要拿世界现在长什么样逐格对账，而不是靠一句"我觉得我看清了"。
			 *
			 * <p>"标记格被挖掉"能不能立刻见效：只要**缺的每一格都证得出来**（区块在、方块已不是标记）
			 * 就采纳 ⇒ 重烘一次（这是**正确**的重烘，{@code 拒缩} 不涨）；证不出来（缺格落在视距外那些
			 * 没看清的区块里）就先保留旧格子，等看清整条时自动纠正 —— 这是"不抖"与"改了就见效"之间
			 * 明确的取舍。
			 */
			box.complete = !cells.isEmpty() && scanAcceptedCells(world, box.key, cells, scan.loaded);
			if (!box.complete) {
				final PlatformGroup kept = previousGroups.get(box.key);
				if (kept != null) {
					carried.put(box.key, kept);
				}
				// 上一拍没有这一组（刚发现、还没扫全一次）⇒ 这一拍就不发布它，下一拍再扫
				continue;
			}
			completePlatforms++;
		}
		// ⑤ 上一拍已经发布、而这一拍没扫全的组：**原对象**填回，签名与批次的账都对得上
		for (final Map.Entry<String, PlatformGroup> entry : carried.entrySet()) {
			next.putIfAbsent(entry.getKey(), entry.getValue());
		}
		/*
		 * ★ 现在才把每一条站台的网格并进所有组的候选列表。
		 *
		 * <p>顺序要紧：③ 的孤儿格与 ④ 的搬运都排在这之前，所以 {@code byGroup.computeIfAbsent} 只会
		 * 在"这一条这一拍是**第一次**扫全"时才建键 —— 上一拍已经建过组的站台不会因此多出一个空列表或
		 * 被重造一份（见下面对 {@code next.containsKey} 的判据）。</p>
		 */
		for (final Map.Entry<String, List<Cell>> entry : byGroup.entrySet()) {
			final List<Cell> cells = entry.getValue();
			if (cells.isEmpty()) {
				continue;
			}
			if (next.containsKey(entry.getKey())) {
				// 这一条这一拍没扫全、已经原样沿用了上一拍的组：这份（半份）格一律不发布
				continue;
			}
			final PlatformGroup group = new PlatformGroup(entry.getKey(), cells);
			next.put(entry.getKey(), group);
		}
		for (final PlatformGroup group : next.values()) {
			candidates += group.cellCount;
			drawable += group.spots.size();
			// 锚点格（ANCHOR）：照扫照算，但**不是落点**（见 PlatformGroup 的类注释）。
			// 这里的 for 顺手把它累计出来，只为读数对账：`候选格 − 可画` 里有一大块就是它。
			anchors += group.anchorCount;
		}
		groups = next;
		candidateCount = candidates;
		drawableCount = drawable;
		anchorCount = anchors;
		lastScanMillis = (System.nanoTime() - scanStarted) / 1000000.0;
		// 不静默：一格候选都没有时要说出来（"看不到人"的第一条要能回答"到底扫到了几格"）
		if (candidates == 0 && !warnedNoSpots) {
			warnedNoSpots = true;
			System.out.println("[MMTR-CROWD] 附近没扫到客流标记格（玩家 " + center.getX() + "," + center.getY()
				+ "," + center.getZ() + " ｜ 站台 " + boxes.size() + " 条 ｜ " + (CHUNK_RADIUS * 2 + 1) + "×"
				+ (CHUNK_RADIUS * 2 + 1) + " 区块）—— 不画。先确认标记方块在不在、区块有没有加载");
		}
		// 扫完就把"哪一组该重烘"标出来（只比候选格签名，签名没变就不动）
		prepareGroups();
		/*
		 * ★ 扫完立刻把**每一组**的客量取一次（不受客量快拍的节流约束，所以直接调 {@link #refreshAllLevels}）。
		 *
		 * <p>为什么不等到下一个客量快拍（最多 1 秒后）：① 新组刚建出来时客量是 0，而"0 就一格都不画"——
		 * 刚进站的那一下会先看不到人，一秒后才出现；② 更要紧的是 {@link #buildDirtyGroups} 现在按
		 * "客量读到 0 就丢"省建块，**读到之前不敢丢**：不在这一拍把客量读出来，开局那 16 条 0% 的站台
		 * 会在这一秒里照旧被烘，那笔浪费就没省下。成本是每组一次归属查询，与几何扫描的代价比可以忽略。</p>
		 */
		refreshAllLevels();
		if (DEBUG) {
			/*
			 * 「扫全 X/Y」= 这一拍**采纳（发布）**了几条 / 这一拍要扫几条（{@link #scan} ④ 的
			 * {@code completePlatforms}）。玩家在视距边缘走动时它是 0/N 而不是 N/N —— 那就是
			 * "这一拍不动这一组、沿用上一拍"的正确读数；整条都看清了才会是 N/N。
			 * **别把它当"区块没加载"的告警**：视距外本来就是空区块，站台扫全与否只决定"这一拍动不动签名"。
			 */
			System.out.println("[MMTR-CROWD] 扫描 " + Math.round(lastScanMillis) + " ms ｜ " + candidates
				+ " 格候选（锚点 " + anchors + "，不站人）｜ 站台组 " + next.size() + "（扫全 "
				+ completePlatforms + "/" + scannedPlatforms
				+ "）｜ 待建块 " + DIRTY_GROUPS.size() + " ｜ 已建块 " + BATCHES.size());
		}
		// 诊断横幅（只打一次：第一次扫完之后，免得跟几何扫描抢在同一次里做重活）
		if (!probed && firstScanDone) {
			probed = true;
			int direct = 0;
			final BlockPos.Mutable cursor = new BlockPos.Mutable();
			for (int dy = -4; dy <= 2; dy++) {
				for (int dx = -16; dx <= 16; dx++) {
					for (int dz = -16; dz <= 16; dz++) {
						cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (BlockCrowdMarker.kindOf(new org.mtr.mapping.holder.BlockState(world.getBlockState(cursor)))
							!= null) {
							direct++;
						}
					}
				}
			}
			System.out.println("[MMTR-CROWD] 诊断：站台镜像=" + LAST_PLATFORMS.size() + " 条 ｜ 发现站台="
				+ DISCOVERED_PLATFORMS.size() + " 条 ｜ 逐格直探（±16 格 × y±4）命中标记格=" + direct
				+ " ｜ 判定用的是 " + BlockCrowdMarker.class.getSimpleName() + ".kindOf ｜ 候选格=" + candidates);
		}
		firstScanDone = true;
	}

	/** 第一次几何扫描做完了没有（诊断横幅要等它一次，免得跟几何扫描抢在同一次里做重活）。 */
	private static boolean firstScanDone = false;

	/**
	 * 已经发现过的站台（hex）→ 最后一次粗扫"见到"它的轮次（{@link #scanCounter}）。
	 *
	 * <p>为什么要有这个记录（而不是"这一拍粗扫到谁就算谁"）：静态数据流是按拍下发的，某一拍可能只下
	 * 差分、恰好这条站台不在镜像里 —— 那一拍就把发现记录丢掉的话，它的候选格会掉进 `"?…"` 组，
	 * 下一拍再回到站台组 ⇒ 白重建一次。所以发现记录留两拍，这两拍里用上一拍的包围盒照旧整条扫
	 * （见 {@link #discoverPlatforms}）；连续两拍都拿不到端点才真丢（站台被删了）。</p>
	 */
	private static final Map<String, Integer> DISCOVERED_PLATFORMS = new LinkedHashMap<>();

	/**
	 * 一条站台要扫的范围：{@code mmtrOrderedPositions()} 两端点的包围盒，水平 ±{@link #PLATFORM_PAD_HORIZONTAL}、
	 * 垂直 ±{@link #PLATFORM_PAD_VERTICAL}。
	 *
	 * <p>为什么放宽这么多：服务端铺标记的实际范围（{@code MmtrCrowdModule.sweepMarkers}）是"站台方块那一排
	 * 往站台里 {@code MARKER_WINDOW = 12} 格、上下各 0–2 层、沿轨整条"。端点包围盒本身只有轨中心线那一条，
	 * 不放宽就漏掉整排标记格。这里取 16 > 12、6 > 2 —— **只多扫一点空区块，绝不能漏格**：
	 * 漏一格 ⇒ 这一组的候选格列表在"扫全"前后不同 ⇒ 签名一变就白重建一次。</p>
	 */
	private static final class PlatformBox {

		final String key;
		final int minX;
		final int maxX;
		final int minY;
		final int maxY;
		final int minZ;
		final int maxZ;
		/**
		 * 这一条这一拍是否"包围盒覆盖到的区块**全是真区块**"（没有视距外的 {@code EmptyChunk}）。
		 *
		 * <p>由 {@link #scanPlatform} 每拍重算。"部分可见"不等于"这一拍不扫"——见 {@link #scan} 里的
		 * 三条判据：真正决定"这一份能不能发布"的是 {@link #complete}。</p>
		 */
		boolean loaded;
		/**
		 * 这一条这一拍扫出来的东西**能不能发布**（{@link #scan} 每拍先清成 false 再重算）。
		 *
		 * <p>判据（见 {@link #scan} ④）：格数不为 0 + {@link #scanAcceptedCells} 通过
		 * （**只许长**；变小要**逐格证实**）。只有 true 才允许把这一条的候选格写进
		 * {@link PlatformGroup}（写进去就等于改签名 ⇒ 建块）。</p>
		 */
		boolean complete;

		PlatformBox(Platform platform) {
			key = platform.getHexId();
			// Platform.mmtrOrderedPositions() 就是两端点（顺序是"字典序小的在前"，与轨的 hex 同一套约定）
			final Position[] ends = platform.mmtrOrderedPositions();
			final int ax = (int) ends[0].getX();
			final int ay = (int) ends[0].getY();
			final int az = (int) ends[0].getZ();
			final int bx = (int) ends[1].getX();
			final int by = (int) ends[1].getY();
			final int bz = (int) ends[1].getZ();
			minX = Math.min(ax, bx) - PLATFORM_PAD_HORIZONTAL;
			maxX = Math.max(ax, bx) + PLATFORM_PAD_HORIZONTAL;
			minY = Math.min(ay, by) - PLATFORM_PAD_VERTICAL;
			maxY = Math.max(ay, by) + PLATFORM_PAD_VERTICAL;
			minZ = Math.min(az, bz) - PLATFORM_PAD_HORIZONTAL;
			maxZ = Math.max(az, bz) + PLATFORM_PAD_HORIZONTAL;
		}

		boolean contains(int x, int y, int z) {
			return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
		}
	}

	/**
	 * 一条站台**上一次被采纳**的那份扫描的**格列表**（hex → 那一份的格列表）。见 {@link #scanAcceptedCells}。
	 *
	 * <p>★ 为什么必须存**格列表**、不能只存格数：采纳一份"变小"的扫描之前，要**逐格**核对
	 * "上次有、这次没有"的那些格现在到底是不是已经不是标记方块了（见 {@link #shrinkProven}）。
	 * 只存一个计数就只能比大小，而"少了几格"这件事**靠计数分不开两种可能**：① 玩家真的把标记挖掉了；
	 * ② 这一拍根本看不清那些格（视距外 / 区块没加载）。猜错的代价正好就是本条要根除的病 ——
	 * 半份扫描替换掉全份 ⇒ 签名变 ⇒ 每拍重烘（实机 1713 ↔ 1447）。存了列表才有可逐格核对的基准。</p>
	 *
	 * <p>内存可以忽略：{@link Cell} 只有 4 个字段、量级几千格；与每组常驻的 {@link PlatformGroup#spots}
	 * （几千个 {@link Spot}，每个还带着外观/姿势）比完全不是一个量级。
	 * 存进去的就是 {@link #dedupe} 排好空间序的那一份列表本身，之后没有任何地方再改它
	 * （{@code byGroup.addAll} 只是把引用抄进另一个列表，{@link PlatformGroup} 排的是那个列表）。</p>
	 *
	 * <p>★ 为什么不能存在 {@link PlatformBox} 上：{@link #discoverPlatforms} 每一拍都按镜像里的
	 * {@link Platform} **新建** PlatformBox 对象（镜像每拍可能换对象），存在盒子里就等于每拍清零 ——
	 * 判据永远 trivially 通过，等于没写（2026-10-13 自查发现并改掉）。</p>
	 *
	 * <p>★ 不做回收（只在换世界 / 资源重载时清空）：条目数 = 本局发现过的站台数（量级几十），
	 * 而丢掉一条基准就等于"下回第一次看到它时无条件采纳一份可能不完整的扫描"—— 恰恰是这里要防的。
	 * 几十条 × 几千格的引用，比一次候选集抖动便宜得多。</p>
	 */
	private static final Map<String, List<Cell>> ACCEPTED_CELLS = new LinkedHashMap<>();

	/**
	 * 这一条这次扫描收到的 {@code cells} 能不能采纳（{@link #scan} 判据 ②）。
	 *
	 * <h3>三条分支</h3>
	 * <ol>
	 *   <li><b>还没见过这条站台</b>（没有基准）⇒ 采纳，并记下这一份格列表。为什么第一眼允许"没看清也采纳"：
	 *       包围盒放宽 ±16 之后可能比视距还大，若要求"整条看清才准出现"，站在长站台一端时就永远看不到人。
	 *       先按看到的那份发布，之后只会往上长（要缩必须逐格证实）；</li>
	 *   <li><b>不小于上次（长或持平）</b>⇒ 采纳，基准改写成这一份。候选集"只许长"这一半**不需要证实**：
	 *       多出来的格是从真区块里逐格读出来的（见 {@link #scanPlatform}），不会凭空多；</li>
	 *   <li><b>比上次小</b>⇒ **逐格证实**（{@link #shrinkProven}）：上次有、这次没有的**每一格**都要
	 *       "所在区块真在 + 那里已经不是标记方块"。有一格证不出 ⇒ 判这一份是半份 ⇒ **拒收**：
	 *       保留上一拍那一组（{@link #scan} 的 {@code carried}）、**基准不动**，并给读数记一笔 `拒缩`。
	 *       全都证得出来 ⇒ 是玩家真的把标记挖掉了 ⇒ 采纳这一份（签名跟着变 ⇒ 重烘一次，这是正确的重烘）。</li>
	 * </ol>
	 *
	 * <h3>为什么"看清了整条（{@code loaded}）"不足以证明这一份完整</h3>
	 * <p>{@code loaded} 只回答"包围盒覆盖的区块**对象**在不在"（不是 {@code null} / {@code EmptyChunk}），
	 * 它是**看没看清**，不是**这一份与上一份的关系**：同样是"扫全"的一拍，视距一变就能少掉几百格。
	 * 所以采纳与否**不许**由它一个人说了算（上一版就是"看清了 ⇒ 多少都采纳"，实机 1713 / 1447 来回跳、
	 * 几乎每拍重烘）。{@code loaded} 现在只用于**诊断**：第一次拒缩时打一行，说明当时整条看清了没有
	 * （累计次数看汇总行的 `拒缩`）。它**不弱**于本判据：包围盒里真没加载的那些块，缺失格会在
	 * {@link #shrinkProven} 第 ① 条被**逐格**挡住；反过来它也不会把"玩家在站台一端挖掉一格"这种
	 * **真变化**一起拒掉 —— 那种格的区块是加载好的、方块也真的换了，两条都满足。</p>
	 *
	 * <p>为什么基准要放在这个常驻 map 里、而不是 {@link PlatformBox} 上：见 {@link #ACCEPTED_CELLS}。</p>
	 */
	private static boolean scanAcceptedCells(ClientWorld world, String key, List<Cell> cells, boolean loaded) {
		final List<Cell> previous = ACCEPTED_CELLS.get(key);
		if (previous != null && cells.size() < previous.size() && !shrinkProven(world, previous, cells)) {
			/*
			 * 半份：**不采纳、也不动基准**。基准一改，下一拍就会拿这份半份当"上次"，越缩越少、
			 * 而且缩的过程本身每拍一次 ⇒ 每拍重烘（正是要根除的病）。
			 */
			shrinkRejectedSinceSummary++;
			if (!warnedShrinkRejected) {
				warnedShrinkRejected = true;
				System.out.println("[MMTR-CROWD] 拒收一次「变小」的扫描（站台 " + key + " ｜ 上次 " + previous.size()
					+ " 格 / 这次 " + cells.size() + " 格 ｜ 缺的格里有一格证不出「已经被挖掉」｜ 这一拍整条看清=" + loaded
					+ "）—— 保留上一拍那一组，累计次数见汇总行的 拒缩；只报一次");
			}
			return false;
		}
		ACCEPTED_CELLS.put(key, cells);
		return true;
	}

	/**
	 * 一份**变小**的扫描能不能证实（{@link #scanAcceptedCells} 第 3 条）：把"上次有、这次没有"的格
	 * **逐格**拿到世界上去对账。
	 *
	 * <p>每一格都要同时满足两条，少一条就判"这一份是半份"：</p>
	 * <ol>
	 *   <li><b>它所在的区块现在真在</b>：{@code world.getChunk(cx, cz)} 不是 {@code null}、也不是
	 *       {@code EmptyChunk} 哨兵（客户端对视距外/没加载的区块给的就是这个哨兵，不是 null）。
	 *       缺的格落在这种块里时，我们**没有资格**说"那里没有标记了"，只能认定这一份不完整。
	 *       这一条是**可证实**的：1.20.4 客户端 {@code ClientChunkManager.loadChunkFromPacket} 是
	 *       "把包填进新建的 WorldChunk、**填完才**放进区块表"，所以从 {@code world.getChunk} 拿到的
	 *       真区块其段数据是齐的（不存在"区块对象在、段数据空着"的中间态 —— 2026-10-13 反汇编确认）；</li>
	 *   <li><b>那个位置的方块已经不是客流标记</b>：
	 *       {@code BlockCrowdMarker.kindOf(new BlockState(world.getBlockState(格)))} == null ——
	 *       与扫描时判定"这一格是不是标记格"用的是**同一个**函数（见 {@link #scanPlatform} 与
	 *       {@link #scan} 的诊断横幅），不另立一套判据。</li>
	 * </ol>
	 *
	 * <p>为什么必须**逐格**、不能"少数服从多数"、也不能只看前几格：缺的那些格里只要**有一格**是因为
	 * 区块没加载而缺席，这一份就是半份；采信它就等于让候选集跟着视距走（正是要根除的病）。
	 * 反过来说，逐格都证得出来时，"玩家挖掉了这些格"是**世界自己给出来的事实**，与玩家站在哪里、
	 * 看不看得清整条无关 —— 这就是判据"可证实"的含义。</p>
	 *
	 * <p>两份列表都是按 (x, z, y) 排好序的（{@link #dedupe} 排的就是这个序），所以这里一次归并走完：
	 * 位置相同的跳过，只对"上次有、这次没有"的格取证。这次**多出来**的格（总体变小、但内容有增有减时
	 * 会出现，比如"挖掉三格、又在别处放了一格"）与"少了哪些格"无关，归并时直接跳过。</p>
	 */
	private static boolean shrinkProven(ClientWorld world, List<Cell> previous, List<Cell> current) {
		int i = 0;
		int j = 0;
		while (i < previous.size()) {
			if (j < current.size()) {
				final int order = compareCells(previous.get(i), current.get(j));
				if (order == 0) {
					i++;
					j++;
					continue;   // 两边都有 ⇒ 与"变小"无关
				}
				if (order > 0) {
					j++;        // 这一份里新冒出来的格 ⇒ 与"少了哪些格"无关
					continue;
				}
			}
			if (!provenRemoved(world, previous.get(i))) {
				return false;   // 有一格证不出 ⇒ 这一份是半份
			}
			i++;
		}
		return true;
	}

	/** 空间序：x → z → y —— 与 {@link #dedupe} / {@link PlatformGroup} 的排序**同一个序**（归并依赖它）。 */
	private static int compareCells(Cell a, Cell b) {
		if (a.x != b.x) {
			return Integer.compare(a.x, b.x);
		}
		return a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.y, b.y);
	}

	/** 取证用的游标（只在渲染线程的扫描里用，见 {@link #scan} 的同族写法）：避免逐格 new 一个 BlockPos。 */
	private static final BlockPos.Mutable PROBE_POS = new BlockPos.Mutable();

	/**
	 * 一格"上次是标记、这一拍没扫到"的取证（{@link #shrinkProven} 的每一格）：
	 * **区块真在** + **那里已经不是标记方块**，两条缺一不可。
	 */
	private static boolean provenRemoved(ClientWorld world, Cell cell) {
		// ① 区块真在（真区块 = 段数据齐，见 shrinkProven 的说明）；视距外的 EmptyChunk 哨兵在这里被挡住
		final WorldChunk chunk = world.getChunk(ChunkSectionPos.getSectionCoord(cell.x),
			ChunkSectionPos.getSectionCoord(cell.z));
		if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk) {
			return false;
		}
		PROBE_POS.set(cell.x, cell.y, cell.z);
		// ② 与扫描时**同一个判据**：kindOf == null ⇒ 这里已经不是客流标记格（玩家挖了 / 换成了别的方块）
		return BlockCrowdMarker.kindOf(new org.mtr.mapping.holder.BlockState(world.getBlockState(PROBE_POS))) == null;
	}

	/**
	 * 把一条包围盒覆盖到的区块**全部**记进 {@code claimedChunks}（在 {@link #scanPlatform} 之前就记）。
	 *
	 * <p>为什么"认领"要早于、且宽于"扫完"：见 {@link #scan} 里那段"必须先认领"的注释 ——
	 * 认领是为了让 {@link #scanOrphans} 永远碰不到站台包围盒里的格，否则同一批格会随"这一条这一拍
	 * 扫没扫全"在站台组与孤儿组之间来回跳，两家的签名都跟着抖。</p>
	 */
	private static void claimBoxChunks(PlatformBox box, Set<Long> claimedChunks) {
		final int minChunkX = ChunkSectionPos.getSectionCoord(box.minX);
		final int maxChunkX = ChunkSectionPos.getSectionCoord(box.maxX);
		final int minChunkZ = ChunkSectionPos.getSectionCoord(box.minZ);
		final int maxChunkZ = ChunkSectionPos.getSectionCoord(box.maxZ);
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				claimedChunks.add(chunkKey(chunkX, chunkZ));
			}
		}
	}

	/**
	 * 一条站台这一拍扫出来的东西（{@link #scanPlatform} 的产物）：格还没按组归并、也没去重。
	 *
	 * <p>为什么不直接往 {@code byGroup} 里塞：{@link #scan} 要**先判"这一份能不能用"**
	 * （{@link PlatformBox#complete}），判不过就整条丢掉、沿用上一拍的组。先收在局部，
	 * 判过了再并进 byGroup —— 这样"半份格子"根本没有机会接近 {@link PlatformGroup}。</p>
	 */
	private static final class ScannedGroup {

		final PlatformBox box;
		final List<Cell> cells;
		/**
		 * 这一条这一拍"整条都看清了"吗（包围盒覆盖的区块全是真区块，见 {@link #scanPlatform}）。
		 *
		 * <p>它**不参与"这一份能不能采纳"的判定**：① 单看它分辨不出"同样是扫全的一拍、视距一变就少
		 * 几百格"；② 包围盒比视距还大的站台永远等不到它。采纳与否由 {@link #scanAcceptedCells}
		 * 的"只许长 + 变小逐格证实"判（那才是可证实的判据）。这里留着它是因为它**确实**说明了一件事：
		 * false ⇒ 这一份一定是半份（有区块没看到）；同时它也是"拒缩"那行诊断要报的数。
		 * 反过来说，它 false 时**绝不发布缩小的份** —— 缺的格会在 {@link #shrinkProven} 第 ① 条被逐格挡住。</p>
		 */
		final boolean loaded;

		ScannedGroup(PlatformBox box, List<Cell> cells, boolean loaded) {
			this.box = box;
			this.cells = cells;
			this.loaded = loaded;
		}
	}

	/** 上一次发现到的站台包围盒（"这一拍要扫哪几条"）。 */
	private static final Map<String, PlatformBox> PLATFORM_BOXES = new LinkedHashMap<>();
	/** 上一次从镜像里看到的站台对象（诊断读数用；不参与判定）。 */
	private static final Map<String, Platform> LAST_PLATFORMS = new LinkedHashMap<>();

	/**
	 * 半径内的**发现**粗扫：只回答"这几条站台在这一带出现了"。
	 *
	 * <p>按 32×32 格分桶（桶 key → 空格数），桶里有标记格的桶就去找这一带的站台
	 * （{@link MmtrCrowdPlatformLevels#platformAt}，返回最近的站台，宽松），拿它的
	 * {@link PlatformBox}。**发现是幂等的**：同一个桶只有坐标，站台归属由当前镜像算 ——
	 * 玩家在半径内走动、镜像更新，都不会让已经发现的站台"消失"（除非标记格真的被挖掉）。</p>
	 */
	private static List<PlatformBox> discoverPlatforms(ClientWorld world, BlockPos center) {
		final int span = CHUNK_RADIUS * 2 + 1;
		final int baseChunkX = ChunkSectionPos.getSectionCoord(center.getX()) - CHUNK_RADIUS;
		final int baseChunkZ = ChunkSectionPos.getSectionCoord(center.getZ()) - CHUNK_RADIUS;
		final Map<Long, Integer> bucketCounts = new LinkedHashMap<>();
		for (int chunkX = baseChunkX; chunkX < baseChunkX + span; chunkX++) {
			for (int chunkZ = baseChunkZ; chunkZ < baseChunkZ + span; chunkZ++) {
				// ★ 必须用 World#getChunk(x,z)：`ChunkManager#getWorldChunk(x,z,false)` 在客户端是**接口默认实现**
				//   （ClientChunkManager 只覆写了 getChunk(x,z,status,create)）⇒ 永远返回 null，
				//   于是"扫到 0 格、什么都不画"而日志里一条异常都没有。本仓 notes/414 踩过同族的坑。
				final WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk) {
					continue;   // 没加载 / 空区块：发现不了（下一拍再说）
				}
				for (int sectionIndex = 0; sectionIndex < chunk.getSectionArray().length; sectionIndex++) {
					final ChunkSection section = chunk.getSectionArray()[sectionIndex];
					if (section == null || section.isEmpty()) {
						continue;
					}
					/*
					 * ★ 先问"这一段里到底有没有客流标记"，再逐格读：半径 8 区块 = 17×17 区块 ×
					 * 每区块最多 4096 格 = 上百万次读方块，5 秒一次也够呛。`hasAny` 只走**调色板**，
					 * 几乎不要钱，而站台上绝大多数段（地下、空中）一格都没有 —— 这一句把扫描成本
					 * 从"格数"降到"段数"。
					 */
					if (!section.hasAny(rawState -> BlockCrowdMarker.kindOf(
						new org.mtr.mapping.holder.BlockState(rawState)) != null)) {
						continue;
					}
					// ★ 段数组是**从世界最低段开始**排的（1.20.4 主世界 minY=-64 ⇒ 数组下标 = 段坐标 + 4），
					//   所以 baseY 必须用 getBottomY() + index*16 —— 直接拿 index 当段坐标会读错整整 4 个段
					final int baseY = world.getBottomY() + sectionIndex * 16;
					for (int y = 0; y < 16; y++) {
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								if (BlockCrowdMarker.kindOf(new org.mtr.mapping.holder.BlockState(
									section.getBlockState(x, y, z))) == null) {
									continue;
								}
								final int worldX = (chunkX << 4) + x;
								final int worldY = baseY + y;
								final int worldZ = (chunkZ << 4) + z;
								final long bucket = bucketKey(worldX, worldY, worldZ);
								bucketCounts.merge(bucket, 1, Integer::sum);
								if (bucketCounts.get(bucket) == 1) {
									// 这个桶第一次出现标记格：按这一格问一句归属，决定"要不要把这条站台整条扫完"
									final String key = groupKeyAt(worldX, worldY, worldZ);
									if (!key.startsWith("?")) {
										// 这一拍见到的记本扫描轮次（>0 都是"见过"）
										DISCOVERED_PLATFORMS.put(key, scanCounter);
									}
								}
							}
						}
					}
				}
			}
		}
		// 这一拍要扫的站台：①镜像里问得到端点的（发现过的都算）；②镜像暂时问不到、但上一拍问到了的
		final List<PlatformBox> boxes = new ArrayList<>();
		final MinecraftClient client = MinecraftClient.getInstance();
		final var data = client == null ? null : org.mtr.mod.client.MinecraftClientData.getInstance();
		final Map<String, PlatformBox> lastBoxes = new LinkedHashMap<>(PLATFORM_BOXES);
		PLATFORM_BOXES.clear();
		LAST_PLATFORMS.clear();
		if (data != null) {
			for (final Platform platform : data.platforms) {
				final String key = platform.getHexId();
				LAST_PLATFORMS.put(key, platform);
				if (DISCOVERED_PLATFORMS.containsKey(key)) {
					try {
						final PlatformBox box = new PlatformBox(platform);
						PLATFORM_BOXES.put(key, box);
						boxes.add(box);
					} catch (RuntimeException exception) {
						// 端点在别的线程被改（静态数据流）时不能把客户端 tick 打掉：这一条下一拍再说
						if (!warnedLevelLookup) {
							warnedLevelLookup = true;
							System.out.println("[MMTR-CROWD] 读站台端点失败（这条站台这一拍跳过，只报一次）：" + exception);
						}
					}
				}
			}
		}
		/*
		 * 发现记录：**见不到也先留一拍**，而且这一拍照旧用上一拍的包围盒整条扫。
		 *
		 * <p>为什么：静态数据流是按拍下发的，某一拍可能只下差分、恰好这条站台不在镜像里 —— 那一拍就把它
		 * 丢掉的话，它的候选格会掉进 `"?…"` 组，下一拍再回到站台组 ⇒ 白重建一次。所以：①粗扫见到了 ⇒ 记
		 * {@code scanCounter}；②镜像里问不到 ⇒ 用**上一拍的包围盒**（差一拍，不是 5 秒前的组）继续扫；
		 * ③连续两拍都见不到 ⇒ 才真的丢掉（站台被删了）。</p>
		 */
		for (final Map.Entry<String, Integer> entry : new ArrayList<>(DISCOVERED_PLATFORMS.entrySet())) {
			final String key = entry.getKey();
			if (LAST_PLATFORMS.containsKey(key)) {
				continue;   // 这一拍从镜像里问到了端点（上面已经排进 boxes）
			}
			final PlatformBox stale = lastBoxes.get(key);
			if (stale == null || entry.getValue() < scanCounter - 1) {
				DISCOVERED_PLATFORMS.remove(key);   // 连着两拍都拿不到端点 ⇒ 真的没了
			} else {
				PLATFORM_BOXES.put(key, stale);
				boxes.add(stale);
			}
		}
		return boxes;
	}

	/**
	 * **把一条站台整条扫完**（按它自己的包围盒，不管玩家半径），返回值 = "这一拍整条都看清了"。
	 *
	 * <p>返回值与 {@code box.loaded} 是同一个意思（返回它只是为了调用点好读）：包围盒覆盖到的**每一个区块
	 * 都是真区块**（不是 {@code null}、不是 {@code EmptyChunk} 哨兵）。**部分可见时照样收、照实把
	 * {@code loaded} 记假**（不是"一格都不收"）：站台长达 220 格、视距只有 160 格时，站在一端
	 * 另一头必然在视距外 —— 若这里直接不收，那条站台就**一个人都画不出来**（实机见过 `0 格候选 ｜ 扫全 0/2`）。
	 * "这一份全不全、要不要采纳"由 {@link #scan} 的 {@link #scanAcceptedCells} 判（**只许长，要缩逐格证实**）：
	 * 这里只回答"看没看清"，**不决定采纳** —— 看清了也可能少掉几百格（视距一变），没看清也可能少掉的
	 * 每一格都证得出"已经被挖掉"。</p>
	 *
	 * <p>收格时按"这一格是不是标记方块"逐格读：标记格只可能出现在站台面上下几格，所以先按段调色板
	 * 剪掉（{@code hasAny}），再逐格读 —— 成本按"段数"走，不按包围盒体积走。</p>
	 *
	 * <p>★ **没有"这一段被别的站台读过了就跳过"这种优化**：两条站台的包围盒重叠时，同一个段里的格子
	 * 要分别判它们各自属于哪条站台（{@link #groupKeyAt} 取最近的那条）。跳过一段就会把后一条站台的格子
	 * 整段丢掉 —— 那是"签名少了一半格"的同一个病。宁可重复读一遍（空段只是一次调色板查询）。</p>
	 */
	private static boolean scanPlatform(ClientWorld world, PlatformBox box, List<Cell> cells) {
		final int minChunkX = ChunkSectionPos.getSectionCoord(box.minX);
		final int maxChunkX = ChunkSectionPos.getSectionCoord(box.maxX);
		final int minChunkZ = ChunkSectionPos.getSectionCoord(box.minZ);
		final int maxChunkZ = ChunkSectionPos.getSectionCoord(box.maxZ);
		/*
		 * ① **如实收、如实记**：能从加载好的区块里收多少收多少；有任何一个没加载 / 是
		 *    {@code EmptyChunk} 哨兵，就把 {@code box.loaded} 记假（"这一份没看清"），但**不中断收集**
		 *    —— "要不要采纳这一份"由 {@link #scanAcceptedCells} 判（只许长；变小逐格证实）。
		 *
		 * ★ 为什么视距外的空区块必须当成"没看清"（2026-10-13 修）：客户端
		 *   {@code ClientChunkManager.getChunk(x, z, status, true)} 对**超出视距**的区块返回的是
		 *   {@code EmptyChunk}（一个哨兵对象），**不是 {@code null}**、也不抛异常。只拦 {@code null} 的话，
		 *   "视距外的区块"就会被当成"这一条扫全了"，于是**半份扫描被当成全份发布**：这条站台的候选格
		 *   少掉视距外那几段 ⇒ 签名变 ⇒ {@link #prepareGroups} 标脏重建。而视距跟着玩家走 ⇒
		 *   候选集跟着玩家走 ⇒ 每一拍都在重烘（实机：2088 ↔ 1846 格候选、每拍 建块 1–3 次）。
		 *   **半份扫描绝不能进签名**：签名一变就是"边走边建块"，这正是方案 A 要根除的东西。
		 *
		 * <p>★ 但"没看清"只是一条**提示**，不能反过来当"看清了 ⇒ 这一份多少都算数"的许可证：
		 *   同样是 {@code loaded} 为真的一拍，视距一变就能少掉几百格（实机 1713 ↔ 1447）。所以
		 *   采纳的判据是 {@link #scanAcceptedCells} 的"只许长 + 变小逐格证实"，{@code loaded} 只在
		 *   拒缩那行诊断里报一下。</p>
		 *
		 * <p>注意这里**不是**"有一个区块没加载就永远不扫这条站台"：那会让包围盒比视距还大的长站台
		 * 一格都画不出来（放宽 ±16 之后包围盒比站台本身大得多）——这里只负责"没全就别当成全"。</p>
		 */
		boolean anyMissing = false;
		final List<WorldChunk> chunks = new ArrayList<>((maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1));
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				final WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk) {
					// 视距外：这一块这一拍看不到。loaded 记假，但**照样继续收别的块** ——
					// "一份全不全、能不能采纳"由 {@link #scanAcceptedCells} 判（只许长；变小逐格证实）。
					// 占位 null 是为了让第二段按坐标顺序取值不错位。
					anyMissing = true;
					chunks.add(null);
					continue;
				}
				chunks.add(chunk);
			}
		}
		box.loaded = !anyMissing;
		// ② 逐段逐格收（区块都已经确认是真区块了，这里只会跳过"这一段没有标记格"）
		//    区块的"认领"不在这里做：它必须在扫之前就完成（且不管扫没扫全），见 claimBoxChunks
		int index = 0;
		for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
			for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
				final WorldChunk chunk = chunks.get(index++);
				if (chunk == null) {
					continue;   // 视距外那一块：这一拍看不到它的格（box.loaded 已记假）
				}
				final int minSection = Math.max(0, ChunkSectionPos.getSectionCoord(box.minY) - ChunkSectionPos.getSectionCoord(world.getBottomY()));
				final int maxSection = Math.min(chunk.getSectionArray().length - 1,
					ChunkSectionPos.getSectionCoord(box.maxY) - ChunkSectionPos.getSectionCoord(world.getBottomY()));
				for (int sectionIndex = minSection; sectionIndex <= maxSection; sectionIndex++) {
					final int baseY = world.getBottomY() + sectionIndex * 16;
					// 纵向不与包围盒相交的段整段跳过：站台面平坦时绝大多数段（地下、空中）都走这一条，
					// 比逐格判 y 便宜得多
					if (baseY + 15 < box.minY || baseY > box.maxY) {
						continue;
					}
					final ChunkSection section = chunk.getSectionArray()[sectionIndex];
					if (section == null || section.isEmpty()) {
						continue;
					}
					if (!section.hasAny(rawState -> BlockCrowdMarker.kindOf(
						new org.mtr.mapping.holder.BlockState(rawState)) != null)) {
						continue;
					}
					for (int y = 0; y < 16; y++) {
						final int worldY = baseY + y;
						if (worldY < box.minY || worldY > box.maxY) {
							continue;
						}
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								final BlockCrowdMarker.Kind kind = BlockCrowdMarker.kindOf(
									new org.mtr.mapping.holder.BlockState(section.getBlockState(x, y, z)));
								if (kind == null) {
									continue;
								}
								final int worldX = (chunkX << 4) + x;
								final int worldZ = (chunkZ << 4) + z;
								if (!box.contains(worldX, worldY, worldZ)) {
									continue;
								}
								final String key = groupKeyAt(worldX, worldY, worldZ);
								// 镜像暂时问不到站台时先挂在这一条自己的 key 下（不是孤儿组）：这一条扫全了，
								// 这些格就是它的候选格，只是"归属待定"。镜像一到，下一次扫全时就换回站台 key。
								final String groupKey = key.startsWith("?") ? box.key : key;
								cells.add(new Cell(worldX, worldY, worldZ, kind));
							}
						}
					}
				}
			}
		}
		/*
		 * ★ 如实返回（2026-10-13 修）：这里原来是写死的 {@code return true;} —— 调用方拿到的永远是
		 *   "这一拍整条都看清了"，于是"没看清的那一拍不许采纳缩小的份"那条判据**从来没有生效过**
		 *   （实机日志 `扫全 1/1` 每一拍都满、而候选格在两个值之间来回跳，就是这个来源）。
		 *   方法注释第一句写的"返回值 = 这一拍整条都看清了"现在才是真的。
		 *
		 * <p>注意它**不是**采纳判据（那一条已改成"只许长 + 变小逐格证实"，见 {@link #shrinkProven}）：
		 * 它只说明"这一拍有没有区块没看到"，用于日志与拒缩诊断。
		 */
		return !anyMissing;
	}

	/**
	 * 兜底：把"取不到站台"的标记格收进 `"?<块坐标>"` 组（客量按 0，**永远不画**）。
	 *
	 * <p>为什么不能直接丢掉：镜像还没同步的那几秒里，站台上的格子全都取不到站台。丢掉的话
	 * "镜像一到 ⇒ 整批重建"（那一拍所有组的格数都变了）。收着它们，签名就稳定，镜像一到只是
	 * 这些格子**换了组**（一次可预期的一次性代价）；镜像一直不到时它们挂在 0 客量的组里，
	 * 一格都不画，但也不假装有站台。</p>
	 *
	 * <p>范围限制在玩家周围 {@link #ORPHAN_SCAN_RADIUS} 区块：这是"镜像延迟/没有站台的轨道旁"这种
	 * 边缘情况的兜底，不是为了把整片世界当孤儿扫一遍。</p>
	 *
	 * <p>★ **凡是落在已发现站台包围盒里的区块一律跳过**（{@link #claimBoxChunks} 在 {@link #scan} 开头
	 * 就把所有包围盒的区块记进 {@code claimedChunks}，**不管那一条这一拍扫没扫全**）。为什么这一条要
	 * 跟"扫全没扫全"脱钩：站台没扫全时它的格这一拍不发布（{@link #scan} 沿用上一拍的组），要是孤儿路径
	 * 还能进它的包围盒，那些格就会**在站台组与 `"?…"` 组之间随扫没扫全来回跳** —— 两边签名都跟着抖，
	 * 而且"孤儿组"本来是永不建块的，却因此每 5 秒把 {@link #DIRTY_GROUPS} 搅一遍。</p>
	 *
	 * <p>★ 收进来的格**一律挂孤儿 key**（{@link #orphanKey}），**绝不用 {@link #groupKeyAt} 往真站台组里塞**
	 * （2026-10-13 修，原来是"这一格问得到站台就挂那个站台"）：这个窗口**跟着玩家位置走**，
	 * 一旦某些格被判给某条站台，那条站台的候选集就会随走位变 —— 那是合同 §2 / §6.7
	 * "候选格集合必须与玩家位置无关"的直接违例（也正是本文件反复在堵的那一族病）。
	 * 跳过包围盒 + 只挂孤儿 key 之后：**站台的候选集只由它自己的包围盒扫描
	 * （{@link #scanPlatform}，与玩家在哪无关）决定**，孤儿组只负责**包围盒外**那点兜底
	 * —— 它随玩家半径变，但永不建块（{@link #worthBuilding} 丢），也进不了任何真站台的组，
	 * 所以不影响"走动 0 建块"。代价只是这些格"不画"（它们照旧进候选格读数）。</p>
	 */
	private static void scanOrphans(ClientWorld world, BlockPos center, Map<String, List<Cell>> byGroup, Set<Long> claimedChunks) {
		final int baseChunkX = ChunkSectionPos.getSectionCoord(center.getX()) - ORPHAN_SCAN_RADIUS;
		final int baseChunkZ = ChunkSectionPos.getSectionCoord(center.getZ()) - ORPHAN_SCAN_RADIUS;
		final int span = ORPHAN_SCAN_RADIUS * 2 + 1;
		for (int chunkX = baseChunkX; chunkX < baseChunkX + span; chunkX++) {
			for (int chunkZ = baseChunkZ; chunkZ < baseChunkZ + span; chunkZ++) {
				if (claimedChunks.contains(chunkKey(chunkX, chunkZ))) {
					continue;   // 落在这条站台的包围盒里 ⇒ 归它自己的扫描管（哪怕这一拍没扫全），绝不进孤儿组
				}
				final WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				if (chunk == null || chunk instanceof net.minecraft.world.chunk.EmptyChunk) {
					continue;
				}
				for (int sectionIndex = 0; sectionIndex < chunk.getSectionArray().length; sectionIndex++) {
					final ChunkSection section = chunk.getSectionArray()[sectionIndex];
					if (section == null || section.isEmpty()) {
						continue;
					}
					if (!section.hasAny(rawState -> BlockCrowdMarker.kindOf(
						new org.mtr.mapping.holder.BlockState(rawState)) != null)) {
						continue;
					}
					final int baseY = world.getBottomY() + sectionIndex * 16;
					for (int y = 0; y < 16; y++) {
						for (int x = 0; x < 16; x++) {
							for (int z = 0; z < 16; z++) {
								final BlockCrowdMarker.Kind kind = BlockCrowdMarker.kindOf(
									new org.mtr.mapping.holder.BlockState(section.getBlockState(x, y, z)));
								if (kind == null) {
									continue;
								}
								final int worldX = (chunkX << 4) + x;
								final int worldY = baseY + y;
								final int worldZ = (chunkZ << 4) + z;
								/*
								 * ★ 一律挂**孤儿 key**（`?<块坐标>`），**绝不用 {@link #groupKeyAt} 塞给真站台**
								 * （2026-10-13 修）。
								 *
								 * <p>为什么：这个窗口是"玩家周围 {@link #ORPHAN_SCAN_RADIUS} 区块"，它**跟着玩家
								 * 位置走**。用 {@code groupKeyAt} 的话，"玩家窗口里、恰好离某条站台中轴
								 * ≤ {@code MmtrCrowdPlatformLevels.MAX_HORIZONTAL_BLOCKS} 格"的那些格会被塞进
								 * **那条真站台的组**里 —— 于是那条站台的候选集随走位多几格、少几格，签名跟着变，
								 * 走两步就重烘一次：这是"候选格集合必须与玩家位置无关"（见类注释与
								 * {@link #scan}）的**直接违例**。站台的候选格只有一个来源 —— 它自己那个
								 * **与玩家在哪无关**的包围盒扫描（{@link #scanPlatform}）。</p>
								 *
								 * <p>代价可控，所以不必心疼：孤儿组本来就被 {@link #worthBuilding} 丢掉、**永不建块**
								 * （客量恒 0，见 {@link #refreshLevel}），所以这些格只是"不画"而已，不会变成白烘；
								 * 它们照旧进读数（候选格数），不会被悄悄吞掉。</p>
								 */
								byGroup.computeIfAbsent(orphanKey(worldX, worldZ), ignored -> new ArrayList<>())
									.add(new Cell(worldX, worldY, worldZ, kind));
							}
						}
					}
				}
			}
		}
	}

	/**
	 * 一格的**组 key**：{@link MmtrCrowdPlatformLevels#platformAt} 返回的站台 {@code getHexId()}；
	 * 取不到站台时用 {@code "?" + 块坐标}（块 = 32×32 格，一个"没有归属的角落"）。
	 *
	 * <p>用 hex 而不是 {@code Platform} 对象当 key：镜像每次下发可能换对象，同一格必须每次都算出同一个 key
	 * （签名/缓存全靠它）。块坐标进 key 是为了让孤儿格**按空间分片**，一个角落的变动不会把别的角落也标脏。</p>
	 */
	private static String groupKeyAt(int x, int y, int z) {
		final Platform platform;
		try {
			platform = MmtrCrowdPlatformLevels.platformAt(x, y, z);
		} catch (RuntimeException exception) {
			// 静态数据流可能在别的线程改 platforms（那一侧的文件不归这里管）；渲染线程不能因此被打掉。
			if (!warnedLevelLookup) {
				warnedLevelLookup = true;
				System.out.println("[MMTR-CROWD] 读站台归属失败（这些格这一拍按'无站台'算，只报一次）：" + exception);
			}
			return orphanKey(x, z);
		}
		return platform == null ? orphanKey(x, z) : platform.getHexId();
	}

	private static String orphanKey(int x, int z) {
		return "?" + (x >> TILE_SHIFT) + "," + (z >> TILE_SHIFT);
	}

	/** 32×32 格的块坐标（只用来做"孤儿格分片"、发现时的分桶与跨块轮取，不再是批的单位）。 */
	private static final int TILE_SHIFT = 5;

	/**
	 * 同一格里同一格坐标只留一格（kind 也一样：一个位置只有一个方块）。
	 *
	 * <p>为什么要去重：站台的包围盒放宽了 ±16 格，相邻站台/同一站的两条站台会互相重叠，同一格可能被
	 * 收两遍。收两遍的后果不是"多画一个人"那么简单 —— 签名与格数都会被算多，画出来还会**重叠成
	 * z-fighting**。排序后只比相邻即可（{@link PlatformGroup} 里本来就要排一次空间序）。</p>
	 */
	private static List<Cell> dedupe(List<Cell> cells) {
		cells.sort((a, b) -> {
			if (a.x != b.x) {
				return Integer.compare(a.x, b.x);
			}
			return a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.y, b.y);
		});
		final List<Cell> out = new ArrayList<>(cells.size());
		Cell previous = null;
		for (final Cell cell : cells) {
			if (previous != null && previous.x == cell.x && previous.y == cell.y && previous.z == cell.z) {
				continue;
			}
			out.add(cell);
			previous = cell;
		}
		return out;
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
	}

	/** 发现粗扫的分桶 key：32×32×32 格一个桶（只回答"这一带有没有标记格"）。 */
	private static long bucketKey(int x, int y, int z) {
		return ((long) (x >> TILE_SHIFT) << 42) ^ ((long) (y >> TILE_SHIFT) << 21) ^ (z >> TILE_SHIFT);
	}

	/**
	 * 一个水平点到站台中轴的横向距离**小于这个数**（格）就算"正好压在中轴上"：这时 {@code v} 的方向
	 * 是数值噪声（一格宽 1 格、格心与轴上点的差可以是 0.0001），所以退化取中轴的一个垂直方向
	 * （符号由该格哈希定，见 {@link PlatformAxis#yawBucketAt}）。
	 */
	private static final double MIN_AXIS_DISTANCE = 0.25;

	/**
	 * 站台的**中轴**（两端点连线的水平投影）：站定格"垂直站台、面向轨道"的唯一依据。
	 *
	 * <p><b>为什么用站台自己的中轴，而不是方块状态里的朝向</b>：{@code BlockCrowdMarker} 的
	 * {@code facing} 属性**只有流动用**（{@code BlockCrowdMarker.java:146} 自己写着"只有「流动」用朝向
	 * （走道轴向）；其余三种存着朝向但不读"）—— 它记的是铺标记那一刻玩家的朝向，与"轨道在哪边"无关。
	 * 拿它当"面向轨道"的判据，等于把当年铺标记的那位玩家站的方向当成轨道方向：铺错一次，整片站台
	 * 全体站反，而且方块状态里存着朝向会让人误以为它是权威的。轨道就在站台中轴上
	 * （{@link Platform#mmtrOrderedPositions()} 的两个端点就是这条站台两头的轨旁点），所以
	 * "格心到中轴的横向向量 v"是**站台几何自己给出来**的：与谁铺的、怎么铺的、方块状态里写的是什么
	 * 都无关。</p>
	 *
	 * <p><b>为什么整个 {@link PlatformGroup} 共用一个轴</b>：一组的 key 就是站台 hex（{@link #scan}
	 * 把一条站台包围盒里扫到的格全挂在它自己的 key 下），所以这一组就是"这条站台的候选格"，中轴只有一根。
	 * 反过来逐格去问"离我最近的是哪条站台"，会把贴着两条站台交界的那几格算到隔壁去 —— 而朝向是烘进顶点
	 * 的静态姿势、又**不进签名**（见 {@link PlatformGroup} 的签名说明），拿错轴就会一直错到这一组
	 * 因为别的原因重烘为止。</p>
	 */
	private static final class PlatformAxis {

		/** 中轴起点 {@code A}（水平坐标，格）。 */
		private final double ax;
		private final double az;
		/** 中轴方向 {@code d = B − A}（未归一化）与它的长度平方。 */
		private final double dx;
		private final double dz;
		private final double lengthSquared;

		PlatformAxis(Position a, Position b) {
			ax = a.getX();
			az = a.getZ();
			dx = b.getX() - ax;
			dz = b.getZ() - az;
			lengthSquared = dx * dx + dz * dz;
		}

		/** 中轴可用吗：两端点重合的站台退化成一点、没有方向，"垂直站台"就无从谈起 ⇒ 当作没有中轴。 */
		boolean usable() {
			return lengthSquared > 1.0E-6;
		}

		/**
		 * 一个水平点 → **身体档**（0–15，22.5° 一档，0 = 北，见 {@link #YAW_POSITIONS}）。
		 *
		 * <p>三步，只有加减乘除与一次 {@code sqrt}，**没有任何随机源** —— 同一个格子每次算出来都一样
		 * （朝向不进签名，所以"重烘一次换了个方向"必须从算法上排除）。</p>
		 * <ol>
		 *   <li><b>中轴上的最近点</b>：{@code Q = A + t·d}，{@code t} 夹在 {@code [0,1]} ——
		 *       站台尽头的格不会被投到线段延长线上的无穷远（否则站台两头的人会朝一个奇怪的方向）；</li>
		 *   <li><b>横向向量</b>：{@code v = 格心 − Q}，它指向"这一格在中轴的哪一侧"。人应当**背着 v 站**，
		 *       也就是**朝轨道**：{@code dir = −v}，取水平单位向量；</li>
		 *   <li><b>退化</b>：{@code |v| < }{@link #MIN_AXIS_DISTANCE}（格子正好压在中轴上，见那个常量）
		 *       时方向是噪声（中轴两侧只差零点几格，但符号会跳），于是取中轴的**一个垂直方向**
		 *       {@code (−dz, dx)}，**符号由这一格的哈希定** ⇒ 同一格重烘还是同一个方向，
		 *       人群看起来是"中轴左右两半各朝一边"，而不是随机的散点。</li>
		 * </ol>
		 *
		 * <p><b>dir → 身体档：为什么 {@code θ = 180° − yawDeg}</b>。模型的正面在 **−z**
		 * （{@code tools/crowd-bake/bake_crowd.py:78} 的 {@code "north": (0, 0, -1)}），而表的第 b 档
		 * 把正面转到 {@code (−sin θ, 0, −cos θ)}、{@code θ = 22.5°·b}（{@link #buildTables} 里
		 * {@code x' = x·cosθ + z·sinθ}、{@code z' = −x·sinθ + z·cosθ} 作用在 {@code (0,0,−1)} 上的结果）。
		 * 要让正面指向 {@code dir = (dx, dz)}，就是解 {@code sin θ = −dx}、{@code cos θ = −dz}。
		 * 另一头，按 MC 的实体偏航约定（{@code 0 = 南/+z}、{@code 90 = 西/−x}、{@code 180 = 北/−z}、
		 * {@code 270 = 东/+x}），"能让实体面朝 dir"的偏航角是 {@code yawDeg = atan2(−dx, dz)}
		 * —— 两式差的就是那半个圈（{@code θ} 的分母是 {@code −dz}），所以 {@code θ = 180° − yawDeg}。
		 * 四个正方向代进去核对（"人正面朝哪儿"写在括号里）：
		 * 南 {@code dir=(0,1)} ⇒ yawDeg 0 ⇒ θ 180 ⇒ 档 8；
		 * 东 {@code dir=(1,0)} ⇒ yawDeg −90 ⇒ θ 270 ⇒ 档 12；
		 * 北 {@code dir=(0,−1)} ⇒ yawDeg 180 ⇒ θ 0 ⇒ 档 0；
		 * 西 {@code dir=(−1,0)} ⇒ yawDeg 90 ⇒ θ 90 ⇒ 档 4。
		 * 档 0 的正面是 {@code (−sin0, −cos0) = (0,−1)} = 北、档 4 = {@code (−1,0)} = 西、
		 * 档 8 = {@code (0,1)} = 南、档 12 = {@code (1,0)} = 东 —— 与要求的方向逐一对上。</p>
		 */
		int yawBucketAt(double px, double pz, long hash) {
			// ① 中轴上的最近点（t 夹到 [0,1]）
			double t = ((px - ax) * dx + (pz - az) * dz) / lengthSquared;
			t = t < 0 ? 0 : Math.min(t, 1);
			final double qx = ax + dx * t;
			final double qz = az + dz * t;
			// ② 横向向量 v = 格心 − 最近点；人背着 v 站 ⇒ dir = −v
			double dirX = qx - px;
			double dirZ = qz - pz;
			final double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
			if (length < MIN_AXIS_DISTANCE) {
				// ③ 正好压在中轴上：取中轴的一个垂直方向 (−dz, dx)，符号由这一格的哈希定
				final double sign = (hash & 1L) == 0L ? 1.0 : -1.0;
				final double inverse = sign / Math.sqrt(lengthSquared);
				dirX = -dz * inverse;
				dirZ = dx * inverse;
			} else {
				dirX /= length;
				dirZ /= length;
			}
			final double yawDegrees = Math.toDegrees(Math.atan2(-dirX, dirZ));
			// 档 = θ / 22.5 四舍五入后夹进 0–15（floorMod 兜住 θ = 360 与负角）
			return Math.floorMod(Math.round((180.0 - yawDegrees) / 22.5), 16);
		}
	}

	/**
	 * 这一组的**站台中轴**：拿组里的格问 {@link MmtrCrowdPlatformLevels#platformAt}，
	 * 报出来的站台 hex 与组 key 对上时，就用它 {@link Platform#mmtrOrderedPositions()} 的两个端点连线。
	 * 取不到 ⇒ **null**（调用方退回"每格随机 22.5° 档"，见 {@link #spot}）。
	 *
	 * <p>为什么要求 hex 对上：一组的格是"在它包围盒里的所有标记格"（{@link #scanPlatform}），
	 * 贴上两条站台交界的那几格，{@code platformAt}（按"最近的站台"判）报的可能是隔壁那条。
	 * 朝向烘进顶点、又不进签名，**拿错轴会一直错到这一组重烘**，所以这里宁可要求"这一格报出来的就是
	 * 这一组这条站台"，对不上就换下一格探；都探不上就返回 null —— **不瞎猜**（孤儿组、镜像还没同步的
	 * 那几拍本来就没有轨道信息，随机档比一个错方向好看）。</p>
	 *
	 * <p>为什么逐格探到命中为止，而不像 {@link #refreshLevel} 那样只探 8 格：归属查询的横向容差只有
	 * {@code MmtrCrowdPlatformLevels.MAX_HORIZONTAL_BLOCKS = 6} 格，而标记格可以铺到离中轴十几格
	 * （服务端往站台里铺 {@code MARKER_WINDOW = 12} 格，见 {@code MmtrCrowdModule.sweepMarkers}）。
	 * 固定探 8 格时探到的可能**全是**"离轴 6 格以外"的那些格 ⇒ 整组找不到中轴 ⇒ 整片站台退回随机朝向
	 * —— 那等于让这一条按"站台有多宽"随机失效，正是本次要修的毛病。走完整个列表的代价有上界
	 * （一组最多一千多格 × 二十几条站台的距离比较），而且**一组一次**（只有第一次扫全、或候选格真的变了
	 * 才建组，见 {@link #prepareGroups}），与建块本身（几十毫秒起）比可以忽略；命中就停，
	 * 所以常态是第一格。</p>
	 */
	private static PlatformAxis platformAxisFor(String key, List<Cell> cells) {
		if (key.startsWith("?")) {
			return null;   // 孤儿组：本来就没有站台，不必花几千次归属查询去确认这件事
		}
		for (final Cell cell : cells) {
			try {
				final Platform platform = MmtrCrowdPlatformLevels.platformAt(cell.x, cell.y, cell.z);
				if (platform != null && key.equals(platform.getHexId())) {
					final Position[] ends = platform.mmtrOrderedPositions();
					final PlatformAxis axis = new PlatformAxis(ends[0], ends[1]);
					if (axis.usable()) {
						return axis;
					}
				}
			} catch (RuntimeException exception) {
				// 静态数据流可能在别的线程改 platforms（那一侧的文件不归这里管）；渲染线程不能因此被打掉。
				if (!warnedLevelLookup) {
					warnedLevelLookup = true;
					System.out.println("[MMTR-CROWD] 读站台中轴失败（这一组按随机朝向烘，只报一次）：" + exception);
				}
			}
		}
		return null;
	}

	/**
	 * 一格 → 一个候选落点：位置（格中心 + 水平抖动）、头档与外观**全部由格子坐标哈希**给
	 * （同一格每次算出来都是同一个人、同一个站姿）；**唯一的例外是站定格的身体朝向** —— 它由
	 * {@link PlatformAxis 站台中轴}给，因为"站台在哪边、轨道在哪边"是站台自己的几何事实，哈希给不出。
	 *
	 * <p>四个哈希都用同一套 {@link #mix}，但**各自异或一个独立的盐**（{@link #YAW_SALT} / {@link #HEAD_SALT} /
	 * {@link #JITTER_SALT_X} / {@link #JITTER_SALT_Z}）：不加盐的话，相邻的 x/z 会让下面那几个
	 * {@code >>> n} 的位段**互相相关**（"朝哪边"和"往哪偏"绑在一起，人群会出现成片的斜向条纹）。
	 * 盐本身是常量，所以确定性一点没变：重烘一次，同一格还是同一个人。</p>
	 *
	 * @param axis 这一组的站台中轴（{@link #platformAxisFor}）；取不到站台时为 null
	 */
	private static Spot spot(int x, int y, int z, BlockCrowdMarker.Kind kind, PlatformAxis axis) {
		final long hash = mix(x * 0x9E3779B97F4A7C15L ^ y * 0xBF58476D1CE4E5B9L ^ z * 0x94D049BB133111EBL);
		/*
		 * 头部档：正前 / 左 30° / 右 30° / 低头 四选一（角度见 HEAD_YAW_DEGREES 与
		 * HEAD_PITCH_DEGREES_PER_VARIANT，建表时算好）。与朝向同一个理由：必须由格子哈希定 ——
		 * 头一旦随"这一拍"变，人就是跳的。
		 */
		final int headVariant = (int) Math.floorMod(mix(hash ^ HEAD_SALT), HEAD_VARIANTS);
		/*
		 * 水平抖动：让一排人不再像"格子点阵"。
		 *
		 * <p>{@code hash01} = 把 53 位有效位压到 [0,1)，再 {@code (hash01 - 0.5) × 0.56} ⇒ ±0.28 格，
		 * x/z 各用**自己的盐**独立取一次。0.28 是量出来的上限：模型 x/z 半宽 0.25 格（见 mesh 里 ±4.05/16），
		 * 0.28 时人整体还在这一格的中心 ±0.53 之内，不会伸进邻格压到别人身上，也不会看起来"站在方块缝里"。</p>
		 */
		final double jitterX = (hash01(mix(hash ^ JITTER_SALT_X)) - 0.5) * 0.56;
		final double jitterZ = (hash01(mix(hash ^ JITTER_SALT_Z)) - 0.5) * 0.56;
		/*
		 * 身体档（顶点表第一维）分两种情况：
		 *   ① **站定格**（GRADE_1 / GRADE_2）⇒ 由站台中轴解出"面向轨道"那一档（中轴见 PlatformAxis），
		 *      于是整片站台的人齐齐垂直站台、面向轨道；
		 *   ② **流动格**（FLOW）⇒ 保持原来的"每格随机 22.5° 档"：走动的人本来就该各走各的，
		 *      整齐反而像仪仗队（这一条是硬要求，别"顺手"把它也改成朝轨道）。
		 * 取不到站台（axis == null：孤儿组 / 镜像还没同步）⇒ 站定格也退回②的随机档，**不瞎猜朝向**
		 * （猜错比随机更难看，而且日志里的"孤儿组"读数会变得无法解释）。
		 *
		 * <p>两种情况都只用"坐标 + 静态数据"，没有随机源：同一格重烘一次还是同一个人同一个朝向；
		 * 也因此**都进不了候选格签名**（签名只有位置 + kind，见 PlatformGroup）。</p>
		 */
		final float yaw = kindOfFlow(kind) || axis == null
			? 22.5F * (int) Math.floorMod(mix(hash ^ YAW_SALT), 16)
			: 22.5F * axis.yawBucketAt(x + 0.5, z + 0.5, hash);
		return new Spot(x + 0.5 + jitterX, y, z + 0.5 + jitterZ, yaw, headVariant,
			skinTiles[(int) Math.floorMod(hash, skinTiles.length)],
			typeTiles[(int) Math.floorMod(hash >>> 13, typeTiles.length)],
			professionTiles[(int) Math.floorMod(hash >>> 27, professionTiles.length)],
			levelTiles[(int) Math.floorMod(hash >>> 41, levelTiles.length)],
			kind);
	}

	/**
	 * 朝向用的盐：与外观分开，免得"朝哪边"和"穿什么"绑在一起（0 = 北，北→西→南→东，22.5° 一档）。
	 *
	 * <p>它现在只喂两种格子：**流动格**（{@code FLOW}）与**取不到站台的站定格**（孤儿组 / 镜像还没同步）
	 * —— 站定格有中轴时朝向由 {@link PlatformAxis#yawBucketAt} 给，不吃这个盐。</p>
	 */
	private static final long YAW_SALT = 0x59A1F3C7D2B4E681L;
	/**
	 * 头部档（四选一：正前 / 左 30° / 右 30° / 低头）用的盐（{@link #spot}）。
	 *
	 * <p>为什么单独一个盐：它管的是**头相对身体**的四种姿势，与"身体朝哪儿"（站定格由中轴给、
	 * 流动格由 {@link #YAW_SALT} 给）、"往哪偏"（{@link #JITTER_SALT_X} / {@link #JITTER_SALT_Z}）、
	 * "穿什么"（直接用 {@code hash}）都必须互不相关 —— 否则"低头的人正好都朝一个方向"这种成片的
	 * 相关会一眼看出来。这一档从 3 档扩到 {@link #HEAD_VARIANTS} 档时，盐的值同时换了一次：
	 * 旧的排列是"三档共有低头"，新的是"四档各一种姿势"，换盐只是把新旧分布彻底切开，**不是**引入
	 * 随机源（盐是常量 ⇒ 同一格重烘还是同一档）。</p>
	 */
	private static final long HEAD_SALT = 0xB7E151628AED2A6BL;
	/** 水平抖动 x 分量的盐。 */
	private static final long JITTER_SALT_X = 0xC2B2AE3D27D4EB4FL;
	/** 水平抖动 z 分量的盐（与 x 分开：两个分量必须各自独立，否则人会沿一条对角线抖）。 */
	private static final long JITTER_SALT_Z = 0x27D4EB2F165667C5L;

	/** 64 位哈希 → [0,1) 的浮点（只取高 53 位，double 的有效位；不引入任何随机源）。 */
	private static double hash01(long value) {
		return (value >>> 11) / (double) (1L << 53);
	}

	private static long mix(long value) {
		long z = value + 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	// ------------------------------------------------------------------ VBO 批次（缓存）

	/**
	 * 把整片雕像**烘进 MTR 优化渲染器的 VBO 批次**（2026-10-11 起的主路）。
	 *
	 * <h3>为什么必须烘</h3>
	 * <p>逐帧现写顶点那条路一个人 ≈ 60 µs：8 区块内 1075 个人 = 64 ms/帧，永远做不到"全显示"。
	 * 烘成批次之后每帧只剩**几个 draw call**，人数与帧时间脱钩。</p>
	 *
	 * <h3>怎么分批</h3>
	 * <p>分组 = **归属站台**（{@link PlatformGroup}）；组内每 {@link #BATCH_SPOTS} 格一批，一批可以**跨块**
	 * （世界坐标按这一批自己的原点记）。理由是**粒度**：块的候选格约 91 个，按块排批时客量 10% 也会
	 * 每块画满 32 人；按整条站台排批，10% 就是"4 批 / 128 人"，与客量线性。</p>
	 *
	 * <h3>什么时候重烘（只剩两种）</h3>
	 * <p>扫描（每 100 tick）之后按组比**候选格签名**（每格的 x,y,z + kind 混一遍再异或 + 格数），
	 * 只有 ①第一次扫全、②候选格列表真的变了，才标脏。**客量变化、玩家走动都不触发**；
	 * 每帧最多花 {@link #GROUP_BUILD_BUDGET_MILLIS} 建，一组由若干批拼成，**拼完才发布**。</p>
	 */

	/** 常驻的**站台组**上限（约 24 条站台；被回收后再进来会重建一次，这是一次性代价）。 */
	private static final int MAX_KEPT_GROUPS = 24;

	/**
	 * 与钢轨那条路同一套顶点映射（{@code MmtrRailMeshCache}）：位置/uv/法线/光照逐顶点，
	 * 颜色与 ModelMat 每 draw 一个常量。
	 */
	private static final VertexAttributeMapping MAPPING = new VertexAttributeMapping.Builder()
		.set(VertexAttributeType.POSITION, VertexAttributeSource.VERTEX_BUFFER)
		.set(VertexAttributeType.COLOR, VertexAttributeSource.GLOBAL)
		.set(VertexAttributeType.UV_TEXTURE, VertexAttributeSource.VERTEX_BUFFER)
		.set(VertexAttributeType.UV_OVERLAY, VertexAttributeSource.GLOBAL)
		.set(VertexAttributeType.UV_LIGHTMAP, VertexAttributeSource.VERTEX_BUFFER)
		.set(VertexAttributeType.NORMAL, VertexAttributeSource.VERTEX_BUFFER)
		.set(VertexAttributeType.MATRIX_MODEL, VertexAttributeSource.GLOBAL)
		.build();

	/**
	 * **一批多少格**（一批 = 一个 {@code OptimizedModel}，= 客量截取的最小单位）。
	 *
	 * <p>一条站台的人 = "整条站台均衡序里的前 n 个候选格"，批是这条前缀的整数倍刻度：
	 * {@code ceil(n / 32)} 批。所以一批必须**整批烘好**（一个人的顶点不许跨批），而一批 32 人 ≈ 8.6 万顶点
	 * —— 索引是 32 位（`RawMesh.upload` 里 `sipush 5125` = GL_UNSIGNED_INT，2026-10-12 在字节码里确认），
	 * 没有 65535 那个上限；唯一要求是**上传前必须 triangulate()**（索引缓冲按"每面 3 个下标"分配）。</p>
	 */
	private static final int BATCH_SPOTS = 32;

	/**
	 * 一**批**烘好的东西：一个 {@code OptimizedModel} + **它自己的原点**（该批格子的最小角）。
	 *
	 * <p>为什么原点按批记：一批可以跨块（合同 §2 的修正），批里格子的世界坐标可能横跨几十格，
	 * 但每批的原点都是"自己那些格子的最小角" ⇒ 顶点存相对原点的 float 仍然只有几十的绝对值，
	 * 精度足够；建块与绘制用**同一个原点**（这里这个字段），所以不可能对不上。</p>
	 */
	private static final class BuiltBatch {

		final OptimizedModelWrapper model;
		final double originX;
		final double originY;
		final double originZ;

		BuiltBatch(OptimizedModelWrapper model, double originX, double originY, double originZ) {
			this.model = model;
			this.originX = originX;
			this.originY = originY;
			this.originZ = originZ;
		}
	}

	/**
	 * 一**组**烘好的东西（组 = 归属站台；取不到站台的格是 `"?<块坐标>"` 组）。
	 *
	 * <p>保留签名（建块的唯一判据）与 {@code lastSeenScan}（回收判据：连续两拍没看见才释放，
	 * 与 415 §15.2 那条"来回走动不反复重烘"同一个修法）。</p>
	 */
	private static final class GroupBatch {

		/** 批序：models.get(b) = 这一组均衡序里第 [b×32, (b+1)×32) 个候选格。 */
		final List<BuiltBatch> batches;
		final int spots;
		final long signature;
		/** 最后一次"被扫描看见"的扫描轮次（回收判据：连续两拍没看见才释放）。 */
		int lastSeenScan;

		GroupBatch(List<BuiltBatch> batches, int spots, long signature) {
			this.batches = batches;
			this.spots = spots;
			this.signature = signature;
		}
	}

	private static final Map<String, GroupBatch> BATCHES = new LinkedHashMap<>();

	/**
	 * **一组的候选格**：跨块轮取 → 均衡序 → 站定组在前、流动组在后 ⇒ "前 n 个"这一句才有意义。
	 *
	 * <h3>为什么要跨块轮流取</h3>
	 * <p>合同 §2 的修正：批必须**跨 tile 排**，一条站台一排。跨块轮流取 = "同一组内的块按 key 排序后
	 * 轮流各取一格"：于是每个 32 格的窗口在**块这一层**就已经散布整条站台（不会"先站满半条"）。</p>
	 *
	 * <h3>均衡序（合同 §2 那条）</h3>
	 * <p>再做一遍 {@code 序号 i 取下标 (i × STRIDE) mod 组格数}，{@code STRIDE} 取与格数互质的常数
	 * （φ = 0.618… 那一档再往上调到互质 —— 低差异序列），于是**任意前缀**在组内均匀散布，
	 * 而不是"前 32 个正好是前两个块"。站定组与流动组各自编号（两组的格数不同），再拼成"站定组 + 流动组"。</p>
	 *
	 * <h3>为什么站定组排前面</h3>
	 * <p>画的就是 {@code 前 n = nStatic + nFlow 个}：只有**站定格先、流动格后**，这个前缀才正好等于
	 * "前 nStatic 个站定格 + 前 nFlow 个流动格"。反过来的话前缀里会塞进一大堆流动格而站定格不够
	 * （合同 §2 的 {@code nFlow = min(F / 10, …)} 就形同虚设）。</p>
	 *
	 * <h3>锚点格（ANCHOR）照扫、照算、但**不站人**</h3>
	 * <p>{@link BlockCrowdMarker.Kind#ANCHOR} 的语义是"归属锚"：它声明这一片标记区归这条站台，
	 * 而它自己铺在站台方块那一排上 —— 那上面本来就不该站人。所以它**进签名、进 {@link #cellCount}、
	 * 进 {@link #anchorCount}（读数与归属都要用）**，但**不进 {@link #spots}**：{@code Spot} 只由非锚格造。
	 * 于是"落点从哪来"与"这一片归谁"两件事分开，而 {@code n = nStatic + nFlow} 的口径照旧自洽
	 * —— {@link #personCount} 用的 {@link #standingCount} 就是**非锚**站定格数（见构造函数）。</p>
	 * <p>为什么不干脆在扫描时就丢掉锚点格：归属查询（{@link #refreshLevel} 的探针）、
	 * `候选格/可画` 的读数、以及"这一组的候选格列表变了没有"都靠它。丢掉它就得另开一条账，
	 * 而且"锚点格的亮度/位置变了"这类诊断也就没了。</p>
	 *
	 * <h3>签名（建块唯一的判据）</h3>
	 * <p>把这一组的格子**按位置排序后**逐格混 (x,y,z,kind) 再异或 + 格数：与扫描顺序无关，
	 * 所以"同一批格子换了个扫描顺序"不会误判成变化；而**不含朝向/外观/头档/抖动/光**
	 * （那些都是坐标（+ 静态数据）的纯函数，或压根不该触发重建）、**不含客量、不含玩家位置** ——
	 * 走动 60 秒签名就该一模一样。</p>
	 *
	 * <h3>身体朝向：站定格"面向轨道"</h3>
	 * <p>构造时先解出这一组的**站台中轴**（{@link #platformAxisFor}），再逐格把格心投到中轴上算横向向量
	 * （{@link PlatformAxis#yawBucketAt}）：站定格背对中轴站 = 面向轨道；流动格不吃这个轴（照旧哈希随机）。
	 * 中轴取不到 ⇒ 全体退回哈希随机档。三种情况都不进签名，所以"改朝向"永远不会变成"重建"。</p>
	 */
	private static final class PlatformGroup {

		final String key;
		/** 均衡序的候选落点（站定组在前、流动组在后）。**已截到"画得到"的那一段**，见构造函数。 */
		final List<Spot> spots;
		/** 扫到的候选格总数（含锚点格 + "任何客量都画不到"的那部分流动格）：只用于读数与签名之外的诊断。 */
		final int cellCount;
		/** **非锚**站定格数（GRADE_1 / GRADE_2）。{@link #personCount} 的 nStatic 用它。 */
		final int standingCount;
		/** 流动格数（FLOW）。 */
		final int flowCount;
		/**
		 * 锚点格数（ANCHOR）：**照扫照算，但不站人**（不进 {@link #spots}）。
		 *
		 * <p>留着它是为了读数对账：`候选格` 减掉 `可画` 的差里有一大块就是它，而"这条站台有几个锚"
		 * 是排查"归属对不对"最直接的一个数。它**不影响**人数（{@link #standingCount} 已经把它排除）。</p>
		 */
		final int anchorCount;
		final long signature;
		/** 这一组的客量（0–100；取不到站台 / 孤儿组恒 0）。**只影响画几批，不进签名。** */
		long lvl;
		/** 可见性判据用的包围盒（相对相机最近点由它算）。 */
		final int boundsMinX;
		final int boundsMinZ;
		final int boundsMaxX;
		final int boundsMaxZ;

		PlatformGroup(String key, List<Cell> cells) {
			this.key = key;
			// ① 空间序：x → z → y（一个格只有一个方块，所以这是个**全序**，扫描顺序换不了它）
			cells.sort((a, b) -> {
				if (a.x != b.x) {
					return Integer.compare(a.x, b.x);
				}
				return a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.y, b.y);
			});
			// ② 签名：位置 + kind 混一遍再异或（与扫描顺序无关）+ 格数
			long hash = 1125899906842597L;
			long kindHash = 0;
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minZ = Integer.MAX_VALUE;
			int maxZ = Integer.MIN_VALUE;
			for (final Cell cell : cells) {
				kindHash ^= mix(cell.x * 0x9E3779B97F4A7C15L ^ (long) cell.y * 0xC2B2AE3D27D4EB4FL
					^ (long) cell.z * 0x165667B19E3779F9L ^ (long) cell.kind.ordinal() * 0x27D4EB2F165667C5L);
				minX = Math.min(minX, cell.x);
				maxX = Math.max(maxX, cell.x);
				minZ = Math.min(minZ, cell.z);
				maxZ = Math.max(maxZ, cell.z);
			}
			hash = hash * 1099511628211L + cells.size();
			this.signature = hash ^ kindHash;
			this.boundsMinX = minX;
			this.boundsMaxX = maxX;
			this.boundsMinZ = minZ;
			this.boundsMaxZ = maxZ;
			// ③ 按 kind 拆成"站定 / 流动"，各自跨块轮取 + 均衡序，然后站定组在前
			final List<Cell> standing = new ArrayList<>();
			final List<Cell> flowing = new ArrayList<>();
			int anchors = 0;
			for (final Cell cell : cells) {
				if (kindOfFlow(cell.kind)) {
					flowing.add(cell);
				} else if (cell.kind == BlockCrowdMarker.Kind.ANCHOR) {
					// ★ 锚点格：只记账，不做落点（见类注释"锚点格照扫、照算、但不站人"）
					anchors++;
				} else {
					standing.add(cell);
				}
			}
			this.anchorCount = anchors;
			this.standingCount = standing.size();
			this.flowCount = flowing.size();
			this.cellCount = cells.size();
			/*
			 * ★ 落点从这里开始才是"非锚"的：{@code standing} / {@code flowing} 里已经没有锚点格，
			 * 所以下面两趟就是"站定在前、流动在后"的落点序。均衡序里的下标 i 与
			 * {@link #personCount} 的前 n 个**一一对应**（站定格数 = standingCount = 非锚站定格数）。
			 *
			 * <p>中轴先解一次（{@link #platformAxisFor}），再逐格投影：站定格据此"面向轨道"，
			 * 流动格不吃它（{@link #spot} 里按 kind 分）。取不到 ⇒ null ⇒ 全体随机档。</p>
			 */
			final PlatformAxis axis = platformAxisFor(this.key, cells);
			final List<Spot> ordered = new ArrayList<>(cells.size());
			for (final Cell cell : interleaveAndBalance(standing)) {
				ordered.add(spot(cell.x, cell.y, cell.z, cell.kind, axis));
			}
			for (final Cell cell : interleaveAndBalance(flowing)) {
				ordered.add(spot(cell.x, cell.y, cell.z, cell.kind, axis));
			}
			/*
			 * ★ 只留"画得到"的格：客量前缀最长是 nStatic + nFlow ≤ 站定格 + ⌊流动格/10⌋
			 * （nFlow 的上限就是 ⌊F/10⌋，见 personCount 的 min 那一步），所以流动格的后 F − ⌊F/10⌋ 个
			 * **任何客量都画不到**。烘它们 = 白占显存 + 白花上传：实测一条站台 1044 格里有 337 格是这种，
			 * 33 批里后 10 批永远排不进队列。
			 *
			 * <p>签名仍按**全部** cell 算（见上面 ②，与这里无关）：所以"画不到的那批流动格被挖掉/换了 kind"
			 * 依旧会正确触发一次重烘 —— 宁可多烘一次，也不要在画得到的格变动时还画着旧的。</p>
			 */
			final int drawableMax = this.standingCount + this.flowCount / 10;
			this.spots = ordered.size() <= drawableMax ? ordered : new ArrayList<>(ordered.subList(0, drawableMax));
		}
	}

	private static boolean kindOfFlow(BlockCrowdMarker.Kind kind) {
		return kind == BlockCrowdMarker.Kind.FLOW;
	}

	/**
	 * 跨块轮流取 + 均衡序：先把格按 **32×32 块 key** 分桶并排序，然后"每一轮每桶各取一格"
	 * （桶内按空间序），最后按 {@code 下标 = i × STRIDE mod n} 打散。
	 *
	 * <p>两步都需要：轮取保证"块这一层"散布整条站台（不先站满一头），均衡序保证"格这一层"任意前缀
	 * 均匀散布（不会因为块的大小差异而扎堆）。桶内用空间序，块 key 用"先 x 后 z"排序 —— 都是确定性的，
	 * 所以同一组格子无论扫描顺序如何，算出来的顺序一模一样（签名与顺序无关，但顺序也不能抖，
	 * 否则批的边界会跳）。</p>
	 */
	private static List<Cell> interleaveAndBalance(List<Cell> cells) {
		if (cells.isEmpty()) {
			return new ArrayList<>();
		}
		final Map<Long, List<Cell>> byTile = new LinkedHashMap<>();
		for (final Cell cell : cells) {
			byTile.computeIfAbsent(tileKey(cell.x, cell.z), ignored -> new ArrayList<>()).add(cell);
		}
		final List<Long> tileKeys = new ArrayList<>(byTile.keySet());
		tileKeys.sort((a, b) -> {
			final int ax = (int) (a >> 32);
			final int bx = (int) (b >> 32);
			if (ax != bx) {
				return Integer.compare(ax, bx);
			}
			return Integer.compare((int) (long) a, (int) (long) b);
		});
		final List<Cell> interleaved = new ArrayList<>(cells.size());
		int remaining = cells.size();
		int round = 0;
		while (remaining > 0) {
			for (final Long tile : tileKeys) {
				final List<Cell> bucket = byTile.get(tile);
				if (round < bucket.size()) {
					interleaved.add(bucket.get(round));
					remaining--;
				}
			}
			round++;
		}
		return balanced(interleaved);
	}

	/** 32×32 格块的 key（只用来分组轮取；批不再按它切）。 */
	private static long tileKey(int x, int z) {
		return ((long) (x >> TILE_SHIFT) << 32) ^ ((z >> TILE_SHIFT) & 0xFFFFFFFFL);
	}

	/** 均衡序：{@code out[i] = source[(i × STRIDE) mod n]}（见 {@link PlatformGroup} 的说明）。 */
	private static List<Cell> balanced(List<Cell> source) {
		final int count = source.size();
		final List<Cell> out = new ArrayList<>(count);
		if (count == 0) {
			return out;
		}
		final int stride = balancedStride(count);
		for (int i = 0; i < count; i++) {
			out.add(source.get((int) ((long) i * stride % count)));
		}
		return out;
	}

	/**
	 * 均衡序的步长：取 φ（0.618…）那一档 —— 低差异序列，任意前缀都均匀散布整组而不是"先站满半条"；
	 * 再往上调到与格数**互质**（不互质时 {@code i × STRIDE mod n} 会退化成几个短循环，散布就废了）。
	 */
	private static int balancedStride(int count) {
		if (count <= 2) {
			return 1;
		}
		int stride = (int) Math.round(count * 0.6180339887498949);
		if (stride < 1) {
			stride = 1;
		}
		while (greatestCommonDivisor(stride, count) != 1) {
			stride = stride % count + 1;
		}
		return stride;
	}

	private static int greatestCommonDivisor(int a, int b) {
		while (b != 0) {
			final int next = a % b;
			a = b;
			b = next;
		}
		return a;
	}

	/** 扫描之后标脏的组：待烘的格 + **扫描算出来的那个签名**（建块必须原样存回去，见下）。 */
	private static final class DirtyGroup {

		final List<Spot> spots;
		final long signature;

		DirtyGroup(List<Spot> spots, long signature) {
			this.spots = spots;
			this.signature = signature;
		}
	}

	private static final Map<String, DirtyGroup> DIRTY_GROUPS = new LinkedHashMap<>();
	/** 批次这条路走不通（映射库私有字段取不到 / 优化渲染器关着）⇒ 回退逐帧画。 */
	private static boolean batchUnavailable = false;
	private static int lastGroupBuildMillis = 0;
	private static Field glStateProtectedField;
	private static boolean glStateProtectedFieldResolved = false;

	/** 扫描轮次计数器（组回收用）。 */
	private static int scanCounter = 0;

	/**
	 * 扫描之后：按组比**候选格签名**，把"变了 / 第一次扫全"的组标脏；不再出现的组释放掉。
	 *
	 * <p>签名在 {@link PlatformGroup} 里算好（位置 + kind 的异或 + 格数），这里只做比较 —— 415 §14 踩过
	 * "签名被算了两个版本"（准备那边一个、建块那边又一个）导致**每 5 秒全部重烘**，所以签名必须
	 * 原样带进 {@link DirtyGroup} 再存回 {@link GroupBatch}。</p>
	 *
	 * <p>回收：**不立刻丢**。玩家来回走动时，组会因为"区块暂时没加载（这一拍没扫全）"而暂时不在
	 * 集合里，一丢一烘就是几百毫秒的抖动。所以留 {@link #MAX_KEPT_GROUPS} 组的余量，
	 * 且只有"连续两拍都没看见"才真的释放。</p>
	 */
	private static void prepareGroups() {
		scanCounter++;
		if (batchUnavailable) {
			return;
		}
		for (final PlatformGroup group : groups.values()) {
			final GroupBatch cached = BATCHES.get(group.key);
			if (cached == null || cached.signature != group.signature) {
				// ① 第一次扫全，或 ② 候选格列表真的变了（挖/放/换 kind）
				DIRTY_GROUPS.put(group.key, new DirtyGroup(group.spots, group.signature));
			} else {
				cached.lastSeenScan = scanCounter;
			}
		}
		if (BATCHES.size() > MAX_KEPT_GROUPS) {
			for (final String key : new ArrayList<>(BATCHES.keySet())) {
				final GroupBatch batch = BATCHES.get(key);
				if (batch != null && batch.lastSeenScan < scanCounter - 1) {
					BATCHES.remove(key);
					closeAll(batch);
					DIRTY_GROUPS.remove(key);
				}
			}
		}
	}

	/**
	 * 这一组这一帧画几个人（合同 §2 的口径）。
	 *
	 * <ul>
	 *   <li>站定（{@code GRADE_1 / GRADE_2}）：{@code round(S × L / 100)}；
	 *       {@code S = }{@link PlatformGroup#standingCount} —— **不含锚点格**（ANCHOR 不是落点，
	 *       见 {@link PlatformGroup} 的类注释；含它的话"前 n 个"里会有一段根本画不出来，
	 *       客量读数与实际人数就对不上了）；</li>
	 *   <li>流动（{@code FLOW}）：{@code L <= 0 ? 0 : min(F / 10, max(2, round(F × L / 100)))}（整数除法）；</li>
	 *   <li>{@code L}：这一组的客量（0–100）；孤儿组恒 0。</li>
	 * </ul>
	 *
	 * <p>两个数相加就是 {@code n}；因为站定组排在流动组前面、且锚点格已经不在
	 * {@link PlatformGroup#spots} 里，这一组的"前 n 个候选落点"恰好等于
	 * "前 nStatic 个站定格 + 前 nFlow 个流动格"。</p>
	 */
	private static int personCount(PlatformGroup group) {
		final long clamped = Math.max(0L, Math.min(100L, group.lvl));
		final int standing = (int) Math.round(group.standingCount * clamped / 100.0);
		final int flow = clamped <= 0L ? 0 : Math.min(group.flowCount / 10,
			Math.max(2, (int) Math.round(group.flowCount * clamped / 100.0)));
		return standing + flow;
	}

	/** 客量快照的节拍（{@link #GROUP_RESYNC_TICKS}）。 */
	private static long lastLevelLookupAt = 0L;
	/** 正在复问客量的那些组（复用同一个 list，别每拍新建）。 */
	private static final List<PlatformGroup> LEVEL_GROUPS = new ArrayList<>();

	/**
	 * 每 {@link #GROUP_RESYNC_TICKS} tick：把每组的客量重读一遍（**只读客量**，不碰候选格、不碰签名）。
	 *
	 * <p>为什么要独立于几何扫描：契约要求"客量改了 ≤ 2 s 画面跟着变"，而几何扫描是 5 秒一拍。
	 * 客量是**跨端唯一会变的数**，读它只有一个入口（{@link MmtrCrowdPlatformLevels}）。</p>
	 */
	private static void refreshLevels() {
		final long now = System.currentTimeMillis();
		if (now - lastLevelLookupAt < GROUP_RESYNC_TICKS * 50L) {
			return;
		}
		lastLevelLookupAt = now;
		refreshAllLevels();
	}

	/**
	 * 把每组的客量**无条件**重读一遍（{@link #refreshLevels} 的节流体；{@link #scan} 扫完也直接调这里，
	 * 见那边的注释），读的时候顺带做**补偿触发**。
	 */
	private static void refreshAllLevels() {
		LEVEL_GROUPS.clear();
		LEVEL_GROUPS.addAll(groups.values());
		for (final PlatformGroup group : LEVEL_GROUPS) {
			refreshLevel(group);
			/*
			 * ★ 补偿触发：客量 > 0、但**还没有批次**的组 ⇒ 当成"新组"标脏，交给 buildDirtyGroups 去建。
			 *
			 * <p>为什么必须有这一条（漏了这组就永远不建）：{@link #buildDirtyGroups} 现在会把"孤儿 / 客量 0"
			 * 的脏组**丢掉不建**（进世界热身期最大的一笔浪费），而建块的触发只剩扫描那边的"候选格列表变了"
			 * （5 秒一拍）。于是"开局 0%、后面升到 50%"这条路径上**一个触发都没有**：客量从 0 到 50
			 * 根本不动候选格列表（{@link PlatformGroup} 的签名里没有客量），扫描看不出任何变化，
			 * 画的时候又只有"排前 ceil(n/32) 批"、没有批次可排 —— 结果就是这条站台**永远画不出人**。
			 * 有了这一条，0 → 50% 最多迟一拍客量快照（1 秒）建一次，之后客量再变化仍然只是改画几批，
			 * 仍是合同 §2 的"0 重建、0 上传"。</p>
			 *
			 * <p>三个排除条件各有理由，少一个就会白烘：<br>
			 * ① {@code BATCHES} 里已有 ⇒ 不用建，客量升上来直接排批就行（这也是"客量掉回 0 不丢已有批次"
			 * 那一条的下半句：批次留着，下次升上来立刻能画）；<br>
			 * ② {@code DIRTY_GROUPS} 里已有 ⇒ 重复标脏没有意义（顺带避免同一组在队列里排两次）；<br>
			 * ③ 正在拼（{@code pendingGroup}）⇒ 它**拼完才进 BATCHES**，现在放回队列会让它拼完又被整组重烘
			 * 一遍 —— 实测一条 1044 格的站台建一次 1774 ms，白烘一次就是这个数量级，比不优化还糟。</p>
			 */
			if (group.lvl > 0L && !BATCHES.containsKey(group.key) && !DIRTY_GROUPS.containsKey(group.key)
				&& (pendingGroup == null || !pendingGroup.key.equals(group.key))) {
				DIRTY_GROUPS.put(group.key, new DirtyGroup(group.spots, group.signature));
			}
		}
	}

	/**
	 * 一组的客量：在这一组的格子里逐格问一句归属，取第一个报出站台的，读它的
	 * {@code Platform.getCrowdLevel()}（0–100）。都问不到 ⇒ **0**（{@code NO_PLATFORM} / 异常 / 孤儿组）。
	 *
	 * <p>为什么不只问第一个格子：{@link MmtrCrowdPlatformLevels#platformAt} 的垂直容差只有 ±4 格，
	 * 一组的第一个格子可能刚好差半格读不到（与改动前"块内取 8 个探针"同一个理由）。</p>
	 *
	 * <p>注意这里**只读客量**：不比对签名、不建块。客量变了只是"这一帧排几批"变了（见类注释）。</p>
	 */
	private static void refreshLevel(PlatformGroup group) {
		if (group.key.startsWith("?")) {
			group.lvl = 0L;   // 没有归属的格：客量按 0，永不画（见 scanOrphans）
			return;
		}
		try {
			Platform platform = null;
			final int step = Math.max(1, group.spots.size() / LEVEL_PROBES);
			for (int i = 0; i < group.spots.size() && platform == null; i += step) {
				final Spot probe = group.spots.get(i);
				platform = MmtrCrowdPlatformLevels.platformAt(
					(int) Math.floor(probe.x), (int) Math.floor(probe.y), (int) Math.floor(probe.z));
			}
			group.lvl = platform == null ? 0L : Math.max(0L, Math.min(100L, platform.getCrowdLevel()));
		} catch (RuntimeException exception) {
			// 静态数据流可能在别的线程改 platforms（那一侧的文件不归这里管）；渲染线程不能因此被打掉。
			if (!warnedLevelLookup) {
				warnedLevelLookup = true;
				System.out.println("[MMTR-CROWD] 读站台客量失败（这一拍按 0 算，只报一次）：" + exception);
			}
			group.lvl = 0L;
		}
	}

	/** 探针格个数：一格里读不到站台就换下一个（见 {@link #refreshLevel}）。 */
	private static final int LEVEL_PROBES = 8;

	/**
	 * 每帧烘**一批**（{@link #BATCH_SPOTS} 格 = 一个 {@code OptimizedModel}），而不是"整组"：
	 * 建 VAO 是全流程里最贵的一步（实测 156 人一块要 239 ms，其中造顶点只占 18 ms），
	 * 一次烘完就是一次 200 ms 级掉帧。
	 *
	 * <p>所以：每帧最多花 {@link #GROUP_BUILD_BUDGET_MILLIS} 建，一组由若干批拼成，
	 * **拼完才发布**（发布前这一组不画，晚几帧出现，比掉帧好）。因为一批就是"客量前缀"的刻度，
	 * 批的边界必须落在 32 个整数人上 —— 所以一"段"正好 = 一批。</p>
	 */
	private static final long GROUP_BUILD_BUDGET_MILLIS = 5L;
	private static PendingGroup pendingGroup;

	/** 正在拼的一组：已经烘好的批 + 还没烘的格。 */
	private static final class PendingGroup {

		final String key;
		final long signature;
		final List<Spot> spots;
		final List<BuiltBatch> batches = new ArrayList<>();
		int cursor;
		/** 这一组已经花在建块上的 CPU 毫秒（跨帧累加，读数用）。 */
		long spentNanos;

		PendingGroup(String key, DirtyGroup dirty) {
			this.key = key;
			this.signature = dirty.signature;
			this.spots = dirty.spots;
		}
	}

	private static void buildDirtyGroups() {
		if (batchUnavailable) {
			return;
		}
		final long started = System.nanoTime();
		final long deadline = started + GROUP_BUILD_BUDGET_MILLIS * 1000000L;
		boolean builtAnything = false;
		while (System.nanoTime() < deadline) {
			if (pendingGroup == null) {
				/*
				 * ★ 取出前先判"值不值得建"：不值得的**直接从 DIRTY_GROUPS 里拿掉**、接着取下一组
				 *   （pendingGroup 一直保持 null，所以绝不会留下半成品的组）。
				 *
				 * <p>为什么要丢：进世界热身期最贵的一步就是建块（实测 1044 格 / 33 批 = 1774 ms），
				 * 而这两类组**烘出来也不会有一个人出现在画面上**（{@link #personCount} 算出来是 0）：</p>
				 * <ol>
				 *   <li>`"?…"` 孤儿组（镜像还没到时的兜底，见 {@link #scanOrphans}）：没有归属站台 ⇒ 客量恒 0；</li>
				 *   <li>客量**真的读到 0%** 的站台：出生点附近 22 条站台里 16 条是 0%。</li>
				 * </ol>
				 * <p>实机读数佐证这笔浪费：进世界后先 296 ms 烘了 3 批孤儿组（1107 格），镜像一到那 1107 格
				 * 又被真站台整批重烘一遍 —— 两遍里没有一格画得出来。</p>
				 *
				 * <p>丢掉不等于"永远不建"：客量升上来的那一刻由 {@link #refreshAllLevels} 的补偿触发重新标脏
				 * （那正是补偿触发必须存在的原因）；已经有批次的组不在这里丢（判据见 {@link #worthBuilding}），
				 * 否则"客量 0 但标记格变了"的站台会一直画着旧格子的位置。</p>
				 */
				final java.util.Iterator<Map.Entry<String, DirtyGroup>> iterator = DIRTY_GROUPS.entrySet().iterator();
				DirtyGroup next = null;
				String nextKey = null;
				while (iterator.hasNext()) {
					final Map.Entry<String, DirtyGroup> entry = iterator.next();
					iterator.remove();
					if (!worthBuilding(entry.getKey())) {
						skippedSinceSummary++;
						continue;   // 丢弃：这一组不进 pendingGroup，接着取下一组
					}
					nextKey = entry.getKey();
					next = entry.getValue();
					break;
				}
				if (next == null) {
					break;   // 这一拍标脏的组全是"不值得建"的（或者压根没有脏组）
				}
				pendingGroup = new PendingGroup(nextKey, next);
			}
			final int from = pendingGroup.cursor;
			// ★ 段 = 批：正好 BATCH_SPOTS 格（客量截取的最小单位，不许把一批切碎，见 BATCH_SPOTS）
			final int to = Math.min(pendingGroup.spots.size(), from + BATCH_SPOTS);
			final long segmentStarted = System.nanoTime();
			final BuiltBatch batch = buildSegment(pendingGroup, from, to);
			final long segmentNanos = System.nanoTime() - segmentStarted;
			if (batchUnavailable) {
				// 建失败 ⇒ disposeBatches() 已经把 pendingGroup 丢掉了（别在它后面碰字段）
				pendingGroup = null;
				DIRTY_GROUPS.clear();
				break;
			}
			pendingGroup.spentNanos += segmentNanos;
			if (batch != null) {
				pendingGroup.batches.add(batch);
				builtAnything = true;
			}
			pendingGroup.cursor = to;
			/*
			 * ★ 一批没烘成（upload 返回空 parts）时，这一组**整组不发布**，下一拍重来。
			 *
			 * <p>为什么不能"跳过这一批、把后面的批接上"：批的意义就是"这一组均衡序里的第 [b×32,(b+1)×32)
			 * 格"，少一批之后 batches.get(b) 与 b 就对不上号了 —— 客量前缀会画到别的格子上（画错位置），
			 * 而且越往后错得越多。整组丢掉重来只是慢一拍。</p>
			 */
			if (batch == null && pendingGroup.cursor < pendingGroup.spots.size()) {
				pendingGroup = null;
				continue;
			}
			if (pendingGroup.cursor >= pendingGroup.spots.size()) {
				// 拼完：整组发布（旧的替换掉、释放显存）
				final GroupBatch groupBatch = new GroupBatch(pendingGroup.batches, pendingGroup.spots.size(),
					pendingGroup.signature);
				groupBatch.lastSeenScan = scanCounter;
				final GroupBatch previous = BATCHES.put(pendingGroup.key, groupBatch);
				if (previous != null) {
					closeAll(previous);
				}
				final int buildMillis = (int) (pendingGroup.spentNanos / 1000000L);
				buildsSinceSummary++;
				if (DEBUG) {
					// 每次建块一行（DEBUG 开时）。站台 hex | ? —— 站台那一栏就是这一组的身份
					System.out.println("[MMTR-CROWD] 建块 " + buildMillis + " ms（站台 " + groupBatchName(pendingGroup.key)
						+ " ｜ " + pendingGroup.spots.size() + " 格 ｜ " + pendingGroup.batches.size() + " 批 ｜ 上传 "
						+ pendingGroup.batches.size() + "）");
				}
				pendingGroup = null;
			}
		}
		if (builtAnything) {
			lastGroupBuildMillis = (int) ((System.nanoTime() - started) / 1000000L);
		}
	}

	/**
	 * 这一组**值不值得烘**（{@link #buildDirtyGroups} 取出脏组时的丢弃判据）。
	 *
	 * <p>不值得 = 烘完一格都画不出来：</p>
	 * <ol>
	 *   <li>{@code "?" + 块坐标} 的孤儿组：没有归属站台 ⇒ 客量恒 0（见 {@link #scanOrphans}），
	 *       {@link #refreshLevel} 也直接把它按 0 算；</li>
	 *   <li>客量**读到** 0：{@link #personCount} = 0 ⇒ 这一组这一帧排 0 批，顶点全是白造。
	 *       但**已经建过批次的组不算**：那一条是"候选格列表真的变了"的重烘（{@link #prepareGroups} 的
	 *       第 ② 条），丢了就会一直画着旧格子的位置 —— 这种组宁可照建（标记格变化本来就少）。</li>
	 * </ol>
	 *
	 * <p>为什么敢照客量丢：{@link #scan} 扫完会**无条件**重读一遍每组的客量（{@link #refreshAllLevels}），
	 * 不等下一拍 {@link #GROUP_RESYNC_TICKS} 的快拍 —— 否则"客量还没读过"与"客量读到 0"分不开，
	 * 开局那 16 条 0% 的站台会在读到客量之前照旧被烘，这一条优化就白写了。</p>
	 */
	private static boolean worthBuilding(String key) {
		if (key.startsWith("?")) {
			return false;
		}
		if (BATCHES.containsKey(key)) {
			return true;
		}
		final PlatformGroup group = groups.get(key);
		// 读不到这一组（这一拍它已经不在 groups 里）时保守地照建：宁可多烘一次，也不要把该画的组吞掉
		if (group == null) {
			return true;
		}
		// 全是流动格且不足 10 个 ⇒ 客量上限 ⌊F/10⌋ = 0，一个人都画不到（见 PlatformGroup 的截断）
		return group.lvl != 0L && !group.spots.isEmpty();
	}

	/** 日志里的站台那一栏：hex 或 {@code ?}（孤儿组）。 */
	private static String groupBatchName(String key) {
		return key.startsWith("?") ? "?" : key;
	}

	/**
	 * 烘一段（{@code spots[from, to)} = 一批）：四个贴图层各写一遍那 28 个盒子，
	 * 顶点相对**这一批自己的原点**（这一批格子的最小角）。
	 *
	 * <p>逐格只做两件"查表"的事：光（{@link #worldLightAt}）与顶点表
	 * （{@code YAW_POSITIONS[身体档][头档]}）。头部那 4 个盒的偏转 + 低头是**建表时**算好的
	 * （见 {@link #buildTables}），这里一次三角函数都不做。</p>
	 */
	private static BuiltBatch buildSegment(PendingGroup pending, int from, int to) {
		try {
			// 这一批自己的原点：这一段格子的最小角（建块与绘制用同一个数，见 BuiltBatch）
			int originX = Integer.MAX_VALUE;
			int originY = Integer.MAX_VALUE;
			int originZ = Integer.MAX_VALUE;
			for (int s = from; s < to; s++) {
				final Spot spot = pending.spots.get(s);
				originX = Math.min(originX, (int) Math.floor(spot.x));
				originY = Math.min(originY, (int) Math.floor(spot.y));
				originZ = Math.min(originZ, (int) Math.floor(spot.z));
			}
			final RawModel rawModel = new RawModel();
			final RawMesh mesh = newRawMesh();
			// 取一次就够：建块发生在渲染线程（进世界之后），world 一整段不会换
			final ClientWorld clientWorld = MinecraftClient.getInstance().world;
			for (int s = from; s < to; s++) {
				final Spot spot = pending.spots.get(s);
				final int bucket = Math.floorMod(Math.round(spot.yaw / 22.5F), 16);
				final float[] positions = YAW_POSITIONS[bucket][spot.headVariant];
				final float[] normals = YAW_NORMALS[bucket][spot.headVariant];
				final float baseX = (float) (spot.x - originX);
				final float baseY = (float) (spot.y - originY);
				final float baseZ = (float) (spot.z - originZ);
				/*
				 * ★ 一个人的 2688 个顶点**共用一个光值**（按这个人的标记格取）。
				 *
				 * <p>为什么一个人只取一次、而不是逐盒/逐顶点取：① 逐顶点取世界光 = 每烘一格几百万次
				 * 光照查询，建块时间会失控（建块本来就是最贵的一步，见 {@link #GROUP_BUILD_BUDGET_MILLIS}）；
				 * ② 同一个人身上出现两种亮度会像"半个人在阴影里"，反而假 —— 原版实体就是一个光值。</p>
				 */
				final int light = worldLightAt(clientWorld, spot);
				final int[] layers = {spot.skin, spot.type, spot.profession, spot.level};
				for (final int layer : layers) {
					final Tile tile = TILES.get(layer);
					for (int i = 0; i < VERTEX_COUNT; i += 4) {
						final int start = mesh.vertices.size();
						for (int corner = 0; corner < 4; corner++) {
							final int index = i + corner;
							final Vertex vertex = new Vertex();
							vertex.position = new org.mtr.mapping.holder.Vector3f(
								baseX + positions[index * 3],
								baseY + positions[index * 3 + 1],
								baseZ + positions[index * 3 + 2]
							);
							vertex.normal = new org.mtr.mapping.holder.Vector3f(
								normals[index * 3], normals[index * 3 + 1], normals[index * 3 + 2]
							);
							vertex.u = (tile.x + VERTEX_U[index]) * INV_ATLAS_WIDTH;
							vertex.v = (tile.y + VERTEX_V[index]) * INV_ATLAS_HEIGHT;
							vertex.color = 0xFFFFFFFF;
							vertex.light = light;
							mesh.addVertex(vertex);
						}
						mesh.faces.add(new Face(new int[]{start, start + 1, start + 2, start + 3}));
					}
				}
			}
			rawModel.append(mesh);
			final OptimizedModelWrapper model = upload(rawModel);
			return model == null ? null : new BuiltBatch(model, originX, originY, originZ);
		} catch (Throwable throwable) {
			batchUnavailable = true;
			disposeBatches();
			final StackTraceElement where = throwable.getStackTrace().length == 0 ? null : throwable.getStackTrace()[0];
			System.out.println("[MMTR-CROWD] 批次烘焙失败（" + throwable + " @ " + where
				+ "），回退逐帧画：人数要闸门，画面一致");
			return null;
		}
	}

	private static RawMesh newRawMesh() {
		return new RawMesh(new MaterialProperties(OptimizedModel.ShaderType.CUTOUT, ATLAS_ID, null));
	}

	/**
	 * 一个人烘进顶点的光（{@link #buildSegment} 逐格调一次；回退路 {@link #draw} 每一帧调一次）：
	 * {@code LightmapTextureManager.pack(block, sky)}，**取的是他自己那一格标记方块**的坐标
	 * （{@code spot.y = 标记格的 y}，抖动永不跨格 ⇒ {@code ofFloored} 一定落回这一格）。
	 *
	 * <p>为什么不取"脚下"或"身体中段"：雕像的脚就踩在这一格的**下表面**上（{@code spot.y} 是那一格的
	 * y 坐标），而这一格正是标记方块自己 —— 一个标记格一个光值，与"哪一格站人"这件事一一对应，
	 * 相邻两格的光可以不同（台阶边、顶棚边缘就是这种），看起来才不像一整片贴上去的亮度。
	 * 换成脚下一格会读到"站台面下面的石头"，换成身体中段会读到人自己站着的那一格空气 ——
	 * 前者的光照是错的，后者与标记格其实同格，但读起来更难解释。回退路一直就是这个坐标，主路与它对齐。</p>
	 *
	 * <p>{@link #WORLD_LIGHT} 关（{@code MMTR_STATUES_WORLDLIGHT=off}）⇒ 一律 {@link #FULL_BRIGHT}
	 * （截图/排查用，与 MTR 自己的渲染一致）。拿不到 world 时同样退回 {@link #FULL_BRIGHT}
	 * —— 这是"烘焙期"的兜底，而烘焙只在进世界之后发生（{@code client.world} 不是 null），
	 * 所以这条路径实际上只在异常世界里走得到。</p>
	 */
	private static int worldLightAt(ClientWorld world, Spot spot) {
		if (!WORLD_LIGHT || world == null) {
			return FULL_BRIGHT;
		}
		final BlockPos pos = BlockPos.ofFloored(spot.x, spot.y, spot.z);
		return LightmapTextureManager.pack(world.getLightLevel(LightType.BLOCK, pos),
			world.getLightLevel(LightType.SKY, pos));
	}

	private static OptimizedModelWrapper upload(RawModel rawModel) {
		/*
		 * ★ 必须三角化：MTR 的 `RawMesh.upload` 给索引缓冲只分配 **faces × 3 × 4 字节**（每面 3 个下标、
		 *   每个下标 4 字节），而我们烘出来的是**四边形** —— 直接上传就是
		 *   `BufferOverflowException`（2026-10-11 实机踩到，字节码逐条确认）。
		 */
		rawModel.triangulate();
		final List<VertexArray> parts = withProtectedGlState(() -> rawModel.upload(MAPPING));
		return parts.isEmpty() ? null : OptimizedModelWrapper.fromOptimizedModel(new OptimizedModel(parts));
	}

	private static void closeAll(GroupBatch batch) {
		for (final BuiltBatch built : batch.batches) {
			if (built != null && built.model != null) {
				built.model.close();
			}
		}
	}

	/** 建 VAO 必须在 {@code GlStateTracker} 的保护区内（与钢轨那边的 {@code withProtectedGlState} 同一口径）。 */
	private static <T> T withProtectedGlState(java.util.function.Supplier<T> action) {
		final boolean alreadyProtected = isGlStateProtected();
		if (!alreadyProtected) {
			GlStateTracker.capture();
		}
		try {
			return action.get();
		} finally {
			if (!alreadyProtected) {
				GlStateTracker.restore();
			}
		}
	}

	/** {@code GlStateTracker.isStateProtected} 是私有的；读不到就当作"已被保护"（不碰 GL 状态，失败即回退）。 */
	private static boolean isGlStateProtected() {
		if (!glStateProtectedFieldResolved) {
			glStateProtectedFieldResolved = true;
			try {
				final Field field = GlStateTracker.class.getDeclaredField("isStateProtected");
				field.setAccessible(true);
				glStateProtectedField = field;
			} catch (Exception exception) {
				Init.LOGGER.warn("[MMTR-CROWD] 取不到 GlStateTracker.isStateProtected —— 雕像批次停用（回退逐帧画）", exception);
			}
		}
		if (glStateProtectedField == null) {
			return true;
		}
		try {
			return glStateProtectedField.getBoolean(null);
		} catch (Exception exception) {
			return true;
		}
	}

	private static void disposeBatches() {
		for (final GroupBatch batch : BATCHES.values()) {
			closeAll(batch);
		}
		BATCHES.clear();
		DIRTY_GROUPS.clear();
		// 正在拼的那一组也丢掉：它的前半段 VBO 是"上一个世界"那套 GL 状态下的产物，拼完再发布就是半新半旧。
		pendingGroup = null;
	}

	/**
	 * 主路：把每一组**该画的那几批**排进 MTR 优化渲染器。
	 *
	 * <p>顶点相对**这一批自己的原点**存（float 精度），所以这里给每个批一次
	 * {@code translate(批原点 − 相机)}；MTR 的 ModelMat 是每 draw 一个常量的顶点属性，
	 * 与钢轨那条路（{@code RenderRails}）逐字同一个写法。</p>
	 *
	 * <p>**客量只改"排几批"**：这一组的客量 {@code L%} ⇒ {@code n} ⇒ {@code ceil(n / 32)} 批 ——
	 * 一个顶点都不重建、一个 buffer 都不上传。组按"离相机近"排：{@link #HARD_MAX_PER_FRAME}
	 * 截住的是**最远**的那些组，眼前的一格不会被远端站台抢掉配额。</p>
	 */
	private static void drawBatches(org.mtr.mapping.mapper.GraphicsHolder graphicsHolder, org.mtr.mapping.holder.Vector3d offset) {
		final double cameraX = offset.getXMapped();
		final double cameraY = offset.getYMapped();
		final double cameraZ = offset.getZMapped();
		int drawnBatches = 0;
		int drawnCells = 0;
		int drawnIntent = 0;
		int levelSeen = -1;
		for (final PlatformGroup group : visibleGroups(cameraX, cameraZ)) {
			final GroupBatch batch = BATCHES.get(group.key);
			if (batch == null || batch.batches.isEmpty()) {
				continue;   // 还没烘出来（正在拼）或这一拍没扫全
			}
			levelSeen = Math.max(levelSeen, (int) group.lvl);
			final int people = personCount(group);
			if (people <= 0) {
				continue;
			}
			final int wanted = (people + BATCH_SPOTS - 1) / BATCH_SPOTS;
			for (int b = 0; b < wanted && b < batch.batches.size(); b++) {
				// 一批 = 这一组均衡序里第 [b×32, (b+1)×32) 格；最后一批可能不满 32
				final int cells = Math.min(BATCH_SPOTS, people - b * BATCH_SPOTS);
				if (cells <= 0 || drawnCells >= HARD_MAX_PER_FRAME) {
					break;
				}
				final BuiltBatch built = batch.batches.get(b);
				if (built == null) {
					continue;
				}
				graphicsHolder.push();
				graphicsHolder.translate(built.originX - cameraX, built.originY - cameraY, built.originZ - cameraZ);
				CustomResourceLoader.OPTIMIZED_RENDERER_WRAPPER.queue(built.model, graphicsHolder,
					org.mtr.mapping.mapper.GraphicsHolder.getDefaultLight());
				graphicsHolder.pop();
				drawnBatches++;
				drawnCells += cells;
			}
			drawnIntent += people;
			if (drawnCells >= HARD_MAX_PER_FRAME) {
				break;
			}
		}
		lastDrawn = drawnCells;
		lastDrawnBatches = drawnBatches;
		lastDrawnIntent = drawnIntent;
		lastDrawnCells = drawnCells;
		lastSeenLevel = Math.max(-1, levelSeen);
		logSummaryIfDue();
	}

	/** 每帧按"组离相机由近到远"排可见组（复用同一个 list，别每帧新建）。 */
	private static final List<PlatformGroup> VISIBLE_GROUPS = new ArrayList<>();

	/**
	 * 这一帧要看的组：包围盒（整条站台）离相机在可见距离内，按"包围盒上离相机最近的那一点"由近到远排。
	 *
	 * <p>为什么用整条站台的包围盒、不用组中心：一条 220 格站台的中心可能在 100 格外，而人正站在它的一端
	 * —— 用中心排会把脚下这条排到最后。包围盒的最近点与"玩家站在站台哪一头"无关，也因此
	 * **玩家走动不会改变这一帧要画的集合**（只有真的走出可见距离才会）。</p>
	 */
	private static List<PlatformGroup> visibleGroups(double cameraX, double cameraZ) {
		VISIBLE_GROUPS.clear();
		final double limit = (VIEW_DISTANCE + 24.0) * (VIEW_DISTANCE + 24.0);
		for (final PlatformGroup group : groups.values()) {
			final double dx = nearest(group.boundsMinX, group.boundsMaxX, cameraX) - cameraX;
			final double dz = nearest(group.boundsMinZ, group.boundsMaxZ, cameraZ) - cameraZ;
			if (dx * dx + dz * dz <= limit) {
				VISIBLE_GROUPS.add(group);
			}
		}
		VISIBLE_GROUPS.sort(java.util.Comparator.comparingDouble(group -> {
			final double dx = nearest(group.boundsMinX, group.boundsMaxX, cameraX) - cameraX;
			final double dz = nearest(group.boundsMinZ, group.boundsMaxZ, cameraZ) - cameraZ;
			return dx * dx + dz * dz;
		}));
		return VISIBLE_GROUPS;
	}

	private static double nearest(double min, double max, double value) {
		return value < min ? min : Math.min(value, max);
	}

	/**
	 * 5 秒一条汇总（Lead 按这个 grep 验收）：
	 * {@code [MMTR-CROWD] 扫描耗时 X ms ｜ N 格候选 ｜ 站台组 G ｜ 建块 K ｜ 跳过 S 组（?/0%）｜ 画 M 批（意图 P 人 / 实画 Q 格）｜ 客量 L% ｜ 拒缩 D}
	 *
	 * <p>**意图 P 人 与 实画 Q 格必须同时给**：P 是"客量算出来该有多少人"（各组 personCount 之和），
	 * Q 是"这一帧真的排进队列的格数"（Σ min(32, n − 32b)，被 {@link #HARD_MAX_PER_FRAME} 夹过）。
	 * 两者只在"被硬上限截住 / 正在拼还没发布"时才不等 —— 上一版只报 P，把"意图"当成"画出来的人数"，
	 * 让人把"客量 10% = 126 人"误读成画面人数。</p>
	 *
	 * <p>**跳过 S 组（?/0%）**：这一轮里被 {@link #worthBuilding} 判成"不值得建"而丢掉的组数
	 * （`?` 孤儿组 + 客量读到 0 的站台）。它与 {@code 建块 K} 一起回答"进世界热身期省了多少"：
	 * 没有这一条，那 S 组会各自烘一遍（一组几十到上千格）。</p>
	 *
	 * <p>**拒缩 N**（2026-10-13 加，**接在最后**，不动前面任何一栏的名字与顺序 —— Lead 按名字 grep 验收）：
	 * 这一轮里"变小、且逐格证不出缺的格真没了"而被拒收的扫描次数（见 {@link #scanAcceptedCells}）。
	 * 它回答"这一拍有没有一份半份扫描被挡回去"：**拒缩 N 涨、建块 K 不涨** = 玩家走位导致读数变小、
	 * 被正确挡回（候选集与玩家位置无关，这就是"走动 0 建块"该有的样子）；**玩家真的挖掉标记**时
	 * K 涨一次、N **不该**涨（缺的格逐格证得出来 ⇒ 采纳）。</p>
	 *
	 * <p>读的时候注意 S 有一个**不会归零的地板**：`?` 孤儿组永远没有批次 ⇒ 每 5 秒的几何扫描都会把它们
	 * 重新标脏一次、再被丢掉一次（{@link #prepareGroups} 只知道"没有批次"）。所以热身期 S 大
	 * （含 16 条 0% 站台），之后稳定在"附近的孤儿组数"上 —— 那只是十几次 map 迭代，不是持续浪费；
	 * 真正要看的是 {@code 建块 K}（走动/热身之后应该是 0）。</p>
	 */
	private static void logSummaryIfDue() {
		if (System.currentTimeMillis() - lastDebugAt <= 5000L) {
			return;
		}
		lastDebugAt = System.currentTimeMillis();
		System.out.println("[MMTR-CROWD] 扫描耗时 " + (Math.round(lastScanMillis * 10.0) / 10.0) + " ms ｜ "
			+ candidateCount + " 格候选（可画 " + drawableCount + "）｜ 站台组 " + groups.size() + " ｜ 建块 " + buildsSinceSummary + " ｜ 跳过 "
			+ skippedSinceSummary + " 组（?/0%）｜ 画 "
			+ lastDrawnBatches + " 批（意图 " + lastDrawnIntent + " 人 / 实画 " + lastDrawnCells + " 格）｜ 客量 "
			+ lastSeenLevel + "% ｜ 拒缩 " + shrinkRejectedSinceSummary);
		buildsSinceSummary = 0;
		skippedSinceSummary = 0;
		shrinkRejectedSinceSummary = 0;
	}

	// ------------------------------------------------------------------ 绘制（回退路：逐帧写顶点）

	/** 这一帧要画的格（每帧重填；MTR 的队列回调在同一个 pass 里读它）。 */
	private static final List<Spot> FRAME = new ArrayList<>();
	/** 同屏可见距离（格）：一条 220 格站台站在中间时两端都在内。 */
	private static final double VIEW_DISTANCE = 128.0;

	/**
	 * 每帧（{@code BEFORE_ENTITIES}）：把这一帧要画的雕像**排进 MTR 的渲染队列**。
	 *
	 * <h3>为什么走 MTR 的队列，而不是自己往实体批次里写顶点（2026-10-11 实测定论）</h3>
	 * <p>自己写顶点那条路（{@code client.getBufferBuilders().getEntityVertexConsumers().getBuffer(...)}
	 * + {@code VertexConsumer.vertex(相机相对坐标)}）画出来的东西**钉在屏幕上**：顶点确实进了实体批次，
	 * 但那个批次 flush 的位置在实体 pass **之外**，拿到手的变换矩阵不是实体 pass 的那一个
	 * （用户实测："村民会跟着视角动，在屏幕上固定"）。</p>
	 * <p>MTR 的队列（{@link MainRenderer#scheduleRender}）不是"另一个画法"，而是**同一个实体批次、
	 * 在实体 pass 内部**派发：{@code MainRenderer.render} 本身就是一个实体渲染器，它先
	 * {@code graphicsHolder.createVertexConsumer(图层)}，紧接着调用回调。车厢、钢轨、标记现形
	 * （{@code RenderCrowdMarker}）全走这条路 —— 它们的位置一直是准的。所以直接沿用它。</p>
	 *
	 * <p>代价：每个四边形一次 {@code IDrawing.drawTexture}（一个人 28 盒 × 6 面 × 4 层 = 672 次），
	 * 所以**回退路**要闸住人数（{@link #TIME_BUDGET_MILLIS} 自调 + {@link #HARD_MAX_PER_FRAME} 硬顶）；
	 * 主路（VBO 批次）不受这两个限制，只按客量排前几批。</p>
	 */
	public static void render(WorldRenderContext context) {
		if (!ENABLED || !loaded) {
			return;
		}
		if (groups.isEmpty()) {
			// 扫描那边已经报过"附近没扫到客流标记格"了，这里只是不画
			return;
		}
		final MinecraftClient client = MinecraftClient.getInstance();
		final ClientWorld world = client.world;
		if (world == null) {
			return;
		}
		final Vec3d camera = context.camera().getPos();

		/*
		 * ★ 主路：**烘好的 VBO 批次**（每组若干批，一批 = 一个 OptimizedModel = 32 格，批可跨块）。
		 * 人数与帧时间脱钩 —— 8 区块内一千多个人也是几十个 draw call，这正是"能缓存的一定先缓存"。
		 * 逐帧写顶点那条路退到回退位。客量变化只改这里"排前几批"，不碰批次。
		 */
		if (!batchUnavailable && OptimizedRenderer.hasOptimizedRendering()) {
			buildDirtyGroups();
			if (BATCHES.isEmpty()) {
				if (!warnedNothingDrawn) {
					warnedNothingDrawn = true;
					System.out.println("[MMTR-CROWD] 批次还没烘出来（待建 " + DIRTY_GROUPS.size() + " 组）—— 下一帧开始画");
				}
				return;
			}
			if (!warnedFirstDraw) {
				warnedFirstDraw = true;
				System.out.println("[MMTR-CROWD] 首次排队（批次）：相机 " + Math.round(camera.x) + ","
					+ Math.round(camera.y) + "," + Math.round(camera.z) + " ｜ " + BATCHES.size() + " 组 ｜ 贴图 "
					+ ATLAS_TEXTURE_PATH + " ｜ 建块 " + lastGroupBuildMillis + " ms");
			}
			MainRenderer.scheduleRender(ATLAS_ID, false, QueuedRenderLayer.EXTERIOR,
				(graphicsHolder, offset) -> drawBatches(graphicsHolder, offset));
			return;
		}
		/*
		 * 回退路（优化渲染器不可用 / 批次建失败）：逐帧写顶点。
		 *
		 * <p>与主路**同一套口径**：每一组取"这一组均衡序（跨块轮取 + 站定组在前）的前 n 个"，
		 * 再被 {@link #frameCap}（{@link #TIME_BUDGET_MILLIS} 自调）与 {@link #HARD_MAX_PER_FRAME} 夹住。
		 * 按块各排各的会在低客量时画多（见类注释），所以这条路也按组算。</p>
		 */
		FRAME.clear();
		final Set<Spot> seen = new LinkedHashSet<>();
		int cappedGroups = 0;
		int intent = 0;
		int levelSeen = -1;
		for (final PlatformGroup group : visibleGroups(camera.x, camera.z)) {
			levelSeen = Math.max(levelSeen, (int) group.lvl);
			final int people = Math.min(personCount(group), group.spots.size());
			intent += people;
			for (int i = 0; i < people; i++) {
				if (FRAME.size() >= frameCap || FRAME.size() >= HARD_MAX_PER_FRAME) {
					cappedGroups++;
					break;
				}
				// 同一格只画一次（防御：分组理论上不会重叠，但重叠时宁可少画也不要叠两个人）
				if (seen.add(group.spots.get(i))) {
					FRAME.add(group.spots.get(i));
				}
			}
		}
		lastDrawn = FRAME.size();
		lastDrawnBatches = (FRAME.size() + BATCH_SPOTS - 1) / BATCH_SPOTS;
		lastDrawnIntent = intent;
		lastDrawnCells = FRAME.size();
		lastSeenLevel = Math.max(-1, levelSeen);
		if (FRAME.isEmpty()) {
			if (!warnedNothingDrawn) {
				warnedNothingDrawn = true;
				System.out.println("[MMTR-CROWD] 候选格 " + candidateCount + " 个但一个都没画（客量 0 / 探不到站台 "
					+ "｜ 被上限夹住 " + cappedGroups + " 组 ｜ 上限 " + frameCap + " ｜ 相机 " + Math.round(camera.x)
					+ "," + Math.round(camera.y) + "," + Math.round(camera.z) + "）");
			}
			logSummaryIfDue();
			return;
		}
		if (!warnedFirstDraw) {
			warnedFirstDraw = true;
			final Spot first = FRAME.get(0);
			final BlockPos pos = BlockPos.ofFloored(first.x, first.y, first.z);
			System.out.println("[MMTR-CROWD] 首次排帧：格 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
				+ " ｜ 相机 " + Math.round(camera.x) + "," + Math.round(camera.y) + "," + Math.round(camera.z)
				+ " ｜ block光=" + world.getLightLevel(LightType.BLOCK, pos)
				+ " sky光=" + world.getLightLevel(LightType.SKY, pos)
				+ " ｜ 排进 " + QueuedRenderLayer.EXTERIOR + " 队列 ｜ 贴图 " + ATLAS_TEXTURE_PATH);
		}
		MainRenderer.scheduleRender(ATLAS_ID, false, QueuedRenderLayer.EXTERIOR,
			(graphicsHolder, offset) -> draw(graphicsHolder, offset, world));
	}

	/**
	 * MTR 队列回调：把所有要画的雕像四边形写进它已经绑好的实体图层。
	 *
	 * <p>坐标一律给**世界坐标**，由 {@code IDrawing} 的 {@code playerOffset} 重载减掉相机位置 ——
	 * 与 {@code StoredMatrixTransformations.transform(graphicsHolder, offset)} 同一口径。</p>
	 */
	private static void draw(org.mtr.mapping.mapper.GraphicsHolder graphicsHolder, org.mtr.mapping.holder.Vector3d offset, ClientWorld world) {
		final long started = System.nanoTime();
		int quads = 0;
		for (final Spot spot : FRAME) {
			/*
			 * 光：与主路**同一个口径**（{@link #worldLightAt}）—— 默认按这一格标记方块的世界光，
			 * {@code MMTR_STATUES_WORLDLIGHT=off} 时全亮。回退路是逐帧写的，本来每帧都会重算，
			 * 所以这条路上的光**会**跟着世界光变；主路（烘进顶点）不会，那是静态烘焙的既定代价
			 * （见 {@link #WORLD_LIGHT} 的注释）。两条路在"取哪一格的光"上必须一致，否则
			 * 一旦回退，画面亮度会突然变一下。
			 */
			final int light = worldLightAt(world, spot);
			final int bucket = Math.floorMod(Math.round(spot.yaw / 22.5F), 16);
			final float[] bodyPositions = YAW_POSITIONS[bucket][HEAD_VARIANT_STRAIGHT];
			final org.mtr.mapping.holder.Direction[] directions = YAW_FACE_DIRECTIONS[bucket];
			final float[] headPositions = YAW_POSITIONS[bucket][spot.headVariant];
			/*
			 * ★ 一个人只做一次矩阵平移，而不是每个四边形都减一遍相机位置。
			 *
			 * <p>{@code IDrawing.drawTexture(..., playerOffset, ...)} 那个重载会对**每个四边形**
			 * 的 12 个坐标各调一次 {@code offset.getXMapped()}（映射层的虚调用，×4 次/四边形）。
			 * 实测（1075 格，DEBUG 读数）：每趟 63 µs/人 —— 672 个四边形里有一半的钱花在这上面。
			 * 换成"平移一次 + 写局部坐标"后，一次虚调用都不剩。</p>
			 */
			graphicsHolder.push();
			graphicsHolder.translate(spot.x - offset.getXMapped(), spot.y - offset.getYMapped(), spot.z - offset.getZMapped());
			final int[] layers = {spot.skin, spot.type, spot.profession, spot.level};
			for (final int layer : layers) {
				final Tile tile = TILES.get(layer);
				for (int i = 0; i < VERTEX_COUNT; i += 4) {
					/*
					 * 角序 = 原版角序**循环**挪一格（c1,c2,c3,c0）：MTR 的 drawTextureInWorld 把 uv 的
					 * (u1,v1) 挂在 c1… 见 GraphicsHolder 的字节码（2026-10-11 逐条读过）；循环不翻绕序，
					 * 所以背面剔除的判断与原版一致。uv 的 v 那一对要跟着角走，别只看 u。
					 *
					 * <p>头档（顶点表的第二维）要在**头的那 4 个盒**上跟着换（与主路同一个姿势）：
					 * 一个四边形 = 一个盒的一个面，{@code i / VERTICES_PER_BOX} 正好是这个盒在
					 * {@link #BOXES} 里的下标（建表时每盒按顺序摊 6 面 × 4 角，表里就是这个序）。
					 * 身体那些四边形每次都拿到同一个数组，没有额外代价。</p>
					 */
					final float[] positions = HEAD_VERTEX[i / VERTICES_PER_BOX] ? headPositions : bodyPositions;
					final int c0 = i;
					final int c1 = i + 1;
					final int c2 = i + 2;
					final int c3 = i + 3;
					IDrawing.drawTexture(graphicsHolder,
						positions[c1 * 3], positions[c1 * 3 + 1], positions[c1 * 3 + 2],
						positions[c2 * 3], positions[c2 * 3 + 1], positions[c2 * 3 + 2],
						positions[c3 * 3], positions[c3 * 3 + 1], positions[c3 * 3 + 2],
						positions[c0 * 3], positions[c0 * 3 + 1], positions[c0 * 3 + 2],
						(tile.x + VERTEX_U[c0]) * INV_ATLAS_WIDTH, (tile.y + VERTEX_V[c0]) * INV_ATLAS_HEIGHT,
						(tile.x + VERTEX_U[c2]) * INV_ATLAS_WIDTH, (tile.y + VERTEX_V[c1]) * INV_ATLAS_HEIGHT,
						directions[(i / 4) % 6], 0xFFFFFFFF, light);
					quads++;
				}
			}
			graphicsHolder.pop();
		}
		if (DEBUG) {
			System.out.println("[MMTR-CROWD] 回退路本帧 " + FRAME.size() + " 人 ｜ " + quads + " 四边形 ｜ 写顶点 "
				+ (System.nanoTime() - started) / 1000000.0 + " ms ｜ 下一帧上限 " + frameCap);
		}
		logSummaryIfDue();
		// 自调：上一帧实测的每人均价 → 这一帧的上限（目标 TIME_BUDGET_MILLIS）。
		lastDrawMillis = (System.nanoTime() - started) / 1000000.0;
		if (lastDrawMillis > 0.05) {
			frameCap = (int) Math.max(MIN_PER_FRAME, Math.min(HARD_MAX_PER_FRAME,
				TIME_BUDGET_MILLIS / (lastDrawMillis / FRAME.size())));
		}
	}

	/** 某个盒子是不是"头"（{@code chain} 里含 {@code head2}）：只有它走头部的偏转 + 低头。 */
	private static boolean isHeadBox(int boxIndex) {
		return BOX_CHAIN[boxIndex] != null && BOX_CHAIN[boxIndex].contains("head2");
	}

	/**
	 * 一次性把网格摊成**线性顶点表**，并烘出**身体档 × 头档**（16 × {@link #HEAD_VARIANTS}）的位置与法线。
	 *
	 * <h3>头部那 4 个盒怎么转（枢轴、顺序、角度正负）</h3>
	 * <p>组合出来的旋转是 <b>R_y(身体档) · R_y(头偏转) · R_x(低头)</b>（右到左 = 先做的在右）。
	 * 写成矩阵是因为"先低头还是先偏转"在这类分步旋转里**不是可交换的**：同一个低头 + 偏转的组合，
	 * 换个顺序会多出十几度的头侧倾（歪头），而这一条要的正是"低头永远是绕头自己的左右轴"。</p>
	 * <ul>
	 *   <li><b>枢轴</b>：模型坐标 {@code (0, 24, 0)}（= 1.5 格高）—— 原版村民/玩家脖颈的位置，
	 *       mesh 里那 4 个 head2 盒的 y 范围 22.0–30.49 正好骑在它上面；位置"先减枢轴、转完加回去"；</li>
	 *   <li><b>① 低头</b>（最里层）：绕模型的 <b>x 轴</b>（左右轴），角度 = 这一档的俯仰
	 *       {@link #HEAD_PITCH_DEGREES_PER_VARIANT}（只有"低头"那一档非 0，默认 −30°）。
	 *       模型朝向 {@code -z}，绕 +x 的右手旋转把头顶推向 {@code -z}、下巴收回 {@code +z}，
	 *       就是"低头看东西"（绕 −x 会变成仰头）；</li>
	 *   <li><b>② 头相对身体偏转</b>（中间层）：绕 <b>y 轴</b>，角度 = 这一档的偏转
	 *       {@link #HEAD_YAW_DEGREES}，四档是 {@code 0 / −30° / +30° / 0}（{@link #spot} 由格子哈希选）。
	 *       负角 = 头转向身体的左边（人朝北时头朝西北），正角 = 右边；</li>
	 *   <li><b>③ 身体档偏转</b>（最外层）：还是同一个绕 Y 公式
	 *       （{@code x' = x·cos + z·sin}、{@code z' = -x·sin + z·cos}，Minecraft 的 {@code POSITIVE_Y} 方向），
	 *       角度 = 这个格子的身档（0 = 北、22.5° 一档）。头与身体**共用**这一个角度，
	 *       所以头的绝对朝向 = 身体档 + 头档，看起来就是"身子朝这边、脑袋又偏了一点"；</li>
	 *   <li><b>单位</b>：mesh 里是 1 单位 = 1/16 格，旋转在**格**里做（先 {@code /16}）——
	 *       与身体档同一个换算，枢轴因此写成 {@code 24/16 = 1.5} 格；</li>
	 *   <li><b>怎么确认转的是脑袋而不是整条人</b>：只有 {@link #isHeadBox} 为真的盒子（bodywear/player/head2
	 *       ×3 + bodywear/player/head2/bone2 ×1，共 4 个）走上面 ①② 两步，其余 24 个盒只走 ③
	 *       ⇒ 它们的顶点与改动前那张"只有身体档"的表逐位相同（也正因为如此，16×4 张表里非头盒那一部分
	 *       逐位相同 —— 回退路给身体盒随便取一档都对，见 {@link #HEAD_VARIANT_STRAIGHT}）。
	 *       判定依据是 mesh 里每个盒的 {@code chain}（不是"撞 y 区间"），换成别的网格只要 chain 还对，
	 *       认出来的头还是头。</li>
	 * </ul>
	 */
	private static void buildTables() {
		final List<float[]> local = new ArrayList<>();
		BOX_CHAIN = new String[BOXES.size()];
		HEAD_VERTEX = new boolean[BOXES.size()];
		for (int boxIndex = 0; boxIndex < BOXES.size(); boxIndex++) {
			final Box box = BOXES.get(boxIndex);
			BOX_CHAIN[boxIndex] = box.chain;
			HEAD_VERTEX[boxIndex] = isHeadBox(boxIndex);
			for (int face = 0; face < 6; face++) {
				final float[] uv = box.uv[face];
				for (int corner = 0; corner < 4; corner++) {
					final float[] point = corner(face, corner, box);
					local.add(new float[]{
						point[0], point[1], point[2],
						FACE_NORMALS[face][0], FACE_NORMALS[face][1], FACE_NORMALS[face][2],
						uv[corner == 0 || corner == 1 ? 0 : 2],
						uv[corner == 0 || corner == 3 ? 1 : 3],
					});
				}
			}
		}
		VERTEX_COUNT = local.size();
		/*
		 * ★ 布局自检：顶点表必须正好是"每盒 VERTICES_PER_BOX 个、盒序与 BOXES 一致"。
		 *
		 * <p>这不是防御式编程，而是**头部姿势的唯一前提**：逐格查表时用 {@code 顶点下标 / VERTICES_PER_BOX}
		 * 反查"这个顶点属于哪个盒、是不是头"（{@link #buildSegment} 与 {@link #draw} 都这么用）。
		 * 一旦网格的盒数/面数变了（换模型、手工改 mesh），这个除法就会把头顶的旋转套到别人身上 ——
		 * 那时候宁可**整片不转头**（把 {@link #HEAD_VERTEX} 全置假）也不能把人转歪，所以在这里判一次。</p>
		 */
		final boolean layoutOk = !BOXES.isEmpty() && VERTEX_COUNT == BOXES.size() * VERTICES_PER_BOX;
		if (!layoutOk) {
			System.out.println("[MMTR-CROWD] 顶点表布局与预期不符（顶点 " + VERTEX_COUNT + " ｜ 盒 " + BOXES.size()
				+ " × " + VERTICES_PER_BOX + "）—— 头部姿势停用（身体照旧），免得把头转套到别的盒上");
			java.util.Arrays.fill(HEAD_VERTEX, false);
		}
		VERTEX_U = new float[VERTEX_COUNT];
		VERTEX_V = new float[VERTEX_COUNT];
		for (int i = 0; i < VERTEX_COUNT; i++) {
			VERTEX_U[i] = local.get(i)[6];
			VERTEX_V[i] = local.get(i)[7];
		}
		for (int bucket = 0; bucket < 16; bucket++) {
			final double radians = Math.toRadians(22.5 * bucket);
			final float cos = (float) Math.cos(radians);
			final float sin = (float) Math.sin(radians);
			// 每个面的法线取"旋转后最接近的那个轴向"：与头档无关（MTR 的四边形只能收一个 Direction，
			// 它只影响法线属性、剔除看绕序），所以 16 档各算一次。
			final org.mtr.mapping.holder.Direction[] directions = new org.mtr.mapping.holder.Direction[6];
			for (int face = 0; face < 6; face++) {
				final float[] normal = FACE_NORMALS[face];
				directions[face] = nearestDirection(
					normal[0] * cos + normal[2] * sin,
					normal[1],
					-normal[0] * sin + normal[2] * cos
				);
			}
			YAW_FACE_DIRECTIONS[bucket] = directions;
			for (int variant = 0; variant < HEAD_VARIANTS; variant++) {
				/*
				 * 头部那一档：**头相对身体**的四种姿势 —— 正前 / 左 30° / 右 30° / 低头，
				 * 角度全在 {@link #HEAD_YAW_DEGREES} 与 {@link #HEAD_PITCH_DEGREES_PER_VARIANT} 里，
				 * 逐格只查表（烘焙里一次三角函数都不做）。
				 *
				 * <p>两次旋转的作用顺序（从里到外）：① 低头（绕**头自己的** x 轴 = 先低头、再跟着头偏转）
				 * ② 头相对身体的偏转（绕 y）③ 外层的身体档偏转（下面那句用 cos/sin 做的绕 Y）。
				 * 头档的偏转只是**相对量**：绝对朝向由外层的身体档给，所以"头朝哪边"= 身体档 + 头档
				 * （偏转只有 0 / −30° / +30° 三种取值），低头那一档的偏转恰好是 0 ⇒ 低头永远绕头自己的
				 * 左右轴、不会因为身体转了 90° 变成歪头。四档里只有低头那一档带俯仰，另外三档平视。</p>
				 */
				final double headYawRadians = Math.toRadians(HEAD_YAW_DEGREES[variant]);
				final double headPitchRadians = Math.toRadians(HEAD_PITCH_DEGREES_PER_VARIANT[variant]);
				final float headCosYaw = (float) Math.cos(headYawRadians);
				final float headSinYaw = (float) Math.sin(headYawRadians);
				final float headCosPitch = (float) Math.cos(headPitchRadians);
				final float headSinPitch = (float) Math.sin(headPitchRadians);
				final float[] positions = new float[VERTEX_COUNT * 3];
				final float[] normals = new float[VERTEX_COUNT * 3];
				// 每个盒按 mesh 顺序摊 VERTICES_PER_BOX 个顶点（与 buildTables 开头填 local 的顺序一致）
				final int verticesPerBox = BOXES.isEmpty() ? VERTICES_PER_BOX : VERTEX_COUNT / BOXES.size();
				for (int i = 0; i < VERTEX_COUNT; i++) {
					final float[] source = local.get(i);
					final int boxIndex = i / verticesPerBox;   // 每盒 6 面 × 4 角 = 24 个顶点，顺序与 local 的填充一致
					// 1/16（网格单位 → 方块）
					float x = source[0] / 16.0F;
					float y = source[1] / 16.0F;
					float z = source[2] / 16.0F;
					float nx = source[3];
					float ny = source[4];
					float nz = source[5];
					if (HEAD_VERTEX[boxIndex]) {
						/*
						 * 头部：① 先低头（绕模型 x 轴 × 这一档的俯仰角，枢轴 = 模型 (0, 24, 0) = 1.5 格高）
						 * ② 再头相对身体偏转（绕 y 轴 × headYawRadians）。
						 * 位置要"先减枢轴、转完加回去"；法线是方向向量，只转不平移。
						 * 枢轴的 24 单位就是 1.5 格：这里用 24/16（已换算到格），与 y 同一单位。
						 */
						final float pivotY = 24.0F / 16.0F;
						final float py = y - pivotY;
						// ① 低头（绕 x）：y/z 一起转，两个结果都要先算出来再写回
						final float pitchY = py * headCosPitch - z * headSinPitch;
						final float pitchZ = py * headSinPitch + z * headCosPitch;
						final float normPitchY = ny * headCosPitch - nz * headSinPitch;
						final float normPitchZ = ny * headSinPitch + nz * headCosPitch;
						// ② 头相对身体偏转（绕 y）：与下面那句身体档的公式同一个，只是输入换成了低头后的 x/z
						final float headX = x * headCosYaw + pitchZ * headSinYaw;
						final float headZ = -x * headSinYaw + pitchZ * headCosYaw;
						x = headX;
						y = pitchY + pivotY;
						z = headZ;
						final float normHeadX = nx * headCosYaw + normPitchZ * headSinYaw;
						final float normHeadZ = -nx * headSinYaw + normPitchZ * headCosYaw;
						nx = normHeadX;
						ny = normPitchY;
						nz = normHeadZ;
					}
					// ③ 绕 Y 的身体档偏转（与 Minecraft 的 POSITIVE_Y 同向）：身体与头共用这一句，
					//    头在 ①② 之后已经带着自己的姿势，这里只是整颗头再跟着身体转到位
					positions[i * 3] = x * cos + z * sin;
					positions[i * 3 + 1] = y;
					positions[i * 3 + 2] = -x * sin + z * cos;
					normals[i * 3] = nx * cos + nz * sin;
					normals[i * 3 + 1] = ny;
					normals[i * 3 + 2] = -nx * sin + nz * cos;
				}
				YAW_POSITIONS[bucket][variant] = positions;
				YAW_NORMALS[bucket][variant] = normals;
			}
		}
		int headBoxes = 0;
		for (final boolean head : HEAD_VERTEX) {
			if (head) {
				headBoxes++;
			}
		}
		System.out.println("[MMTR-CROWD] 顶点表：每档 " + VERTEX_COUNT + " 顶点 × 16 朝向 × " + HEAD_VARIANTS
			+ " 头档（共 " + (VERTEX_COUNT * 16 * HEAD_VARIANTS * 6 * 4 / 1024) + " KB ｜ 头部盒 " + headBoxes
			+ "/" + BOXES.size() + " 个）｜ 头档角度（偏转, 俯仰）= " + headPoseSummary());
	}

	/**
	 * 四档头姿势的**真实角度**一行（建表那条日志打的就是它）。
	 *
	 * <p>为什么要把角度原样打出来、还要标"朝下/仰头"：低头那一档的正负号依赖"模型正面在 −z"这条
	 * 建模约定（见 {@link #HEAD_PITCH_DEGREES}），而这一点**实机一眼可辨** —— 看着不对就用
	 * {@code -Dmmtr.statues.headpitch=30} 翻过来。写死一句"统一低头 30°"会把翻过来的那一局也报成低头，
	 * 所以这里把这一局实际用的数与方向都打出来：日志自己就能对账，不必回到代码里猜。</p>
	 */
	private static String headPoseSummary() {
		final StringBuilder builder = new StringBuilder();
		for (int variant = 0; variant < HEAD_VARIANTS; variant++) {
			if (variant > 0) {
				builder.append(" ／ ");
			}
			final double pitch = HEAD_PITCH_DEGREES_PER_VARIANT[variant];
			builder.append(HEAD_VARIANT_NAMES[variant]).append('(')
				.append(degrees(HEAD_YAW_DEGREES[variant])).append("°, ")
				.append(degrees(pitch)).append("° ")
				.append(pitch < 0 ? "朝下" : pitch > 0 ? "仰头" : "平视").append(')');
		}
		return builder.toString();
	}

	/** 角度打印（只求日志好看：一位小数即可，四档只有 0 / ±30 与 {@code headpitch} 给的那个数）。 */
	private static String degrees(double value) {
		return Double.toString(Math.round(value * 10.0) / 10.0);
	}

	/** 旋转后的法线 → 最接近的轴向（MTR 的四边形只接受一个 Direction）。 */
	private static org.mtr.mapping.holder.Direction nearestDirection(float x, float y, float z) {
		final float ax = Math.abs(x);
		final float ay = Math.abs(y);
		final float az = Math.abs(z);
		if (ay >= ax && ay >= az) {
			return y >= 0 ? org.mtr.mapping.holder.Direction.UP : org.mtr.mapping.holder.Direction.DOWN;
		}
		if (ax >= az) {
			return x >= 0 ? org.mtr.mapping.holder.Direction.EAST : org.mtr.mapping.holder.Direction.WEST;
		}
		return z >= 0 ? org.mtr.mapping.holder.Direction.SOUTH : org.mtr.mapping.holder.Direction.NORTH;
	}

	/**
	 * 面角序与角上的 uv 分配都照**原版**（`CubeFace` 角表 + `ModelElementTexture` 的 u/v 取角规则，
	 * 见烘焙器里的注释）；这里只把"盒子局部坐标"取出来。
	 */
	private static final int[][][] FACE_CORNERS = {
		{{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}},       // down
		{{0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0}},       // up
		{{1, 1, 0}, {1, 0, 0}, {0, 0, 0}, {0, 1, 0}},       // north
		{{0, 1, 1}, {0, 0, 1}, {1, 0, 1}, {1, 1, 1}},       // south
		{{0, 1, 0}, {0, 0, 0}, {0, 0, 1}, {0, 1, 1}},       // west
		{{1, 1, 1}, {1, 0, 1}, {1, 0, 0}, {1, 1, 0}},       // east
	};
	private static final float[][] FACE_NORMALS = {
		{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0},
	};

	private static float[] corner(int face, int index, Box box) {
		final int[] pick = FACE_CORNERS[face][index];
		return new float[]{
			pick[0] == 0 ? box.low[0] : box.high[0],
			pick[1] == 0 ? box.low[1] : box.high[1],
			pick[2] == 0 ? box.low[2] : box.high[2],
		};
	}

	/** 候选格数量（读数用；= 上一次扫描看到的标记格数）。 */
	public static int spotCount() {
		return candidateCount;
	}

	/** 上一帧排进队列的**格数**（读数用）。 */
	public static int lastDrawnCount() {
		return lastDrawn;
	}
}
