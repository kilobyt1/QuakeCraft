package dev.quakecraft.bridge.client.mixin;

import dev.quakecraft.bridge.client.QuakeCraftClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When QuakeCraft passthrough is active, Quake 3's bg_pmove.c is the sole
 * authority for player movement and collision, so vanilla LocalPlayer.move(SELF)
 * is bypassed.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void quakecraft$bypassVanillaMove(final MoverType type, final Vec3 delta, final CallbackInfo ci) {
		if (type == MoverType.SELF && QuakeCraftClient.CONTROLLER.isPassthroughActive()) {
			ci.cancel();
		}
	}

	@Inject(method = "sendPosition", at = @At("HEAD"), cancellable = true)
	private void quakecraft$bypassVanillaSendPosition(final CallbackInfo ci) {
		if (QuakeCraftClient.CONTROLLER.isPassthroughActive()) {
			ci.cancel();
		}
	}
}
