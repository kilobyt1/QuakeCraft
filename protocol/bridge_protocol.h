/*
 * ============================================================================
 * QuakeCraft Bridge Protocol (protocol/bridge_protocol.h)
 * ============================================================================
 * Single Source of Truth for the Win32 Named Shared Memory layout shared by:
 *   1. Quake III Arena engine (C, x86_64-w64-mingw32)
 *   2. Minecraft Java 26.3 Fabric transceiver (Java 25 FFM API)
 *
 * All structures are explicitly padded and verified with _Static_assert.
 * ============================================================================
 */
#ifndef QUAKECRAFT_BRIDGE_PROTOCOL_H
#define QUAKECRAFT_BRIDGE_PROTOCOL_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define QCB_MAGIC               0x51434252U /* "QCBR" in little-endian */
#define QCB_VERSION             1U
#define QCB_SHM_NAME            "Local\\QuakeCraftBridgeV1"

#define QCB_UNITS_PER_BLOCK     32.0f
#define QCB_BLOCKS_PER_UNIT     (1.0f / 32.0f)
#define QCB_FEET_OFFSET_Z       24.0f       /* Quake 3 MINS_Z = -24 */

#define QCB_VOXEL_DIM           64
#define QCB_VOXEL_HALF          32
#define QCB_VOXEL_TOTAL         (QCB_VOXEL_DIM * QCB_VOXEL_DIM * QCB_VOXEL_DIM) /* 262144 */

#define QCB_MAX_ACTORS          128
#define QCB_MAX_EVENTS          64

#define QCB_MAX_FRAME_W         1920
#define QCB_MAX_FRAME_H         1080
#define QCB_MAX_FRAME_PIXELS    (QCB_MAX_FRAME_W * QCB_MAX_FRAME_H)
#define QCB_FRAME_RGBA_BYTES    (QCB_MAX_FRAME_PIXELS * 4U) /* 8,294,400 */
#define QCB_FRAME_DEPTH_BYTES   (QCB_MAX_FRAME_PIXELS * 4U) /* 8,294,400 */
#define QCB_FRAME_HUD_BYTES     (QCB_MAX_FRAME_PIXELS * 4U) /* 8,294,400 */
#define QCB_FRAME_SLOT_BYTES    (QCB_FRAME_RGBA_BYTES + QCB_FRAME_DEPTH_BYTES + QCB_FRAME_HUD_BYTES) /* 24,883,200 */
#define QCB_FRAME_SLOTS         3

/* Voxel cell types */
#define QCB_VOXEL_AIR           0
#define QCB_VOXEL_SOLID         1
#define QCB_VOXEL_SLAB_BOTTOM   2
#define QCB_VOXEL_SLAB_TOP      3
#define QCB_VOXEL_STAIR_EAST    4
#define QCB_VOXEL_STAIR_WEST    5
#define QCB_VOXEL_STAIR_SOUTH   6
#define QCB_VOXEL_STAIR_NORTH   7
#define QCB_VOXEL_WATER         8
#define QCB_VOXEL_LAVA          9
#define QCB_VOXEL_SLIME         10
#define QCB_VOXEL_ICE           11
#define QCB_VOXEL_LADDER        12

/* Pose flags */
#define QCB_POSE_VALID          (1U << 0)
#define QCB_POSE_DEAD           (1U << 1)
#define QCB_POSE_GIBBED         (1U << 2)
#define QCB_POSE_GROUNDED       (1U << 3)
#define QCB_POSE_IN_WATER       (1U << 4)
#define QCB_POSE_CROUCHING      (1U << 5)
#define QCB_POSE_FIRING         (1U << 6)

/* Input flags */
#define QCB_INPUT_ACTIVE         (1U << 0)
#define QCB_INPUT_PASSTHROUGH_ON (1U << 1)
#define QCB_INPUT_MC_GUI_OPEN    (1U << 1)
#define QCB_INPUT_BUILD_MODE     (1U << 2)
#define QCB_INPUT_THIRD_PERSON   (1U << 3)

