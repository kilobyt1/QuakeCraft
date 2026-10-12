package dev.quakecraft.bridge;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuakeCraftMod implements ModInitializer {
	public static final String MOD_ID = "quakecraft";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOG.info("QuakeCraft Bridge initialized (shared memory mapping: {})", SharedMemoryBridge.SHM_NAME);
	}
}
