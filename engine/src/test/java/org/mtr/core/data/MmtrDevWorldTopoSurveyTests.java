package org.mtr.core.data;

import org.junit.jupiter.api.Test;
import org.mtr.core.simulation.Simulator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Topology survey of the LIVE dev world (run/world): every platform rail with its endpoint
 * coordinates - decides whether the passenger test line can form a timetable loop or needs
 * turnback support.
 */
public final class MmtrDevWorldTopoSurveyTests {

	private static final Path DEV_WORLD_MTR_ROOT = Paths.get("C:/Users/30354/Desktop/Shuisoi DEV/MC/mmtr/game/fabric/run/world/mtr");

	@Test
	public void surveyPlatformRails() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_WORLD_MTR_ROOT), "live dev world not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_WORLD_MTR_ROOT, false);
		assertTrue(sim.rails.size() > 0, "rails loaded");

		System.out.println("[TOPO] stations=" + sim.stations.size() + " platforms=" + sim.platforms.size());
		for (final Station station : sim.stations) {
			System.out.println("[TOPO] station '" + station.getName() + "' id=" + station.getId());
		}
		for (final Platform platform : sim.platforms) {
			final Rail rail = platform.mmtrGraphRail();
			if (rail == null) {
				System.out.println("[TOPO] platform " + platform.getId() + " name=" + platform.getName() + " NO RAIL");
				continue;
			}
			final Position p1 = rail.getPosition1();
			final Position p2 = rail.getPosition2();
			System.out.println("[TOPO] platform " + platform.getId() + " name=" + platform.getName()
				+ " rail " + rail.getHexId()
				+ " len=" + Math.round(rail.railMath.getLength() * 10.0) / 10.0
				+ " (" + p1.getX() + "," + p1.getZ() + ") -> (" + p2.getX() + "," + p2.getZ() + ")");
		}
	}

	/**
	 * Full-rail connectivity survey: every rail with its two endpoints and the rails connected at
	 * each end (junction nodes = rails with 2+ neighbours on one side). Answers the timetable
	 * question: loop (a rail chain returns to itself) or dead-end terminal (needs turnback).
	 */
	@Test
	public void surveyRailConnectivity() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_WORLD_MTR_ROOT), "live dev world not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_WORLD_MTR_ROOT, false);
		assertTrue(sim.rails.size() > 0, "rails loaded");

		final java.util.HashMap<String, Rail> byHex = new java.util.HashMap<>();
		final java.util.HashMap<String, Position[]> railEnds = new java.util.HashMap<>();
		sim.positionsToRail.forEach((node, neighbourMap) -> neighbourMap.forEach((pos, rail) -> {
			byHex.putIfAbsent(rail.getHexId(), rail);
			final Position[] ends = railEnds.computeIfAbsent(rail.getHexId(), k -> new Position[2]);
			if (ends[0] == null) {
				ends[0] = node;
			} else if (ends[1] == null && !ends[0].equals(node)) {
				ends[1] = node;
			}
		}));

		final java.util.ArrayList<Position[]> sorted = new java.util.ArrayList<>();
		final java.util.HashMap<String, Integer> railIndex = new java.util.HashMap<>();
		railEnds.forEach((hex, ends) -> {
			railIndex.put(hex, sorted.size());
			sorted.add(ends);
		});
		sorted.sort((a, b) -> {
			final int byX = Integer.compare((int) (a[0].getX() + a[1].getX()), (int) (b[0].getX() + b[1].getX()));
			return byX != 0 ? byX : Double.compare(Math.min(a[0].getZ(), a[1].getZ()), Math.min(b[0].getZ(), b[1].getZ()));
		});
		final String[] hexByIndex = new String[sorted.size()];
		railIndex.forEach((hex, index) -> hexByIndex[index] = hex);

		int junctionNodes = 0;
		int deadEndNodes = 0;
		final int[] junctionCount = {0};
		final int[] deadEndCount = {0};
		final int[][] neighbourCounts = new int[sorted.size()][2];
		final java.util.HashMap<String, java.util.ArrayList<String>> neighboursAt = new java.util.HashMap<>();
		sim.positionsToRail.forEach((node, neighbourMap) -> {
			if (neighbourMap.size() >= 3) {
				junctionCount[0]++;
			}
			if (neighbourMap.size() == 1) {
				deadEndCount[0]++;
			}
			neighbourMap.forEach((pos, rail) -> {
				final String hex = rail.getHexId();
				final int index = railIndex.get(hex);
				final Position[] ends = railEnds.get(hex);
				final int endIndex = ends[0].equals(node) ? 0 : 1;
				neighbourCounts[index][endIndex]++;
				final java.util.ArrayList<String> list = neighboursAt.computeIfAbsent(hex, k -> new java.util.ArrayList<>());
				neighbourMap.forEach((otherPos, otherRail) -> {
					if (!otherRail.getHexId().equals(hex) && !list.contains(otherRail.getHexId())) {
						list.add(otherRail.getHexId());
					}
				});
			});
		});
		junctionNodes = junctionCount[0];
		deadEndNodes = deadEndCount[0];

		System.out.println("[CONN] totalRails=" + railEnds.size() + " junctionNodes(>=3)=" + junctionNodes + " deadEndNodes=" + deadEndNodes);
		// Mark platform rails so the mainline stops are easy to spot in the dump.
		final java.util.HashSet<String> platformRails = new java.util.HashSet<>();
		for (final Platform platform : sim.platforms) {
			final Rail rail = platform.mmtrGraphRail();
			if (rail != null) {
				platformRails.add(rail.getHexId());
			}
		}
		for (int i = 0; i < sorted.size(); i++) {
			final String hex = hexByIndex[i];
			final Position[] ends = railEnds.get(hex);
			final Rail rail = byHex.get(hex);
			final StringBuilder line = new StringBuilder();
			line.append("[CONN] ").append(i).append(platformRails.contains(hex) ? " P " : "   ")
				.append(hex).append(" len=").append(Math.round(rail.railMath.getLength() * 10.0) / 10.0)
				.append(" (").append((int) ends[0].getX()).append(",").append((int) ends[0].getZ()).append(") -> (")
				.append((int) ends[1].getX()).append(",").append((int) ends[1].getZ()).append(")");
			final java.util.ArrayList<String> list = neighboursAt.getOrDefault(hex, new java.util.ArrayList<>());
			final StringBuilder nbr = new StringBuilder();
			for (final String nHex : list) {
				final int nIndex = railIndex.get(nHex);
				final Position[] nEnds = railEnds.get(nHex);
				final Position far = nEnds[0].equals(ends[0]) || nEnds[0].equals(ends[1]) ? nEnds[1] : nEnds[0];
				nbr.append(" ").append((int) far.getX()).append(",").append((int) far.getZ());
			}
			line.append(" nbrs[").append(neighbourCounts[i][0]).append("/").append(neighbourCounts[i][1]).append("]:").append(nbr);
			System.out.println(line);
		}
	}

	/**
	 * Balloon-junction focus: for the corridor-end connector rails print, per endpoint, the chord
	 * bearing of the rail and the rail's own stored angle AT that endpoint (getStartAngle(pos)) -
	 * decides whether stored angles are smooth continuation tangents or something else.
	 */
	@Test
	public void surveyBalloonAngles() {
		org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(DEV_WORLD_MTR_ROOT), "live dev world not present - skipping");
		final Simulator sim = new Simulator("minecraft/overworld", new String[]{"minecraft/overworld"}, DEV_WORLD_MTR_ROOT, false);
		assertTrue(sim.rails.size() > 0, "rails loaded");
		// Corridor-end connectors and neighbours around the north balloon (x=-155/-147 north tips)
		// and the south balloon (z -476..-495 ladder): x/z rounded short keys.
		final java.util.HashSet<String> wantedEnds = new java.util.HashSet<>();
		for (final String key : new String[]{"-155,-189", "-155,-169", "-147,-169", "-147,-147", "-155,-476", "-161,-486", "-170,-478", "-161,-495", "-147,-476", "-176,-478"}) {
			wantedEnds.add(key);
		}
		final java.util.HashSet<Rail> printed = new java.util.HashSet<>();
		sim.positionsToRail.forEach((node, neighbourMap) -> {
			final String key = (int) node.getX() + "," + (int) node.getZ();
			if (!wantedEnds.contains(key)) {
				return;
			}
			neighbourMap.forEach((far, rail) -> {
				if (printed.contains(rail)) {
					return;
				}
				printed.add(rail);
				final Position p1 = rail.getPosition1();
				final Position p2 = rail.getPosition2();
				System.out.println("[BAL] node " + key + " rail " + rail.getHexId().substring(rail.getHexId().length() - 24)
					+ " p1=(" + (int) p1.getX() + "," + (int) p1.getZ() + ") p2=(" + (int) p2.getX() + "," + (int) p2.getZ() + ")"
					+ " a1=" + rail.getStartAngle(p1).name() + " a2=" + rail.getStartAngle(p2).name());
			});
		});
		for (final Rail rail : printed) {
			final Position p1 = rail.getPosition1();
			final Position p2 = rail.getPosition2();
			final double bearing = Math.toDegrees(Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX()));
			final org.mtr.core.tool.Angle chord = org.mtr.core.tool.Angle.fromAngle((float) bearing);
			System.out.println("[CHORD] " + rail.getHexId().substring(rail.getHexId().length() - 24) + " p1->p2 bearing=" + Math.round(bearing) + " sector=" + chord.name()
				+ " a1=" + rail.getStartAngle(p1).name() + " a2=" + rail.getStartAngle(p2).name());
		}
	}
}
