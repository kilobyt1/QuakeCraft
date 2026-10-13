package dev.quakecraft.bridge.client;

import java.lang.invoke.VarHandle;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.mojang.blaze3d.platform.InputConstants;
import dev.quakecraft.bridge.QuakeCraftMod;
import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Stage 4: Two-Way Combat, Proxy Actor Streaming, Block Destruction & Pickups.
 *
 * 1. Scans nearby Minecraft LivingEntity mobs within 48 blocks and publishes them
 *    into shm->actors so Quake 3 links them as shootable gentity_t proxy targets.
 * 2. Drains shm->q3ToMcEvents (EV_DAMAGE_ENTITY, EV_EXPLOSION, EV_BREAK_BLOCK)
 *    and executes authoritative damage, knockback, and block breaking on IntegratedServer.
 * 3. Detects Minecraft damage/healing on the player and forwards EV_PLAYER_HURT /
 *    EV_GIVE_ITEM into shm->mcToQ3Events.
 */
public final class ActorSyncAndCombat {
	public static final int ITEM_HEALTH_SMALL = 1;
	public static final int ITEM_HEALTH_MEDIUM = 2;
	public static final int ITEM_HEALTH_LARGE = 3;
	public static final int ITEM_MEGA_HEALTH = 4;
	public static final int ITEM_ARMOR_SHARD = 5;
	public static final int ITEM_ARMOR_COMBAT = 6;
	public static final int ITEM_ARMOR_BODY = 7;
	public static final int ITEM_QUAD_DAMAGE = 8;
	public static final int ITEM_HASTE = 9;
	public static final int ITEM_REGEN = 10;
	public static final int ITEM_AMMO_PACK = 11;
	public static final int ITEM_BATTLESUIT = 12;

	private int actorSeq = 0;
	private float lastPlayerMcHealth = 20.0f;
	private int lastSyncedQ3Health = 100;
	private int hitConfirmCount = 0;
	private int killCount = 0;
	private boolean prevF8Down = false;
	private boolean prevF9Down = false;

	private final float[] tmpQ3Pos = new float[3];

	private int tickCounter = 0;

	public void onClientTick(
		final Minecraft mc,
		final SharedMemoryBridge bridge,
		final PlayerControllerSync controller
	) {
		if (bridge == null || mc.player == null || mc.level == null || !controller.isPassthroughActive()) {
			return;
		}
		final LocalPlayer player = mc.player;
		final ClientLevel level = mc.level;

		final float anchorX = controller.getAnchorMcX();
		final float anchorY = controller.getAnchorMcY();
		final float anchorZ = controller.getAnchorMcZ();

		// 1. Stream nearby LivingEntity mobs -> shm->actors (throttled to 10 Hz for CPU efficiency)
		if ((tickCounter++ & 1) == 0) {
			publishNearbyActors(bridge, level, player, anchorX, anchorY, anchorZ);
		}

		// 2. Quick powerup / pickup hotkeys in gameplay:
		//    F8 = Give Quad Damage + Battle Suit + MegaHealth + Red Armor + Full Ammo
		if (mc.mouseHandler.isMouseGrabbed()) {
			final boolean f8Down = InputConstants.isKeyDown(InputConstants.KEY_F8);
			if (f8Down && !prevF8Down) {
				pushEventToQ3(bridge, SharedMemoryBridge.EV_GIVE_ITEM, ITEM_QUAD_DAMAGE, 30, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);
				pushEventToQ3(bridge, SharedMemoryBridge.EV_GIVE_ITEM, ITEM_BATTLESUIT, 30, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);
				pushEventToQ3(bridge, SharedMemoryBridge.EV_GIVE_ITEM, ITEM_MEGA_HEALTH, 100, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);
				pushEventToQ3(bridge, SharedMemoryBridge.EV_GIVE_ITEM, ITEM_ARMOR_BODY, 100, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);
				pushEventToQ3(bridge, SharedMemoryBridge.EV_GIVE_ITEM, ITEM_AMMO_PACK, 200, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f);
				player.sendOverlayMessage(Component.literal("[QuakeCraft] QUAD DAMAGE + BATTLE SUIT + MEGAHEALTH + RED ARMOR (F8)!"));
			}
			prevF8Down = f8Down;
		}

		// 3. Forward Minecraft player damage / healing -> Quake 3
		syncPlayerHealthWithQuake(mc, bridge, player);

		// 4. Drain Quake 3 -> Minecraft combat & block destruction events
		drainQ3Events(mc, bridge, player, anchorX, anchorY, anchorZ);
	}

