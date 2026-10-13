package dev.quakecraft.bridge.client.mixin;

import dev.quakecraft.bridge.client.QuakeCraftClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Invokes QuakeFrameCompositor at the end of GameRenderer.renderLevel() so
 * Quake 3's 3D world entities, projectiles, explosions, first-person weapon,
 * and HUD are depth-composited into Minecraft's mainRenderTarget before
 * screenshot capture and GUI rendering.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method = "render3dHud", at = @At("HEAD"), cancellable = true)
	private void quakecraft$compositeQuakeFrameBeforeHandClear(final CallbackInfo ci) {
		final Minecraft mc = Minecraft.getInstance();
		QuakeCraftClient.COMPOSITOR.composite(mc, QuakeCraftClient.bridge);
		if (QuakeCraftClient.CONTROLLER.isPassthroughActive()
			&& !QuakeCraftClient.CONTROLLER.isMcInteractMode()
			&& mc.options.getCameraType().isFirstPerson()) {
			ci.cancel();
		}
	}
}
