package dev.quakecraft.bridge.client.mixin;

import dev.quakecraft.bridge.client.QuakeCraftClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents vanilla Minecraft left-click block breaking / melee attack while in
 * Quake III Combat mode (F6 toggles Minecraft Interact mode when block mining
 * is desired).
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void quakecraft$suppressVanillaStartAttack(final CallbackInfoReturnable<Boolean> cir) {
		if (QuakeCraftClient.CONTROLLER.isPassthroughActive()
			&& !QuakeCraftClient.CONTROLLER.isMcInteractMode()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void quakecraft$suppressVanillaContinueAttack(final boolean leftClick, final CallbackInfo ci) {
		if (QuakeCraftClient.CONTROLLER.isPassthroughActive()
			&& !QuakeCraftClient.CONTROLLER.isMcInteractMode()) {
			final Minecraft mc = (Minecraft) (Object) this;
			if (mc.gameMode != null) {
				mc.gameMode.stopDestroyBlock();
			}
			ci.cancel();
		}
	}
}
