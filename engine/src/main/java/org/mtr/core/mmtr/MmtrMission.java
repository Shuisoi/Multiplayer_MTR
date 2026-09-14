package org.mtr.core.mmtr;

import org.jspecify.annotations.Nullable;
import org.mtr.core.mmtr.task.MmtrTask;

import java.util.UUID;

/**
 * A mission assigned to a Train (consist). The mission is owned by the train entity — players and
 * AI are only optional executors that read/execute it; with no executor the autopilot drives the
 * same semantics.
 */
public final class MmtrMission {

	public enum Kind { PASSENGER, FREIGHT, MANEUVER }
	public enum State { ASSIGNED, DISPATCHED, AT_TARGET, COMPLETE, FAILED, CANCELED }
	public enum Executor { AUTOPILOT, PLAYER, AI }

	private final long trainVehicleId;
	private final Kind kind;
	private final long startSidingId;
	private final long targetSidingId;
	private final long assignedMillis;
	private State state = State.ASSIGNED;
	private Executor executor = Executor.AUTOPILOT;
	private @Nullable UUID executorPlayer;
	private @Nullable String failureReason;
	/** The task this mission executes (作业单步骤 → 任务实例); null for legacy ad-hoc dispatches. */
	private @Nullable MmtrTask task;
	/**
	 * C10: this movement is a 调车 shunt. When the vehicle plans it, the engine grants a ROUTE-wide
	 * 调车授权 (every rail of the plan), so S1 does not stop it on a rail another train occupies on the
	 * way to the target - and the coupling gate has the authority it requires.
	 */
	private boolean needsShuntAuthority;

	public void setNeedsShuntAuthority(boolean value) {
		needsShuntAuthority = value;
	}

	public boolean needsShuntAuthority() {
		return needsShuntAuthority;
	}

	public MmtrMission(long trainVehicleId, Kind kind, long startSidingId, long targetSidingId, long assignedMillis) {
		this.trainVehicleId = trainVehicleId;
		this.kind = kind;
		this.startSidingId = startSidingId;
		this.targetSidingId = targetSidingId;
		this.assignedMillis = assignedMillis;
	}

	/** Attach the task definition this mission executes (where/when/what for timetable/interlocking). */
	public void attachTask(@Nullable MmtrTask task) {
		this.task = task;
	}

	/**
	 * **原地动作的目的轨**（notes/150）。
	 *
	 * <p>换端这类任务按设计没有目标对象（车一动不动），可任务生命周期又需要"有没有到位"这件事 ——
	 * 于是派车时把**车此刻所在的那根轨**记下来当目的轨：车已经在目标轨上 ⇒ 立刻"到位"，
	 * 生命周期照常（已派→到点→完成），真正动手的是车辆侧的原地动作执行器。</p>
	 */
	private String inPlaceTargetRailHex = "";

	public void setInPlaceTargetRailHex(String railHex) {
		inPlaceTargetRailHex = railHex == null ? "" : railHex;
	}

	/** 这是一次**原地动作**（没有目的地：目的轨就是车现在所在的那根轨）。 */
	public boolean isInPlace() {
		return task != null && task.inPlace();
	}

	public String getInPlaceTargetRailHex() {
		return inPlaceTargetRailHex;
	}

	@Nullable
	public MmtrTask getTask() {
		return task;
	}

	public long getTrainVehicleId() { return trainVehicleId; }
	public Kind getKind() { return kind; }
	public long getStartSidingId() { return startSidingId; }
	public long getTargetSidingId() { return targetSidingId; }
	public long getAssignedMillis() { return assignedMillis; }
	public State getState() { return state; }
	public Executor getExecutor() { return executor; }
	public @Nullable UUID getExecutorPlayer() { return executorPlayer; }
	public @Nullable String getFailureReason() { return failureReason; }

	public boolean isTerminal() {
		return state == State.COMPLETE || state == State.FAILED || state == State.CANCELED;
	}

	public boolean dispatch() {
		return transition(State.DISPATCHED);
	}

	public boolean atTarget() {
		return transition(State.AT_TARGET);
	}

	public boolean complete() {
		return transition(State.COMPLETE);
	}

	public boolean fail(String reason) {
		if (state == State.FAILED) {
			return false;
		}
		failureReason = reason;
		return transition(State.FAILED);
	}

	public boolean cancel() {
		return transition(State.CANCELED);
	}

	/** Hands the driving execution to a player/AI (or back to the autopilot). The mission is unchanged. */
	public boolean setExecutor(Executor executor, @Nullable UUID playerUuid) {
		if (isTerminal()) {
			return false;
		}
		if (executor == Executor.PLAYER && playerUuid == null) {
			return false;
		}
		if (executor != Executor.PLAYER && playerUuid != null) {
			playerUuid = null;
		}
		this.executor = executor;
		this.executorPlayer = playerUuid;
		return true;
	}

	private boolean transition(State next) {
		final boolean allowed = switch (state) {
			case ASSIGNED -> next == State.DISPATCHED || next == State.FAILED || next == State.CANCELED;
			case DISPATCHED -> next == State.AT_TARGET || next == State.FAILED || next == State.CANCELED;
			case AT_TARGET -> next == State.COMPLETE || next == State.FAILED || next == State.CANCELED;
			default -> false;
		};
		if (allowed) {
			state = next;
		}
		return allowed;
	}
}
