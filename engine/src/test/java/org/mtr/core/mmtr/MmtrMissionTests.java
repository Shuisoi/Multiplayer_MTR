package org.mtr.core.mmtr;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mission state machine: train-owned, executor optional/swappable. */
public final class MmtrMissionTests {

	@Test
	public void lifecycleDispatcherTargetComplete() {
		final MmtrMission mission = new MmtrMission(7L, MmtrMission.Kind.PASSENGER, 1L, 2L, 1000L);
		assertEquals(MmtrMission.State.ASSIGNED, mission.getState());
		assertTrue(mission.dispatch());
		assertTrue(mission.atTarget());
		assertTrue(mission.complete());
		assertTrue(mission.isTerminal());
		assertFalse(mission.dispatch(), "terminal missions cannot be redispatched");
	}

	@Test
	public void failAndCancelAllowedFromActiveStates() {
		final MmtrMission a = new MmtrMission(1L, MmtrMission.Kind.FREIGHT, 1L, 2L, 0);
		assertTrue(a.fail("blocked"));
		assertFalse(a.cancel(), "already failed");
		final MmtrMission b = new MmtrMission(2L, MmtrMission.Kind.FREIGHT, 1L, 2L, 0);
		assertTrue(b.cancel());
		assertFalse(b.atTarget());
	}

	@Test
	public void executorHandoffAutopilotPlayerAi() {
		final MmtrMission mission = new MmtrMission(3L, MmtrMission.Kind.PASSENGER, 1L, 2L, 0);
		assertEquals(MmtrMission.Executor.AUTOPILOT, mission.getExecutor());
		final UUID player = UUID.randomUUID();
		assertTrue(mission.setExecutor(MmtrMission.Executor.PLAYER, player));
		assertEquals(player, mission.getExecutorPlayer());
		assertTrue(mission.setExecutor(MmtrMission.Executor.AI, null));
		assertFalse(mission.setExecutor(MmtrMission.Executor.PLAYER, null), "player executor needs a uuid");
		mission.dispatch();
		mission.atTarget();
		mission.complete();
		assertFalse(mission.setExecutor(MmtrMission.Executor.AUTOPILOT, null), "terminal missions cannot change executor");
	}
}