/* Quake 3 button bits (matches code/game/q_shared.h) */
#define QCB_BUTTON_ATTACK       1U
#define QCB_BUTTON_TALK         2U
#define QCB_BUTTON_USE_HOLDABLE 4U
#define QCB_BUTTON_GESTURE      8U
#define QCB_BUTTON_WALKING      16U

/* Event types: Q3 -> MC */
#define QCB_EV_DAMAGE_ENTITY    1U
#define QCB_EV_EXPLOSION        2U
#define QCB_EV_BREAK_BLOCK      3U

/* Event types: MC -> Q3 */
#define QCB_EV_PLAYER_HURT      101U
#define QCB_EV_GIVE_ITEM        102U

/* Pickup item subtypes for QCB_EV_GIVE_ITEM */
#define QCB_ITEM_HEALTH_SMALL   1U  /* +5 HP (allows overheal to 200) */
#define QCB_ITEM_HEALTH_MEDIUM  2U  /* +25 HP */
#define QCB_ITEM_HEALTH_LARGE   3U  /* +50 HP */
#define QCB_ITEM_MEGA_HEALTH    4U  /* +100 HP */
#define QCB_ITEM_ARMOR_SHARD    5U  /* +5 Armor */
#define QCB_ITEM_ARMOR_COMBAT   6U  /* +50 Yellow Armor */
#define QCB_ITEM_ARMOR_BODY     7U  /* +100 Red Armor */
#define QCB_ITEM_QUAD_DAMAGE    8U  /* Quad Damage 30s */
#define QCB_ITEM_HASTE          9U  /* Haste 30s */
#define QCB_ITEM_REGEN          10U /* Regeneration 30s */
#define QCB_ITEM_AMMO_PACK      11U /* Refill all weapons */

/* ----------------------------------------------------------------------------
 * 1. QcbPlayerPose (128 bytes) - Written by Quake 3, read by Minecraft
 * ------------------------------------------------------------------------- */
typedef struct QcbPlayerPose {
    uint32_t seq;               /* 0: Seqlock (odd = writing, even = ready) */
    uint32_t flags;             /* 4: QCB_POSE_* */
    uint64_t timestampNs;       /* 8: QPC timestamp in ns */
    float    q3Origin[3];       /* 16: Quake 3 position (x, y, z) */
    float    q3Velocity[3];     /* 28: Quake 3 velocity (vx, vy, vz) */
    float    q3ViewAngles[3];   /* 40: Quake 3 view angles (pitch, yaw, roll) */
    float    viewHeight;        /* 52: Eye offset from q3Origin[2] (default 26) */
    int32_t  health;            /* 56: Quake 3 health (100 base) */
    int32_t  armor;             /* 60: Quake 3 armor */
    int32_t  weapon;            /* 64: Active weapon (WP_GAUNTLET..WP_BFG) */
    int32_t  weaponState;       /* 68: WEAPON_READY / FIRING / RAISING / DROPPING */
    int32_t  currentAmmo;       /* 72: Ammo of active weapon */
    int32_t  legsAnim;          /* 76: MD3 legs animation index */
    int32_t  torsoAnim;         /* 80: MD3 torso animation index */
    uint32_t teleportAckSeq;    /* 84: Highest teleportSeq applied by Q3 */
    int32_t  ammo[10];          /* 88..127: Ammo for WP_NONE..WP_BFG */
} QcbPlayerPose;

/* ----------------------------------------------------------------------------
 * 2. QcbInputState (128 bytes) - Written by Minecraft, read by Quake 3
 * ------------------------------------------------------------------------- */
