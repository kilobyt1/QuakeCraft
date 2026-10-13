package dev.quakecraft.bridge;

import java.io.File;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

/**
 * Mechanical Oracle for Vertical Slice 2 (Stage 3: Depth-Aware Frame Capture & Overlay Export):
 * Launches real 64-bit quake3-bridge.exe, fires a rocket in third-person & first-person views,
 * and verifies that Local\QuakeCraftBridgeV1 triple-buffered frame slots receive:
 *   1. Valid frame metadata (width=640, height=480, fovY=70.0, even seqlock, frameNumber > 0)
 *   2. Layer 0 (World RGBA8) + Layer 1 (Linear R32F depth in Q3 units) with non-zero 3D entity pixels
 *      while BSP world background pixels have depth == 0.0f (sky/background sentinel)
 *   3. Layer 2 (Overlay RGBA8) with non-zero first-person weapon (RF_DEPTHHACK) + 2D HUD pixels
 */
public final class TestSlice2FrameCapture {
	public static void main(String[] args) throws Throwable {
		SharedMemoryBridge bridge = SharedMemoryBridge.openOrCreate(SharedMemoryBridge.SHM_NAME);
		MemorySegment seg = bridge.segment;

		// Clear frame descriptors
		for (int i = 0; i < SharedMemoryBridge.FRAME_SLOTS; i++) {
			long descOff = SharedMemoryBridge.OFF_FRAMES_DESC + (long) i * SharedMemoryBridge.STRIDE_FRAME_DESC;
			seg.set(SharedMemoryBridge.INT, descOff + 0L, 0);
			seg.set(SharedMemoryBridge.LONG, descOff + 24L, 0L);
		}

		// Setup voxel floor at by=0
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_X, -32);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Y, -32);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_ORIGIN_Z, -32);
		seg.asSlice(SharedMemoryBridge.OFF_VOXEL_CELLS, SharedMemoryBridge.VOXEL_TOTAL).fill((byte) 0);
		int dyFloor = 32;
		for (int dz = 16; dz < 48; dz++) {
			for (int dx = 16; dx < 48; dx++) {
				long idx = (long) dyFloor * 4096L + (long) dz * 64L + (long) dx;
				seg.set(SharedMemoryBridge.BYTE, SharedMemoryBridge.OFF_VOXEL_CELLS + idx, (byte) SharedMemoryBridge.VOX_SOLID);
			}
		}
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.VOXELS_SEQ, 2);

		// Configure input: first-person (with weapon + HUD), Rocket Launcher (5), FOV 70, 640x480
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 1);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FLAGS, SharedMemoryBridge.FLAG_ACTIVE | SharedMemoryBridge.FLAG_PASSTHROUGH_ON);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_WEAPON_SELECT, 5);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_YAW, -90.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_PITCH, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_FOV_Y, 70.0f);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_VIEWPORT_W, 640);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_VIEWPORT_H, 480);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_X, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Y, 0.0f);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Z, 80.0f);
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
			// Phase 1: Wait for first-person frame with Overlay RGBA8 (weapon + 2D HUD) and transparent sky depth
			long deadline = System.currentTimeMillis() + 15000L;
			boolean fpVerified = false;
			while (System.currentTimeMillis() < deadline) {
				int slot = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.HDR_LATEST_FRAME_SLOT);
				long descOff = SharedMemoryBridge.OFF_FRAMES_DESC + (long) slot * SharedMemoryBridge.STRIDE_FRAME_DESC;
				int seq = seg.get(SharedMemoryBridge.INT, descOff + 0L);
				long frameNum = seg.get(SharedMemoryBridge.LONG, descOff + 24L);
				if (seq > 0 && (seq & 1) == 0 && frameNum >= 10) {
					int w = seg.get(SharedMemoryBridge.INT, descOff + 8L);
					int h = seg.get(SharedMemoryBridge.INT, descOff + 12L);
					float fovY = seg.get(SharedMemoryBridge.FLOAT, descOff + 96L);
					int hudOff = seg.get(SharedMemoryBridge.INT, descOff + 116L);
					int depthOff = seg.get(SharedMemoryBridge.INT, descOff + 112L);

					int nonZeroOverlay = 0;
					int zeroDepthPixels = 0;
					int totalPixels = w * h;
					for (int i = 0; i < totalPixels; i += 4) {
						int rgba = seg.get(SharedMemoryBridge.INT, Integer.toUnsignedLong(hudOff) + (long) i * 4L);
						if ((rgba & 0x00FFFFFF) != 0) {
							nonZeroOverlay++;
						}
						float d = seg.get(SharedMemoryBridge.FLOAT, Integer.toUnsignedLong(depthOff) + (long) i * 4L);
						if (d == 0.0f) {
							zeroDepthPixels++;
						}
					}
					if (w == 640 && h == 480 && Math.abs(fovY - 70.0f) < 1.0f && nonZeroOverlay > 500 && zeroDepthPixels > 10000) {
						System.out.printf(
							"[PASS] Phase 1 (First-Person Overlay + Sky Sentinel): frame=%d (%dx%d fovY=%.1f) overlayPixels=%d skySentinelPixels=%d%n",
							frameNum, w, h, fovY, nonZeroOverlay * 4, zeroDepthPixels * 4
						);
						fpVerified = true;
						break;
					}
				}
				Thread.sleep(30L);
			}
			if (!fpVerified) {
				throw new IllegalStateException("Phase 1 failed: First-person Overlay RGBA8 or sky depth sentinel not verified");
			}

			// Phase 2: Switch to Third-Person (FLAG_THIRD_PERSON = 1<<3) + fire Rocket Launcher so 3D player model + rocket render in World pass
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 3);
			VarHandle.fullFence();
			seg.set(
				SharedMemoryBridge.INT,
				SharedMemoryBridge.INPUT_FLAGS,
				SharedMemoryBridge.FLAG_ACTIVE | SharedMemoryBridge.FLAG_PASSTHROUGH_ON | SharedMemoryBridge.FLAG_THIRD_PERSON
			);
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, SharedMemoryBridge.BTN_ATTACK);
			VarHandle.fullFence();
			seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, 4);

			long tpDeadline = System.currentTimeMillis() + 8000L;
			boolean tpVerified = false;
			while (System.currentTimeMillis() < tpDeadline) {
				int slot = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.HDR_LATEST_FRAME_SLOT);
				long descOff = SharedMemoryBridge.OFF_FRAMES_DESC + (long) slot * SharedMemoryBridge.STRIDE_FRAME_DESC;
				int seq = seg.get(SharedMemoryBridge.INT, descOff + 0L);
				if (seq > 0 && (seq & 1) == 0) {
					int w = seg.get(SharedMemoryBridge.INT, descOff + 8L);
					int h = seg.get(SharedMemoryBridge.INT, descOff + 12L);
					int rgbaOff = seg.get(SharedMemoryBridge.INT, descOff + 108L);
					int depthOff = seg.get(SharedMemoryBridge.INT, descOff + 112L);

					int valid3dEntityPixels = 0;
					float minDepthQ3 = Float.MAX_VALUE;
					float maxDepthQ3 = 0.0f;
					int totalPixels = w * h;
					for (int i = 0; i < totalPixels; i++) {
						float d = seg.get(SharedMemoryBridge.FLOAT, Integer.toUnsignedLong(depthOff) + (long) i * 4L);
						int rgba = seg.get(SharedMemoryBridge.INT, Integer.toUnsignedLong(rgbaOff) + (long) i * 4L);
						if (d > 4.0f && d < 500.0f && (rgba & 0x00FFFFFF) != 0) {
							valid3dEntityPixels++;
							if (d < minDepthQ3) minDepthQ3 = d;
							if (d > maxDepthQ3) maxDepthQ3 = d;
						}
					}
					if (valid3dEntityPixels > 500) {
						System.out.printf(
							"[PASS] Phase 2 (3D World RGBA8 + Linear R32F Depth): entityPixels=%d depthRangeQ3=[%.2f .. %.2f] units%n",
							valid3dEntityPixels, minDepthQ3, maxDepthQ3
						);
						tpVerified = true;
						break;
					}
				}
				Thread.sleep(30L);
			}
			if (!tpVerified) {
				throw new IllegalStateException("Phase 2 failed: 3D entity RGBA8 + linear R32F depth not captured");
			}
		} finally {
			ProcessHandle.of(q3Pid).ifPresent(ph -> {
				ph.destroyForcibly();
				System.out.println("Cleaned up quake3-bridge.exe (PID=" + q3Pid + ")");
			});
		}
	}
}
