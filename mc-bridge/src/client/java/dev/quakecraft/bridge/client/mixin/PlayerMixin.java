package dev.quakecraft.bridge.client.mixin;

import dev.quakecraft.bridge.client.QuakeCraftClient;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures that whenever QuakeCraft passthrough is active, noPhysics is maintained
 * on the player entity across every tick (overriding vanilla Player.tick() which
 * resets noPhysics = isSpectator()), preventing ServerGamePacketListenerImpl from
 * rubberbanding high-speed bunny-hopping or jumping into block boundaries.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void quakecraft$enforceNoPhysicsWhenPassthrough(final CallbackInfo ci) {
		if (QuakeCraftClient.CONTROLLER.isPassthroughActive()) {
			((Player) (Object) this).noPhysics = true;
		}
	}
}
