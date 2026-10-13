package dev.quakecraft.bridge.client;

import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import dev.quakecraft.bridge.QuakeCraftMod;
import dev.quakecraft.bridge.SharedMemoryBridge;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

/**
 * Depth-aware OpenGL frame compositor for QuakeCraft (Stage 3).
 *
 * Reads the newest triple-buffered Quake 3 frame slot from Local\QuakeCraftBridgeV1:
 *   - Layer 0: 3D World / Entities RGBA8
 *   - Layer 1: 3D Linear Depth R32F (in Quake 3 units, 32.0 units = 1.0 MC block)
 *   - Layer 2: First-person weapon (RF_DEPTHHACK) + 2D HUD RGBA8
 *
 * Composites Layer 0 + Layer 1 into Minecraft 26.3's mainRenderTarget using
 * Reversed-Z (z_mc = zNearMc / (z_q3 / 32.0), GL_GEQUAL), then blends Layer 2 on top.
 */
public final class QuakeFrameCompositor {
	private static final String VERT_SRC = """
		#version 330 core
		out vec2 vUv;
		void main() {
			vec2 pos = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
			vUv = pos;
			gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
		}
		""";

	private static final String WORLD_FRAG_SRC = """
		#version 330 core
		in vec2 vUv;
		uniform sampler2D uWorldColor;
		uniform sampler2D uWorldDepth;
		uniform vec2 uUvScale;
		uniform float uMcNear;
		uniform float uMcFar;
		out vec4 fragColor;
		void main() {
			vec2 uv = vUv * uUvScale;
			vec4 col = texture(uWorldColor, uv);
			float zQ3 = texture(uWorldDepth, uv).r;
			float luma = max(col.r, max(col.g, col.b));
			if (zQ3 > 0.001) {
				float zMc = zQ3 * (1.0 / 32.0);
				float revZ = (uMcNear / max(zMc, uMcNear)) - (uMcNear / uMcFar);
				gl_FragDepth = clamp(revZ, 0.0, 1.0);
				fragColor = vec4(col.rgb, 1.0);
			} else if (luma > 0.02) {
				gl_FragDepth = 0.9999;
				float alpha = smoothstep(0.02, 0.15, luma);
				fragColor = vec4(col.rgb, alpha);
			} else {
				discard;
			}
		}
		""";

	private static final String OVERLAY_FRAG_SRC = """
		#version 330 core
		in vec2 vUv;
		uniform sampler2D uOverlayColor;
		uniform vec2 uUvScale;
		out vec4 fragColor;
		void main() {
			vec2 uv = vUv * uUvScale;
			vec4 col = texture(uOverlayColor, uv);
			float luma = max(col.r, max(col.g, col.b));
			if (luma <= 0.015) {
				discard;
			}
			float alpha = smoothstep(0.015, 0.06, luma);
			fragColor = vec4(col.rgb, alpha);
		}
		""";

	private boolean initialized = false;
	private boolean initFailed = false;

	private int fbo = 0;
	private int vao = 0;
	private int texWorldRgba = 0;
	private int texWorldDepth = 0;
	private int texOverlayRgba = 0;

	private int progWorld = 0;
	private int uWorldColor = -1;
	private int uWorldDepth = -1;
	private int uWorldUvScale = -1;
	private int uWorldMcNear = -1;
	private int uWorldMcFar = -1;

	private int progOverlay = 0;
	private int uOverlayColor = -1;
	private int uOverlayUvScale = -1;

	private Object glEncoderBackend = null;
	private Field lastPipelineField = null;
	private Field lastProgramField = null;

	private long lastUploadedFrameNumber = -1L;
	private int cachedFrameWidth = 0;
	private int cachedFrameHeight = 0;