typedef struct QcbInputState {
    uint32_t seq;               /* 0: Seqlock */
    uint32_t flags;             /* 4: QCB_INPUT_* */
    uint64_t timestampNs;       /* 8: QPC timestamp in ns */
    int32_t  forwardmove;       /* 16: -127..+127 */
    int32_t  rightmove;         /* 20: -127..+127 */
    int32_t  upmove;            /* 24: -127..+127 (+127 = jump, -127 = crouch) */
    uint32_t buttons;           /* 28: QCB_BUTTON_* */
    int32_t  weaponSelect;      /* 32: 0 = keep, 1..9 = switch to weapon */
    float    mcYaw;             /* 36: Minecraft camera yaw (degrees) */
    float    mcPitch;           /* 40: Minecraft camera pitch (degrees) */
    float    fovY;              /* 44: Minecraft vertical FOV (degrees) */
    int32_t  viewportWidth;     /* 48: Requested render width (clamped to 1920) */
    int32_t  viewportHeight;    /* 52: Requested render height (clamped to 1080) */
    uint32_t teleportSeq;       /* 56: Incremented when MC forces position reset */
    float    anchorMcPos[3];    /* 60: Session origin anchor in MC block coords */
    float    teleportQ3Origin[3]; /* 72: Target Q3 origin on teleport */
    float    teleportQ3Yaw;     /* 84: Target Q3 yaw on teleport */
    uint32_t hitConfirmCount;   /* 88: Incremented when MC confirms mob hit */
    int32_t  killCount;         /* 92: Total MC mob kills in session (frag count) */
    uint32_t reserved[8];       /* 96..127: Reserved for future use */
} QcbInputState;

/* ----------------------------------------------------------------------------
 * 3. QcbVoxelGrid (262,176 bytes) - Written by Minecraft, read by Quake 3
 * ------------------------------------------------------------------------- */
typedef struct QcbVoxelGrid {
    uint32_t seq;               /* 0: Seqlock */
    int32_t  originBlockX;      /* 4: Minimum corner MC block X (relative to anchor) */
    int32_t  originBlockY;      /* 8: Minimum corner MC block Y (relative to anchor) */
    int32_t  originBlockZ;      /* 12: Minimum corner MC block Z (relative to anchor) */
    float    anchorMcX;         /* 16: World anchor X */
    float    anchorMcY;         /* 20: World anchor Y */
    float    anchorMcZ;         /* 24: World anchor Z */
    uint32_t updateCount;       /* 28: Number of grid updates published */
    uint8_t  cells[QCB_VOXEL_TOTAL]; /* 32..262175: [dy * 4096 + dz * 64 + dx] */
} QcbVoxelGrid;

/* ----------------------------------------------------------------------------
 * 4. QcbActorTable (8,208 bytes) - Written by Minecraft, read by Quake 3
 * ------------------------------------------------------------------------- */
typedef struct QcbActorSlot {
    int32_t  mcEntityId;        /* 0: Minecraft entity network ID */
    uint32_t generation;        /* 4: Generation / spawn serial */
    uint32_t entityType;        /* 8: Mob archetype */
    uint32_t flags;             /* 12: 1=active, 2=hostile, 4=in_water */
    float    q3Origin[3];       /* 16: Center position in Quake 3 units */
    float    q3Velocity[3];     /* 28: Velocity in Quake 3 units/s */
    float    halfWidth;         /* 40: Horizontal half-extent in Q3 units */
    float    height;            /* 44: Full height in Q3 units */
    float    healthQ3;          /* 48: Current health in Q3 units (MC_HP * 5) */
    float    maxHealthQ3;       /* 52: Max health in Q3 units */
    float    yaw;               /* 56: Facing yaw in Q3 degrees */
    float    pad0;              /* 60: Padding to 64 bytes */
} QcbActorSlot;

typedef struct QcbActorTable {
    uint32_t     seq;           /* 0: Seqlock */
    uint32_t     count;         /* 4: Active actor count (0..QCB_MAX_ACTORS) */
    uint64_t     timestampNs;   /* 8: QPC timestamp */
    QcbActorSlot actors[QCB_MAX_ACTORS]; /* 16..8207 */
} QcbActorTable;

/* ----------------------------------------------------------------------------
 * 5. QcbEvent (64 bytes) & QcbEventRing (4,112 bytes)
 * ------------------------------------------------------------------------- */
