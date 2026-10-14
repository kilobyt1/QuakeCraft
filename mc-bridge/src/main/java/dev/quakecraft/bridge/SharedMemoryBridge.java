package dev.quakecraft.bridge;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Single Source of Truth Java mapping for Local\QuakeCraftBridgeV1.
 * Matches protocol/bridge_protocol.h byte-for-byte.
 */
public final class SharedMemoryBridge {
	public static final String SHM_NAME = "Local\\QuakeCraftBridgeV1";
	public static final int MAGIC = 0x51434252; // "QCBR"
	public static final int VERSION = 1;

	public static final float SCALE_Q3_PER_MC = 32.0f;
	public static final float SCALE_MC_PER_Q3 = 1.0f / 32.0f;
	public static final float Q3_PLAYER_MINS_Z = -24.0f;

	public static final int VOXEL_DIM = 64;
	public static final int VOXEL_HALF = 32;
	public static final int VOXEL_TOTAL = VOXEL_DIM * VOXEL_DIM * VOXEL_DIM;

	public static final int MAX_ACTORS = 128;
	public static final int EVENT_RING_CAP = 64;

	public static final int FRAME_SLOTS = 3;
	public static final int MAX_WIDTH = 1920;
	public static final int MAX_HEIGHT = 1080;
	public static final long LAYER_BYTES = (long) MAX_WIDTH * MAX_HEIGHT * 4L;
	public static final long SLOT_PIXEL_BYTES = LAYER_BYTES * 3L;

	// Bridge flags
	public static final int FLAG_ACTIVE = 1 << 0;
	public static final int FLAG_PASSTHROUGH_ON = 1 << 1;
	public static final int FLAG_MC_INTERACT = 1 << 2;
	public static final int FLAG_THIRD_PERSON = 1 << 3;
	public static final int FLAG_RESET_REQUEST = 1 << 4;

	// Quake 3 buttons
	public static final int BTN_ATTACK = 1;
	public static final int BTN_TALK = 2;
	public static final int BTN_USE_HOLDABLE = 4;
	public static final int BTN_GESTURE = 8;
	public static final int BTN_WALKING = 16;
	public static final int BTN_JUMP = 1024;
	public static final int BTN_CROUCH = 2048;

	// Voxel shapes (low 4 bits)
	public static final int VOX_AIR = 0x0;
	public static final int VOX_SOLID = 0x1;
	public static final int VOX_SLAB_BOTTOM = 0x2;
	public static final int VOX_SLAB_TOP = 0x3;
	public static final int VOX_STAIR_N = 0x4;
	public static final int VOX_STAIR_S = 0x5;
	public static final int VOX_STAIR_E = 0x6;
	public static final int VOX_STAIR_W = 0x7;
	public static final int VOX_WATER = 0x8;
	public static final int VOX_LAVA = 0x9;
	public static final int VOX_SLIME = 0xA;
	public static final int VOX_LADDER = 0xB;
	public static final int VOX_FENCE = 0xC;

	// Voxel surface flags (high 4 bits)
	public static final int SURF_SLICK = 0x10;
	public static final int SURF_BOUNCE = 0x20;
	public static final int SURF_BREAKABLE = 0x40;
	public static final int SURF_METAL = 0x80;

	// Event types
	public static final int EV_NONE = 0;
	public static final int EV_DAMAGE_ENTITY = 1;
	public static final int EV_EXPLOSION = 2;
	public static final int EV_BREAK_BLOCK = 3;
	public static final int EV_WEAPON_FIRED = 4;
	public static final int EV_RAIL_BEAM = 5;
	public static final int EV_PLAYER_HURT = 101;
	public static final int EV_GIVE_ITEM = 102;
	public static final int EV_TELEPORT_SYNC = 103;
	public static final int EV_RESPAWN = 104;

