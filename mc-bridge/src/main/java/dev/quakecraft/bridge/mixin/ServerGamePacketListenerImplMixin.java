package dev.quakecraft.bridge.mixin;

import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Common mixin applied to ServerGamePacketListenerImpl on the integrated server.
 * When QuakeCraft passthrough is active, Quake 3 has sole authority over player
 * movement and collision.
 *
 * 1. Enforces player.noPhysics = true at the start of handleMovePlayer so the server
 *    skips movement validation checks (isEntityCollidingWithAnythingNew, moved wrongly,
 *    and rubberband teleport(DDDFF)).
 * 2. Suppresses forceSendPlayerSupportBlocks() so the server never logs false alarms
 *    ("Player standing on air") and never spams clientbound block updates.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Shadow
	public ServerPlayer player;

	@Inject(method = "handleMovePlayer", at = @At("HEAD"))
	private void quakecraft$enforceNoPhysicsOnPacket(final ServerboundMovePlayerPacket packet, final CallbackInfo ci) {
		if (SharedMemoryBridge.isPassthroughActive()) {
			if (this.player != null) {
				this.player.noPhysics = true;
			}
		}
	}

	@Inject(method = "forceSendPlayerSupportBlocks", at = @At("HEAD"), cancellable = true)
	private void quakecraft$suppressStandingOnAir(final CallbackInfo ci) {
		if (SharedMemoryBridge.isPassthroughActive()) {
			ci.cancel();
		}
	}
}