typedef struct QcbEvent {
    uint32_t type;              /* 0: QCB_EV_* */
    int32_t  targetId;          /* 4: mcEntityId or itemType */
    int32_t  intParam1;         /* 8: damageQ3 / blockX / amount */
    int32_t  intParam2;         /* 12: weaponId / blockY */
    int32_t  intParam3;         /* 16: isGib / blockZ */
    float    floatParam1;       /* 20: radiusQ3 */
    float    vecA[3];           /* 24: knockbackQ3[3] or explosionPosQ3[3] */
    float    vecB[3];           /* 36: hitPosQ3[3] or attackerPosQ3[3] */
    uint64_t timestampNs;       /* 48: Event timestamp */
    uint64_t pad0;              /* 56: Pad to 64 bytes */
} QcbEvent;

typedef struct QcbEventRing {
    uint32_t writeIdx;          /* 0: Monotonically increasing write index */
    uint32_t readIdx;           /* 4: Monotonically increasing read index */
    uint64_t pad0;              /* 8: Alignment */
    QcbEvent events[QCB_MAX_EVENTS]; /* 16..4111 */
} QcbEventRing;

/* ----------------------------------------------------------------------------
 * 6. QcbFrameSlotDesc (128 bytes) - Frame metadata per triple-buffer slot
 * ------------------------------------------------------------------------- */
typedef struct QcbFrameSlotDesc {
    uint32_t seq;               /* 0: Seqlock (odd while writing pixels, even when ready) */
    uint32_t flags;             /* 4: 1=valid, 2=has_depth, 4=has_hud, 8=bottom_up */
    uint32_t width;             /* 8: Rendered width (1..1920) */
    uint32_t height;            /* 12: Rendered height (1..1080) */
    uint64_t timestampNs;       /* 16: QPC timestamp when frame finished rendering */
    uint64_t frameNumber;       /* 24: Monotonic frame counter */
    float    renderQ3Origin[3]; /* 32: Camera origin in Q3 units for this frame */
    float    renderQ3Angles[3]; /* 44: Camera angles (pitch, yaw, roll) */
    float    renderViewAxis[9]; /* 56: 3x3 view axis (forward[3], left[3], up[3]) */
    float    fovX;              /* 92: Horizontal FOV in degrees */
    float    fovY;              /* 96: Vertical FOV in degrees */
    float    nearClipQ3;        /* 100: Q3 zNear in Q3 units (typically 4.0f) */
    float    farClipQ3;         /* 104: Q3 zFar in Q3 units */
    uint32_t rgbaOffset;        /* 108: Byte offset from start of SHM to RGBA8 buffer */
    uint32_t depthOffset;       /* 112: Byte offset from start of SHM to R32F depth buffer */
    uint32_t hudOffset;         /* 116: Byte offset from start of SHM to HUD RGBA8 buffer */
    uint32_t reserved[2];       /* 120..127 */
} QcbFrameSlotDesc;

/* ----------------------------------------------------------------------------
 * 7. QcbBridgeHeader (1024 bytes) & Complete Shared Memory Layout
 * ------------------------------------------------------------------------- */
typedef struct QcbBridgeHeader {
    uint32_t magic;             /* 0: QCB_MAGIC (0x51434252) */
    uint32_t version;           /* 4: QCB_VERSION (1) */
    uint32_t totalBytes;        /* 8: sizeof(QcbSharedMemory) */
    uint32_t q3Pid;             /* 12: Quake 3 process ID */
    uint32_t mcPid;             /* 16: Minecraft process ID */
    uint32_t latestFrameSlot;   /* 20: Most recently completed frame slot (0..2) */
    uint64_t q3HeartbeatNs;     /* 24: Updated every Q3 frame */
    uint64_t mcHeartbeatNs;     /* 32: Updated every MC frame */
    uint64_t q3Epoch;           /* 40: Incremented when Q3 starts/restarts */
    uint64_t mcEpoch;           /* 48: Incremented when MC starts/restarts */
    uint8_t  reserved[200];     /* 56..255 */
    QcbPlayerPose    pose;      /* 256..383 (128 B) */
    QcbInputState    input;     /* 384..511 (128 B) */
    QcbFrameSlotDesc frames[QCB_FRAME_SLOTS]; /* 512..895 (384 B) */
    uint8_t  padTo1024[128];    /* 896..1023 (128 B) */
} QcbBridgeHeader;

