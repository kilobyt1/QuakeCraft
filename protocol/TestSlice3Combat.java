package dev.quakecraft.bridge;

import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/**
 * Mechanical Oracle for Vertical Slice 3 (Stage 4: Two-Way Combat, Proxy Entities & Block Destruction):
 * Launches real 64-bit quake3-bridge.exe and verifies across Local\QuakeCraftBridgeV1:
 *   1. Phase 1 (Hitscan + Gibbing): Places a Minecraft Zombie proxy actor at +X (128, 0, 56) in shm->actors,
 *      fires the Quake 3 Railgun (weapon 7), and verifies EV_DAMAGE_ENTITY (with targetId=4201, damage=100,
 *      isGib=1) and EV_BREAK_BLOCK are emitted into shm->q3ToMcEvents.
 *   2. Phase 2 (Projectile Explosion & Splash): Places a Minecraft Creeper proxy actor at +X (96, 0, 56),
 *      switches to Rocket Launcher (weapon 5), fires a rocket, and verifies EV_EXPLOSION (with radius=120)
 *      and EV_DAMAGE_ENTITY are emitted into shm->q3ToMcEvents.
 *   3. Phase 3 (MC -> Q3 Damage & Armor Absorption): Pushes EV_GIVE_ITEM (Red Armor +100) and
 *      EV_PLAYER_HURT (damage=60) into shm->mcToQ3Events and verifies Quake 3's genuine 66% armor
 *      absorption reduces player health by 20 (100 -> 80) and armor by 40 (100 -> 60) in shm->pose.
 */
public final class TestSlice3Combat {
	public static void main(String[] args) throws Throwable {
		SharedMemoryBridge bridge = SharedMemoryBridge.openOrCreate(SharedMemoryBridge.SHM_NAME);
		MemorySegment seg = bridge.segment;

		// Reset event rings
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 0L, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 4L, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_MC_TO_Q3 + 0L, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_MC_TO_Q3 + 4L, 0);

