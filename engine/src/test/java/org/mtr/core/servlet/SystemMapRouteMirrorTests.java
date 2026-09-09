package org.mtr.core.servlet;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.TransportMode;
import org.mtr.core.mmtr.route.MmtrRoute;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Angle;

import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A4: the ops feed carries the exact derived view the CLIENT mirror is built from, so an operator can
 * compare what the game shows with what the engine told the clients. This pins the feed shape against
 * the registry views it wraps ({@code setMainRouteNextRails} / {@code pendingEntryRails}).
 */
public final class SystemMapRouteMirrorTests {

	private static final ObjectArrayList<String> NO_STYLES = new ObjectArrayList<>();

	private static Rail through(Position p1, Position p2) {
		return Rail.newRail(p1, Angle.fromAngle(0), p2, Angle.fromAngle(180), Rail.Shape.QUADRATIC, 0, NO_STYLES,
			80, 80, false, false, true, false, true, TransportMode.TRAIN);
	}

	@Test
	public void theFeedMirrorMatchesTheRegistryViews() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-route-mirror-feed"), false);
		final Rail a = through(new Position(-20, 0, 0), new Position(0, 0, 0));
		final Rail b = through(new Position(0, 0, 0), new Position(60, 0, 0));
		final Rail c = through(new Position(60, 0, 0), new Position(120, 0, 0));
		sim.rails.add(a);
		sim.rails.add(b);
		sim.rails.add(c);
		sim.sync();

		final ObjectArrayList<String> setRails = new ObjectArrayList<>();
		setRails.add(a.getHexId());
		setRails.add(b.getHexId());
		setRails.add(c.getHexId());
		sim.mmtrRoutes.request(new MmtrRoute(1, "v1", MmtrRoute.Kind.MAIN, setRails, new ObjectArrayList<>(), c.getHexId(), 1000));
		sim.mmtrRoutes.refresh(1, sim.mmtrPointAuthority);

		final ObjectArrayList<String> pendingRails = new ObjectArrayList<>();
		pendingRails.add(b.getHexId());
		pendingRails.add(c.getHexId());
		final ObjectArrayList<String[]> fork = new ObjectArrayList<>();
		fork.add(new String[]{"0", "0", "0", a.getHexId(), "0"});
		sim.mmtrRoutes.request(new MmtrRoute(2, "v2", MmtrRoute.Kind.MAIN, pendingRails, fork, c.getHexId(), 1000));
		sim.mmtrRoutes.refresh(2, sim.mmtrPointAuthority);

		final JsonObject mirror = SystemMapServlet.mmtrRouteMirrorJson(sim);
		final JsonObject nextRails = mirror.getAsJsonObject("nextRails");
		assertEquals(b.getHexId(), nextRails.get(a.getHexId()).getAsString(), "the SET route's locked path is mirrored");
		assertEquals(c.getHexId(), nextRails.get(b.getHexId()).getAsString());
		assertTrue(mirror.getAsJsonArray("pendingEntries").toString().contains(b.getHexId()),
			"the PENDING route's entry rail is mirrored as a danger head: " + mirror.getAsJsonArray("pendingEntries"));
	}

	@Test
	public void anIdleNetworkMirrorsNothing() {
		final Simulator sim = new Simulator("test", new String[]{"test"}, Paths.get("build/mmtr-route-mirror-idle"), false);
		sim.rails.add(through(new Position(-20, 0, 0), new Position(0, 0, 0)));
		sim.sync();
		final JsonObject mirror = SystemMapServlet.mmtrRouteMirrorJson(sim);
		assertEquals(0, mirror.getAsJsonObject("nextRails").size(), "no route -> no narrowing");
		assertEquals(0, mirror.getAsJsonArray("pendingEntries").size(), "no route -> no forced-red entry");
	}
}
