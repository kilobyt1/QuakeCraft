package dev.quakecraft.bridge.client.mixin;

import dev.quakecraft.bridge.client.QuakeCraftClient;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Suppresses rendering of the vanilla Minecraft player model when QuakeCraft
 * passthrough is active, so only the Quake 3 Sarge model (with MD3 animations)
 * is visible in 3rd-person mode.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private <E extends Entity> void quakecraft$suppressVanillaPlayerRender(
		final E entity,
		final Frustum frustum,
		final double x,
		final double y,
		final double z,
		final float partialTick,
		final CallbackInfoReturnable<Boolean> cir
	) {
		if (entity instanceof LocalPlayer && QuakeCraftClient.CONTROLLER.isPassthroughActive()) {
			cir.setReturnValue(false);
		}
	}
}