	private void publishNearbyActors(
		final SharedMemoryBridge bridge,
		final ClientLevel level,
		final LocalPlayer player,
		final float anchorX,
		final float anchorY,
		final float anchorZ
	) {
		final AABB searchBox = player.getBoundingBox().inflate(32.0, 24.0, 32.0);
		final List<LivingEntity> entities = level.getEntitiesOfClass(
			LivingEntity.class,
			searchBox,
			ent -> ent != player && ent.isAlive() && !ent.isSpectator()
		);

		if (entities.size() > SharedMemoryBridge.MAX_ACTORS) {
			entities.sort(Comparator.comparingDouble(ent -> ent.distanceToSqr(player)));
		}
		final int count = Math.min(SharedMemoryBridge.MAX_ACTORS, entities.size());

		final var seg = bridge.segment;
		actorSeq += 2;
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_SEQ, actorSeq - 1);
		VarHandle.fullFence();

		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_COUNT, count);
		seg.set(SharedMemoryBridge.LONG, SharedMemoryBridge.ACTORS_TIMESTAMP_NS, System.nanoTime());

		for (int i = 0; i < count; i++) {
			final LivingEntity ent = entities.get(i);
			final long base = SharedMemoryBridge.OFF_ACTOR_ARRAY + (long) i * SharedMemoryBridge.STRIDE_ACTOR;

			final double centerMcX = ent.getX();
			final double centerMcY = ent.getY() + ent.getBbHeight() * 0.5;
			final double centerMcZ = ent.getZ();

			SharedMemoryBridge.mcPointToQ3(
				(float) centerMcX,
				(float) centerMcY,
				(float) centerMcZ,
				anchorX,
				anchorY,
				anchorZ,
				tmpQ3Pos
			);

			final var vel = ent.getDeltaMovement();
			final float q3Vx = (float) (vel.x * 20.0 * SharedMemoryBridge.SCALE_Q3_PER_MC);
			final float q3Vy = (float) (-vel.z * 20.0 * SharedMemoryBridge.SCALE_Q3_PER_MC);
			final float q3Vz = (float) (vel.y * 20.0 * SharedMemoryBridge.SCALE_Q3_PER_MC);

			final float halfWidthQ3 = Math.max(8.0f, ent.getBbWidth() * 0.5f * SharedMemoryBridge.SCALE_Q3_PER_MC);
			final float heightQ3 = Math.max(16.0f, ent.getBbHeight() * SharedMemoryBridge.SCALE_Q3_PER_MC);
			final float healthQ3 = Math.max(1.0f, ent.getHealth() * 5.0f);
			final float maxHealthQ3 = Math.max(5.0f, ent.getMaxHealth() * 5.0f);

			int flags = 1; // active
			if (ent instanceof Enemy) {
				flags |= 2; // hostile
			}
			if (ent.isInWater()) {
				flags |= 4;
			}

			seg.set(SharedMemoryBridge.INT, base + 0L, ent.getId());
			seg.set(SharedMemoryBridge.INT, base + 4L, ent.tickCount);
			seg.set(SharedMemoryBridge.INT, base + 8L, (ent instanceof Enemy) ? 1 : 0);
			seg.set(SharedMemoryBridge.INT, base + 12L, flags);
			seg.set(SharedMemoryBridge.FLOAT, base + 16L, tmpQ3Pos[0]);
			seg.set(SharedMemoryBridge.FLOAT, base + 20L, tmpQ3Pos[1]);
			seg.set(SharedMemoryBridge.FLOAT, base + 24L, tmpQ3Pos[2]);
			seg.set(SharedMemoryBridge.FLOAT, base + 28L, q3Vx);
			seg.set(SharedMemoryBridge.FLOAT, base + 32L, q3Vy);
			seg.set(SharedMemoryBridge.FLOAT, base + 36L, q3Vz);
			seg.set(SharedMemoryBridge.FLOAT, base + 40L, halfWidthQ3);
			seg.set(SharedMemoryBridge.FLOAT, base + 44L, heightQ3);
			seg.set(SharedMemoryBridge.FLOAT, base + 48L, healthQ3);
			seg.set(SharedMemoryBridge.FLOAT, base + 52L, maxHealthQ3);
			seg.set(SharedMemoryBridge.FLOAT, base + 56L, SharedMemoryBridge.mcYawToQ3Yaw(ent.getYRot()));
			seg.set(SharedMemoryBridge.FLOAT, base + 60L, 0.0f);
		}

		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.ACTORS_SEQ, actorSeq);
	}

	private void syncPlayerHealthWithQuake(
		final Minecraft mc,
		final SharedMemoryBridge bridge,
		final LocalPlayer player
	) {
		final var seg = bridge.segment;
		final int q3Health = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.POSE_HEALTH);
		final float currentMcHealth = player.getHealth();

		// If Minecraft hurt the player since last tick (e.g. skeleton arrow, creeper, zombie melee)
		if (currentMcHealth < lastPlayerMcHealth - 0.25f && q3Health > 0) {
			final float lostMcHp = lastPlayerMcHealth - currentMcHealth;
			final int damageQ3 = Math.max(1, Math.round(lostMcHp * 5.0f));
			pushEventToQ3(
				bridge,
				SharedMemoryBridge.EV_PLAYER_HURT,
				0,
				damageQ3,
				0,
				0,
				0.0f,
				0.0f,
				0.0f,
				1.0f
			);
		} else if (currentMcHealth > lastPlayerMcHealth + 0.5f && q3Health > 0 && q3Health < 100) {
			// Player ate food / regenerated in Minecraft -> grant health in Quake 3
			final int gainQ3 = Math.max(5, Math.round((currentMcHealth - lastPlayerMcHealth) * 5.0f));
			pushEventToQ3(
				bridge,
				SharedMemoryBridge.EV_GIVE_ITEM,
				ITEM_HEALTH_MEDIUM,
				gainQ3,
				0,
				0,
				0.0f,
				0.0f,
				0.0f,
				0.0f
			);
		}

		// Sync Quake 3 health back to Minecraft player health bar (100 Q3 HP = 20 MC HP)
		if (q3Health != lastSyncedQ3Health && q3Health > 0) {
			final float targetMcHp = Math.max(1.0f, Math.min(20.0f, q3Health / 5.0f));
			player.setHealth(targetMcHp);
			lastPlayerMcHealth = targetMcHp;
			lastSyncedQ3Health = q3Health;

			final IntegratedServer server = mc.getSingleplayerServer();
			if (server != null) {
				final UUID uuid = player.getUUID();
				server.executeIfPossible(() -> {
					final ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
					if (sp != null && sp.isAlive()) {
						sp.setHealth(targetMcHp);
					}
				});
			}
		} else {
			lastPlayerMcHealth = player.getHealth();
		}
	}

	private void drainQ3Events(
		final Minecraft mc,
		final SharedMemoryBridge bridge,
		final LocalPlayer player,
		final float anchorX,
		final float anchorY,
		final float anchorZ
	) {
		final var seg = bridge.segment;
		final long ringBase = SharedMemoryBridge.OFF_Q3_TO_MC;
		int writeIdx = seg.get(SharedMemoryBridge.INT, ringBase + 0L);
		int readIdx = seg.get(SharedMemoryBridge.INT, ringBase + 4L);

		if (readIdx == writeIdx) {
			return;
		}
		if (Integer.compareUnsigned(writeIdx - readIdx, SharedMemoryBridge.EVENT_RING_CAP) > 0) {
			readIdx = writeIdx - SharedMemoryBridge.EVENT_RING_CAP;
		}

		final IntegratedServer server = mc.getSingleplayerServer();
		final UUID playerUuid = player.getUUID();

		while (readIdx != writeIdx) {
			VarHandle.acquireFence();
			final int slot = readIdx & (SharedMemoryBridge.EVENT_RING_CAP - 1);
			final long evOff = ringBase + 16L + (long) slot * SharedMemoryBridge.STRIDE_EVENT;

			final int type = seg.get(SharedMemoryBridge.INT, evOff + 0L);
			final int targetId = seg.get(SharedMemoryBridge.INT, evOff + 4L);
			final int intParam1 = seg.get(SharedMemoryBridge.INT, evOff + 8L);
			final int intParam2 = seg.get(SharedMemoryBridge.INT, evOff + 12L);
			final int intParam3 = seg.get(SharedMemoryBridge.INT, evOff + 16L);
			final float floatParam1 = seg.get(SharedMemoryBridge.FLOAT, evOff + 20L);
			final float vecAx = seg.get(SharedMemoryBridge.FLOAT, evOff + 24L);
			final float vecAy = seg.get(SharedMemoryBridge.FLOAT, evOff + 28L);
			final float vecAz = seg.get(SharedMemoryBridge.FLOAT, evOff + 32L);

			readIdx++;
			VarHandle.releaseFence();
			seg.set(SharedMemoryBridge.INT, ringBase + 4L, readIdx);

			if (server == null) {
				continue;
			}

			if (type == SharedMemoryBridge.EV_DAMAGE_ENTITY) {
				final float damageMc = Math.max(0.5f, intParam1 / 5.0f);
				final boolean isGib = (intParam3 != 0);
				// Convert Q3 knockback velocity (units/sec) to MC velocity (blocks/tick)
				final double kbScale = SharedMemoryBridge.SCALE_MC_PER_Q3 / 20.0;
				final double kbX = vecAx * kbScale;
				final double kbY = Math.max(0.15, vecAz * kbScale);
				final double kbZ = -vecAy * kbScale;

				hitConfirmCount++;
				seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_HIT_CONFIRM_COUNT, hitConfirmCount);

				server.executeIfPossible(() -> {
					final ServerPlayer sp = server.getPlayerList().getPlayer(playerUuid);
					if (sp == null) {
						return;
					}
					final ServerLevel serverLevel = (ServerLevel) sp.level();
					final Entity target = serverLevel.getEntity(targetId);
					if (target instanceof LivingEntity living && living.isAlive()) {
						living.setInvulnerableTime(0); // Quake weapons have no 0.5s i-frame delay!
						living.hurtServer(serverLevel, serverLevel.damageSources().playerAttack(sp), damageMc);
						living.push(kbX, kbY, kbZ);

						if (!living.isAlive() || isGib) {
							if (isGib && living.isAlive()) {
								living.hurtServer(serverLevel, serverLevel.damageSources().playerAttack(sp), 1000.0f);
							}
							killCount++;
							seg.set(SharedMemoryBridge.INT, SharedMemoryBridge.INPUT_KILL_COUNT, killCount);
						}
					}
				});
			} else if (type == SharedMemoryBridge.EV_EXPLOSION) {
				final int centerBx = intParam1;
				final int centerBy = intParam2;
				final int centerBz = intParam3;
				final int radiusBlocks = Math.min(3, Math.max(1, Math.round(floatParam1 * SharedMemoryBridge.SCALE_MC_PER_Q3)));

				server.executeIfPossible(() -> {
					final ServerPlayer sp = server.getPlayerList().getPlayer(playerUuid);
					if (sp == null) {
						return;
					}
					final ServerLevel serverLevel = (ServerLevel) sp.level();
					final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
					final int playerFootY = (int) Math.floor(sp.getY());
					final double playerX = sp.getX();
					final double playerZ = sp.getZ();

					for (int dy = -radiusBlocks; dy <= radiusBlocks; dy++) {
						for (int dz = -radiusBlocks; dz <= radiusBlocks; dz++) {
							for (int dx = -radiusBlocks; dx <= radiusBlocks; dx++) {
								if (dx * dx + dy * dy + dz * dz > radiusBlocks * radiusBlocks + 1) {
									continue;
								}
								final int wx = centerBx + dx;
								final int wy = centerBy + dy;
								final int wz = centerBz + dz;
								// Never break ground blocks under the player's feet (e.g. during rocket jumps or close combat)
								final double hDistSq = (wx + 0.5 - playerX) * (wx + 0.5 - playerX)
									+ (wz + 0.5 - playerZ) * (wz + 0.5 - playerZ);
								if (wy <= playerFootY && hDistSq < 6.25) {
									continue;
								}
								mpos.set(wx, wy, wz);
								final BlockState state = serverLevel.getBlockState(mpos);
								if (state.isAir()) {
									continue;
								}
								if (state.is(Blocks.TNT)) {
									TntBlock.prime(serverLevel, mpos);
									serverLevel.removeBlock(mpos, false);
									continue;
								}
								final float destroySpeed = state.getDestroySpeed(serverLevel, mpos);
								// Rockets/BFG break glass, leaves, wood, dirt, sand, stone, cobblestone (hardness <= 2.5)
								if (destroySpeed >= 0.0f && destroySpeed <= 2.5f) {
									serverLevel.destroyBlock(mpos, true, sp);
								}
							}
						}
					}
				});
			} else if (type == SharedMemoryBridge.EV_BREAK_BLOCK) {
				final int bx = intParam1;
				final int by = intParam2;
				final int bz = intParam3;

				server.executeIfPossible(() -> {
					final ServerPlayer sp = server.getPlayerList().getPlayer(playerUuid);
					if (sp == null) {
						return;
					}
					final ServerLevel serverLevel = (ServerLevel) sp.level();
					final BlockPos pos = new BlockPos(bx, by, bz);
					final BlockState state = serverLevel.getBlockState(pos);
					if (state.isAir()) {
						return;
					}
					if (state.is(Blocks.TNT)) {
						TntBlock.prime(serverLevel, pos);
						serverLevel.removeBlock(pos, false);
						return;
					}
					// Hitscan weapons only shatter fragile glass/panes/ice, never dirt/grass/stone under the player's feet!
					if (state.getBlock() instanceof net.minecraft.world.level.block.HalfTransparentBlock
						|| state.is(net.minecraft.tags.BlockTags.IMPERMEABLE)) {
						serverLevel.destroyBlock(pos, true, sp);
					}
				});
			}
		}
	}

	public static void pushEventToQ3(
		final SharedMemoryBridge bridge,
		final int type,
		final int targetId,
		final int intParam1,
		final int intParam2,
		final int intParam3,
		final float floatParam1,
		final float vecAx,
		final float vecAy,
		final float vecAz
	) {
		final var seg = bridge.segment;
		final long ringBase = SharedMemoryBridge.OFF_MC_TO_Q3;
		final int writeIdx = seg.get(SharedMemoryBridge.INT, ringBase + 0L);
		final int readIdx = seg.get(SharedMemoryBridge.INT, ringBase + 4L);

		if (Integer.compareUnsigned(writeIdx - readIdx, SharedMemoryBridge.EVENT_RING_CAP) >= 0) {
			seg.set(SharedMemoryBridge.INT, ringBase + 4L, writeIdx - SharedMemoryBridge.EVENT_RING_CAP + 1);
		}

		final int slot = writeIdx & (SharedMemoryBridge.EVENT_RING_CAP - 1);
		final long evOff = ringBase + 16L + (long) slot * SharedMemoryBridge.STRIDE_EVENT;

		seg.set(SharedMemoryBridge.INT, evOff + 0L, type);
		seg.set(SharedMemoryBridge.INT, evOff + 4L, targetId);
		seg.set(SharedMemoryBridge.INT, evOff + 8L, intParam1);
		seg.set(SharedMemoryBridge.INT, evOff + 12L, intParam2);
		seg.set(SharedMemoryBridge.INT, evOff + 16L, intParam3);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 20L, floatParam1);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 24L, vecAx);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 28L, vecAy);
		seg.set(SharedMemoryBridge.FLOAT, evOff + 32L, vecAz);
		seg.set(SharedMemoryBridge.LONG, evOff + 48L, System.nanoTime());

		VarHandle.fullFence();
		seg.set(SharedMemoryBridge.INT, ringBase + 0L, writeIdx + 1);
		VarHandle.fullFence();
	}
}
