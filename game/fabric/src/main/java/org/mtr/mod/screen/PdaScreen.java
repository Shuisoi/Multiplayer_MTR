package org.mtr.mod.screen;

import org.mtr.mapping.holder.ClickableWidget;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.ButtonWidgetExtension;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.client.MmtrDutyView;
import org.mtr.mod.client.MmtrPdaList;
import org.mtr.mod.client.MinecraftClientData;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.packet.PacketMmtrDutyOp;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * **综合运转面板（PDA）**（notes/408 §3）。
 *
 * <h2>它回答的问题</h2>
 * <p>"场上哪几趟车我能上、上去之后归不归我、我什么时候能退出" —— 这三件事在这块面板出现之前
 * **没有任何一处显示**（notes/408 §1：HUD 上那行"司机/自动"在 2026-09-25 被删掉了，
 * 而删掉之后"这趟车归谁"就没有单一来源了）。</p>
 *
 * <h2>两页</h2>
 * <ul>
 *   <li><b>车次列表页</b>：一行一趟车（车次 / 值守状态 / 当前这一步 / 下一站 / 速度），
 *       行尾两个按钮 <b>直接上车</b> / <b>站台接站</b>，可用性由 {@link MmtrDutyView} 那张表定；
 *       如果那趟车是我自己在值守，行尾换成 <b>取消值守</b>。</li>
 *   <li><b>驾驶页</b>（驾驶中按 TAB 直接翻到这一页）：我正在开的那趟车 —— 作业单、第几步、
 *       这一步要干什么、子任务提示、速度、到停车点距离，加两个按钮 <b>马上退出</b> / <b>下一站退出</b>。</li>
 * </ul>
 *
 * <h2>名单从哪来（2026-10-09 修的那条 bug）</h2>
 * <p>列表页的行**不再**由本类遍历 {@code MinecraftClientData.vehicles} 造出来。那份镜像是
 * **按玩家位置同步**的（{@code PacketMmtrBoardPlayer} 的类注释里写着），玩家站在几百格外时
 * 那辆车根本不在镜像里 —— 用户原话："这边 PDA 没有显示全部车次，而是只有附近的车次"。
 * 现在名单只有一个来源：引擎的 {@code MmtrDutyRegistry.allVehicleRows()}（它枚举**全部股道**上的车），
 * 经 {@code PacketMmtrDutyList} 落到 {@link MmtrPdaList}，本类只读那份缓存
 * （打开面板时与之后每秒各要一次，上行是 {@link PacketMmtrDutyOp.Op#LIST}）。</p>
 *
 * <h2>几条刻意的取舍</h2>
 * <ol>
 *   <li><b>不判业务、不造数据</b>：这一页的字全部来自**引擎**（列表行的字段，或车上那几个
 *       {@code *FromSync()} 镜像），按钮能不能点用的是 {@link MmtrDutyView} 里那张表的同一份编码。
 *       引擎仍然是权威 —— 它拒绝时会把原因写进 {@code [MMTR-DUTY]} 指令日志，面板把回话显示在底部那一行。</li>
 *   <li><b>不超过 10 行、不做滚动</b>：现场这局场上 10 辆车（{@code [MMTR-HLTH] vehicles=10}），
 *       一屏放得下；多出来的车次在末尾老实写"+N 趟没显示"，不假装列表是完整的。
 *       真需要滚动再换 {@code DashboardList}，那是一次独立的重构。</li>
 *   <li><b>只在有界面时重建</b>：{@code tick2} 每拍重算行与按钮状态（车次会增删、值守会变），
 *       但**不**每拍发包 —— 名单每秒要一次（只读的刷新），唯一的"写"是玩家按按钮那一下。</li>
 *   <li><b>驾驶页的"我值守的那趟车"从引擎行里按 {@code dutyCrew} 找</b>，不从本地镜像找：
 *       人站在几百格外时本地镜像里没有那辆车，靠它找会让驾驶页翻成空白。车**在我附近**时
 *       仍然读本地那辆车（步号 / 子任务只有它带）。</li>
 * </ol>
 */
public class PdaScreen extends MTRScreenBase implements IGui {

	/** 一行的高度（字 + 两个按钮，与 {@code SQUARE_SIZE} 对齐）。 */
	private static final int ROW_HEIGHT = 22;
	/** 一屏最多显示几趟车。 */
	private static final int MAX_ROWS = 10;
	private static final int ROW_BUTTON_WIDTH = 64;
	private static final int ROW_BUTTON_HEIGHT = 18;
	/** 名单多久向引擎要一次（面板打开时另有一次，见 {@link #requestRows(boolean)}）。 */
	private static final long LIST_REFRESH_INTERVAL_MILLIS = 1000L;

	private final boolean drivePageOnOpen;
	private boolean drivePage;

	/** 列表页：第 i 行现在显示的是哪趟车（0 = 这一行空着）。 */
	private final long[] rowVehicleIds = new long[MAX_ROWS];
	/** 列表页每行的两个按钮。 */
	private final ButtonWidgetExtension[] boardButtons = new ButtonWidgetExtension[MAX_ROWS];
	private final ButtonWidgetExtension[] meetButtons = new ButtonWidgetExtension[MAX_ROWS];

	/**
	 * 这一拍拿到的引擎名单（{@code tick2} 存下，{@code render} 与按钮读的是**同一份快照**）——
	 * 于是"画出来的那一行"与"按下去发出去的那辆车"永远是同一趟车，哪怕下一秒名单就换了。
	 */
	private List<MmtrPdaList.Row> rows = Collections.emptyList();

	/** 驾驶页。 */
	private final ButtonWidgetExtension buttonExitNow;
	private final ButtonWidgetExtension buttonExitNext;
	private final ButtonWidgetExtension buttonTogglePage;

	/** 底部那一行"上一次动作的结果"（引擎被拒的原因原样显示，或本地的"已发送"）。 */
	private String notice = "";
	/** 驾驶页现在盯的是哪趟车（0 = 还没有）。 */
	private long focusedVehicleId;
	/** 上一次向引擎要名单的时刻（毫秒）—— 打开面板时立刻要一次，之后每秒一次。 */
	private long lastListRequestMillis;

	public PdaScreen(boolean drivePage) {
		super();
		this.drivePageOnOpen = drivePage;
		this.drivePage = drivePage;

		for (int i = 0; i < MAX_ROWS; i++) {
			final int row = i;
			boardButtons[i] = new ButtonWidgetExtension(0, 0, 0, ROW_BUTTON_HEIGHT, TextHelper.literal("直接上车"), button -> send(row, PacketMmtrDutyOp.Op.CLAIM_DIRECT));
			meetButtons[i] = new ButtonWidgetExtension(0, 0, 0, ROW_BUTTON_HEIGHT, TextHelper.literal("站台接站"), button -> send(row, PacketMmtrDutyOp.Op.CLAIM_PLATFORM));
			boardButtons[i].visible = false;
			meetButtons[i].visible = false;
		}

		buttonExitNow = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.literal("马上退出"), button -> {
			sendFocused(PacketMmtrDutyOp.Op.EXIT_NOW);
			notice = "已请求：马上退出（交还自动执行）—— 结果见引擎指令日志 [MMTR-DUTY]";
		});
		buttonExitNext = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.literal("下一站退出"), button -> {
			sendFocused(PacketMmtrDutyOp.Op.EXIT_NEXT);
			notice = "已请求：下一站退出 —— 到站停稳后自动交还；若有接站的人在等，车交给他";
		});
		buttonTogglePage = new ButtonWidgetExtension(0, 0, 0, SQUARE_SIZE, TextHelper.literal(""), button -> {
			// ★ 必须写 `this.drivePage`：构造函数那个同名**参数**把字段遮住了，而参数在 lambda 里
			//   不是 effectively final（它没有重新赋值，但它是参数 —— 实测 javac 报"必须是最终变量"）。
			this.drivePage = !this.drivePage;
			notice = "";
		});
	}

	@Override
	protected void init2() {
		super.init2();

		for (int i = 0; i < MAX_ROWS; i++) {
			addChild(new ClickableWidget(boardButtons[i]));
			addChild(new ClickableWidget(meetButtons[i]));
		}
		addChild(new ClickableWidget(buttonExitNow));
		addChild(new ClickableWidget(buttonExitNext));
		addChild(new ClickableWidget(buttonTogglePage));

		// 开局若是"驾驶中按 TAB"，但此刻其实没有在开任何车（比如刚退出），就退回列表页 ——
		// 驾驶页在一辆空车上是没有意义的（四行字全是空的）。
		if (drivePage && myRow() == null && myVehicle() == null) {
			drivePage = false;
		}
		// 打开面板就立刻要一份名单（不管 1 秒节流）；之后由 tick2 每秒要一次。
		requestRows(true);
		layout();
	}

	@Override
	public void tick2() {
		/*
		 * 驾驶页盯的是**引擎说的那趟车**（dutyCrew == 我），不是"我附近镜像里有没有它"：
		 * 人站在几百格外时那辆车不在本地镜像里，靠镜像找会让驾驶页翻成空白。
		 */
		final MmtrPdaList.Row engineRow = myRow();
		final VehicleExtension localVehicle = myVehicle();
		final long focused = engineRow != null ? engineRow.vehicleId() : (localVehicle == null ? 0 : localVehicle.getId());
		if (drivePage && focused == 0) {
			drivePage = false;
		}
		focusedVehicleId = focused;
		requestRows(false);
		refreshRows();
		// 切换按钮的文案要跟着当前页走 —— 原来是个**空白按钮**（构造时给的空串），
		// 玩家看不出它是干什么的。文案说的是"按下去会看到什么"，不是"现在在哪一页"。
		buttonTogglePage.setMessage2(new Text(TextHelper.literal(drivePage ? "看车次列表" : "看我开的车").data));
		layout();
	}

	/**
	 * 向引擎要一份**全部车次**的名单（上行 {@link PacketMmtrDutyOp.Op#LIST}，vehicleId 传 0）。
	 *
	 * <p>为什么不自己从 {@code MinecraftClientData.vehicles} 造行：那份镜像**按玩家位置同步**，
	 * 站在几百格外时车不在里面 —— "只显示附近的车次"就是这么来的。引擎是唯一真源，
	 * 界面只显示它给的那一份（{@link MmtrPdaList}）。</p>
	 *
	 * @param force {@code true} = 面板刚打开，不管 1 秒节流立刻问一次
	 */
	private void requestRows(boolean force) {
		final long now = System.currentTimeMillis();
		if (!force && now - lastListRequestMillis < LIST_REFRESH_INTERVAL_MILLIS) {
			return;
		}
		lastListRequestMillis = now;
		org.mtr.mod.InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrDutyOp(0, PacketMmtrDutyOp.Op.LIST));
	}

	private void layout() {
		final int rightEdge = width - TEXT_PADDING;
		/*
		 * 切换按钮放**顶部右侧**（与标题同一行）。
		 *
		 * 原来是放底部的，但底部是"上一次动作结果"那一行（notice）与驾驶页两个按钮的地盘 ——
		 * 在小窗口（GUI 缩放 4 时 height 只有 ~270）它们会互相压住。放顶部就永远不冲突，
		 * 而且"这一页的另一页怎么看"本来就该和标题在一起。
		 */
		IDrawing.setPositionAndWidth(buttonTogglePage, rightEdge - PANEL_WIDTH, TEXT_PADDING, PANEL_WIDTH);
		for (int i = 0; i < MAX_ROWS; i++) {
			final int y = listTop() + i * ROW_HEIGHT;
			IDrawing.setPositionAndWidth(boardButtons[i], rightEdge - 2 * ROW_BUTTON_WIDTH - TEXT_PADDING, y, ROW_BUTTON_WIDTH);
			IDrawing.setPositionAndWidth(meetButtons[i], rightEdge - ROW_BUTTON_WIDTH, y, ROW_BUTTON_WIDTH);
		}
		/*
		 * 驾驶页两个按钮：正文是 7 行（车次/值守/时刻表/这一步/现在该做/速度/子任务），
		 * 所以从第 7 行（= listTop + 7 * ROW_HEIGHT）下面开始 —— 实测原来写 6 行，正好压在
		 * "子任务"那一行上（按钮盖住文字，而且盖住的是最难看清的那一行）。
		 */
		final int driveY = listTop() + 7 * ROW_HEIGHT;
		IDrawing.setPositionAndWidth(buttonExitNow, rightEdge - PANEL_WIDTH, driveY, PANEL_WIDTH - TEXT_FIELD_PADDING);
		IDrawing.setPositionAndWidth(buttonExitNext, rightEdge - PANEL_WIDTH, driveY + SQUARE_SIZE + TEXT_PADDING, PANEL_WIDTH - TEXT_FIELD_PADDING);
	}

	private int listTop() {
		return SQUARE_SIZE + TEXT_PADDING;
	}

	/**
	 * 列表页：把**引擎给的那份名单**排成行（{@link MmtrPdaList}），并按 {@link MmtrDutyView} 决定按钮的可见性。
	 *
	 * <p>名单本身不在本类里拼：以前这里遍历 {@code MinecraftClientData.vehicles}（按玩家位置同步的镜像），
	 * 于是站在几百格外只能看到附近几趟车。现在只读引擎的名单 —— 客户端不推断"场上有哪些车次"。</p>
	 */
	private void refreshRows() {
		final String myUuid = myUuid();
		rows = MmtrPdaList.rows();
		for (int i = 0; i < MAX_ROWS; i++) {
			final boolean has = i < rows.size();
			rowVehicleIds[i] = has ? rows.get(i).vehicleId() : 0;
			if (!has) {
				boardButtons[i].visible = false;
				meetButtons[i].visible = false;
				continue;
			}
			final MmtrPdaList.Row row = rows.get(i);
			/*
			 * 三条可见性规则，全部来自 MmtrDutyView 那一张表（不在本类里另判一遍）：
			 *   · 无人 / 已退出 → 两个按钮都给；
			 *   · 有玩家·下一站退出 → 只给"站台接站"（用户口径：仅可以选择接站）；
			 *   · 我自己的值守 → 两个都不给，换成一个"取消值守"（自己跟自己接站没有意义）。
			 *
			 * 喂给那张表的状态名来自**引擎的 operatorOf**（不是车上那个镜像字段）：后者在
			 * "有人正在站台上等它"（WAITING）时是空串，而那张表把 WAITING 明确算成"谁都不能上"——
			 * 与引擎自己的拒绝口径（occupancyRefusal）一致。
			 */
			final MmtrDutyView.State state = MmtrDutyView.parse(row.dutyState());
			final boolean mine = MmtrDutyView.isMine(row.dutyCrew(), myUuid);
			final boolean direct = MmtrDutyView.canBoardDirect(state, mine);
			final boolean meet = MmtrDutyView.canMeetAtPlatform(state, mine);
			boardButtons[i].visible = direct || mine;
			meetButtons[i].visible = meet;
			boardButtons[i].active = direct;
			meetButtons[i].active = meet;
			boardButtons[i].setMessage2(new Text(TextHelper.literal(mine ? "取消值守" : "直接上车").data));
		}
		truncatedCount = Math.max(0, rows.size() - MAX_ROWS);
	}

	private int truncatedCount;

	@Override
	public void render(GraphicsHolder graphicsHolder, int mouseX, int mouseY, float delta) {
		renderBackground(graphicsHolder);
		final String myUuid = myUuid();
		/*
		 * 驾驶页那趟车 = **引擎说的"我值守的那趟"**（它可能离我很远、不在本地镜像里）。
		 * 本地镜像里有它时就用本地那个对象（步号 / 子任务只有它带），没有就只画引擎行的字段。
		 */
		final MmtrPdaList.Row engineRow = myRow();

		graphicsHolder.drawText(TextHelper.literal("综合运转面板" + (drivePage ? " · 驾驶" : " · 车次")), TEXT_PADDING, TEXT_PADDING, ARGB_WHITE, false, GraphicsHolder.getDefaultLight());

		if (drivePage) {
			renderDrivePage(graphicsHolder, engineRow == null ? myVehicle() : byId(engineRow.vehicleId()), engineRow);
		} else {
			renderListPage(graphicsHolder, myUuid);
		}

		if (!notice.isEmpty()) {
			graphicsHolder.drawText(TextHelper.literal(notice), TEXT_PADDING, height - SQUARE_SIZE - TEXT_PADDING, ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
		}
		super.render(graphicsHolder, mouseX, mouseY, delta);
	}

	private void renderListPage(GraphicsHolder graphicsHolder, String myUuid) {
		if (rows.isEmpty()) {
			graphicsHolder.drawText(TextHelper.literal(emptyListNotice()), TEXT_PADDING, listTop(), ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
			return;
		}
		final int count = Math.min(MAX_ROWS, rows.size());
		for (int i = 0; i < count; i++) {
			final MmtrPdaList.Row row = rows.get(i);
			final MmtrDutyView.State state = MmtrDutyView.parse(row.dutyState());
			final boolean mine = MmtrDutyView.isMine(row.dutyCrew(), myUuid);
			final String text = row.jobId()
				+ "  " + (mine ? "我 · " : "") + state.word()
				+ (!mine && !row.dutyCrew().isEmpty() ? "（" + shortUuid(row.dutyCrew()) + "）" : "")
				+ (row.dutyWaiting() > 1 ? "（" + row.dutyWaiting() + " 人认领）" : "")
				+ "  " + row.taskNote()
				+ nextStationSuffix(row)
				+ "  " + Math.round(row.speedKmh()) + "km/h";
			graphicsHolder.drawText(TextHelper.literal(text), TEXT_PADDING, listTop() + i * ROW_HEIGHT + 4, mine ? ARGB_WHITE : ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
		}
		if (truncatedCount > 0) {
			graphicsHolder.drawText(TextHelper.literal("还有 " + truncatedCount + " 趟车次没显示（这一页只放得下 " + MAX_ROWS + " 行）"), TEXT_PADDING, listTop() + MAX_ROWS * ROW_HEIGHT + 4, ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
		}
	}

	/**
	 * 名单还没到（或引擎说场上没有车次）时这一页写什么。
	 *
	 * <p>"缓存空"有两种意思，混在一起会让现场最难查：①**还没收到**（刚打开面板 / 引擎没回话）；
	 * ②**引擎说没有**（场上真的没有挂着车次号的车）。所以用 {@link MmtrPdaList#receivedAtMillis()}
	 * 把这两句分开说 —— 不假装"空列表 = 没有车次"。</p>
	 */
	private String emptyListNotice() {
		final long receivedAt = MmtrPdaList.receivedAtMillis();
		if (receivedAt == 0) {
			return "正在向引擎取车次列表…（本地镜像只同步你附近的车，所以这一页的名单必须由引擎给）";
		}
		final long seconds = Math.max(0, (System.currentTimeMillis() - receivedAt) / 1000);
		return "引擎回话：此刻场上没有挂着车次号的车（" + seconds + " 秒前）";
	}

	private void renderDrivePage(GraphicsHolder graphicsHolder, VehicleExtension vehicle, MmtrPdaList.Row row) {
		if (vehicle == null && row == null) {
			graphicsHolder.drawText(TextHelper.literal("我现在没有在开任何车（驾驶页要在一趟我自己值守的车上才有内容）"), TEXT_PADDING, listTop(), ARGB_LIGHT_GRAY, false, GraphicsHolder.getDefaultLight());
			return;
		}
		if (vehicle == null) {
			renderDrivePageFromEngineRow(graphicsHolder, row);
			return;
		}
		/*
		 * 值守状态一律优先用**引擎行**那一份：它来自 MmtrDutyRegistry.operatorOf，
		 * 而车上那个 mmtrDutyState 镜像在"有人正在站台上等它"（WAITING）时按设计是空串 ——
		 * 于是"我在等车"这种情况在驾驶页上会显示成"无人"，与列表页互相矛盾。只在没有行时读镜像。
		 */
		final String stateName = row == null ? vehicle.getMmtrDutyStateFromSync() : row.dutyState();
		final int step = vehicle.getMmtrTaskStepFromSync();
		final int steps = vehicle.getMmtrTaskStepsFromSync();
		int y = listTop();
		y = line(graphicsHolder, "车次：" + vehicle.getMmtrJobIdFromSync() + "（车 " + vehicle.getId() + "）", y, ARGB_WHITE);
		y = line(graphicsHolder, "值守：" + MmtrDutyView.parse(stateName).word() + "（引擎状态 " + stateName + "）", y, ARGB_WHITE);
		// 诚实写清这一页**看不到什么**：引擎只发"当前这一步"，整趟的剩余站序它不发。
		// 写在这一行里而不是另起一行，是因为另起的那一行正好会被下面两个按钮压住（实测）。
		y = line(graphicsHolder, "时刻表：第 " + step + " / " + steps + " 步（只发当前这一步，后续站序引擎不发）", y, ARGB_WHITE);
		y = line(graphicsHolder, "这一步：" + vehicle.getMmtrTaskNoteFromSync(), y, ARGB_WHITE);
		final String hint = vehicle.getMmtrSubTaskHintFromSync();
		if (!hint.isEmpty()) {
			y = line(graphicsHolder, "现在该做：" + hint, y, ARGB_WHITE);
		}
		y = line(graphicsHolder, "速度：" + Math.round(vehicle.getSpeed() * 3600) + " km/h"
			+ "  到停车点：" + Math.round(vehicle.getMmtrDistanceToStopTargetM()) + " m", y, ARGB_WHITE);
		line(graphicsHolder, "子任务：" + (vehicle.getMmtrSubTasksFromSync().isEmpty() ? "（这一步没有子任务）" : vehicle.getMmtrSubTasksFromSync()), y, ARGB_LIGHT_GRAY);
	}

	/**
	 * 驾驶页的"引擎行版"：**我值守的那趟车不在本地镜像里**（人离它几百格）时画这个。
	 *
	 * <p>这一页能说的是列表行带的那些字段（引擎算好的原话），说不了步号与子任务 ——
	 * 那两样只有车上的镜像带。如实写清，不拿别的东西凑数（铁律：引擎是唯一真源，
	 * 客户端不许自己推断车次）。</p>
	 */
	private void renderDrivePageFromEngineRow(GraphicsHolder graphicsHolder, MmtrPdaList.Row row) {
		int y = listTop();
		y = line(graphicsHolder, "车次：" + row.jobId() + "（车 " + row.vehicleId() + "）", y, ARGB_WHITE);
		y = line(graphicsHolder, "值守：" + MmtrDutyView.parse(row.dutyState()).word() + "（引擎状态 " + row.dutyState() + "）", y, ARGB_WHITE);
		y = line(graphicsHolder, "这一步：" + (row.taskNote().isEmpty() ? "（引擎没给这一步的说明）" : row.taskNote()), y, ARGB_WHITE);
		y = line(graphicsHolder, "下一站：" + (row.nextStation().isEmpty() ? "—（后面不再有站台作业）" : row.nextStation()), y, ARGB_WHITE);
		y = line(graphicsHolder, "速度：" + Math.round(row.speedKmh()) + " km/h"
			+ "  到停车点：" + (row.distanceToStopM() < 0 ? "—（本趟没有停车目标）" : Math.round(row.distanceToStopM()) + " m"), y, ARGB_WHITE);
		line(graphicsHolder, "这趟车不在你的客户端镜像里（你离它很远）：以上数字来自引擎的车次列表；步号与子任务只有车上的镜像带", y, ARGB_LIGHT_GRAY);
	}

	private int line(GraphicsHolder graphicsHolder, String text, int y, int color) {
		graphicsHolder.drawText(TextHelper.literal(text), TEXT_PADDING, y, color, false, GraphicsHolder.getDefaultLight());
		return y + ROW_HEIGHT;
	}

	private String nextStationSuffix(MmtrPdaList.Row row) {
		return row.nextStation().isEmpty() ? "" : "  下一站 " + row.nextStation();
	}

	@Override
	public boolean isPauseScreen2() {
		return false;
	}

	/** 列表某一行的按钮：把那一行**现在**对应的车发出去（行与车的对应关系每拍都可能在变）。 */
	private void send(int row, PacketMmtrDutyOp.Op op) {
		if (row < 0 || row >= MAX_ROWS || rowVehicleIds[row] == 0) {
			return;
		}
		sendOp(rowVehicleIds[row], op);
	}

	private void sendFocused(PacketMmtrDutyOp.Op op) {
		if (focusedVehicleId != 0) {
			sendOp(focusedVehicleId, op);
		}
	}

	private void sendOp(long vehicleId, PacketMmtrDutyOp.Op op) {
		org.mtr.mod.InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketMmtrDutyOp(vehicleId, op));
		notice = "已发送：" + op.label + "（车 " + vehicleId + "）—— 被拒的原因会写进引擎指令日志";
	}

	/**
	 * **引擎那份名单里我值守的那趟车**（{@code dutyCrew == 我的 uuid}）。
	 *
	 * <p>驾驶页的"我在开哪趟车"以**这一份**为准：名单是全局的（枚举全部股道上的车），
	 * 所以人站在几百格外时驾驶页照样有内容 —— 而本地镜像 {@code MinecraftClientData.vehicles}
	 * 里根本没有那辆车，靠它找会翻成空白。</p>
	 */
	private MmtrPdaList.Row myRow() {
		final String myUuid = myUuid();
		if (myUuid.isEmpty()) {
			return null;
		}
		for (final MmtrPdaList.Row row : MmtrPdaList.rows()) {
			if (MmtrDutyView.isMine(row.dutyCrew(), myUuid)) {
				return row;
			}
		}
		return null;
	}

	/**
	 * **本地镜像里那趟车**（只在我附近的车才在镜像里）。
	 *
	 * <p>它现在只提供"引擎行带不了"的那几样（步号、子任务、子任务提示），
	 * 以及"我没有值守但在驾驶室里"（操作员用指令把我放进来、或刚退出还没清）这一种现场的兜底 ——
	 * "我值守的那趟车"请看 {@link #myRow()}。</p>
	 */
	private VehicleExtension myVehicle() {
		final String myUuid = myUuid();
		if (myUuid.isEmpty()) {
			return null;
		}
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (MmtrDutyView.isMine(vehicle, myUuid)) {
				return vehicle;
			}
		}
		// 退一步：没有值守记录但我坐在驾驶室里（例如操作员用指令把我放进来、或刚退出还没清），
		// 也把驾驶页指向我在的这趟车 —— 否则"驾驶中按 TAB"会翻到一张空页。
		return MmtrDriverSeat.ridingVehicle();
	}

	private String myUuid() {
		final org.mtr.mapping.holder.ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		final UUID uuid = player == null ? null : player.getUuid();
		return uuid == null ? "" : uuid.toString();
	}

	private VehicleExtension byId(long vehicleId) {
		for (final VehicleExtension vehicle : MinecraftClientData.getInstance().vehicles) {
			if (vehicle.getId() == vehicleId) {
				return vehicle;
			}
		}
		return null;
	}

	/** uuid 太长写不下，界面上只留前 8 位（够认人，且不会把行撑开）。 */
	private static String shortUuid(String uuid) {
		return uuid == null || uuid.length() <= 8 ? (uuid == null ? "" : uuid) : uuid.substring(0, 8);
	}
}