	// Exact byte offsets (verified against protocol/bridge_protocol.h)
	public static final long OFF_HEADER = 0L;
	public static final long SIZE_HEADER = 1024L;
	public static final long HDR_MAGIC = 0L;
	public static final long HDR_VERSION = 4L;
	public static final long HDR_TOTAL_BYTES = 8L;
	public static final long HDR_Q3_PID = 12L;
	public static final long HDR_MC_PID = 16L;
	public static final long HDR_LATEST_FRAME_SLOT = 20L;
	public static final long HDR_Q3_HEARTBEAT_NS = 24L;
	public static final long HDR_MC_HEARTBEAT_NS = 32L;
	public static final long HDR_Q3_EPOCH = 40L;
	public static final long HDR_MC_EPOCH = 48L;

	public static final long OFF_POSE = 256L;
	public static final long SIZE_POSE = 128L;
	public static final long POSE_SEQ = OFF_POSE + 0L;
	public static final long POSE_FLAGS = OFF_POSE + 4L;
	public static final long POSE_TIMESTAMP_NS = OFF_POSE + 8L;
	public static final long POSE_ORIGIN_X = OFF_POSE + 16L;
	public static final long POSE_ORIGIN_Y = OFF_POSE + 20L;
	public static final long POSE_ORIGIN_Z = OFF_POSE + 24L;
	public static final long POSE_VEL_X = OFF_POSE + 28L;
	public static final long POSE_VEL_Y = OFF_POSE + 32L;
	public static final long POSE_VEL_Z = OFF_POSE + 36L;
	public static final long POSE_PITCH = OFF_POSE + 40L;
	public static final long POSE_YAW = OFF_POSE + 44L;
	public static final long POSE_ROLL = OFF_POSE + 48L;
	public static final long POSE_VIEWHEIGHT = OFF_POSE + 52L;
	public static final long POSE_HEALTH = OFF_POSE + 56L;
	public static final long POSE_ARMOR = OFF_POSE + 60L;
	public static final long POSE_WEAPON = OFF_POSE + 64L;
	public static final long POSE_WEAPON_STATE = OFF_POSE + 68L;
	public static final long POSE_CURRENT_AMMO = OFF_POSE + 72L;
	public static final long POSE_LEGS_ANIM = OFF_POSE + 76L;
	public static final long POSE_TORSO_ANIM = OFF_POSE + 80L;
	public static final long POSE_TELEPORT_ACK_SEQ = OFF_POSE + 84L;
	public static final long POSE_AMMO_ARRAY = OFF_POSE + 88L;

	public static final long OFF_INPUT = 384L;
	public static final long SIZE_INPUT = 128L;
	public static final long INPUT_SEQ = OFF_INPUT + 0L;
	public static final long INPUT_FLAGS = OFF_INPUT + 4L;
	public static final long INPUT_TIMESTAMP_NS = OFF_INPUT + 8L;
	public static final long INPUT_FORWARDMOVE = OFF_INPUT + 16L;
	public static final long INPUT_RIGHTMOVE = OFF_INPUT + 20L;
	public static final long INPUT_UPMOVE = OFF_INPUT + 24L;
	public static final long INPUT_BUTTONS = OFF_INPUT + 28L;
	public static final long INPUT_WEAPON_SELECT = OFF_INPUT + 32L;
	public static final long INPUT_MC_YAW = OFF_INPUT + 36L;
	public static final long INPUT_MC_PITCH = OFF_INPUT + 40L;
	public static final long INPUT_FOV_Y = OFF_INPUT + 44L;
	public static final long INPUT_VIEWPORT_W = OFF_INPUT + 48L;
	public static final long INPUT_VIEWPORT_H = OFF_INPUT + 52L;
	public static final long INPUT_TELEPORT_SEQ = OFF_INPUT + 56L;
	public static final long INPUT_ANCHOR_MC_X = OFF_INPUT + 60L;
	public static final long INPUT_ANCHOR_MC_Y = OFF_INPUT + 64L;
	public static final long INPUT_ANCHOR_MC_Z = OFF_INPUT + 68L;
	public static final long INPUT_TELEPORT_Q3_X = OFF_INPUT + 72L;
	public static final long INPUT_TELEPORT_Q3_Y = OFF_INPUT + 76L;
	public static final long INPUT_TELEPORT_Q3_Z = OFF_INPUT + 80L;
	public static final long INPUT_TELEPORT_Q3_YAW = OFF_INPUT + 84L;
	public static final long INPUT_HIT_CONFIRM_COUNT = OFF_INPUT + 88L;
	public static final long INPUT_KILL_COUNT = OFF_INPUT + 92L;

