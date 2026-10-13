package dev.quakecraft.bridge.client;

import java.lang.invoke.VarHandle;
import com.mojang.blaze3d.platform.InputConstants;
import dev.quakecraft.bridge.QuakeCraftMod;
import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Synchronizes Minecraft input/window/camera state -> Quake 3 (shm->input) and
 * reads authoritative Quake 3 PMove pose (shm->pose) back into Minecraft's
 * LocalPlayer, Camera, and IntegratedServer ServerPlayer.
 */
public final class PlayerControllerSync {
	private final VoxelStreamer voxelStreamer = new VoxelStreamer();

	private boolean anchorInitialized = false;
	private float anchorMcX = 0.0f;
	private float anchorMcY = 64.0f;
	private float anchorMcZ = 0.0f;

	private int inputSeq = 0;
	private int teleportSeq = 0;

	private boolean passthroughEnabled = true;
	private boolean mcInteractMode = false;
	private boolean prevF6Down = false;
	private boolean prevF7Down = false;

	private boolean hasValidPose = false;
	private double latestMcFeetX = 0.0;
	private double latestMcFeetY = 64.0;
	private double latestMcFeetZ = 0.0;
	private double latestMcEyeY = 65.62;
	private float latestMcYaw = 0.0f;
	private float latestMcPitch = 0.0f;
	private int latestHealth = 100;
	private int latestArmor = 0;
	private int latestWeapon = 2;
	private int latestAmmo = 100;

	/**
	 * The POSE_SEQ value from the last tick-level pose read. Used in
	 * onRenderFrameUpdate to detect whether the pose has actually changed since
	 * the last tick; if it hasn't, we reuse latestMcFeet* directly and skip
	 * the setPos call to eliminate render-frame positional jitter.
	 */
	private int lastAppliedPoseSeq = 0;

	private final float[] tmpQ3 = new float[3];
	private final float[] tmpMc = new float[3];

	public boolean isPassthroughActive() {
		return passthroughEnabled && hasValidPose;
	}

	public boolean isPassthroughEnabled() {
		return passthroughEnabled;
	}

	public boolean isMcInteractMode() {
		return mcInteractMode;
	}

	public float getAnchorMcX() {
		return anchorMcX;
	}

	public float getAnchorMcY() {
		return anchorMcY;
	}

	public float getAnchorMcZ() {
		return anchorMcZ;
	}

	public double getLatestMcFeetX() {
		return latestMcFeetX;
	}

	public double getLatestMcFeetY() {
		return latestMcFeetY;
	}

	public double getLatestMcFeetZ() {
		return latestMcFeetZ;
	}

	public double getLatestMcEyeY() {
		return latestMcEyeY;
	}

	public float getLatestMcYaw() {
		return latestMcYaw;
	}

	public float getLatestMcPitch() {
		return latestMcPitch;
	}