	public void composite(final Minecraft mc, final SharedMemoryBridge bridge) {
		if (bridge == null || initFailed || !QuakeCraftClient.CONTROLLER.isPassthroughEnabled()) {
			return;
		}
		final RenderTarget target = mc.gameRenderer.mainRenderTarget();
		if (target == null || !(target.getColorTexture() instanceof GlTexture glColorTex)
			|| !(target.getDepthTexture() instanceof GlTexture glDepthTex)) {
			return;
		}

		if (!initialized) {
			if (!initGlResources()) {
				initFailed = true;
				return;
			}
			initialized = true;
		}

		// Save FBO bindings from GlStateManager (zero glGetInteger GPU pipeline stalls!)
		final int prevDrawFbo = GlStateManager.getFrameBuffer(GL30.GL_DRAW_FRAMEBUFFER);
		final int prevReadFbo = GlStateManager.getFrameBuffer(GL30.GL_READ_FRAMEBUFFER);

		if (!uploadLatestFrameIfNewer(bridge) || cachedFrameWidth <= 0 || cachedFrameHeight <= 0) {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);
			return;
		}

		final float uScale = (float) cachedFrameWidth / (float) SharedMemoryBridge.MAX_WIDTH;
		final float vScale = (float) cachedFrameHeight / (float) SharedMemoryBridge.MAX_HEIGHT;
		final float mcNear = 0.05f;
		final float mcFar = Math.max(64.0f, mc.options.getEffectiveRenderDistance() * 64.0f);

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, glColorTex.glId(), 0);
		GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, glDepthTex.glId(), 0);
		GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);

		GlStateManager._viewport(0, 0, target.width, target.height);
		GlStateManager._disableScissorTest();
		GlStateManager._disableCull();
		GlStateManager._colorMask(0, 15);
		GlStateManager._glBindVertexArray(vao);

		// Bind compositor textures exclusively on Texture Units 5, 6, 7 so Minecraft's
		// Sampler0 (LOCATION_BLOCKS), Sampler1 (OverlayTexture), and Sampler2 (Lightmap)
		// on units 0..2 are NEVER touched or disturbed!
		GL33.glBindSampler(5, 0);
		GL33.glBindSampler(6, 0);
		GL33.glBindSampler(7, 0);

		GlStateManager._activeTexture(GL13.GL_TEXTURE5);
		GlStateManager._bindTexture(texWorldRgba);
		GlStateManager._activeTexture(GL13.GL_TEXTURE6);
		GlStateManager._bindTexture(texWorldDepth);
		GlStateManager._activeTexture(GL13.GL_TEXTURE7);
		GlStateManager._bindTexture(texOverlayRgba);

		// Pass 1: Depth-aware 3D world & entities (Reversed-Z: GL_GEQUAL)
		GlStateManager._glUseProgram(progWorld);
		GL20.glUniform2f(uWorldUvScale, uScale, vScale);
		GL20.glUniform1f(uWorldMcNear, mcNear);
		GL20.glUniform1f(uWorldMcFar, mcFar);

		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11.GL_GEQUAL);
		GlStateManager._depthMask(true);
		GlStateManager._enableBlend(0);
		GlStateManager._blendFuncSeparate(
			GL11.GL_SRC_ALPHA,
			GL11.GL_ONE_MINUS_SRC_ALPHA,
			GL11.GL_ONE,
			GL11.GL_ONE_MINUS_SRC_ALPHA
		);
		GlStateManager._blendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);

		GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, 3);

		// Pass 2: First-person weapon (RF_DEPTHHACK) + 2D Quake 3 HUD overlay
		GlStateManager._glUseProgram(progOverlay);
		GL20.glUniform2f(uOverlayUvScale, uScale, vScale);

		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._enableBlend(0);
		GlStateManager._blendFuncSeparate(
			GL11.GL_SRC_ALPHA,
			GL11.GL_ONE_MINUS_SRC_ALPHA,
			GL11.GL_ONE,
			GL11.GL_ONE_MINUS_SRC_ALPHA
		);
		GlStateManager._blendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);

		GlStateManager._drawArrays(GL11.GL_TRIANGLES, 0, 3);

		// Restore GL state cleanly for RenderPearl without touching units 0..2
		GlStateManager._depthFunc(GL11.GL_LEQUAL);
		GlStateManager._depthMask(true);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._glBindVertexArray(0);
		GlStateManager._glUseProgram(0);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
		invalidateRenderPearlPipelineCache();
	}

	private void invalidateRenderPearlPipelineCache() {
		if (glEncoderBackend == null) {
			return;
		}
		try {
			if (lastPipelineField != null) {
				lastPipelineField.set(glEncoderBackend, null);
			}
			if (lastProgramField != null) {
				lastProgramField.set(glEncoderBackend, null);
			}
		} catch (Throwable ignored) {
		}
	}

	private boolean uploadLatestFrameIfNewer(final SharedMemoryBridge bridge) {
		final var seg = bridge.segment;
		final long q3Hb = seg.get(SharedMemoryBridge.LONG, SharedMemoryBridge.HDR_Q3_HEARTBEAT_NS);
		if (q3Hb == 0L) {
			return false;
		}

		final int slot = seg.get(SharedMemoryBridge.INT, SharedMemoryBridge.HDR_LATEST_FRAME_SLOT);
		if (slot < 0 || slot >= SharedMemoryBridge.FRAME_SLOTS) {
			return false;
		}

		final long descOff = SharedMemoryBridge.OFF_FRAMES_DESC + (long) slot * SharedMemoryBridge.STRIDE_FRAME_DESC;
		final int seq1 = seg.get(SharedMemoryBridge.INT, descOff + 0L);
		if (seq1 == 0 || (seq1 & 1) != 0) {
			return cachedFrameWidth > 0;
		}
		VarHandle.acquireFence();

		final int width = seg.get(SharedMemoryBridge.INT, descOff + 8L);
		final int height = seg.get(SharedMemoryBridge.INT, descOff + 12L);
		final long frameNum = seg.get(SharedMemoryBridge.LONG, descOff + 24L);
		final int rgbaOff = seg.get(SharedMemoryBridge.INT, descOff + 108L);
		final int depthOff = seg.get(SharedMemoryBridge.INT, descOff + 112L);
		final int hudOff = seg.get(SharedMemoryBridge.INT, descOff + 116L);

		if (width <= 0 || width > SharedMemoryBridge.MAX_WIDTH || height <= 0 || height > SharedMemoryBridge.MAX_HEIGHT) {
			return cachedFrameWidth > 0;
		}
		if (frameNum == lastUploadedFrameNumber && cachedFrameWidth > 0) {
			return true;
		}

		final long baseAddr = seg.address();
		final long worldPtr = baseAddr + Integer.toUnsignedLong(rgbaOff);
		final long depthPtr = baseAddr + Integer.toUnsignedLong(depthOff);
		final long hudPtr = baseAddr + Integer.toUnsignedLong(hudOff);

		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
		GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);

		GlStateManager._activeTexture(GL13.GL_TEXTURE5);
		GlStateManager._bindTexture(texWorldRgba);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, worldPtr);

		GlStateManager._activeTexture(GL13.GL_TEXTURE6);
		GlStateManager._bindTexture(texWorldDepth);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RED, GL11.GL_FLOAT, depthPtr);

		GlStateManager._activeTexture(GL13.GL_TEXTURE7);
		GlStateManager._bindTexture(texOverlayRgba);
		GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, hudPtr);

		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);

		VarHandle.acquireFence();
		final int seq2 = seg.get(SharedMemoryBridge.INT, descOff + 0L);
		if (seq1 == seq2) {
			lastUploadedFrameNumber = frameNum;
			cachedFrameWidth = width;
			cachedFrameHeight = height;
		}
		return cachedFrameWidth > 0;
	}

	private boolean initGlResources() {
		try {
			progWorld = compileProgram(VERT_SRC, WORLD_FRAG_SRC);
			uWorldColor = GL20.glGetUniformLocation(progWorld, "uWorldColor");
			uWorldDepth = GL20.glGetUniformLocation(progWorld, "uWorldDepth");
			uWorldUvScale = GL20.glGetUniformLocation(progWorld, "uUvScale");
			uWorldMcNear = GL20.glGetUniformLocation(progWorld, "uMcNear");
			uWorldMcFar = GL20.glGetUniformLocation(progWorld, "uMcFar");

			GlStateManager._glUseProgram(progWorld);
			GL20.glUniform1i(uWorldColor, 5);
			GL20.glUniform1i(uWorldDepth, 6);

			progOverlay = compileProgram(VERT_SRC, OVERLAY_FRAG_SRC);
			uOverlayColor = GL20.glGetUniformLocation(progOverlay, "uOverlayColor");
			uOverlayUvScale = GL20.glGetUniformLocation(progOverlay, "uUvScale");

			GlStateManager._glUseProgram(progOverlay);
			GL20.glUniform1i(uOverlayColor, 7);
			GlStateManager._glUseProgram(0);

			fbo = GL30.glGenFramebuffers();
			vao = GL30.glGenVertexArrays();

			texWorldRgba = createTexture2D(GL13.GL_TEXTURE5, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, GL11.GL_LINEAR);
			texWorldDepth = createTexture2D(GL13.GL_TEXTURE6, GL30.GL_R32F, GL11.GL_RED, GL11.GL_FLOAT, GL11.GL_NEAREST);
			texOverlayRgba = createTexture2D(GL13.GL_TEXTURE7, GL11.GL_RGBA8, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, GL11.GL_LINEAR);
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);

			try {
				if (RenderSystem.getDevice().createCommandEncoder() instanceof FrontendCommandEncoder fe) {
					glEncoderBackend = fe.backend();
					if (glEncoderBackend != null) {
						lastPipelineField = glEncoderBackend.getClass().getDeclaredField("lastPipeline");
						lastPipelineField.setAccessible(true);
						lastProgramField = glEncoderBackend.getClass().getDeclaredField("lastProgram");
						lastProgramField.setAccessible(true);
					}
				}
			} catch (Throwable t) {
				QuakeCraftMod.LOG.warn("Could not reflect GlCommandEncoder.lastPipeline", t);
			}

			QuakeCraftMod.LOG.info("Initialized QuakeFrameCompositor OpenGL shaders and triple-buffer textures on units 5..7");
			return true;
		} catch (Throwable t) {
			QuakeCraftMod.LOG.error("Failed to initialize QuakeFrameCompositor", t);
			return false;
		}
	}

	private static int createTexture2D(final int unit, final int internalFormat, final int format, final int type, final int filter) {
		final int tex = GL11.glGenTextures();
		GlStateManager._activeTexture(unit);
		GlStateManager._bindTexture(tex);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_BASE_LEVEL, 0);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, filter);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexImage2D(
			GL11.GL_TEXTURE_2D,
			0,
			internalFormat,
			SharedMemoryBridge.MAX_WIDTH,
			SharedMemoryBridge.MAX_HEIGHT,
			0,
			format,
			type,
			0L
		);
		return tex;
	}

	private static int compileProgram(final String vertSrc, final String fragSrc) {
		final int vs = compileShader(GL20.GL_VERTEX_SHADER, vertSrc);
		final int fs = compileShader(GL20.GL_FRAGMENT_SHADER, fragSrc);
		final int prog = GL20.glCreateProgram();
		GL20.glAttachShader(prog, vs);
		GL20.glAttachShader(prog, fs);
		GL20.glLinkProgram(prog);
		if (GL20.glGetProgrami(prog, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			final String log = GL20.glGetProgramInfoLog(prog);
			throw new IllegalStateException("Shader link failed: " + log);
		}
		GL20.glDeleteShader(vs);
		GL20.glDeleteShader(fs);
		return prog;
	}

	private static int compileShader(final int type, final String src) {
		final int sh = GL20.glCreateShader(type);
		GL20.glShaderSource(sh, src);
		GL20.glCompileShader(sh);
		if (GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			final String log = GL20.glGetShaderInfoLog(sh);
			throw new IllegalStateException("Shader compile failed: " + log);
		}
		return sh;
	}
}
