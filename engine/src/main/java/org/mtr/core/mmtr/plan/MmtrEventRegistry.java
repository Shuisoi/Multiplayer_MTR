package org.mtr.core.mmtr.plan;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jspecify.annotations.Nullable;

/**
 * P5：**事件登记表**（运行时的扰动集合，{@code 任务系统-线路派生与车底交路-设计.md} §6.3）。
 *
 * <p>它**不落盘**：配置里那份是规则（{@code MmtrEventRule}），事件实例是运行时的东西 ——
 * 重启即清空是符合语义的（"这次运营里发生了什么事"不该跨重启继承）。</p>
 *
 * <p>对外两问（§6.3 的"推前台展示"）：**生效中的**与**即将生效的**，各带类型/目标/起止/理由/剩余时长。</p>
 */
public final class MmtrEventRegistry {

	/** "即将生效"的窗口：多久以内算即将（前台提前弹条）。 */
	public static final long UPCOMING_WINDOW_MILLIS = 30L * 60 * 1000;

	private final ObjectArrayList<MmtrEvent> events = new ObjectArrayList<>();

	/** 登记（同 id 覆盖）。 */
	public MmtrEvent put(MmtrEvent event) {
		if (event == null) {
			return null;
		}
		for (int i = 0; i < events.size(); i++) {
			if (events.get(i).eventId.equals(event.eventId)) {
				events.set(i, event);
				return event;
			}
		}
		events.add(event);
		return event;
	}

	public boolean remove(String eventId) {
		return events.removeIf(event -> event.eventId.equals(eventId));
	}

	public @Nullable MmtrEvent get(String eventId) {
		for (final MmtrEvent event : events) {
			if (event.eventId.equals(eventId)) {
				return event;
			}
		}
		return null;
	}

	/** 终结一个事件（人工或后续事件）：把结束时刻钉在现在。 */
	public boolean end(String eventId, long dayTimeMillis) {
		final MmtrEvent event = get(eventId);
		if (event == null) {
			return false;
		}
		event.endAt(dayTimeMillis);
		return true;
	}

	public ObjectArrayList<MmtrEvent> all() {
		return new ObjectArrayList<>(events);
	}

	/** 生效中的（按开始时刻定序，便于前台稳定显示）。 */
	public ObjectArrayList<MmtrEvent> activeAt(long dayTimeMillis) {
		final ObjectArrayList<MmtrEvent> out = new ObjectArrayList<>();
		for (final MmtrEvent event : events) {
			if (event.isActiveAt(dayTimeMillis)) {
				out.add(event);
			}
		}
		out.sort((a, b) -> Long.compare(a.startMillis, b.startMillis));
		return out;
	}

	/** 即将生效的（{@link #UPCOMING_WINDOW_MILLIS} 以内）。 */
	public ObjectArrayList<MmtrEvent> upcomingAt(long dayTimeMillis) {
		final ObjectArrayList<MmtrEvent> out = new ObjectArrayList<>();
		for (final MmtrEvent event : events) {
			final long delta = event.startMillis - dayTimeMillis;
			if (delta > 0 && delta <= UPCOMING_WINDOW_MILLIS) {
				out.add(event);
			}
		}
		out.sort((a, b) -> Long.compare(a.startMillis, b.startMillis));
		return out;
	}

	/** 清掉早已结束的（默认保留 1 小时，前台还能看到"刚恢复"）。 */
	public int pruneEnded(long dayTimeMillis, long keepMillis) {
		return events.removeIf(event -> event.endMillis >= 0 && dayTimeMillis > event.endMillis + keepMillis) ? 1 : 0;
	}

	public int size() {
		return events.size();
	}

	/** 前台文案：生效中的在前，即将生效的在后（§6.3）。 */
	public ObjectArrayList<String> describeForFeed(long dayTimeMillis) {
		final ObjectArrayList<String> lines = new ObjectArrayList<>();
		for (final MmtrEvent event : activeAt(dayTimeMillis)) {
			lines.add("生效：" + event.describe(dayTimeMillis));
		}
		for (final MmtrEvent event : upcomingAt(dayTimeMillis)) {
			lines.add("即将：" + event.describe(dayTimeMillis));
		}
		return lines;
	}
}