	public static final long OFF_FRAMES_DESC = 512L;
	public static final long STRIDE_FRAME_DESC = 128L;

	public static final long OFF_VOXELS = 1024L;
	public static final long VOXELS_SEQ = OFF_VOXELS + 0L;
	public static final long VOXELS_ORIGIN_X = OFF_VOXELS + 4L;
	public static final long VOXELS_ORIGIN_Y = OFF_VOXELS + 8L;
	public static final long VOXELS_ORIGIN_Z = OFF_VOXELS + 12L;
	public static final long VOXELS_ANCHOR_X = OFF_VOXELS + 16L;
	public static final long VOXELS_ANCHOR_Y = OFF_VOXELS + 20L;
	public static final long VOXELS_ANCHOR_Z = OFF_VOXELS + 24L;
	public static final long VOXELS_UPDATE_COUNT = OFF_VOXELS + 28L;
	public static final long OFF_VOXEL_CELLS = OFF_VOXELS + 32L;
	public static final long SIZE_VOXELS = 262176L;

	public static final long OFF_ACTORS = 263200L;
	public static final long ACTORS_SEQ = OFF_ACTORS + 0L;
	public static final long ACTORS_COUNT = OFF_ACTORS + 4L;
	public static final long ACTORS_TIMESTAMP_NS = OFF_ACTORS + 8L;
	public static final long OFF_ACTOR_ARRAY = OFF_ACTORS + 16L;
	public static final long STRIDE_ACTOR = 64L;
	public static final long SIZE_ACTORS = 8208L;

	public static final long OFF_Q3_TO_MC = 271408L;
	public static final long SIZE_EVENT_RING = 4112L;
	public static final long STRIDE_EVENT = 64L;

	public static final long OFF_MC_TO_Q3 = 275520L;
	public static final long OFF_FRAME_PIXELS = 282624L;
	public static final long TOTAL_BYTES = 74932224L;

	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;

	public static final ValueLayout.OfByte BYTE = ValueLayout.JAVA_BYTE;
	public static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	public static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	public static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
	public static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

	public final MemorySegment segment;

	private SharedMemoryBridge(final MemorySegment segment) {
		this.segment = segment;
	}

	public static SharedMemoryBridge openOrCreate() throws Throwable {
		return openOrCreate(SHM_NAME);
	}

	public static SharedMemoryBridge openOrCreate(final String mappingName) throws Throwable {
		Linker linker = Linker.nativeLinker();
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		MethodHandle createFileMapping = linker.downcallHandle(
			kernel32.find("CreateFileMappingW").orElseThrow(),
			FunctionDescriptor.of(
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT,
				ValueLayout.ADDRESS
			)
		);
		MethodHandle mapViewOfFile = linker.downcallHandle(
			kernel32.find("MapViewOfFile").orElseThrow(),
			FunctionDescriptor.of(
				ValueLayout.ADDRESS,
				ValueLayout.ADDRESS,
				ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT,
				ValueLayout.JAVA_INT,
				ValueLayout.JAVA_LONG
			)
		);

		MemorySegment wideName = Arena.global().allocateFrom(
			ValueLayout.JAVA_BYTE,
			(mappingName + "\0").getBytes(StandardCharsets.UTF_16LE)
		);
		MemorySegment invalidHandle = MemorySegment.ofAddress(-1L);
		MemorySegment handle = (MemorySegment) createFileMapping.invoke(
			invalidHandle,
			MemorySegment.NULL,
			PAGE_READWRITE,
			(int) (TOTAL_BYTES >>> 32),
			(int) TOTAL_BYTES,
			wideName
		);
		if (handle.address() == 0L) {
			throw new IllegalStateException("CreateFileMappingW failed for " + mappingName);
		}

		MemorySegment view = (MemorySegment) mapViewOfFile.invoke(
			handle,
			FILE_MAP_ALL_ACCESS,
			0,
			0,
			TOTAL_BYTES
		);
		if (view.address() == 0L) {
			throw new IllegalStateException("MapViewOfFile failed for " + mappingName);
		}

		MemorySegment seg = view.reinterpret(TOTAL_BYTES);
		int existingMagic = seg.get(INT, HDR_MAGIC);
		if (existingMagic != MAGIC) {
			seg.set(INT, HDR_MAGIC, MAGIC);
			seg.set(INT, HDR_VERSION, VERSION);
			seg.set(INT, HDR_TOTAL_BYTES, (int) TOTAL_BYTES);
			seg.set(INT, HDR_LATEST_FRAME_SLOT, 0);
			seg.set(INT, INPUT_FLAGS, FLAG_ACTIVE | FLAG_PASSTHROUGH_ON);
			seg.set(INT, INPUT_VIEWPORT_W, 1280);
			seg.set(INT, INPUT_VIEWPORT_H, 720);
			seg.set(FLOAT, INPUT_FOV_Y, 70.0f);
			VarHandle.fullFence();
		}
		seg.set(INT, HDR_MC_PID, (int) ProcessHandle.current().pid());
		seg.set(LONG, HDR_MC_HEARTBEAT_NS, System.nanoTime());
		return new SharedMemoryBridge(seg);
	}

