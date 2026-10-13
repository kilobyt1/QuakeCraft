package dev.quakecraft.bridge.client.mixin;

import dev.quakecraft.bridge.client.PlayerControllerSync;
import dev.quakecraft.bridge.client.QuakeCraftClient;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Synchronizes Minecraft's first-person Camera eye position with the exact
 * Quake 3 PMove origin + viewheight at render rate before frustum and
 * projection matrices are computed.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(double x, double y, double z);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void quakecraft$syncCameraToQ3Pose(final float partialTick, final CallbackInfo ci) {
		final PlayerControllerSync ctrl = QuakeCraftClient.CONTROLLER;
		final Minecraft mc = Minecraft.getInstance();
		ctrl.onRenderFrameUpdate(mc, QuakeCraftClient.bridge);
		if (ctrl.isPassthroughActive() && mc.options.getCameraType().isFirstPerson()) {
			this.setPosition(
				ctrl.getRenderFeetX(),
				ctrl.getRenderEyeY(),
				ctrl.getRenderFeetZ()
			);
		}
	}
}