	public void onClientTick(final Minecraft mc, final SharedMemoryBridge bridge) {
		if (bridge == null) {
			return;
		}
		final LocalPlayer player = mc.player;
		final var level = mc.level;
		if (player == null || level == null) {
			anchorInitialized = false;
			hasValidPose = false;
			return;
		}

		// Handle F6 (MC Interact Toggle) and F7 (Passthrough Toggle) when gameplay mouse is grabbed
		if (mc.mouseHandler.isMouseGrabbed()) {
			final boolean f6Down = InputConstants.isKeyDown(InputConstants.KEY_F6);
			if (f6Down && !prevF6Down) {
				mcInteractMode = !mcInteractMode;
				player.sendOverlayMessage(Component.literal(
					mcInteractMode
						? "[QuakeCraft] Mode: Minecraft Interact (F6)"
						: "[QuakeCraft] Mode: Quake III Combat (F6)"
				));
			}
			prevF6Down = f6Down;

			final boolean f7Down = InputConstants.isKeyDown(InputConstants.KEY_F7);
			if (f7Down && !prevF7Down) {
				passthroughEnabled = !passthroughEnabled;
				player.sendOverlayMessage(Component.literal(
					passthroughEnabled
						? "[QuakeCraft] Passthrough: ON (F7)"
						: "[QuakeCraft] Passthrough: OFF (F7)"
				));
				if (passthroughEnabled) {
					issueTeleportToCurrentMcPos(bridge, player);
				} else {
					final IntegratedServer server = mc.getSingleplayerServer();
					if (server != null) {
						final java.util.UUID uuid = player.getUUID();
						server.executeIfPossible(() -> {
							final ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
							if (sp != null) {
								sp.noPhysics = false;
								if (sp.connection != null) {
									sp.connection.resetPosition();
								}
							}
						});
					}
				}
			}
			prevF7Down = f7Down;
		}

		if (player.isDeadOrDying()) {
			anchorInitialized = false;
			hasValidPose = false;
			return;
		}

		// Wait until the client chunk at the player's position is actually loaded before scanning voxels or anchoring
		if (!level.hasChunk(player.getBlockX() >> 4, player.getBlockZ() >> 4)) {
			hasValidPose = false;
			return;
		}

		boolean forceVoxelScan = false;
		if (!anchorInitialized) {
			anchorMcX = (float) Math.floor(player.getX());
			anchorMcY = (float) Math.floor(player.getY());
			anchorMcZ = (float) Math.floor(player.getZ());
			anchorInitialized = true;
			forceVoxelScan = true;
			issueTeleportToCurrentMcPos(bridge, player);
			QuakeCraftMod.LOG.info(
				"Initialized QuakeCraft coordinate anchor at MC ({}, {}, {})",
				anchorMcX, anchorMcY, anchorMcZ
			);
		}

		// Stream 64x64x64 voxels around player BEFORE Quake 3 steps physics
		voxelStreamer.update(
			bridge,
			level,
			player.getX(),
			player.getY(),
			player.getZ(),
			anchorMcX,
			anchorMcY,
			anchorMcZ,
			forceVoxelScan
		);

		// Publish current frame/tick input to Quake 3
		publishInputFrame(mc, bridge, player);

		// Read latest authoritative Quake 3 pose and sync ServerPlayer on tick
		if (passthroughEnabled && readAndApplyQ3Pose(mc, bridge, player, true)) {
			// Check if Minecraft teleported the player externally (e.g. /tp command or dimension change)
			final double dx = player.getX() - latestMcFeetX;
			final double dy = player.getY() - latestMcFeetY;
			final double dz = player.getZ() - latestMcFeetZ;
			if (dx * dx + dy * dy + dz * dz > 64.0) {
				issueTeleportToCurrentMcPos(bridge, player);
			}
		}
	}

	public void onRenderFrameUpdate(final Minecraft mc, final SharedMemoryBridge bridge) {
		if (bridge == null || mc.player == null || mc.level == null || !anchorInitialized || teleportSeq == 0) {
			return;
		}
		// Always update the mouse/input frame so Quake 3 sees fresh yaw/pitch every render frame.
		publishInputFrame(mc, bridge, mc.player);

		if (!passthroughEnabled || !hasValidPose) {
			return;
		}

		// Only re-apply the Q3 position when a NEW pose has been published since the
		// last tick. This prevents the render thread from snapping the player back to a
		// stale Q3 position every frame (60-165 Hz), which was the root cause of the
		// mid-air surfing, flying, and pit-bounce artifacts.
		final var seg = bridge.segment;
		final int currentSeq = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_SEQ);
		if (currentSeq == lastAppliedPoseSeq || (currentSeq & 1) != 0) {
			// Pose unchanged or being written — hold current latestMcFeet* position.
			// Just keep the player's rendered position pinned without calling setPos.
			final LocalPlayer player = mc.player;
			player.xo = latestMcFeetX;
			player.yo = latestMcFeetY;
			player.zo = latestMcFeetZ;
			player.setPos(latestMcFeetX, latestMcFeetY, latestMcFeetZ);
			return;
		}

