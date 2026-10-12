package dev.quakecraft.bridge;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

public final class TestCrossProcessShm {
	public static void main(String[] args) throws Throwable {
		String mapName = "Local\\QuakeCraftBridgeV1_Test";
		SharedMemoryBridge bridge = SharedMemoryBridge.openOrCreate(mapName);
		MemorySegment seg = bridge.segment;

		// Populate Input channel from Java
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_YAW, 45.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_PITCH, -15.0f);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FORWARDMOVE, 127);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_UPMOVE, 127);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, SharedMemoryBridge.BTN_JUMP);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 2);

		// Populate Voxel snapshot from Java
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_X, 100);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Y, 64);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Z, -200);
		seg.set(SharedMemoryBridge.BYTE, SharedMemoryBridge.OFF_VOXEL_CELLS + (32L * 4096L + 32L * 64L + 32L), (byte) SharedMemoryBridge.VOX_SOLID);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 2);

		// Run C verifier while keeping mapping alive in this process
		ProcessBuilder pb = new ProcessBuilder("C:\\QuakeCraft\\build\\test_protocol_layout.exe", "--verify-shm");
		pb.inheritIO();
		Process p = pb.start();
		int rc = p.waitFor();
		if (rc != 0) {
			throw new IllegalStateException("C --verify-shm exited with " + rc);
		}

		// Verify C's Pose write back in Java
		int poseSeq = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_SEQ);
		float qx = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_X);
		float qy = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Y);
		float qz = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Z);
		int health = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_HEALTH);
		int armor = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_ARMOR);
		int weapon = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON);
		int ammo = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_CURRENT_AMMO);

		if (poseSeq != 2 || qx != 64.0f || qy != -96.0f || qz != 56.0f || health != 125 || armor != 100 || weapon != 5 || ammo != 25) {
			throw new IllegalStateException("Mismatch reading C pose in Java: seq=" + poseSeq + " pos=(" + qx + "," + qy + "," + qz + ")");
		}
		System.out.println("Java<->C Roundtrip verification PASSED! health=" + health + " armor=" + armor + " weapon=" + weapon + " ammo=" + ammo);
	}
}