		// Setup voxel floor at by=0 and a breakable wall at bx=6 (Q3 X = 192..224)
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_X, -32);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Y, -32);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Z, -32);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_X, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_Y, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.VOXELS_ANCHOR_Z, 0.0f);
		seg.asSlice(SharedMemoryBridge.OFF_VOXEL_CELLS, SharedMemoryBridge.VOXEL_TOTAL).fill((byte) 0);

		int dyFloor = 32; // by = 0 -> Q3 Z in [0..32]
		for (int dz = 16; dz < 48; dz++) {
			for (int dx = 16; dx < 48; dx++) {
				long idx = (long) dyFloor * 4096L + (long) dz * 64L + (long) dx;
				seg.set(SharedMemoryBridge.BYTE, SharedMemoryBridge.OFF_VOXEL_CELLS + idx, (byte) SharedMemoryBridge.VOX_SOLID);
			}
		}
		// Wall at bx = 6 (dx = 38), by = 1..3 (dy = 33..35), bz = -2..2 (dz = 30..34)
		for (int dy = 33; dy <= 35; dy++) {
			for (int dz = 30; dz <= 34; dz++) {
				long idx = (long) dy * 4096L + (long) dz * 64L + 38L;
				seg.set(
					SharedMemoryBridge.BYTE,
					SharedMemoryBridge.OFF_VOXEL_CELLS + idx,
					(byte) (SharedMemoryBridge.VOX_SOLID | SharedMemoryBridge.SURF_METAL)
				);
			}
		}
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 2);

		// Populate 1 Minecraft Zombie actor in shm->actors at Q3 (110, 0, 56), healthQ3 = 100 (20 MC HP)
		writeActor(seg, 0, 4201, 110.0f, 0.0f, 56.0f, 12.0f, 58.0f, 100.0f);

		// Teleport player to (0, 0, 56) facing +X (mcYaw = -90 -> q3Yaw = 0), select Railgun (weapon 7)
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FLAGS, SharedMemoryBridge.FLAG_ACTIVE | SharedMemoryBridge.FLAG_PASSTHROUGH_ON);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, 0);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_WEAPON_SELECT, 7); // WP_RAILGUN
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_YAW, -90.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_PITCH, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_FOV_Y, 70.0f);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_VIEWPORT_W, 640);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_VIEWPORT_H, 480);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_X, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Y, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Z, 56.0f);
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
			// Wait until player has teleportAckSeq == 1 and weapon == 7 (WP_RAILGUN) and weaponState == 0 (WEAPON_READY)
			long readyDeadline = System.currentTimeMillis() + 15000L;
			boolean ready = false;
			while (System.currentTimeMillis() < readyDeadline) {
				int ack = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_TELEPORT_ACK_SEQ);
				int weap = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON);
				int weapState = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON_STATE);
				if (ack >= 1 && weap == 7 && weapState == 0) {
					ready = true;
					break;
				}
				Thread.sleep(25L);
			}
			if (!ready) {
				throw new IllegalStateException("Timed out waiting for Railgun ready state");
			}

			// Phase 1: Press BTN_ATTACK with Railgun -> should pierce Zombie (4201) AND hit the wall behind it!
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 3);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, SharedMemoryBridge.BTN_ATTACK);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 4);

			long phase1Deadline = System.currentTimeMillis() + 5000L;
			boolean gotRailDamage = false;
			boolean gotRailBreak = false;
			while (System.currentTimeMillis() < phase1Deadline && (!gotRailDamage || !gotRailBreak)) {
				int w = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 0L);
				int r = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 4L);
				while (r != w) {
					int slot = r & (SharedMemoryBridge.EVENT_RING_CAP - 1);
					long evOff = SharedMemoryBridge.OFF_Q3_TO_MC + 16L + (long) slot * SharedMemoryBridge.STRIDE_EVENT;
					int type = seg.get(SharedMemoryBridge.INT, evOff + 0L);
					int targetId = seg.get(SharedMemoryBridge.INT, evOff + 4L);
					int p1 = seg.get(SharedMemoryBridge.INT, evOff + 8L);
					int p2 = seg.get(SharedMemoryBridge.INT, evOff + 12L);
					int p3 = seg.get(SharedMemoryBridge.INT, evOff + 16L);
					r++;
					seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 4L, r);

					if (type == SharedMemoryBridge.EV_DAMAGE_ENTITY && targetId == 4201) {
						System.out.printf(
							"[PASS] Phase 1a (Railgun Entity Hit): targetId=%d damageQ3=%d mod=%d isGib=%d%n",
							targetId, p1, p2, p3
						);
						gotRailDamage = true;
					} else if (type == SharedMemoryBridge.EV_BREAK_BLOCK) {
						System.out.printf(
							"[PASS] Phase 1b (Railgun Piercing Block Break): weapon=%d block=(%d, %d, %d)%n",
							targetId, p1, p2, p3
						);
						gotRailBreak = true;
					}
				}
				Thread.sleep(20L);
			}
			if (!gotRailDamage || !gotRailBreak) {
				throw new IllegalStateException("Phase 1 failed: gotRailDamage=" + gotRailDamage + " gotRailBreak=" + gotRailBreak);
			}

			// Phase 2: Switch to Rocket Launcher (weapon 5), place Creeper actor (targetId=4202) near wall at (170, 0, 56), fire rocket
			writeActor(seg, 0, 4202, 170.0f, 0.0f, 56.0f, 12.0f, 54.0f, 100.0f);
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 5);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, 0);
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_WEAPON_SELECT, 5); // WP_ROCKET_LAUNCHER
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 6);

			// Wait for Rocket Launcher ready
			long rlDeadline = System.currentTimeMillis() + 5000L;
			while (System.currentTimeMillis() < rlDeadline) {
				int weap = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON);
				int weapState = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON_STATE);
				if (weap == 5 && weapState == 0) {
					break;
				}
				Thread.sleep(25L);
			}

			// Fire Rocket!
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 7);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, SharedMemoryBridge.BTN_ATTACK);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 8);

			long phase2Deadline = System.currentTimeMillis() + 5000L;
			boolean gotRocketDamage = false;
			boolean gotExplosion = false;
			while (System.currentTimeMillis() < phase2Deadline && (!gotRocketDamage || !gotExplosion)) {
				int w = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 0L);
				int r = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 4L);
				while (r != w) {
					int slot = r & (SharedMemoryBridge.EVENT_RING_CAP - 1);
					long evOff = SharedMemoryBridge.OFF_Q3_TO_MC + 16L + (long) slot * SharedMemoryBridge.STRIDE_EVENT;
					int type = seg.get(SharedMemoryBridge.INT, evOff + 0L);
					int targetId = seg.get(SharedMemoryBridge.INT, evOff + 4L);
					int p1 = seg.get(SharedMemoryBridge.INT, evOff + 8L);
					int p2 = seg.get(SharedMemoryBridge.INT, evOff + 12L);
					int p3 = seg.get(SharedMemoryBridge.INT, evOff + 16L);
					float radius = seg.get(SharedMemoryBridge.FLOAT, evOff + 20L);
					r++;
					seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.OFF_Q3_TO_MC + 4L, r);

					if (type == SharedMemoryBridge.EV_DAMAGE_ENTITY && targetId == 4202) {
						System.out.printf(
							"[PASS] Phase 2a (Rocket Direct/Splash Hit): targetId=%d damageQ3=%d isGib=%d%n",
							targetId, p1, p3
						);
						gotRocketDamage = true;
					} else if (type == SharedMemoryBridge.EV_EXPLOSION) {
						System.out.printf(
							"[PASS] Phase 2b (Rocket Explosion Event): weapon=%d block=(%d, %d, %d) radiusQ3=%.1f%n",
							targetId, p1, p2, p3, radius
						);
						gotExplosion = true;
					}
				}
				Thread.sleep(20L);
			}
			if (!gotRocketDamage || !gotExplosion) {
				throw new IllegalStateException("Phase 2 failed: gotRocketDamage=" + gotRocketDamage + " gotExplosion=" + gotExplosion);
			}

			// Stop firing
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 9);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, 0);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 10);

			// Phase 3: Push EV_GIVE_ITEM (Red Armor +100, itemType=7) and EV_PLAYER_HURT (damage=60) into shm->mcToQ3Events
			int healthBefore = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_HEALTH);
			pushMcToQ3Event(seg, SharedMemoryBridge.EV_GIVE_ITEM, 7, 100);
			Thread.sleep(100L);
			int armorAfterPickup = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_ARMOR);
			if (armorAfterPickup < 50) {
				throw new IllegalStateException("Expected armor pickup >= 50, got " + armorAfterPickup);
			}

			pushMcToQ3Event(seg, SharedMemoryBridge.EV_PLAYER_HURT, 0, 60);
			long phase3Deadline = System.currentTimeMillis() + 3000L;
			boolean gotHurt = false;
			while (System.currentTimeMillis() < phase3Deadline) {
				int hpNow = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_HEALTH);
				int armorNow = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_ARMOR);
				if (hpNow < healthBefore && armorNow < armorAfterPickup) {
					System.out.printf(
						"[PASS] Phase 3 (MC -> Q3 Damage & 66%% Armor Absorption): health %d -> %d, armor %d -> %d%n",
						healthBefore, hpNow, armorAfterPickup, armorNow
					);
					gotHurt = true;
					break;
				}
				Thread.sleep(20L);
			}
			if (!gotHurt) {
				throw new IllegalStateException("Phase 3 failed: Player health/armor did not reflect MC -> Q3 damage event");
			}
		} finally {
			ProcessHandle.of(q3Pid).ifPresent(ph -> {
				ph.destroyForcibly();
				System.out.println("Cleaned up quake3-bridge.exe (PID=" + q3Pid + ")");
			});
		}
	}

	private static void writeActor(
		MemorySegment seg,
		int slot,
		int mcEntityId,
		float qx,
		float qy,
		float qz,
		float halfWidth,
		float height,
		float healthQ3
	) {
		int seq = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_SEQ) + 2;
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_SEQ, seq - 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_COUNT, slot + 1);
		seg.set(SharedMemoryBridge.LONG, SharedMemoryBridge.ACTORS_TIMESTAMP_NS, System.nanoTime());

		long base = SharedMemoryBridge.OFF_ACTOR_ARRAY + (long) slot * SharedMemoryBridge.STRIDE_ACTOR;
		seg.set(SharedMemoryBridge.INT, base + 0L, mcEntityId);
		seg.set(SharedMemoryBridge.INT, base + 4L, 1);
		seg.set(SharedMemoryBridge.INT, base + 8L, 1);
		seg.set(SharedMemoryBridge.INT, base + 12L, 1 | 2); // active | hostile
		seg.set(SharedMemoryBridge.FLOAT, base + 16L, qx);
		seg.set(SharedMemoryBridge.FLOAT, base + 20L, qy);
		seg.set(SharedMemoryBridge.FLOAT, base + 24L, qz);
		seg.set(SharedMemoryBridge.FLOAT, base + 28L, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, base + 32L, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, base + 36L, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, base + 40L, halfWidth);
		seg.set(SharedMemoryBridge.FLOAT, base + 44L, height);
		seg.set(SharedMemoryBridge.FLOAT, base + 48L, healthQ3);
		seg.set(SharedMemoryBridge.FLOAT, base + 52L, healthQ3);
		seg.set(SharedMemoryBridge.FLOAT, base + 56L, 180.0f);
		seg.set(SharedMemoryBridge.FLOAT, base + 60L, 0.0f);

		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_SEQ, seq);
	}

	private static void pushMcToQ3Event(MemorySegment seg, int type, int targetId, int intParam1) {
		long ringBase = SharedMemoryBridge.OFF_MC_TO_Q3;
		int w = seg.get(SharedMemoryBridge.INT, ringBase + 0L);
		int slot = w & (SharedMemoryBridge.EVENT_RING_CAP - 1);
		long evOff = ringBase + 16L + (long) slot * SharedMemoryBridge.STRIDE_EVENT;
		seg.set(SharedMemoryBridge.INT, evOff + 0L, type);
		seg.set(SharedMemoryBridge.INT, evOff + 4L, targetId);
		seg.set(SharedMemoryBridge.INT, evOff + 8L, intParam1);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 24L, -1.0f);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 28L, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 32L, 0.0f);
		seg.set(SharedMemoryBridge.LONG, evOff + 48L, System.nanoTime());
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, ringBase + 0L, w + 1);
		VarHandle.fullFence();
	}
}
