package dev.quakecraft.bridge.mixin;

import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Common mixin applied to Player (both Client LocalPlayer and IntegratedServer ServerPlayer).
 * Overrides Player.tick() which normally resets noPhysics = isSpectator() in Survival mode,
 * maintaining noPhysics = true whenever QuakeCraft passthrough is active.
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "tick", at = @At("TAIL"))
	private void quakecraft$maintainNoPhysicsWhenPassthrough(final CallbackInfo ci) {
		if (SharedMemoryBridge.isPassthroughActive()) {
			((Player) (Object) this).noPhysics = true;
		}
	}
}
