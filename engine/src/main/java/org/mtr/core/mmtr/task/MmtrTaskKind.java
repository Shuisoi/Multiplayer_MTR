package org.mtr.core.mmtr.task;

/**
 * MMTR task taxonomy (任务类型): a task is "某时 × 某地 × 某操作" - a planned operation a
 * consist/driver must perform at a target (platform / siding / other consist / turnback) within
 * a time window. Concrete task classes derive from {@link MmtrTask} and declare their own
 * operation semantics; the timetable / schedule layer renders tasks as rows and the execution
 * layer (missions, later the interlocking) consumes their where/when/what.
 */
public enum MmtrTaskKind {

	/** 调车/回库/转线: drive to a siding and stop (no doors). */
	DRIVE_TO_SIDING,
	/** 客运到站: drive to a platform and stop aligned (no door cycle - that is STATION_SERVICE). */
	DRIVE_TO_PLATFORM,
	/** 客运停站作业: standing at a platform, run the door cycle (open -> dwell -> close). */
	STATION_SERVICE,
	/** 折返/掉头: drive around the turnback (via the junction table) onto the return track. */
	DRIVE_TURNBACK,
	/**
	 * 原地换端 (change ends in place): the consist stands and the crew changes cabs - no movement,
	 * no target, no dwell. Distinct from {@link #DRIVE_TURNBACK}, which runs around a turnback lead.
	 */
	CHANGE_ENDS,
	/** 连挂走行 (接口预留): approach and stop at the coupling point of another consist /
	 * 派生非动力车 (Vehicle derivation lands in a later slice); the actual "hooked" transition
	 * is the future COUPLE action. */
	DRIVE_TO_CONSIST,
	/** 货运装卸停留 (占位): stop at a freight point / stabling siding and work for a duration. */
	FREIGHT_WORK
}
