package org.mtr.mod.sound;

import org.mtr.mapping.holder.ClientPlayerEntity;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.SoundCategory;
import org.mtr.mapping.holder.SoundEvent;
import org.mtr.mapping.mapper.SoundHelper;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * **任务提示音**（用户口径 2026-09-25：「带有完成提示音效，音效可以采用我的世界音符盒音效」）。
 *
 * <h2>为什么是这三个音、为什么音高不一样</h2>
 *
 * <p>用**原版音符盒**（{@code minecraft:block.note_block.*}）是用户点名的：不引入音频文件、
 * 不占资源包、音量跟随玩家的"方块"音量条，玩家一听就知道是 MC 自己的声音。</p>
 *
 * <p>三件事各给一个**盲听可分辨**的音，而不是同一声音响三次 —— 坐在驾驶台上时玩家未必看屏幕，
 * "完成了一条 / 换步了 / 整趟作业做完了"必须靠耳朵就能分开：</p>
 *
 * <table>
 *   <caption>三种提示音</caption>
 *   <tr><th>音色</th><th>时刻</th><th>听感</th></tr>
 *   <tr><td>{@code harp}</td><td>一条子任务完成</td><td>单音，音高随进度上行（越接近收尾越高）</td></tr>
 *   <tr><td>{@code bell}</td><td>换步</td><td>单音，比竖琴亮 —— "下一步来了，看屏幕"</td></tr>
 *   <tr><td>{@code harp} ×3</td><td>整趟作业完成</td><td>上行三音小句（1.0 → 1.25 → 1.5），收尾音留最久</td></tr>
 * </table>
 *
 * <h2>为什么不用 {@code SoundEvents} 里那套映射常量</h2>
 *
 * <p>MTR 的映射层只搬了它自己用得上的原版音效（翻遍 {@code org.mtr.mapping.holder.SoundEvents}
 * 没有 {@code block.note_block.*}）。音符盒走的是**按名字取音效**这条路 ——
 * {@link SoundHelper#createSoundEvent} 就是 {@code SoundEvent.of(Identifier)}，
 * 与 {@code BlockTrainAnnouncer} 播自定义报站音是同一个入口（那边同样只给了一个 id 字符串）。
 * 因此这里不新增任何注册项，也不需要资源包。</p>
 *
 * <h2>为什么播放要排队</h2>
 *
 * <p>整趟完成的三个音必须**依次**响（同一帧播三次只会听见最后一次）。所以排一个小队列、按毫秒推进；
 * 顺带解决了"完成音还没播完又来一条子任务"的叠音问题 —— 新音接在队尾，不覆盖、不丢失。</p>
 */
public final class MmtrTaskSounds {

	private MmtrTaskSounds() {
	}

	/** 一条子任务完成（音符盒竖琴）。 */
	private static final SoundEvent NOTE_HARP = SoundHelper.createSoundEvent(new Identifier("minecraft", "block.note_block.harp"));
	/** 换步（音符盒钟）—— 比竖琴亮，与"完成一条"区分得开。 */
	private static final SoundEvent NOTE_BELL = SoundHelper.createSoundEvent(new Identifier("minecraft", "block.note_block.bell"));

	/**
	 * 同一个音效带音高的**去重窗口**（毫秒）。
	 *
	 * <p>为什么需要它：状态是**镜像**来的，网络抖动/重传可能让同一格在两帧里各报一次跃迁；
	 * 而"宁可不响，也不要连着响两声"（连响两声会让人以为完成了两条）。60 ms 远小于任何真实事件
	 * 间隔（作业步骤本身是秒级），因此不会吞掉真事件。</p>
	 */
	private static final long DEDUP_MILLIS = 60;

	/** 音量：音符盒在玩家自己耳边响，1.0 按"方块"音量条走（不抢报站/鸣笛）。 */
	private static final float VOLUME = 1.0F;

	/** 等待播放的音。队列极短（最多几句），用 {@link ArrayDeque} 足够。 */
	private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
	/** 同一 key 的最近一次入队时刻，用于去重。 */
	private static final Map<String, Long> LAST_PLAYED = new HashMap<>();

	/** 到点的绝对时刻（毫秒）算在**入队时**就定好，之后只与当前时刻比较，不必维护"上一拍"。 */
	private record Pending(long dueAtMillis, SoundEvent event, float pitch, String key) {
	}

	/**
	 * **一条子任务完成**：单音，音高按进度上行（进度 0 → 1 映射到 0.95 → 1.65）。
	 *
	 * <p>进度越靠后音越高，于是「到站停稳 → 开门 → 停够 → 关门」听起来是一路往上走的 ——
	 * 不必看屏幕也能从耳朵判断是不是快到发车了。</p>
	 *
	 * <p>去重键里带 {@code total}（本轮条数）而不只是"第几条"：连续两步的收尾可能是同一个
	 * "第 4 条"（比如"开往"那步以停在目标收尾、"停站乘降"那步以关门收尾），只按条数去重会把
	 * 后一次吞掉 —— 少响一声比多响一声难查得多（多响只是吵，少响会被当成"提示音没做出来"）。</p>
	 *
	 * @param doneCount 已完成的子任务条数（含这一条）
	 * @param total     本轮子任务总条数（≤1 时按单音处理）
	 */
	public static void subTaskDone(int doneCount, int total) {
		final float progress = total <= 1 ? 1F : Math.min(1F, Math.max(1F / total, (float) doneCount / total));
		play(NOTE_HARP, 0.95F + 0.7F * progress, "subtask:" + doneCount + "/" + total, 0);
	}

	/** **换步**：钟单音 —— 作业表往下走了一步（"下一步来了"）。 */
	public static void stepChanged(int step) {
		play(NOTE_BELL, 1.0F, "step:" + step, 0);
	}

	/**
	 * **整趟作业完成**：上行三音小句（竖琴 1.0 → 1.25 → 1.5），间隔 0 / 140 / 300 ms。
	 *
	 * <p>前两个音"紧、短"，最后一个"开"，听感上是一句"收工" —— 与前面单音的性质完全不同。</p>
	 */
	public static void jobComplete() {
		play(NOTE_HARP, 1.0F, "job:0", 0);
		play(NOTE_HARP, 1.25F, "job:1", 140);
		play(NOTE_HARP, 1.5F, "job:2", 300);
	}

	/**
	 * **每帧推一拍**（由 {@code MmtrTaskHud} 在 GUI 钩子里调，见该类关于"钩子每帧都跑"的说明）。
	 *
	 * <p>只与"现在几点"比较，不依赖调用间隔是否均匀：即使游戏卡顿或窗口失焦，到点的音也只是延后，
	 * 不会丢、也不会挤在同一帧里。</p>
	 */
	public static void tick() {
		if (PENDING.isEmpty()) {
			return;
		}
		final long now = System.currentTimeMillis();
		while (!PENDING.isEmpty() && PENDING.peekFirst().dueAtMillis() <= now) {
			emit(PENDING.pollFirst());
		}
	}

	private static void play(SoundEvent event, float pitch, String key, long delayMillis) {
		final long now = System.currentTimeMillis();
		/*
		 * 去重：同一个 key 在 DEDUP_MILLIS 内只排一次。连"排队"都不进 ——
		 * 否则延迟音会在去重窗口之外被再捞回来，等于没去重。
		 */
		final Long last = LAST_PLAYED.get(key);
		if (last != null && now - last < DEDUP_MILLIS) {
			return;
		}
		LAST_PLAYED.put(key, now);
		if (LAST_PLAYED.size() > 64) {
			// 长期运行不让这张表长起来（key 是有限的几种："subtask:N/M" / "step:N" / "job:N"）。
			LAST_PLAYED.entrySet().removeIf(entry -> now - entry.getValue() > 10_000);
		}
		PENDING.addLast(new Pending(now + Math.max(0, delayMillis), event, pitch, key));
	}

	/** 真正发声：在**玩家自己身上**播（这条提示是说给这位玩家的，不是场景音）。 */
	private static void emit(Pending pending) {
		final ClientPlayerEntity player = MinecraftClient.getInstance().getPlayerMapped();
		if (player == null) {
			return;
		}
		player.playSound(pending.event(), SoundCategory.getBlocksMapped(), VOLUME, pending.pitch());
	}
}
