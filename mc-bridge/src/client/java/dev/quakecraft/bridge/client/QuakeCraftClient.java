package dev.quakecraft.bridge.client;

import dev.quakecraft.bridge.QuakeCraftMod;
import dev.quakecraft.bridge.SharedMemoryBridge;
import net.fabricmc.api.ClientModInitializer;

public final class QuakeCraftClient implements ClientModInitializer {
	public static SharedMemoryBridge bridge;

	@Override
	public void onInitializeClient() {
		try {
			bridge = SharedMemoryBridge.openOrCreate();
			QuakeCraftMod.LOG.info("QuakeCraft Client attached to {} ({} MB)",
				SharedMemoryBridge.SHM_NAME,
				SharedMemoryBridge.TOTAL_BYTES >> 20
			);
		} catch (Throwable t) {
			QuakeCraftMod.LOG.error("Failed to open QuakeCraft shared memory bridge", t);
		}
	}
}
