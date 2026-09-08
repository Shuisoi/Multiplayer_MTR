package org.mtr.core.mmtr.task;

/**
 * MMTR task base class (任务基类): a planned operation "某时 × 某地 × 某操作".
 *
 * <p>Concrete kinds derive from this class and define their operation through
 * {@link #kind()} / {@link #describe()}; location is a numeric world-object reference
 * ({@link #targetRef}) with a declared kind ({@link #targetKind}) that {@link #validate()}
 * checks, and time is the plan window ({@link #earliestMs} .. {@link #dueMs}, millisecond
 * engine/job clock semantics - the schedule layer renders these as 计划到/发).
 *
 * <p>Consist jobs instantiate tasks step by step; {@code MmtrMission} stays the runtime
 * execution container attached to a vehicle (state / executor / failure) and carries the task.
 * Players may execute a task too - the engine then only observes position/door/coupling
 * conditions instead of driving.</p>
 */
public abstract class MmtrTask {

	/** Target object kinds for {@link #targetKind}. */
	public static final String TARGET_PLATFORM = "PLATFORM";
	public static final String TARGET_SIDING = "SIDING";
	public static final String TARGET_CONSIST = "CONSIST";
	public static final String TARGET_FREIGHT = "FREIGHT";

	/** Stable id within its owning job/sheet (job step id for scheduled tasks). */
	public String taskId = "";
	/** Target world-object id: platform / siding / (future) derived non-powered vehicle. */
	public long targetRef;
	/** Declared target object kind; concrete kinds enforce their own via {@link #validate()}. */
	public String targetKind = "";
	/** Earliest allowed start (0 = none). */
	public long earliestMs;
	/** Planned completion / due time - the timetable row's 计划时刻. */
	public long dueMs;
	/** Free-form note for humans (时间表/作业注释). */
	public String note = "";

	protected MmtrTask(String taskId, String targetKind, long targetRef, long dueMs) {
		this.taskId = taskId == null ? "" : taskId;
		this.targetKind = targetKind == null ? "" : targetKind;
		this.targetRef = targetRef;
		this.dueMs = dueMs;
	}

	/** The target-kind this task requires; empty = any. Used by {@link #invalidWhenTargetMismatch()}. */
	protected String requiredTargetKind() {
		return "";
	}

	/** Default target-kind check: "" when the target matches the required kind (or none required). */
	protected String invalidWhenTargetMismatch() {
		final String required = requiredTargetKind();
		if (required.isEmpty() || required.equals(targetKind)) {
			return "";
		}
		return "目标对象类型应为 " + required + "，实际 " + targetKind + "（task " + taskId + "）";
	}

	public abstract MmtrTaskKind kind();

	/** Human/timetable one-liner, e.g. "开往 5 站台(到 07:05)". */
	public abstract String describe();

	/** Kind-specific target checks (target kind, parameter sanity); empty string = ok. */
	public abstract String validate();

	@Override
	public String toString() {
		return "[" + kind() + " " + taskId + " -> " + targetKind + "#" + targetRef + " due=" + dueMs + "] " + describe();
	}
}
