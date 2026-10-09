package org.mtr.mod.render;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.MutableText;
import org.mtr.mapping.holder.Text;
import org.mtr.mapping.mapper.GraphicsHolder;
import org.mtr.mapping.mapper.GuiDrawing;
import org.mtr.mapping.mapper.TextHelper;
import org.mtr.mod.client.IDrawing;
import org.mtr.mod.client.MmtrDriverSeat;
import org.mtr.mod.client.MmtrSubTaskView;
import org.mtr.mod.data.IGui;
import org.mtr.mod.data.VehicleExtension;
import org.mtr.mod.sound.MmtrTaskSounds;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * **屏幕左上角的作业（任务）卡片** + 任务三种时刻的**音符盒提示音**。
 *
 * <h2>为什么从右上角搬到左上角（用户口径 2026-09-25）</h2>
 *
 * <p>在这之前，作业 / 任务 / 子任务 / 提示这四行是塞在右上角 {@link MmtrDriverHud} 那块面板里的，
 * 与速度 / 电机 / 手柄 / 管压 / 制动力这些**操作读数**混在一起。用户这一轮的要求是把任务单独做成
 * 一块 UI 并<b>放到左上角</b>，于是：</p>
 *
 * <ul>
 *   <li>左上角这块**只说任务**：作业单名 · 时间 · 任务状态 · 任务说明（大号字）· 到目标点的距离 ·
 *       当前那条子任务 · 现在该按的键；</li>
 *   <li>右上角那块**只说车**：速度 / 加速度 / 电机 / 手柄 / 气压 / 制动力 / 定速 / 换向，
 *       **不带任何任务行**（用户口径 2026-09-25「右上角不要再出现作业/任务/子任务/提示」）。</li>
 * </ul>
 *
 * <p>两块都**没有标题行**（用户口径 2026-09-25「先把前缀全部删除」）：标题是给"第一次看这块 UI"的人
 * 用的，而这两块的信息各自只有一类，看内容就知道是哪块；常驻的标题只是在每屏每个角落都占一行。</p>
 *
 * <p>这不是纯审美问题：任务信息与操作读数混排时，面板宽度由最长的那一行决定，而作业说明可以很长 ——
 * 一块面板又宽又高，把右上角（视线扫过速度与手柄的落点）占满了。拆开之后两块各自窄而短，
 * 左上角放"我现在该干什么"、右上角放"车现在什么状态"，正好对应司机的两种注意力。</p>
 *
 * <h2>什么时候显示</h2>
 *
 * <p><b>坐在这列车上就有</b>（不限司机位）—— 与右上角的操作读数不同：那块只有握着驾驶室钥匙的人该看
 * （判据 {@link MmtrDriverSeat#isAtControls()}），而"这趟车在做什么作业"是车上所有人都能看到的信息，
 * 乘客与自动驾驶下的人也一样看得到。没有作业的车（调车自由开、停放车）不画。</p>
 *
 * <p><b>但提示音与动作栏播报只给司机</b>（同一条 {@link MmtrDriverSeat#isAtControls()} 判据）：
 * "请按开门键"是说给正在干活的那个人的 —— 乘客在自动车上每站听一遍提示音、看一遍"子任务：请按开门键"，
 * 既是噪音也是误导（他按不了，也不该按）。所以卡片看得见 ≠ 会响。</p>
 *
 * <p>任务做完（{@code COMPLETE} / {@code FAILED}）之后卡片**多留几秒**再说再见：完成那一刻
 * 玩家可能刚下车或在看别处，收工结果必须留在屏幕上够久（{@link #COMPLETE_LINGER_MILLIS}）。</p>
 *
 * <h2>判定一个字都不在这里</h2>
 *
 * <p>与其它 MMTR HUD 同一条规矩（notes/170 §8.4）：作业号、人话说明、第几步、谁在执行、子任务清单、
 * "现在该做什么"，全部由引擎算好按原话发下来。这里只做两件事：<b>画</b>，以及
 * <b>比较这一拍与上一拍</b>好知道该不该响提示音 / 播报一句。</p>
 *
 * <h2>提示音为什么要"比较"而不是"监听事件"</h2>
 *
 * <p>客户端没有任务事件流，拿到的是每拍刷新的**镜像快照**（{@code VehicleExtension} 的
 * {@code getMmtr*FromSync()}）。所以"一条子任务完成 / 换了一步 / 整趟做完"这三种时刻，
 * 只能由**相邻两拍的快照之差**得到。三处跃迁分别是：</p>
 *
 * <ol>
 *   <li><b>子任务完成</b>：{@code getMmtrSubTaskRevisionFromSync()} 涨了（引擎每判定一条完成就 +1），
 *       比"数清单里有几个 DONE"更稳 —— 清单本身与判定无关地重发时不会误报；</li>
 *   <li><b>换步</b>：{@code getMmtrTaskStepFromSync()} 变了（且仍是同一趟作业、仍在执行）；</li>
 *   <li><b>整趟完成</b>：{@code getMmtrMissionStateFromSync()} 变成 {@code COMPLETE}。</li>
 * </ol>
 *
 * <p>三种音各自什么样、为什么这样选，见 {@link MmtrTaskSounds}。</p>
 * <p>失败（{@code FAILED}）不响"完成音" —— 那是收工音，用在失败上会骗人；失败只把卡片留在屏幕上
 * （并染警示色），让司机自己看到"这趟没跑完"。</p>
 */
public final class MmtrTaskHud {

	private MmtrTaskHud() {
	}

	private static final int EDGE_PADDING = 6;
	private static final int PADDING = 5;
	private static final int LINE_SPACING = 2;
	private static final int BACKGROUND_COLOR = 0xA0000000;
	/** 面板最左那根状态色竖条的宽度（像素）。 */
	private static final int ACCENT_WIDTH = 2;
	private static final int LABEL_COLOR = 0xFF9FB3C6;
	private static final int VALUE_COLOR = 0xFFF2F4F6;
	private static final int JOB_COLOR = 0xFF9FE08F;
	/** 大号字任务那一行：整块卡片的主角，用最高对比度的亮色。 */
	private static final int TASK_COLOR = 0xFFFFFFFF;
	private static final int SUB_TASK_COLOR = 0xFFCFE8FF;
	/** 按键提示那一行：最实用的一行（现在按什么），给一个比子任务更"要动手"的暖色。 */
	private static final int KEY_HINT_COLOR = 0xFFFFD98A;
	private static final int COMPLETE_COLOR = 0xFF7CE38B;
	private static final int FAILED_COLOR = 0xFFFF6B6B;
	/** 完成/失败那几秒给整块面板染一层淡绿/淡红，让"收工了"在余光里也看得见。 */
	private static final int COMPLETE_TINT = 0x307CE38B;
	private static final int FAILED_TINT = 0x30FF6B6B;

	/**
	 * **大号字任务**那一行的字号倍率（用户口径 2026-09-25「然后是大号字任务」）。
	 *
	 * <p>≈16px：明显的"主角"字号，又不至于把卡片撑成半屏。用整体缩放实现（见 {@link #drawScaledText}），
	 * 不去加新的字体 provider。</p>
	 */
	private static final float TASK_SCALE = 1.57F;
	/**
	 * **时间**那一行的字号倍率（用户口径 2026-09-25「时间的字体太小」）。
	 *
	 * <p>≈11px：明显比正文（8px）大、但又**小于**大号任务行 —— 它是"锚"，不是主角；
	 * 若跟大任务一样大，两行会互相抢，卡片就没有主视觉了。</p>
	 */
	private static final float TIME_SCALE = 1.4F;
	/**
	 * 大号任务那一行的**四周留白**（屏幕像素）：左边比其余行多让这么多，上下各让一半。
	 *
	 * <p>用户口径 2026-09-25「大任务显示位置太靠左」—— 它原来与其余行严格左对齐，读数上"对，
	 * 但看着挤在边上"。让出这一段之后它自成一块，与上面两行拉开距离。</p>
	 */
	private static final int TASK_LINE_INSET = 8;
	/** 按键提示框的内边距（框体比框内文字四周各大这么多像素）。 */
	private static final int KEY_BOX_PAD = 2;
	/** 按键提示框：底色比卡片底色略亮一点，让它看起来是"框里的一条"。 */
	private static final int KEY_BOX_BACKGROUND_COLOR = 0x50000000;
	/** 按键提示框的描边色（与框内文字同色，于是整条提示是一个整体）。 */
	private static final int KEY_BOX_BORDER_COLOR = 0xFFFFD98A;
	/**
	 * 大号任务行在**基础字号**下最多占多少像素。
	 *
	 * <p>为什么要有这个上限：任务说明可以很长（作业单的 {@code note}），若宽度按缩放后的真实像素算，
	 * 一条长说明会把卡片撑到整屏宽。按基础宽度截断 ⇒ 缩放后的宽度也有界（×{@link #TASK_SCALE}），
	 * 于是卡片宽度可预测，且超长内容以 {@code …} 明示被裁过。</p>
	 */
	private static final int TASK_LINE_MAX_BASE_PX = 150;
	/** 换步/完成之后面板染色的持续时间（毫秒）—— 一句话的长度，够余光扫到。 */
	private static final long FLASH_MILLIS = 1500;
	/** 任务终态之后卡片继续显示的时长（毫秒）。 */
	private static final long COMPLETE_LINGER_MILLIS = 8000;

	/**
	 * 上一拍比较用的键。**整块一起换**：换车（上了另一列车）时必须一次性重置，
	 * 否则会拿 A 车的步号与 B 车的比，凭空"换步"响一声。
	 */
	private static long trackedVehicleId = -1;
	private static String trackedJobId = "";
	private static int trackedStep = -1;
	private static int trackedSteps;
	private static String trackedState = "";
	/**
	 * 已见过的子任务版本号。用 {@link Long#MIN_VALUE} 而不是 {@code -1} 表示"还没见过" ——
	 * 引擎的版本号**从 0 开始**，若拿 -1 当哨兵，上车时那一拍就会把"初次看到 rev 0"当成
	 * "涨了一条"而凭空响一声（实测最容易在"上车即到站停稳完成"的场景里出现）。
	 */
	private static long trackedRevision = Long.MIN_VALUE;
	private static String trackedNote = "";
	private static String trackedHint = "";
	/** 上一拍已完成的子任务条数（由 revision 变化触发重数）。 */
	private static int trackedDoneCount;
	/** 跃迁的显示用时刻（0 = 还没跃迁过）。 */
	private static long flashMillis;
	private static boolean flashFailed;
	/** 任务进入终态的时刻（0 = 不在终态）—— 卡片留几秒就靠它。 */
	private static long completedAtMillis;

	/**
	 * 一行里的**一段文字**：文本 + 颜色 + **字号倍率**。
	 *
	 * <p>为什么要分段而不是"一行一个字符串"：用户口径 2026-09-25「作业单的名字写在时间右边」要求
	 * **同一行里两种字号两种颜色**（时间大、作业名小）。把倍率放在段上，测量、行高、绘制三处读同一个数，
	 * 改一处不会让别处错位。</p>
	 */
	private record Seg(String text, int color, float scale) {
	}

	/** 一行 = 若干段（从左到右紧接着排），行高取本行**最高的一段**。 */
	private record Row(List<Seg> segs) {

		static Row of(String text, int color) {
			return new Row(List.of(new Seg(text, color, 1F)));
		}

		static Row of(String text, int color, float scale) {
			return new Row(List.of(new Seg(text, color, scale)));
		}

		static Row of(Seg... segs) {
			return new Row(List.of(segs));
		}

		/**
		 * 行高 = **最高那一段**的字号。
		 *
		 * <p>注意不能取第一段：时间那一行第一段（时间）比第二段（作业名）大，取第一段只是碰巧对；
		 * 反过来就会把行高算小、两行文字叠在一起。</p>
		 */
		float lineScale() {
			float tallest = 1F;
			for (final Seg seg : segs) {
				tallest = Math.max(tallest, seg.scale());
			}
			return tallest;
		}

		/** 这一行是不是"大号行"（有字号 &gt; 1 的段）。 */
		boolean isLarge() {
			for (final Seg seg : segs) {
				if (seg.scale() > 1F) {
					return true;
				}
			}
			return false;
		}

		/** 段之间的间隔。 */
		static final int SEG_GAP = 6;
	}

	/**
	 * **每帧跑一次**：推进状态、该响的响、该播报的播报，然后把卡片画出来。
	 *
	 * <p>注册在 GUI 渲染钩子上（{@link org.mtr.mod.InitClient}）。这个钩子**每帧都调用**，
	 * 与本类是否需要画无关 —— 这一点是刻意的：任务跃迁的探测不能只在"看得见卡片"时才做。</p>
	 */
	public static void tick(GraphicsHolder graphicsHolder) {
		MmtrTaskSounds.tick();
		final VehicleExtension vehicle = MmtrDriverSeat.ridingVehicle();
		if (update(vehicle) && MinecraftClient.getInstance().getCurrentScreenMapped() == null) {
			render(graphicsHolder, vehicle);
		}
	}

	/**
	 * 比较这一拍与上一拍，返回**这一拍是否该画卡片**。
	 *
	 * <p>把"推进状态"与"画"分开，是为了让音效与播报不受 GUI 开关影响：
	 * 玩家开着背包时卡片不画，但子任务照样在完成，提示音不能因此丢。</p>
	 */
	private static boolean update(@Nullable VehicleExtension vehicle) {
		if (vehicle == null) {
			clearTracking();
			return false;
		}
		if (vehicle.getId() != trackedVehicleId) {
			// 上了另一列车：静默重置（这不算"换步"，不该响）。
			resetFor(vehicle.getId());
		}

		final String jobId = vehicle.getMmtrJobIdFromSync();
		final int step = vehicle.getMmtrTaskStepFromSync();
		final int steps = vehicle.getMmtrTaskStepsFromSync();
		final String state = vehicle.getMmtrMissionStateFromSync();
		final String note = vehicle.getMmtrTaskNoteFromSync();
		final String hint = vehicle.getMmtrSubTaskHintFromSync();
		final List<MmtrSubTaskView.Entry> subTasks = MmtrSubTaskView.of(vehicle);
		final boolean hasJob = !jobId.isEmpty() || step >= 0;
		/*
		 * 提示音与动作栏播报**只给握着驾驶室钥匙的人**（与右上角操作读数同一条判据）。
		 *
		 * 卡片本身是"这趟车在做什么作业"，车上所有人都该看得到（所以它不设这个闸）；
		 * 但"你该开门了"这种播报与提示音是说给**正在干活的那个人**的：乘客在自动车上每站都听一遍
		 * 提示音、看一遍"子任务：请按开门键"，那既是噪音也是误导（他按不了，也不该按）。
		 */
		final boolean notify = MmtrDriverSeat.isAtControls();

		/*
		 * ①换步：同一趟作业、步号变了。两个"不算换步"的例外：
		 *   · trackedStep < 0 —— 刚接上这趟活，那是"开始"不是"换步"；
		 *   · 上一拍已是终态（COMPLETE/FAILED）—— 那时步号是被收工清掉的，不是往下走了一步。
		 */
		if (hasJob && notify && jobId.equals(trackedJobId) && trackedStep >= 0 && step != trackedStep && !isTerminal(trackedState)) {
			MmtrTaskSounds.stepChanged(step);
			flashMillis = System.currentTimeMillis();
			flashFailed = false;
		}

		/*
		 * ②一条子任务完成：靠引擎的子任务版本号。换了一段作业（版本号变小 = 新一轮）就只记基线，不响。
		 */
		final long revision = vehicle.getMmtrSubTaskRevisionFromSync();
		if (revision > trackedRevision) {
			final int doneCount = countDone(subTasks);
			if (notify && trackedRevision != Long.MIN_VALUE && doneCount > trackedDoneCount) {
				MmtrTaskSounds.subTaskDone(doneCount, subTasks.size());
				flashMillis = System.currentTimeMillis();
				flashFailed = false;
			}
			trackedRevision = revision;
			trackedDoneCount = doneCount;
		} else if (revision < trackedRevision) {
			// 换了一段作业（版本号变小 = 引擎重开了子任务链）：只记基线，不响。
			trackedRevision = revision;
			trackedDoneCount = countDone(subTasks);
		}

		/* ③整趟作业完成 / 失败：只在**进入**终态的那一拍响一次。 */
		final boolean terminal = isTerminal(state);
		if (terminal && !isTerminal(trackedState)) {
			completedAtMillis = System.currentTimeMillis();
			flashMillis = completedAtMillis;
			flashFailed = !isComplete(state);
			if (notify) {
				if (isComplete(state)) {
					MmtrTaskSounds.jobComplete();
				}
				announce(completeText(state, jobId));
			}
		} else if (!terminal && isTerminal(trackedState)) {
			// 新的一趟接上了：清掉上一趟的收工痕迹。
			completedAtMillis = 0;
		}

		/*
		 * 动作栏播报（与提示音同一个理由：司机在站台上等，只更新角落里的小字等于没说）。
		 * 与音效**分开**的判据：换步时播报"下一步做什么"，子任务提示变化时播报"现在该做什么"。
		 * 两者都在文字真的变了时才说一次，所以"还差 12s"那种每秒变的计时不会刷屏。
		 */
		if (notify && hasJob && !note.isEmpty() && !note.equals(trackedNote)) {
			announce("任务 " + (steps > 0 && step >= 0 ? (step + 1) + "/" + steps + "：" : "") + note);
		}
		if (notify && !hint.isEmpty() && !hint.equals(trackedHint)) {
			announce("子任务：" + hint);
		}

		trackedJobId = jobId;
		trackedStep = step;
		trackedSteps = steps;
		trackedState = state;
		trackedNote = note;
		trackedHint = hint;

		if (!hasJob) {
			return false;
		}
		if (!terminal) {
			return true;
		}
		// 终态：完成/失败之后留几秒再撤掉卡片。
		return completedAtMillis > 0 && System.currentTimeMillis() - completedAtMillis < COMPLETE_LINGER_MILLIS;
	}

	private static void render(GraphicsHolder graphicsHolder, VehicleExtension vehicle) {
		final List<Row> rows = rows(vehicle);
		if (rows.isEmpty()) {
			return;
		}

		/*
		 * 字体：MMTR 屏幕 UI 字体（DIN 1451 西文 + HarmonyOS Sans SC 中文，notes/221）。
		 * ★ 测量与绘制必须用**同一份带样式的文本**：换字体后字宽会变，用默认字体量、用 UI 字体画，
		 *   面板宽度与文本裁剪就会错位（这类"差几个像素"的问题在屏幕上很难归因）。
		 */
		/*
		 * 面板宽度**两遍定稿**，因为大号那一行参与宽度、而它自己又要按面板宽度裁剪：
		 *
		 * 第一遍：量每一行在**基础字号**下的宽度。大号行按 {@link #TASK_LINE_MAX_BASE_PX} 先截一道 ——
		 * 这是**有界**的，所以"宽度 → 裁剪"这条链没有循环依赖，一条长任务说明不会把整块卡片撑到半屏。
		 * 第二遍：定出面板宽之后，再按"面板宽 ÷ 倍率"把大号行裁进卡片。
		 *
		 * 宽度按 **字宽 × 该段倍率** 累加：漏掉这个乘法，卡片背景就会比大号字窄
		 * （背景比文字窄是屏幕上最像渲染 bug 的现象）。
		 */
		final MutableText[][] segTexts = new MutableText[rows.size()][];
		int textWidth = 0;
		for (int i = 0; i < rows.size(); i++) {
			final Row row = rows.get(i);
			final List<Seg> segs = row.segs();
			segTexts[i] = new MutableText[segs.size()];
			int rowWidth = 0;
			for (int s = 0; s < segs.size(); s++) {
				final Seg seg = segs.get(s);
				segTexts[i][s] = IDrawing.withUIFont(TextHelper.literal(seg.text()));
				// 大号段先按有界上限截一道，保证"宽度 → 裁剪"没有循环依赖。
				final MutableText measured = seg.scale() > 1F ? trimToWidth(segTexts[i][s], TASK_LINE_MAX_BASE_PX) : segTexts[i][s];
				if (s > 0) {
					rowWidth += Row.SEG_GAP;
				}
				rowWidth += (int) Math.ceil(GraphicsHolder.getTextWidth(measured) * seg.scale());
			}
			textWidth = Math.max(textWidth, rowWidth);
		}

		/*
		 * 按键提示那一行**外面套一个独立边框**（用户口径 2026-09-25「按键提示独立一个边框提示」）：
		 * 框体比文字四周各大出 {@link #KEY_BOX_PAD} 像素，所以面板宽度必须**把这段留白算进去**，
		 * 否则框会顶出卡片、或者文字贴着框线 —— 两种都很难看，而且都属于"差几个像素"那类难查的问题。
		 */
		final Row keyRow = keyHintRow(vehicle);
		if (keyRow != null) {
			textWidth = Math.max(textWidth, keyHintTextWidth(keyRow) + KEY_BOX_PAD * 2);
		}

		final int panelWidth = PADDING * 2 + textWidth;
		/*
		 * 行高逐行累加。★ 大号任务那一行**额外多给一段上下留白**（{@link #TASK_LINE_INSET}）：
		 * 用户口径 2026-09-25「大任务显示位置太靠左」—— 它不只是左边要缩进，上下也要透气，
		 * 否则大字紧贴上一行的作业名，看起来像"挤在一起的两行"，而不是一块独立的主视觉。
		 */
		int contentHeight = 0;
		for (final Row row : rows) {
			contentHeight += Math.round(IGui.TEXT_HEIGHT * row.lineScale());
			if (row.isLarge()) {
				contentHeight += TASK_LINE_INSET;
			}
		}
		final int panelHeight = PADDING * 2 + contentHeight + (rows.size() - 1) * LINE_SPACING;
		final int panelLeft = EDGE_PADDING;
		final int panelTop = EDGE_PADDING;
		final String missionState = vehicle.getMmtrMissionStateFromSync();
		final int accentColor = isComplete(missionState) ? COMPLETE_COLOR : "FAILED".equals(missionState) ? FAILED_COLOR : JOB_COLOR;

		// 第二遍：把大号任务行裁进卡片可用宽度（基础字号下的上限 = （面板宽 − 大任务左缩进）÷ 倍率）。
		final int taskBaseLimit = Math.max(1, Math.min(TASK_LINE_MAX_BASE_PX,
			Math.round((textWidth - TASK_LINE_INSET) / TASK_SCALE)));
		for (int i = 0; i < rows.size(); i++) {
			final Row row = rows.get(i);
			if (!row.isLarge()) {
				continue;
			}
			for (int s = 0; s < segTexts[i].length; s++) {
				if (row.segs().get(s).scale() > 1F) {
					segTexts[i][s] = trimToWidth(segTexts[i][s], taskBaseLimit);
				}
			}
		}

		final GuiDrawing guiDrawing = new GuiDrawing(graphicsHolder);
		guiDrawing.beginDrawingRectangle();
		// 底色 + （若有）跃迁/终态的染色：单独一层盖在上面，颜色随剩余时间淡出。
		guiDrawing.drawRectangle(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight,
			System.currentTimeMillis() - flashMillis < FLASH_MILLIS ? tint() : BACKGROUND_COLOR);
		/*
		 * **左侧那根状态色竖条**：这是"它是一块独立面板"最省地方的说法 —— 不进文字、不占行高，
		 * 一眼就能按颜色区分"在跑 / 已收工 / 失败了"。它同时是面板的边界，所以整块看起来是个卡片，
		 * 而不是几行浮在屏幕上的字。
		 */
		guiDrawing.drawRectangle(panelLeft, panelTop, panelLeft + ACCENT_WIDTH, panelTop + panelHeight, accentColor);

		int y = panelTop + PADDING;
		for (int i = 0; i < rows.size(); i++) {
			final Row row = rows.get(i);
			final float rowScale = row.lineScale();
			/*
			 * 大号行的**水平缩进**：用户口径「大任务显示位置太靠左」，所以它比其余行再往右让
			 * {@link #TASK_LINE_INSET}。★ 这个量是**屏幕像素**，而 drawScaledText 的 x 是缩放前坐标
			 * （会被放大 ×scale），所以必须先除回缩放前 —— 否则实际缩进会变成 `INSET × scale`，
			 * 看着"缩进对了"而换一个倍率就错。
			 */
			final boolean large = row.isLarge();
			int cursorScreenX = panelLeft + PADDING + (large ? TASK_LINE_INSET : 0);
			if (large && rowScale > 0F) {
				y += TASK_LINE_INSET / 2;
			}
			final List<Seg> segs = row.segs();
			for (int s = 0; s < segs.size(); s++) {
				final Seg seg = segs.get(s);
				final int segWidth = (int) Math.ceil(GraphicsHolder.getTextWidth(segTexts[i][s]) * seg.scale());
				if (seg.scale() > 1F) {
					drawScaledText(graphicsHolder, segTexts[i][s], Math.round((cursorScreenX - panelLeft) / seg.scale()), y, seg.color(), seg.scale());
				} else {
					graphicsHolder.drawText(segTexts[i][s], cursorScreenX, y, seg.color(), true, GraphicsHolder.getDefaultLight());
				}
				cursorScreenX += segWidth + Row.SEG_GAP;
			}
			y += Math.round(IGui.TEXT_HEIGHT * rowScale) + LINE_SPACING;
			if (large) {
				y += TASK_LINE_INSET / 2;
			}
		}

		/*
		 * 按键提示**最后画框**（画在所有文字之后）：框的底色必须压在框内文字**下面**、
		 * 却要压在卡片底色**上面**；若在文字之前画，半透明底色会把框内文字一起蒙住。
		 * 框体只包住那一段文字，所以它是一个"独立的小提示条"，与卡片里其余读数区分开。
		 */
		if (keyRow != null) {
			final int keyTextHeight = Math.round(IGui.TEXT_HEIGHT * keyRow.lineScale());
			final int keyTop = y - LINE_SPACING - keyTextHeight - KEY_BOX_PAD;
			final int keyLeft = panelLeft + PADDING - KEY_BOX_PAD;
			final int keyRight = keyLeft + keyHintTextWidth(keyRow) + KEY_BOX_PAD * 2;
			final int keyBottom = keyTop + keyTextHeight + KEY_BOX_PAD * 2;
			guiDrawing.beginDrawingRectangle();
			guiDrawing.drawRectangle(keyLeft, keyTop, keyRight, keyBottom, KEY_BOX_BACKGROUND_COLOR);
			// 四边各画一条 1 px 的线，凑成描边（GuiDrawing 只有填充矩形，没有 stroke）。
			guiDrawing.drawRectangle(keyLeft, keyTop, keyRight, keyTop + 1, KEY_BOX_BORDER_COLOR);
			guiDrawing.drawRectangle(keyLeft, keyBottom - 1, keyRight, keyBottom, KEY_BOX_BORDER_COLOR);
			guiDrawing.drawRectangle(keyLeft, keyTop, keyLeft + 1, keyBottom, KEY_BOX_BORDER_COLOR);
			guiDrawing.drawRectangle(keyRight - 1, keyTop, keyRight, keyBottom, KEY_BOX_BORDER_COLOR);
			guiDrawing.finishDrawingRectangle();
		}
	}

	/** 按键提示那一行（没有要按的键时返回 {@code null} = 整行不画、也不画框）。 */
	@Nullable
	private static Row keyHintRow(VehicleExtension vehicle) {
		final List<MmtrSubTaskView.Entry> subTasks = MmtrSubTaskView.of(vehicle);
		final String keys = actionKeyHint(vehicle, subTasks);
		return keys.isEmpty() ? null : Row.of(keys, KEY_HINT_COLOR);
	}

	/** 按键提示那一行文字的像素宽（框体按它加留白）。 */
	private static int keyHintTextWidth(Row keyRow) {
		int width = 0;
		for (final Seg seg : keyRow.segs()) {
			width += (int) Math.ceil(GraphicsHolder.getTextWidth(IDrawing.withUIFont(TextHelper.literal(seg.text()))) * seg.scale());
		}
		return width;
	}

	/**
	 * **按倍率画一行字**：先平移到该行的 (x, y)，再整体缩放，然后在原点画。
	 *
	 * <p>为什么用矩阵缩放而不是换一个更大的字体：TTF provider 是固定 size 烘出来的（notes/221），
	 * 想再来一套大字号就得再加一个 provider 与一整套字宽；而"任务这一行要显眼"本质上只是排版要求，
	 * 缩放就够，且**缩放后的字宽 = 基础字宽 × 倍率**（测量因此仍是同一份 UI 字体文本的宽度，不会错位）。</p>
	 */
	private static void drawScaledText(GraphicsHolder graphicsHolder, MutableText text, int x, int y, int color, float scale) {
		graphicsHolder.push();
		graphicsHolder.scale(scale, scale, 1F);
		graphicsHolder.drawText(text, Math.round(x / scale), Math.round(y / scale), color, true, GraphicsHolder.getDefaultLight());
		graphicsHolder.pop();
	}

	/**
	 * 值守那三个字（notes/408 §3.4）。
	 *
	 * <p>词表与可用性表都在 {@link org.mtr.mod.client.MmtrDutyView} 里 —— **HUD 与面板说同一句话**，
	 * 所以这里只做"没值守的人怎么写"这一件事：镜像字段为空 = 这趟车是自动在跑，不是"有个叫 IDLE 的人"。</p>
	 */
	private static String dutyWord(VehicleExtension vehicle) {
		final org.mtr.mod.client.MmtrDutyView.State state = org.mtr.mod.client.MmtrDutyView.of(vehicle);
		return state == org.mtr.mod.client.MmtrDutyView.State.NONE ? "自动运行（无人值守）" : state.word();
	}

	/**
	 * 这一拍要画的行。**只有值、没有前缀**（用户口径 2026-09-25「不要把前缀排出来 / 先把前缀全部删除」），
	 * 且按当轮追加的口径排：
	 *
	 * <ol>
	 *   <li><b>作业单名</b>（如 {@code TT-LOOP-1}）—— **单独一行、写在时间上面**；</li>
	 *   <li><b>时间</b>（{@link #TIME_SCALE} 倍字号，见 {@link #timeRow()}}）；</li>
	 *   <li>任务状态（只在终态出现，如 {@code 已完成}）；</li>
	 *   <li><b>大号字任务</b>（{@link #TASK_SCALE} 倍字号，且左右上下都缩进，见 {@link #TASK_LINE_INSET}）；</li>
	 *   <li><b>到目标点的距离</b>（小字，见 {@link #distanceText}）；</li>
	 *   <li>子任务 —— **一次只画当前那一条**（见 {@link #currentSubTask}）；</li>
	 *   <li>按键提示 —— **外面套一个独立边框**（见 {@code KEY_BOX_*}）。</li>
	 * </ol>
	 *
	 * <p><b>不再显示执行者</b>（用户口径 2026-09-25「把司机删除」）：原来有一行 {@code 司机} / {@code 自动}。
	 * 它被删掉是因为在那块卡片上它是**冗余**的 —— 人坐在司机位上才看得见这块卡片，
	 * 而"这步谁在执行"在玩家接管/归还的那一刻本来就有动作栏播报；多一行小字只会稀释
	 * 大号任务那一行的分量。</p>
	 */
	private static List<Row> rows(VehicleExtension vehicle) {
		final List<Row> rows = new ArrayList<>();
		final String jobId = vehicle.getMmtrJobIdFromSync();
		final String state = vehicle.getMmtrMissionStateFromSync();
		final int jobColor = isTerminal(state) ? terminalColor(state) : JOB_COLOR;

		/*
		 * ① **作业单名字**：单独一行、写在时间**上面**（用户口径 2026-09-25「作业单放在时间上面显示吧」）。
		 *
		 * 它从"时间右边那一段"搬到这里，并且**顺手去掉了 `9/14` 这个分数**（「作业单不显示分数进度」）：
		 * 作业单名是"我在跑哪趟活"，是一个**身份**；`9/14` 是"跑到哪儿了"，是一个**进度**。两者挤在同一行时，
		 * 每换一步就变的那个数字比名字抢眼，于是"跑哪趟活"反而最难看清 —— 而司机报话、对单子时用的是名字。
		 *
		 * 进度并没有丢：换步时动作栏仍会播报 `任务 10/14：…`（见 {@link #update}）—— 那是一次性的一句话，
		 * 说完就走，不会常驻在卡片上跟名字抢注意力。
		 */
		if (!jobId.isEmpty()) {
			rows.add(Row.of(jobId, jobColor));
		}

		/*
		 * ★ **值守行**（notes/408 §3.4）：这趟车现在归谁。
		 *
		 * <p>这里原本有一行 `司机` / `自动`，**2026-09-25 用户口径「把司机删除」把它删了**，
		 * 当时的理由是"人坐在司机位上才看得见这块卡片，所以它是冗余的"。那个理由后来被现场推翻了：
		 * 接管/归还只在那一刻播报一句，说完就走，于是"我现在到底算不算在开"没有一个**常驻**的地方可查
		 * —— 而这正是司机会问的问题（notes/408 §1）。</p>
		 *
		 * <p>补回来的不是一个 `司机/自动` 二值，而是引擎那台值守状态机的词：
		 * 等待接站 / 已上车·未获驾驶权 / 运转中 / 运转中·下一站退出 / 已退出 / 无人。
		 * 它顺带解释了"为什么我推了手柄车不走"（答案是：未获驾驶权）。</p>
		 */
		rows.add(Row.of("值守：" + dutyWord(vehicle), LABEL_COLOR));

		// ② 时间行（当前时刻 = 系统时间即北京时间）。
		rows.add(timeRow());

		// ③ 任务状态：只在终态出现（"已完成 / 失败"）。正常跑的时候不占一行。
		if (isTerminal(state)) {
			rows.add(Row.of(stateText(state), terminalColor(state)));
		}

		// ④ 大号字任务：这一步的人话说明（引擎给的原话）。
		final String note = vehicle.getMmtrTaskNoteFromSync();
		if (!note.isEmpty()) {
			rows.add(Row.of(note, TASK_COLOR, TASK_SCALE));
		}

		/*
		 * ⑤ **到目标点的距离**（用户口径 2026-09-25「显示到目标点的距离」）。
		 *
		 * 紧贴在大号任务那一行**下面**、用小字：它是对上面那句话的补充 —— 上面说"开到 3站1台"，
		 * 这里回答"还有多远"。两个数都是引擎发的（见 {@link VehicleExtension#getMmtrDistanceToStopTargetM}）。
		 *
		 * 为什么用米、不换算成"1.2 km"：司机在进站那几秒是**按米**刹车的，`1240 m` 比 `1.2 km` 直接；
		 * 而 1 km 以上若只留一位小数，`1240` 会被显示成 `1.2`（丢掉 40 m）。这条线只有几公里，
		 * 米制不会长到读不下。
		 */
		final String distance = distanceText(vehicle.getMmtrDistanceToStopTargetM());
		if (!distance.isEmpty()) {
			rows.add(Row.of(distance, VALUE_COLOR));
		}

		/*
		 * ⑥ 子任务链（用户口径 2026-09-21）：到站停稳 → 开门 → 停够 → 关门 ——
		 * **一次只显示一条**（用户口径 2026-09-25「不一次性全部显示，单独显示每一步」）。
		 *
		 * <p>为什么从"全列出来"改成"只显示当前这条"：一条链 4 条、每条一行，卡片就有 4 行小字，
		 * 而其中大多是**已经做过或还没轮到**的 —— 司机在站台上要读的只有"现在这一条"，
		 * 其余的行只把卡片撑高、把大号任务那一行往下压。做完的那条也不必留痕：它完成的那一刻
		 * 有提示音 + 动作栏播报（见 {@link #update}），而且**下一条会立刻顶上来**，
		 * 于是"做到第几条了"从"这一条是哪个"就能读出来。</p>
		 *
		 * <p>标记字形见 {@link MmtrSubTaskView#line}：原来的 ✔/▶ 不在随包字体里，会落到 unifont
		 * 而与其余文字不同源 —— 那正是"看起来像一串乱符号"的来源。</p>
		 */
		final MmtrSubTaskView.Entry currentSubTask = currentSubTask(MmtrSubTaskView.of(vehicle));
		if (currentSubTask != null) {
			rows.add(Row.of(MmtrSubTaskView.line(currentSubTask), entryColor(currentSubTask)));
		}
		return rows;
	}

	/**
	 * **到目标点的距离**那一行：{@code 320 m}；没有武装的目标点（{@code -1}）时返回空串（整行不画）。
	 *
	 * <p>它由 {@link VehicleExtension#getMmtrDistanceToStopTargetM()} 给（引擎两个镜像数相减），
	 * 所以这里只做**格式化**：取整到米、加上单位。没有目标点时那一格**不是画 0，而是整行不出现** ——
	 * 停放车、自由调车的卡片上不该有一个恒为 0 的数字占着一行。</p>
	 *
	 * <p>整段是**纯算术**（不碰字体、不碰画布）所以能离线断言 —— 见 {@code MmtrTaskHudTests}，
	 * 与速度 HUD 同一套做法（notes/224）。输入契约：{@code -1} = 没有目标；其余 ≥ 0（越过的负值
	 * 已在 {@code VehicleExtension} 那边夹成 0，这里不再夹 —— 真出现别的负数就该是 bug，别悄悄吞掉）。</p>
	 */
	static String distanceText(double metres) {
		return metres < 0 ? "" : Math.round(metres) + " m";
	}

	/**
	 * 这一拍要显示的那**一条**子任务：第一条还没完成的；全完成时给最后一条。
	 *
	 * <p>全完成时为什么还留一条（而不是让这一行消失）：这一步的收尾（引擎判完成 → 状态推进 → 下一步
	 * 顶上来）就发生在那几拍里，让行先空掉再被填上会**闪一下**；留住最后一条（绿 ◉）正好说明
	 * "这条也过了，本步这就完事"。</p>
	 */
	@Nullable
	private static MmtrSubTaskView.Entry currentSubTask(List<MmtrSubTaskView.Entry> subTasks) {
		if (subTasks.isEmpty()) {
			return null;
		}
		final int index = MmtrSubTaskView.currentIndex(subTasks);
		return subTasks.get(index < 0 ? subTasks.size() - 1 : index);
	}

	/**
	 * **时间行**。
	 *
	 * <p>现在只有当前时刻（系统时间 = 北京时间）。用户口径 2026-09-25 要求这一行放**两个时刻**：
	 * 「时间分两种，一个是任务预计完成时间，一个是当前时间」，而且**不做估算** ——
	 * 后一句口径是「作业单之后会手动写入准确的到达时间 / 这个后期时刻表会手动锁死」，
	 * 所以那一格将来显示的是**作业单上手写的到达时刻**，不是引擎猜的剩余时间。</p>
	 *
	 * <p><b>为什么现在还不能接上去</b>（不是缺一个字段那么简单）：作业单里的 `dueTimeOfDayMs`
	 * 是**锚点时钟**上的毫秒 —— {@code dayTime = (currentMillis - anchor) % MILLIS_PER_DAY}，
	 * 而 {@code anchor} 是调度器第一次 tick 的时刻（进程内变量，重启即归零，见 {@code MmtrJobScheduler}）。
	 * 于是现在把它格式化出来会得到一个"开机时长"式的假时刻（如 {@code 00:02:30}），与旁边那口北京时间
	 * 不是同一口钟 —— 比不显示更糟。引擎自己的注释也写着 "in-game-clock mapping is layered on later"。
	 * 等"后期时刻表"把锚点/时刻映射钉死，这里加第二段即可（行模型本来就是分段的，见 {@link Seg}）：
	 * 预计用暗色小字、当前时间用亮色大字，靠字号与明度区分，不新增任何前缀。</p>
	 */
	private static Row timeRow() {
		return Row.of(beijingTime(), VALUE_COLOR, TIME_SCALE);
	}

	/**
	 * **系统时间（北京时间）**：{@code HH:mm:ss}。
	 *
	 * <p>为什么写死 {@code Asia/Shanghai} 而不是用机器默认时区：用户口径是"系统时间即为北京时间"，
	 * 而开发机/服务器的本地时区是环境变量（实测这台是 UTC+8，下一个人那儿未必）——
	 * 写死时区才能保证**任何部署下显示的都是北京时间**。用 {@code java.time} 取（MC 映射层没有 Date）。</p>
	 */
	static String beijingTime() {
		return java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
			.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
	}

	/**
	 * **现在该按哪个键**（用户口径 2026-09-25「然后是提示按键」）。
	 *
	 * <p>键位取自 {@code KeyBindings} 的真值（Y = 两侧门 / U = 右侧门 / N = 手动确认 / B = 接管作业），
	 * 不是这里编的；**只在子任务链正在做某一条时给**：链走完（或没有链）就什么都不显示 ——
	 * 一块常驻的"按键"行会在没事可做时变成噪音，而司机对"该按什么"的注意力恰恰是最稀缺的。</p>
	 *
	 * <p>映射按引擎给的 {@code KIND}（{@code OPEN_DOORS} / {@code CLOSE_DOORS} / …）走，不按文案猜：
	 * 文案是引擎按原话发下来的人话（notes/170 §8.4），拿它做字符串匹配会在引擎改词时静默失效。
	 * 未知类型返回空串 —— 引擎加新子任务时只是少一行提示，不会显示一个错的键。</p>
	 */
	private static String actionKeyHint(VehicleExtension vehicle, List<MmtrSubTaskView.Entry> subTasks) {
		final int index = MmtrSubTaskView.currentIndex(subTasks);
		if (index < 0) {
			return "";
		}
		return switch (subTasks.get(index).kind()) {
			case "OPEN_DOORS" -> "Y / U 开门";
			case "CLOSE_DOORS" -> "Y / U 关门";
			case "WAIT_PASSENGERS" -> "";
			case "STOP_AT_TARGET" -> "";
			default -> "";
		};
	}

	/** 整块面板的染色：按剩余时间淡出；过了 {@link #FLASH_MILLIS} 就交回纯底色。 */
	private static int tint() {
		final long remaining = FLASH_MILLIS - (System.currentTimeMillis() - flashMillis);
		if (remaining <= 0) {
			return BACKGROUND_COLOR;
		}
		final int base = flashFailed ? FAILED_TINT : COMPLETE_TINT;
		final int alpha = (int) ((base >>> 24) * remaining / FLASH_MILLIS);
		// 染色期间底色用不透明的黑：半透明底 + 半透明染色叠起来字会糊。
		return (alpha << 24) | (base & 0xFFFFFF);
	}

	/*
	 * 这里原来有一条十格进度条（●●●●●●●○○○ 3/5）。**删掉的理由**：用户口径是"不要把前缀排出来"，
	 * 而删掉「作业」这个前缀之后，进度条单独成行只剩 `●●●●●●●○○○` 与下一行的 `TT-LOOP-1 · 9/14`
	 * 说同一件事 —— 同一份"第几步"在一个角落里说两遍就是噪音。进度本身仍由作业号那一行给
	 * （`N/M`），而且换步时还有**钟声**与动作栏播报（见 §2/§3），信息并没有丢。
	 *
	 * 顺带记一个曾经踩过的字形坑（将来若要恢复进度条，别再踩）：`▉`(U+2589) 在随包的
	 * HarmonyOS Sans SC 里有、`░`(U+2591) **没有** —— 混用会让条子一半来自随包字体、一半落到
	 * MC 内置 unifont，字面大小与基线都不同，看起来像一条错位的花条子。要用就用 `●`(U+25CF) /
	 * `○`(U+25CB)：两者都在该字体里，且进宽都是整齐的 1.000 em。
	 */

	/**
	 * 一条子任务那一行的颜色：**分点之后必须分色**，否则四条并排的小字读不出层次。
	 *
	 * <ul>
	 *   <li>已完成（引擎已判 + 客户端已确认）→ 成功绿：这件事真的算完了；</li>
	 *   <li>已完成但客户端还没确认 → 完成色（同绿）：**字形**已经用 {@code ◉?} 说出了"还没确认"，
	 *       颜色不必再说一遍 —— 一条状态用两个通道重复表达，反而让人怀疑哪个为准；</li>
	 *   <li>正在做 → 子任务亮色（跳出来）；</li>
	 *   <li>还没轮到 → 弱色（退下去，让正在做的那条更显眼）。</li>
	 * </ul>
	 */
	private static int entryColor(MmtrSubTaskView.Entry entry) {
		if (entry.isDone()) {
			return COMPLETE_COLOR;
		}
		return "ACTIVE".equals(entry.state()) ? SUB_TASK_COLOR : LABEL_COLOR;
	}

	private static int countDone(List<MmtrSubTaskView.Entry> entries) {
		int done = 0;
		for (final MmtrSubTaskView.Entry entry : entries) {
			if (entry.isDone()) {
				done++;
			}
		}
		return done;
	}

	private static boolean isTerminal(String state) {
		return isComplete(state) || "FAILED".equals(state);
	}

	private static boolean isComplete(String state) {
		return "COMPLETE".equals(state);
	}

	private static int terminalColor(String state) {
		return isComplete(state) ? COMPLETE_COLOR : FAILED_COLOR;
	}

	private static String stateText(String state) {
		return switch (state) {
			case "COMPLETE" -> "已完成";
			case "FAILED" -> "失败";
			default -> state;
		};
	}

	private static String completeText(String state, String jobId) {
		final String prefix = jobId.isEmpty() ? "" : jobId + " ";
		return isComplete(state) ? "作业 " + prefix + "已完成" : "作业 " + prefix + "失败";
	}

	/**
	 * 按**像素宽度**裁到给定宽度内（末尾加 {@code …}）。
	 *
	 * <p>为什么不能只按字数裁：中英混排下一个汉字与一个数字的宽度差一倍，按字数裁的结果是
	 * 有时留白一大截、有时直接溢出背景。这里逐字累加真实宽度，量的是与绘制同一份带样式文本的宽。</p>
	 */
	private static MutableText trimToWidth(MutableText text, int maxWidth) {
		if (maxWidth <= 0 || GraphicsHolder.getTextWidth(text) <= maxWidth) {
			return text;
		}
		final String plain = text.getString();
		final MutableText ellipsis = IDrawing.withUIFont(TextHelper.literal("…"));
		final int ellipsisWidth = GraphicsHolder.getTextWidth(ellipsis);
		final StringBuilder builder = new StringBuilder();
		int width = 0;
		for (int i = 0; i < plain.length(); i++) {
			final char character = plain.charAt(i);
			final int characterWidth = GraphicsHolder.getTextWidth(IDrawing.withUIFont(TextHelper.literal(String.valueOf(character))));
			if (width + characterWidth + ellipsisWidth > maxWidth) {
				break;
			}
			builder.append(character);
			width += characterWidth;
		}
		return IDrawing.withUIFont(TextHelper.literal(builder + "…"));
	}

	private static void announce(String text) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player != null) {
			player.sendMessage(new Text(TextHelper.literal(text).data), true);
		}
	}

	/** 换车（或下车）时静默重置：不响、不播报，只把比较用的键清掉。 */
	private static void resetFor(long vehicleId) {
		trackedVehicleId = vehicleId;
		trackedJobId = "";
		trackedStep = -1;
		trackedSteps = 0;
		trackedState = "";
		trackedRevision = Long.MIN_VALUE;
		trackedDoneCount = 0;
		trackedNote = "";
		trackedHint = "";
		completedAtMillis = 0;
	}

	private static void clearTracking() {
		trackedVehicleId = -1;
		resetFor(-1);
	}
}