typedef struct QcbSharedMemory {
    QcbBridgeHeader header;                     /* 0 .. 1,023 (1,024 B) */
    QcbVoxelGrid    voxels;                     /* 1,024 .. 263,199 (262,176 B) */
    QcbActorTable   actors;                     /* 263,200 .. 271,407 (8,208 B) */
    QcbEventRing    q3ToMcEvents;               /* 271,408 .. 275,519 (4,112 B) */
    QcbEventRing    mcToQ3Events;               /* 275,520 .. 279,631 (4,112 B) */
    uint8_t         padToPage[2992];            /* 279,632 .. 282,623 (align to 4096: 282624 = 69 * 4096) */
    uint8_t         frameBytes[QCB_FRAME_SLOTS * QCB_FRAME_SLOT_BYTES]; /* 282,624 .. 74,932,223 */
} QcbSharedMemory;

#define QCB_OFFSET_HEADER           0U
#define QCB_OFFSET_POSE             256U
#define QCB_OFFSET_INPUT            384U
#define QCB_OFFSET_FRAMES_DESC      512U
#define QCB_OFFSET_VOXELS           1024U
#define QCB_OFFSET_ACTORS           263200U
#define QCB_OFFSET_Q3_TO_MC_RING    271408U
#define QCB_OFFSET_MC_TO_Q3_RING    275520U
#define QCB_OFFSET_FRAME_DATA       282624U
#define QCB_TOTAL_SHM_BYTES         (QCB_OFFSET_FRAME_DATA + (QCB_FRAME_SLOTS * QCB_FRAME_SLOT_BYTES)) /* 74,932,224 */

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 201112L
#include <stddef.h>
_Static_assert(sizeof(QcbPlayerPose) == 128, "QcbPlayerPose must be 128 bytes");
_Static_assert(sizeof(QcbInputState) == 128, "QcbInputState must be 128 bytes");
_Static_assert(sizeof(QcbVoxelGrid) == 262176, "QcbVoxelGrid must be 262176 bytes");
_Static_assert(sizeof(QcbActorSlot) == 64, "QcbActorSlot must be 64 bytes");
_Static_assert(sizeof(QcbActorTable) == 8208, "QcbActorTable must be 8208 bytes");
_Static_assert(sizeof(QcbEvent) == 64, "QcbEvent must be 64 bytes");
_Static_assert(sizeof(QcbEventRing) == 4112, "QcbEventRing must be 4112 bytes");
_Static_assert(sizeof(QcbFrameSlotDesc) == 128, "QcbFrameSlotDesc must be 128 bytes");
_Static_assert(sizeof(QcbBridgeHeader) == 1024, "QcbBridgeHeader must be 1024 bytes");
_Static_assert(offsetof(QcbSharedMemory, header.pose) == QCB_OFFSET_POSE, "pose offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, header.input) == QCB_OFFSET_INPUT, "input offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, header.frames) == QCB_OFFSET_FRAMES_DESC, "frames desc offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, voxels) == QCB_OFFSET_VOXELS, "voxels offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, actors) == QCB_OFFSET_ACTORS, "actors offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, q3ToMcEvents) == QCB_OFFSET_Q3_TO_MC_RING, "q3ToMcEvents offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, mcToQ3Events) == QCB_OFFSET_MC_TO_Q3_RING, "mcToQ3Events offset mismatch");
_Static_assert(offsetof(QcbSharedMemory, frameBytes) == QCB_OFFSET_FRAME_DATA, "frameBytes offset mismatch");
_Static_assert(sizeof(QcbSharedMemory) == QCB_TOTAL_SHM_BYTES, "QcbSharedMemory size mismatch");
#endif

#ifdef __cplusplus
}
#endif

#endif /* QUAKECRAFT_BRIDGE_PROTOCOL_H */
