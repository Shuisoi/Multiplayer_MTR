package org.mtr.mod.client;

import org.mtr.mapping.holder.ClientPlayerEntity;

/**
 * Permission seam for boarding and cab operation. Everything is open for now (the creative driver
 * key is no longer required to board a train), but every gate goes through this class so a later
 * slice can plug in a real authoriser (driver licences, depot rosters, server-side grants) without
 * touching the render/riding code again.
 *
 * <p>Usage: call the matching {@code canXxx} before allowing the action. The default authoriser
 * allows everything; install a different one with {@link #setAuthorizer}.</p>
 */
public final class MmtrCabPermissions {

	private MmtrCabPermissions() {
	}

	/** Implement this to restrict anything; every method defaults to "allowed". */
	public interface Authorizer {

		/** May the player walk into the train (and take a cab with the interact key)? */
		default boolean canBoard(ClientPlayerEntity player, long vehicleId) {
			return true;
		}

		/** May the player work the throttle, brake and reverser? */
		default boolean canDrive(ClientPlayerEntity player, long vehicleId) {
			return true;
		}

		/** May the player open and close the doors? */
		default boolean canOpenDoors(ClientPlayerEntity player, long vehicleId) {
			return true;
		}

		/** May the player see the in-cab console while riding? */
		default boolean canViewConsole(ClientPlayerEntity player, long vehicleId) {
			return true;
		}
	}

	private static Authorizer authorizer = new Authorizer() {
	};

	/** Installs a new authoriser; {@code null} restores the fully open default. */
	public static void setAuthorizer(Authorizer newAuthorizer) {
		authorizer = newAuthorizer == null ? new Authorizer() {
		} : newAuthorizer;
	}

	public static boolean canBoard(ClientPlayerEntity player, long vehicleId) {
		return authorizer.canBoard(player, vehicleId);
	}

	public static boolean canDrive(ClientPlayerEntity player, long vehicleId) {
		return authorizer.canDrive(player, vehicleId);
	}

	public static boolean canOpenDoors(ClientPlayerEntity player, long vehicleId) {
		return authorizer.canOpenDoors(player, vehicleId);
	}

	public static boolean canViewConsole(ClientPlayerEntity player, long vehicleId) {
		return authorizer.canViewConsole(player, vehicleId);
	}
}