	// Coordinate bijection helpers (matching protocol/bridge_protocol.h)
	public static void q3OriginToMcFeet(float qx, float qy, float qz, float spX, float spY, float spZ, float[] outMc) {
		outMc[0] = spX + qx * SCALE_MC_PER_Q3;
		outMc[1] = spY + (qz + Q3_PLAYER_MINS_Z) * SCALE_MC_PER_Q3;
		outMc[2] = spZ - qy * SCALE_MC_PER_Q3;
	}

	public static void mcFeetToQ3Origin(float mx, float my, float mz, float spX, float spY, float spZ, float[] outQ3) {
		outQ3[0] = (mx - spX) * SCALE_Q3_PER_MC;
		outQ3[1] = -(mz - spZ) * SCALE_Q3_PER_MC;
		outQ3[2] = (my - spY) * SCALE_Q3_PER_MC - Q3_PLAYER_MINS_Z;
	}

	public static void q3PointToMc(float qx, float qy, float qz, float spX, float spY, float spZ, float[] outMc) {
		outMc[0] = spX + qx * SCALE_MC_PER_Q3;
		outMc[1] = spY + qz * SCALE_MC_PER_Q3;
		outMc[2] = spZ - qy * SCALE_MC_PER_Q3;
	}

	public static void mcPointToQ3(float mx, float my, float mz, float spX, float spY, float spZ, float[] outQ3) {
		outQ3[0] = (mx - spX) * SCALE_Q3_PER_MC;
		outQ3[1] = -(mz - spZ) * SCALE_Q3_PER_MC;
		outQ3[2] = (my - spY) * SCALE_Q3_PER_MC;
	}

	public static float mcYawToQ3Yaw(float mcYawDeg) {
		float y = -90.0f - mcYawDeg;
		while (y <= -180.0f) y += 360.0f;
		while (y > 180.0f) y -= 360.0f;
		return y;
	}

	public static float q3YawToMcYaw(float q3YawDeg) {
		float y = -90.0f - q3YawDeg;
		while (y <= -180.0f) y += 360.0f;
		while (y > 180.0f) y -= 360.0f;
		return y;
	}

	private static volatile SharedMemoryBridge GLOBAL_INSTANCE = null;

	public static boolean isPassthroughActive() {
		try {
			if (GLOBAL_INSTANCE == null) {
				GLOBAL_INSTANCE = openOrCreate();
			}
			if (GLOBAL_INSTANCE != null && GLOBAL_INSTANCE.segment != null) {
				final int flags = GLOBAL_INSTANCE.segment.get(INT, INPUT_FLAGS);
				return (flags & FLAG_PASSTHROUGH_ON) != 0;
			}
		} catch (Throwable ignored) {
		}
		return false;
	}
}
