package dev.quakecraft.bridge;

import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/**
 * Mechanical Oracle for Vertical Slice 1:
 * Launches the real 64-bit quake3-bridge.exe + qagamex86.dll, feeds a synthetic
 * 64x64x64 Minecraft voxel floor + wall via Local\QuakeCraftBridgeV1, and verifies:
 *   1. Authoritative Q3 gravity + landing on voxel floor (z = 56.125, GROUNDED)
 *   2. Authoritative Q3 jumping (z rises > 75) and forward acceleration (vx > 200)
 *   3. Authoritative Q3 swept AABB collision against a voxel wall at bx=6 (x <= 177.0)
 *   4. Weapon selection from shared memory (weaponSelect=7 -> WP_RAILGUN)
 */
public final class TestSlice1Physics {
	public static void main(String[] args) throws Throwable {
		SharedMemoryBridge bridge = SharedMemoryBridge.openOrCreate(SharedMemoryBridge.SHM_NAME);
		MemorySegment seg = bridge.segment;

		// Clear pose & setup voxel floor at by=0 (top at Q3 Z = +32.0, so player feet at 32.0 -> origin Z = 56.125)
		// plus a solid wall at bx=6 (Q3 X = 192.0, so player maxs[0]=15 stops at X = 176.875)
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_SEQ, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_TELEPORT_ACK_SEQ, 0);

		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_X, -32);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Y, -32);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Z, -32);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_X, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_Y, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_Z, 0.0f);

		// Zero all voxel cells first
		seg.asSlice(SharedMemoryBridge.OFF_VOXEL_CELLS, SharedMemoryBridge.VOXEL_TOTAL).fill((byte) 0);

		// Floor at by = 0 -> dy = 0 - (-32) = 32
		int dyFloor = 32;
		for (int dz = 16; dz < 48; dz++) {
			for (int dx = 16; dx < 48; dx++) {
				long idx = (long) dyFloor * 4096L + (long) dz * 64L + (long) dx;
				seg.set(SharedMemoryBridge.BYTE, SharedMemoryBridge.OFF_VOXEL_CELLS + idx, (byte) SharedMemoryBridge.VOX_SOLID);
			}
		}

		// Wall at bx = 6 -> dx = 6 - (-32) = 38, for by = 1..4 (dy = 33..36)
		int dxWall = 38;
		for (int dy = 33; dy <= 36; dy++) {
			for (int dz = 16; dz < 48; dz++) {
				long idx = (long) dy * 4096L + (long) dz * 64L + (long) dxWall;
				seg.set(SharedMemoryBridge.BYTE, SharedMemoryBridge.OFF_VOXEL_CELLS + idx, (byte) SharedMemoryBridge.VOX_SOLID);
			}
		}
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 2);

		// Request initial teleport to Q3 (0, 0, 120) looking along +X (mcYaw = -90 -> q3Yaw = 0)
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FLAGS, SharedMemoryBridge.FLAG_ACTIVE | SharedMemoryBridge.FLAG_PASSTHROUGH_ON);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FORWARDMOVE, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_RIGHTMOVE, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_UPMOVE, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_WEAPON_SELECT, 0);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_YAW, -90.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_PITCH, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_X, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Y, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Z, 120.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_YAW, 0.0f);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_TELEPORT_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 2);

		ProcessBuilder pb = new ProcessBuilder(
			"C:\\QuakeCraft\\build\\q3\\quake3-bridge.exe",
			"+set", "fs_basepath", "C:\\QuakeCraft\\Sources\\QUAKE3",
			"+set", "fs_homepath", "C:\\QuakeCraft\\lab\\q3home",
			"+set", "vm_game", "0",
			"+set", "vm_cgame", "0",
			"+set", "vm_ui", "0",
			"+set", "sv_pure", "0",
			"+set", "r_fullscreen", "0",
			"+set", "r_mode", "3",
			"+set", "in_mouse", "0",
			"+set", "s_initsound", "0",
			"+set", "bot_enable", "0",
			"+devmap", "q3dm1"
		);
		pb.directory(new File("C:\\QuakeCraft\\build\\q3"));
		Process q3 = pb.start();
		long q3Pid = q3.pid();
		System.out.println("Launched quake3-bridge.exe (PID=" + q3Pid + ")");

		try {
			// Phase 1: Wait for Q3 to spawn player, ack teleportSeq=1, and land on voxel floor (z ~ 56.125)
			long deadline = System.currentTimeMillis() + 15000L;
			boolean landed = false;
			float landedZ = 0.0f;
			while (System.currentTimeMillis() < deadline) {
				int ack = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_TELEPORT_ACK_SEQ);
				int flags = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_FLAGS);
				float z = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Z);
				if (ack == 1 && (flags & 8) != 0 && Math.abs(z - 56.125f) < 1.0f) {
					landed = true;
					landedZ = z;
					break;
				}
				Thread.sleep(20L);
			}
			if (!landed) {
				float x = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_X);
				float y = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Y);
				float z = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Z);
				int ack = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_TELEPORT_ACK_SEQ);
				int seq = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_SEQ);
				throw new IllegalStateException("Player did not land on voxel floor: seq=" + seq + " ack=" + ack + " pos=(" + x + "," + y + "," + z + ")");
			}
			System.out.println("[PASS] Phase 1: Player landed on voxel floor at Z=" + landedZ);

			// Phase 2: Drive forward + jump + switch to Railgun (weapon 7) towards wall at bx=6 (X=192, player stops ~176.875)
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 3);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FORWARDMOVE, 127);
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_UPMOVE, 127);
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, SharedMemoryBridge.BTN_JUMP);
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_WEAPON_SELECT, 7);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 4);

			float maxZ = landedZ;
			float maxVx = 0.0f;
			float finalX = 0.0f;
			int finalWeapon = 0;

			for (int i = 0; i < 75; i++) { // 1.5 seconds of running/jumping into the wall
				// Release jump after 200ms so player lands and keeps running into the wall
				if (i == 10) {
					seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 5);
					VarHandle.fullFence();
					seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_UPMOVE, 0);
					seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, 0);
					VarHandle.fullFence();
					seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 6);
				}
				float x = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_X);
				float z = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Z);
				float vx = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_VEL_X);
				int w = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON);
				if (z > maxZ) maxZ = z;
				if (vx > maxVx) maxVx = vx;
				finalX = x;
				finalWeapon = w;
				Thread.sleep(20L);
			}

			System.out.println(String.format(
				"[INFO] Phase 2 metrics: maxZ=%.2f maxVx=%.2f finalX=%.3f finalWeapon=%d",
				maxZ, maxVx, finalX, finalWeapon
			));

			if (maxZ < 75.0f) {
				throw new IllegalStateException("Jump height too low: maxZ=" + maxZ);
			}
			if (maxVx < 200.0f) {
				throw new IllegalStateException("Forward velocity too low: maxVx=" + maxVx);
			}
			if (finalX < 170.0f || finalX > 177.5f) {
				throw new IllegalStateException("Player did not stop against voxel wall at X=176.875: finalX=" + finalX);
			}
			if (finalWeapon != 7) {
				throw new IllegalStateException("Weapon did not switch to WP_RAILGUN (7): finalWeapon=" + finalWeapon);
			}
			System.out.println("[PASS] Phase 2: Jump, acceleration, voxel wall stop (finalX=" + finalX + "), and weapon switch (WP_RAILGUN) verified!");
		} finally {
			q3.destroyForcibly();
			q3.waitFor();
			System.out.println("Cleaned up quake3-bridge.exe (PID=" + q3Pid + ")");
		}
	}
}