		// New pose is ready — do the full read+apply (without syncing server; that's
		// done once per tick in onClientTick).
		readAndApplyQ3Pose(mc, bridge, mc.player, false);
	}

	private void issueTeleportToCurrentMcPos(final SharedMemoryBridge bridge, final LocalPlayer player) {
		SharedMemoryBridge.mcFeetToQ3Origin(
			(float) player.getX(),
			(float) player.getY(),
			(float) player.getZ(),
			anchorMcX,
			anchorMcY,
			anchorMcZ,
			tmpQ3
		);
		final float q3Yaw = SharedMemoryBridge.mcYawToQ3Yaw(player.getYRot());
		final var seg = bridge.segment;
		teleportSeq++;
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_ANCHOR_MC_X, anchorMcX);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_ANCHOR_MC_Y, anchorMcY);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_ANCHOR_MC_Z, anchorMcZ);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_X, tmpQ3[0]);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Y, tmpQ3[1]);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_Z, tmpQ3[2]);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_TELEPORT_Q3_YAW, q3Yaw);
		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_TELEPORT_SEQ, teleportSeq);
	}

	private void publishInputFrame(final Minecraft mc, final SharedMemoryBridge bridge, final LocalPlayer player) {
		final Options opts = mc.options;
		final boolean guiOpen = !mc.mouseHandler.isMouseGrabbed();

		int forward = 0;
		int right = 0;
		int up = 0;
		int buttons = 0;

		if (!guiOpen && passthroughEnabled) {
			if (opts.keyUp.isDown()) {
				forward += 127;
			}
			if (opts.keyDown.isDown()) {
				forward -= 127;
			}
			if (opts.keyRight.isDown()) {
				right += 127;
			}
			if (opts.keyLeft.isDown()) {
				right -= 127;
			}
			if (opts.keyJump.isDown()) {
				up += 127;
				buttons |= SharedMemoryBridge.BTN_JUMP;
			}
			if (opts.keyShift.isDown()) {
				up -= 127;
				buttons |= SharedMemoryBridge.BTN_CROUCH;
			}
			if (!mcInteractMode && opts.keyAttack.isDown()) {
				buttons |= SharedMemoryBridge.BTN_ATTACK;
			}
			if (opts.keyUse.isDown() && !mcInteractMode) {
				buttons |= SharedMemoryBridge.BTN_USE_HOLDABLE;
			}
		}

		// Map hotbar slot [0..8] -> Quake 3 weapon [1..9]
		final int hotbarSlot = player.getInventory().getSelectedSlot();
		final int weaponSelect = Math.max(1, Math.min(9, hotbarSlot + 1));

		int flags = SharedMemoryBridge.FLAG_ACTIVE;
		if (passthroughEnabled && mc.isWindowActive() && !mc.isPaused()) {
			flags |= SharedMemoryBridge.FLAG_PASSTHROUGH_ON;
		}
		if (mcInteractMode) {
			flags |= SharedMemoryBridge.FLAG_MC_INTERACT;
		}
		if (opts.getCameraType() != CameraType.FIRST_PERSON) {
			flags |= SharedMemoryBridge.FLAG_THIRD_PERSON;
		}

		final int winW = Math.max(64, Math.min(SharedMemoryBridge.MAX_WIDTH, mc.getWindow().getWidth()));
		final int winH = Math.max(64, Math.min(SharedMemoryBridge.MAX_HEIGHT, mc.getWindow().getHeight()));
		final float fovY = (float) opts.fov().get().intValue();

		final var seg = bridge.segment;
		inputSeq += 2;
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, inputSeq - 1);
		VarHandle.fullFence();

		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FLAGS, flags);
		seg.set(SharedMemoryBridge.LONG, SharedMemoryBridge.INPUT_TIMESTAMP_NS, System.nanoTime());
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_FORWARDMOVE, forward);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_RIGHTMOVE, right);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_UPMOVE, up);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_BUTTONS, buttons);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_WEAPON_SELECT, weaponSelect);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_YAW, player.getYRot());
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_MC_PITCH, player.getXRot());
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_FOV_Y, fovY);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_VIEWPORT_W, winW);
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_VIEWPORT_H, winH);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_ANCHOR_MC_X, anchorMcX);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_ANCHOR_MC_Y, anchorMcY);
		seg.set(SharedMemoryBridge.FLOAT, SharedMemoryBridge.INPUT_ANCHOR_MC_Z, anchorMcZ);
		seg.set(SharedMemoryBridge.LONG, SharedMemoryBridge.HDR_MC_HEARTBEAT_NS, System.nanoTime());

		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_SEQ, inputSeq);
	}

	private boolean readAndApplyQ3Pose(
		final Minecraft mc,
		final SharedMemoryBridge bridge,
		final LocalPlayer player,
		final boolean syncServer
	) {
		if (!anchorInitialized || teleportSeq == 0) {
			hasValidPose = false;
			return false;
		}
		final var seg = bridge.segment;
		final long q3Heartbeat = seg.get(SharedMemoryBridge.LONG, SharedMemoryBridge.HDR_Q3_HEARTBEAT_NS);
		if (q3Heartbeat == 0L) {
			hasValidPose = false;
			return false;
		}

		float qx = 0.0f;
		float qy = 0.0f;
		float qz = 0.0f;
		float vx = 0.0f;
		float vy = 0.0f;
		float vz = 0.0f;
		float pitch = 0.0f;
		float yaw = 0.0f;
		float viewheight = 26.0f;
		int health = 100;
		int armor = 0;
		int weapon = 2;
		int ammo = 100;
		int poseFlags = 0;
		int ackTeleport = 0;

		boolean readOk = false;
		int readSeq = 0;
		for (int attempt = 0; attempt < 4; attempt++) {
			final int seq1 = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_SEQ);
			if (seq1 == 0 || (seq1 & 1) != 0) {
				continue;
			}
			VarHandle.acquireFence();
			poseFlags = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_FLAGS);
			qx = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_X);
			qy = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Y);
			qz = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_ORIGIN_Z);
			vx = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_VEL_X);
			vy = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_VEL_Y);
			vz = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_VEL_Z);
			pitch = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_PITCH);
			yaw = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_YAW);
			viewheight = seg.get(SharedMemoryBridge.FLOAT, SharedMemoryBridge.POSE_VIEWHEIGHT);
			health = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_HEALTH);
			armor = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_ARMOR);
			weapon = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_WEAPON);
			ammo = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_CURRENT_AMMO);
			ackTeleport = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_TELEPORT_ACK_SEQ);
			VarHandle.acquireFence();
			final int seq2 = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_SEQ);
			if (seq1 == seq2) {
				readSeq = seq1;
				readOk = true;
				break;
			}
		}

		if (!readOk || ackTeleport < teleportSeq) {
			return false;
		}

		// Record which seq we successfully consumed — onRenderFrameUpdate uses this to
		// decide whether a new pose is available without re-reading shared memory.
		lastAppliedPoseSeq = readSeq;

		SharedMemoryBridge.q3OriginToMcFeet(qx, qy, qz, anchorMcX, anchorMcY, anchorMcZ, tmpMc);
		final boolean onGround  = (poseFlags & (1 << 3)) != 0;
		final boolean inWater   = (poseFlags & (1 << 4)) != 0;
		double feetY = tmpMc[1];
		// Only apply half-block snapping when on solid ground (not in water/lava/air).
		// Applying the snap while swimming caused the camera to bounce between Y values
		// because PMove's water physics doesn't produce integer-aligned origins.
		if (onGround && !inWater) {
			// Quake 3 PMove hovers by SURFACE_CLIP_EPSILON (0.125 Q3 units = 0.00390625 MC blocks);
			// snap to the exact block/slab half-step so Minecraft server sees the player flush on the ground.
			final double snappedHalf = Math.round(feetY * 2.0) * 0.5;
			if (Math.abs(feetY - snappedHalf) < 0.015) {
				feetY = snappedHalf;
			}
		}

		latestMcFeetX = tmpMc[0];
		latestMcFeetY = feetY;
		latestMcFeetZ = tmpMc[2];
		latestMcEyeY = latestMcFeetY + (viewheight - SharedMemoryBridge.Q3_PLAYER_MINS_Z) * SharedMemoryBridge.SCALE_MC_PER_Q3;
		latestMcYaw = player.getYRot();
		latestMcPitch = player.getXRot();
		latestHealth = health;
		latestArmor = armor;
		latestWeapon = weapon;
		latestAmmo = ammo;
		hasValidPose = true;

		// Synchronize LocalPlayer position & velocity (blocks/tick = Q3 units/sec / (32 * 20))
		player.xo = latestMcFeetX;
		player.yo = latestMcFeetY;
		player.zo = latestMcFeetZ;
		player.setPos(latestMcFeetX, latestMcFeetY, latestMcFeetZ);
		player.setOnGround(onGround && !inWater);
		final double scaleVel = SharedMemoryBridge.SCALE_MC_PER_Q3 / 20.0;
		player.setDeltaMovement(vx * scaleVel, vz * scaleVel, -vy * scaleVel);
		player.resetFallDistance();

		// Synchronize IntegratedServer ServerPlayer once per tick with noPhysics + resetPosition()
		// so ServerGamePacketListenerImpl never rubberbands position or camera angles in Survival mode!
		if (syncServer) {
			final IntegratedServer server = mc.getSingleplayerServer();
			if (server != null) {
				final double targetX = latestMcFeetX;
				final double targetY = latestMcFeetY;
				final double targetZ = latestMcFeetZ;
				final float targetYaw = latestMcYaw;
				final float targetPitch = latestMcPitch;
				final boolean targetOnGround = onGround && !inWater;
				final java.util.UUID uuid = player.getUUID();
				server.executeIfPossible(() -> {
					final ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
					if (sp != null) {
						sp.noPhysics = true;
						sp.absSnapTo(targetX, targetY, targetZ, targetYaw, targetPitch);
						sp.setOnGround(targetOnGround);
						sp.setDeltaMovement(0.0, 0.0, 0.0);
						sp.resetFallDistance();
						if (sp.connection != null) {
							sp.connection.resetPosition();
						}
					}
				});
			}
		}

		return true;
	}
}
