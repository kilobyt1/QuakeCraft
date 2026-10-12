/*
 * ============================================================================
 * QuakeCraft Bridge — Quake 3 Shared Memory, Voxel Collision & Sync (q3_bridge.c)
 * ============================================================================
 * Linked into quake3-bridge.exe, qagamex86.dll, and cgamex86.dll.
 * Uses Win32 Named Shared Memory ("Local\\QuakeCraftBridgeV1").
 * ============================================================================
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

#include "q3_bridge.h"
#include "../game/surfaceflags.h"
#include "../game/bg_public.h"

#define QCB_SURFACE_CLIP_EPSILON 0.125f

static HANDLE           s_hMapFile = NULL;
static QcbSharedMemory *s_shm      = NULL;
static qboolean         s_initTried = qfalse;
static LARGE_INTEGER    s_qpcFreq  = {0};

uint64_t QCB_NowNs(void) {
    LARGE_INTEGER counter;
    if (s_qpcFreq.QuadPart == 0) {
        QueryPerformanceFrequency(&s_qpcFreq);
    }
    QueryPerformanceCounter(&counter);
    if (s_qpcFreq.QuadPart <= 0) {
        return 0;
    }
    return (uint64_t)((double)counter.QuadPart * 1000000000.0 / (double)s_qpcFreq.QuadPart);
}

void QCB_Init(void) {
    if (s_shm != NULL) {
        return;
    }
    s_initTried = qtrue;

    s_hMapFile = CreateFileMappingA(
        INVALID_HANDLE_VALUE,
        NULL,
        PAGE_READWRITE,
        (DWORD)((uint64_t)QCB_TOTAL_SHM_BYTES >> 32),
        (DWORD)(QCB_TOTAL_SHM_BYTES & 0xFFFFFFFFU),
        QCB_SHM_NAME
    );
    if (s_hMapFile == NULL) {
        return;
    }

    s_shm = (QcbSharedMemory *)MapViewOfFile(
        s_hMapFile,
        FILE_MAP_ALL_ACCESS,
        0,
        0,
        QCB_TOTAL_SHM_BYTES
    );
    if (s_shm == NULL) {
        CloseHandle(s_hMapFile);
        s_hMapFile = NULL;
        return;
    }

    if (s_shm->header.magic != QCB_MAGIC) {
        int i;
        s_shm->header.magic = QCB_MAGIC;
        s_shm->header.version = QCB_VERSION;
        s_shm->header.totalBytes = QCB_TOTAL_SHM_BYTES;
        s_shm->header.latestFrameSlot = 0;
        for (i = 0; i < QCB_FRAME_SLOTS; i++) {
            uint32_t baseOff = QCB_OFFSET_FRAME_DATA + (uint32_t)i * QCB_FRAME_SLOT_BYTES;
            s_shm->header.frames[i].rgbaOffset  = baseOff;
            s_shm->header.frames[i].depthOffset = baseOff + QCB_FRAME_RGBA_BYTES;
            s_shm->header.frames[i].hudOffset   = baseOff + QCB_FRAME_RGBA_BYTES + QCB_FRAME_DEPTH_BYTES;
        }
        MemoryBarrier();
    }

    s_shm->header.q3Pid = (uint32_t)GetCurrentProcessId();
    s_shm->header.q3HeartbeatNs = QCB_NowNs();
    s_shm->header.q3Epoch++;
    MemoryBarrier();
}

void QCB_Shutdown(void) {
    if (s_shm != NULL) {
        UnmapViewOfFile(s_shm);
        s_shm = NULL;
    }
    if (s_hMapFile != NULL) {
        CloseHandle(s_hMapFile);
        s_hMapFile = NULL;
    }
    s_initTried = qfalse;
}

QcbSharedMemory *QCB_GetShm(void) {
    if (s_shm == NULL && !s_initTried) {
        QCB_Init();
    }
    return s_shm;
}

qboolean QCB_IsActive(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || shm->header.magic != QCB_MAGIC) {
        return qfalse;
    }
    return qtrue;
}

qboolean QCB_HasVoxels(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || shm->header.magic != QCB_MAGIC) {
        return qfalse;
    }
    return (shm->voxels.seq > 0) ? qtrue : qfalse;
}

void QCB_Frame(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm) {
        return;
    }
    shm->header.q3Pid = (uint32_t)GetCurrentProcessId();
    shm->header.q3HeartbeatNs = QCB_NowNs();
}

static void QCB_SetPlaneSignbits(cplane_t *out) {
    int bits = 0;
    int j;
    for (j = 0; j < 3; j++) {
        if (out->normal[j] < 0) {
            bits |= 1 << j;
        }
    }
    out->signbits = (byte)bits;
}

/*
 * Exact Quake 3 brush-plane swept AABB vs static AABB [bmin, bmax]
 */
static void QCB_ClipBoxToBox(
    trace_t *trace,
    const vec3_t start,
    const vec3_t end,
    const vec3_t mins,
    const vec3_t maxs,
    const vec3_t bmin,
    const vec3_t bmax,
    int boxContents,
    int boxSurfFlags,
    qboolean isPositionTest
) {
    float cmin[3], cmax[3];
    int axis;

    cmin[0] = bmin[0] - maxs[0];
    cmin[1] = bmin[1] - maxs[1];
    cmin[2] = bmin[2] - maxs[2];

    cmax[0] = bmax[0] - mins[0];
    cmax[1] = bmax[1] - mins[1];
    cmax[2] = bmax[2] - mins[2];

    if (isPositionTest) {
        if (start[0] > cmin[0] && start[0] < cmax[0] &&
            start[1] > cmin[1] && start[1] < cmax[1] &&
            start[2] > cmin[2] && start[2] < cmax[2]) {
            trace->startsolid = qtrue;
            trace->allsolid = qtrue;
            trace->fraction = 0.0f;
            trace->contents = boxContents;
        }
        return;
    }

    float enterFrac = -1.0f;
    float leaveFrac = 1.0f;
    qboolean startout = qfalse;
    qboolean getout = qfalse;
    int hitAxis = 0;
    int hitSign = 1;

    for (axis = 0; axis < 3; axis++) {
        /* +1 plane at cmax[axis] */
        {
            float d1 = start[axis] - cmax[axis];
            float d2 = end[axis] - cmax[axis];
            if (d2 > 0.0f) getout = qtrue;
            if (d1 > 0.0f) startout = qtrue;
            if (d1 > 0.0f && (d2 >= QCB_SURFACE_CLIP_EPSILON || d2 >= d1)) {
                return;
            }
            if (!(d1 <= 0.0f && d2 <= 0.0f)) {
                if (d1 > d2) {
                    float f = (d1 - QCB_SURFACE_CLIP_EPSILON) / (d1 - d2);
                    if (f < 0.0f) f = 0.0f;
                    if (f > enterFrac) {
                        enterFrac = f;
                        hitAxis = axis;
                        hitSign = 1;
                    }
                } else {
                    float f = (d1 + QCB_SURFACE_CLIP_EPSILON) / (d1 - d2);
                    if (f > 1.0f) f = 1.0f;
                    if (f < leaveFrac) {
                        leaveFrac = f;
                    }
                }
            }
        }

        /* -1 plane at cmin[axis] */
        {
            float d1 = cmin[axis] - start[axis];
            float d2 = cmin[axis] - end[axis];
            if (d2 > 0.0f) getout = qtrue;
            if (d1 > 0.0f) startout = qtrue;
            if (d1 > 0.0f && (d2 >= QCB_SURFACE_CLIP_EPSILON || d2 >= d1)) {
                return;
            }
            if (!(d1 <= 0.0f && d2 <= 0.0f)) {
                if (d1 > d2) {
                    float f = (d1 - QCB_SURFACE_CLIP_EPSILON) / (d1 - d2);
                    if (f < 0.0f) f = 0.0f;
                    if (f > enterFrac) {
                        enterFrac = f;
                        hitAxis = axis;
                        hitSign = -1;
                    }
                } else {
                    float f = (d1 + QCB_SURFACE_CLIP_EPSILON) / (d1 - d2);
                    if (f > 1.0f) f = 1.0f;
                    if (f < leaveFrac) {
                        leaveFrac = f;
                    }
                }
            }
        }
    }

    if (!startout) {
        trace->startsolid = qtrue;
        if (!getout) {
            trace->allsolid = qtrue;
            trace->fraction = 0.0f;
            trace->contents = boxContents;
        }
        return;
    }

    if (enterFrac < leaveFrac) {
        if (enterFrac > -1.0f && enterFrac < trace->fraction) {
            if (enterFrac < 0.0f) {
                enterFrac = 0.0f;
            }
            trace->fraction = enterFrac;
            memset(trace->plane.normal, 0, sizeof(trace->plane.normal));
            trace->plane.normal[hitAxis] = (float)hitSign;
            trace->plane.dist = (hitSign > 0) ? bmax[hitAxis] : -bmin[hitAxis];
            trace->plane.type = (byte)hitAxis;
            QCB_SetPlaneSignbits(&trace->plane);
            trace->surfaceFlags = boxSurfFlags;
            trace->contents = boxContents;
        }
    }
}

void QCB_VoxelTrace(
    trace_t *results,
    const vec3_t start,
    const vec3_t end,
    const vec3_t minsIn,
    const vec3_t maxsIn,
    int brushmask
) {
    QcbSharedMemory *shm = QCB_GetShm();
    vec3_t mins, maxs;
    vec3_t traceMin, traceMax;
    int i;

    memset(results, 0, sizeof(*results));
    results->fraction = 1.0f;
    VectorCopy(end, results->endpos);

    if (!shm || shm->voxels.seq == 0) {
        return;
    }

    if (minsIn) {
        VectorCopy(minsIn, mins);
    } else {
        memset(mins, 0, sizeof(mins));
    }
    if (maxsIn) {
        VectorCopy(maxsIn, maxs);
    } else {
        memset(maxs, 0, sizeof(maxs));
    }

    for (i = 0; i < 3; i++) {
        if (start[i] < end[i]) {
            traceMin[i] = start[i] + mins[i] - 1.0f;
            traceMax[i] = end[i] + maxs[i] + 1.0f;
        } else {
            traceMin[i] = end[i] + mins[i] - 1.0f;
            traceMax[i] = start[i] + maxs[i] + 1.0f;
        }
    }

    float anchorX = shm->voxels.anchorMcX;
    float anchorY = shm->voxels.anchorMcY;
    float anchorZ = shm->voxels.anchorMcZ;
    int origX = shm->voxels.originBlockX;
    int origY = shm->voxels.originBlockY;
    int origZ = shm->voxels.originBlockZ;

    /* Convert Q3 trace bounds to MC block index bounds:
     * MC_X = anchorX + Q3_X / 32
     * MC_Y = anchorY + Q3_Z / 32
     * MC_Z = anchorZ - Q3_Y / 32
     */
    int minBx = (int)floorf(anchorX + traceMin[0] * QCB_BLOCKS_PER_UNIT);
    int maxBx = (int)floorf(anchorX + traceMax[0] * QCB_BLOCKS_PER_UNIT);
    int minBy = (int)floorf(anchorY + traceMin[2] * QCB_BLOCKS_PER_UNIT);
    int maxBy = (int)floorf(anchorY + traceMax[2] * QCB_BLOCKS_PER_UNIT);
    int minBz = (int)floorf(anchorZ - traceMax[1] * QCB_BLOCKS_PER_UNIT);
    int maxBz = (int)floorf(anchorZ - traceMin[1] * QCB_BLOCKS_PER_UNIT);

    int dx0 = minBx - origX; if (dx0 < 0) dx0 = 0;
    int dx1 = maxBx - origX; if (dx1 >= QCB_VOXEL_DIM) dx1 = QCB_VOXEL_DIM - 1;
    int dy0 = minBy - origY; if (dy0 < 0) dy0 = 0;
    int dy1 = maxBy - origY; if (dy1 >= QCB_VOXEL_DIM) dy1 = QCB_VOXEL_DIM - 1;
    int dz0 = minBz - origZ; if (dz0 < 0) dz0 = 0;
    int dz1 = maxBz - origZ; if (dz1 >= QCB_VOXEL_DIM) dz1 = QCB_VOXEL_DIM - 1;

    qboolean isPositionTest = (start[0] == end[0] && start[1] == end[1] && start[2] == end[2]) ? qtrue : qfalse;

    int dy, dz, dx;
    for (dy = dy0; dy <= dy1; dy++) {
        int by = origY + dy;
        float bminZ = ((float)by - anchorY) * QCB_UNITS_PER_BLOCK;
        float bmaxZ = bminZ + QCB_UNITS_PER_BLOCK;

        for (dz = dz0; dz <= dz1; dz++) {
            int bz = origZ + dz;
            float bminY = -((float)(bz + 1) - anchorZ) * QCB_UNITS_PER_BLOCK;
            float bmaxY = bminY + QCB_UNITS_PER_BLOCK;

            for (dx = dx0; dx <= dx1; dx++) {
                uint8_t rawCell = shm->voxels.cells[dy * 4096 + dz * 64 + dx];
                if (rawCell == QCB_VOXEL_AIR) {
                    continue;
                }

                uint8_t shape = rawCell & 0x0FU;
                int boxContents = CONTENTS_SOLID;
                int boxSurfFlags = 0;

                if ((rawCell & 0x10U) || shape == QCB_VOXEL_ICE) {
                    boxSurfFlags |= SURF_SLICK;
                }
                if (rawCell & 0x80U) {
                    boxSurfFlags |= SURF_METALSTEPS;
                }

                if (shape == QCB_VOXEL_WATER) {
                    boxContents = CONTENTS_WATER;
                } else if (shape == QCB_VOXEL_LAVA) {
                    boxContents = CONTENTS_LAVA;
                }

                if (!(brushmask & boxContents)) {
                    continue;
                }

                int bx = origX + dx;
                float bminX = ((float)bx - anchorX) * QCB_UNITS_PER_BLOCK;
                float bmaxX = bminX + QCB_UNITS_PER_BLOCK;

                vec3_t boxMin = { bminX, bminY, bminZ };
                vec3_t boxMax = { bmaxX, bmaxY, bmaxZ };

                if (shape == QCB_VOXEL_SLAB_BOTTOM) {
                    boxMax[2] = bminZ + 16.0f;
                    QCB_ClipBoxToBox(results, start, end, mins, maxs, boxMin, boxMax, boxContents, boxSurfFlags, isPositionTest);
                } else if (shape == QCB_VOXEL_SLAB_TOP) {
                    boxMin[2] = bminZ + 16.0f;
                    QCB_ClipBoxToBox(results, start, end, mins, maxs, boxMin, boxMax, boxContents, boxSurfFlags, isPositionTest);
                } else if (shape >= QCB_VOXEL_STAIR_EAST && shape <= QCB_VOXEL_STAIR_NORTH) {
                    /* Bottom slab half */
                    vec3_t botMax = { bmaxX, bmaxY, bminZ + 16.0f };
                    QCB_ClipBoxToBox(results, start, end, mins, maxs, boxMin, botMax, boxContents, boxSurfFlags, isPositionTest);
                    /* Upper step half */
                    vec3_t topMin = { bminX, bminY, bminZ + 16.0f };
                    vec3_t topMax = { bmaxX, bmaxY, bmaxZ };
                    if (shape == QCB_VOXEL_STAIR_EAST) {
                        topMin[0] = bminX + 16.0f;
                    } else if (shape == QCB_VOXEL_STAIR_WEST) {
                        topMax[0] = bminX + 16.0f;
                    } else if (shape == QCB_VOXEL_STAIR_NORTH) {
                        /* MC North is -Z, which is +Y in Q3 */
                        topMin[1] = bminY + 16.0f;
                    } else if (shape == QCB_VOXEL_STAIR_SOUTH) {
                        /* MC South is +Z, which is -Y in Q3 */
                        topMax[1] = bminY + 16.0f;
                    }
                    QCB_ClipBoxToBox(results, start, end, mins, maxs, topMin, topMax, boxContents, boxSurfFlags, isPositionTest);
                } else {
                    QCB_ClipBoxToBox(results, start, end, mins, maxs, boxMin, boxMax, boxContents, boxSurfFlags, isPositionTest);
                }

                if (results->allsolid) {
                    VectorCopy(start, results->endpos);
                    return;
                }
            }
        }
    }

    if (results->fraction == 1.0f) {
        VectorCopy(end, results->endpos);
    } else {
        for (i = 0; i < 3; i++) {
            results->endpos[i] = start[i] + results->fraction * (end[i] - start[i]);
        }
    }
}

int QCB_VoxelPointContents(const vec3_t p) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || shm->voxels.seq == 0) {
        return 0;
    }

    float anchorX = shm->voxels.anchorMcX;
    float anchorY = shm->voxels.anchorMcY;
    float anchorZ = shm->voxels.anchorMcZ;

    int bx = (int)floorf(anchorX + p[0] * QCB_BLOCKS_PER_UNIT);
    int by = (int)floorf(anchorY + p[2] * QCB_BLOCKS_PER_UNIT);
    int bz = (int)floorf(anchorZ - p[1] * QCB_BLOCKS_PER_UNIT);

    int dx = bx - shm->voxels.originBlockX;
    int dy = by - shm->voxels.originBlockY;
    int dz = bz - shm->voxels.originBlockZ;

    if (dx < 0 || dx >= QCB_VOXEL_DIM ||
        dy < 0 || dy >= QCB_VOXEL_DIM ||
        dz < 0 || dz >= QCB_VOXEL_DIM) {
        return 0;
    }

    uint8_t rawCell = shm->voxels.cells[dy * 4096 + dz * 64 + dx];
    uint8_t shape = rawCell & 0x0FU;

    if (shape == QCB_VOXEL_AIR) {
        return 0;
    }
    if (shape == QCB_VOXEL_WATER) {
        return CONTENTS_WATER;
    }
    if (shape == QCB_VOXEL_LAVA) {
        return CONTENTS_LAVA;
    }
    if (shape == QCB_VOXEL_SLAB_BOTTOM) {
        float localZ = p[2] - (((float)by - anchorY) * QCB_UNITS_PER_BLOCK);
        return (localZ <= 16.0f) ? CONTENTS_SOLID : 0;
    }
    if (shape == QCB_VOXEL_SLAB_TOP) {
        float localZ = p[2] - (((float)by - anchorY) * QCB_UNITS_PER_BLOCK);
        return (localZ >= 16.0f) ? CONTENTS_SOLID : 0;
    }
    return CONTENTS_SOLID;
}

static signed char QCB_ClampChar(int i) {
    if (i < -128) return -128;
    if (i > 127) return 127;
    return (signed char)i;
}

void QCB_ApplyClientInput(usercmd_t *cmd, vec3_t viewangles, int *cgameWeaponSelect) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || shm->header.input.seq == 0) {
        return;
    }
    if (!(shm->header.input.flags & QCB_INPUT_ACTIVE)) {
        return;
    }

    int32_t fwd   = shm->header.input.forwardmove;
    int32_t right = shm->header.input.rightmove;
    int32_t up    = shm->header.input.upmove;
    uint32_t btns = shm->header.input.buttons;

    if (btns & 1024U) {
        up = 127;
    } else if (btns & 2048U) {
        up = -127;
    }

    cmd->forwardmove = QCB_ClampChar(fwd);
    cmd->rightmove   = QCB_ClampChar(right);
    cmd->upmove      = QCB_ClampChar(up);
    cmd->buttons     = (int)(btns & 0xFFU);

    if (shm->header.input.weaponSelect >= 1 && shm->header.input.weaponSelect <= 9) {
        if (cgameWeaponSelect) {
            *cgameWeaponSelect = shm->header.input.weaponSelect;
        }
        cmd->weapon = (byte)shm->header.input.weaponSelect;
    }

    /* Convert Minecraft camera yaw/pitch to Quake 3 viewangles:
     * q3Yaw = -90.0f - mcYaw, q3Pitch = mcPitch
     */
    float q3Yaw = -90.0f - shm->header.input.mcYaw;
    while (q3Yaw <= -180.0f) q3Yaw += 360.0f;
    while (q3Yaw > 180.0f) q3Yaw -= 360.0f;

    float q3Pitch = shm->header.input.mcPitch;
    if (q3Pitch > 89.0f) q3Pitch = 89.0f;
    if (q3Pitch < -89.0f) q3Pitch = -89.0f;

    viewangles[PITCH] = q3Pitch;
    viewangles[YAW]   = q3Yaw;
    viewangles[ROLL]  = 0.0f;
}

qboolean QCB_CheckPendingTeleport(vec3_t outOrigin, float *outYaw, uint32_t *outSeq) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || shm->header.input.seq == 0) {
        return qfalse;
    }
    uint32_t reqSeq = shm->header.input.teleportSeq;
    uint32_t ackSeq = shm->header.pose.teleportAckSeq;
    if (reqSeq != 0 && reqSeq != ackSeq) {
        outOrigin[0] = shm->header.input.teleportQ3Origin[0];
        outOrigin[1] = shm->header.input.teleportQ3Origin[1];
        outOrigin[2] = shm->header.input.teleportQ3Origin[2];
        if (outYaw) {
            *outYaw = shm->header.input.teleportQ3Yaw;
        }
        if (outSeq) {
            *outSeq = reqSeq;
        }
        return qtrue;
    }
    return qfalse;
}

void QCB_AckTeleport(uint32_t seq) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm) {
        return;
    }
    shm->header.pose.teleportAckSeq = seq;
    MemoryBarrier();
}

void QCB_PublishPlayerPose(const playerState_t *ps) {
    QcbSharedMemory *shm = QCB_GetShm();
    int i;
    if (!shm || !ps) {
        return;
    }

    uint32_t nextSeq = (shm->header.pose.seq + 1U) | 1U;
    shm->header.pose.seq = nextSeq;
    MemoryBarrier();

    uint32_t flags = QCB_POSE_VALID;
    if (ps->pm_type == PM_DEAD || ps->stats[STAT_HEALTH] <= 0) {
        flags |= QCB_POSE_DEAD;
        if (ps->stats[STAT_HEALTH] <= -40) {
            flags |= QCB_POSE_GIBBED;
        }
    }
    if (ps->groundEntityNum != ENTITYNUM_NONE) {
        flags |= QCB_POSE_GROUNDED;
    }
    if (ps->pm_flags & PMF_DUCKED) {
        flags |= QCB_POSE_CROUCHING;
    }
    if (ps->eFlags & EF_FIRING) {
        flags |= QCB_POSE_FIRING;
    }

    shm->header.pose.flags          = flags;
    shm->header.pose.timestampNs    = QCB_NowNs();
    shm->header.pose.q3Origin[0]    = ps->origin[0];
    shm->header.pose.q3Origin[1]    = ps->origin[1];
    shm->header.pose.q3Origin[2]    = ps->origin[2];
    shm->header.pose.q3Velocity[0]  = ps->velocity[0];
    shm->header.pose.q3Velocity[1]  = ps->velocity[1];
    shm->header.pose.q3Velocity[2]  = ps->velocity[2];
    shm->header.pose.q3ViewAngles[0] = ps->viewangles[0];
    shm->header.pose.q3ViewAngles[1] = ps->viewangles[1];
    shm->header.pose.q3ViewAngles[2] = ps->viewangles[2];
    shm->header.pose.viewHeight     = (float)ps->viewheight;
    shm->header.pose.health         = ps->stats[STAT_HEALTH];
    shm->header.pose.armor          = ps->stats[STAT_ARMOR];
    shm->header.pose.weapon         = ps->weapon;
    shm->header.pose.weaponState    = ps->weaponstate;
    shm->header.pose.currentAmmo    = (ps->weapon >= 0 && ps->weapon < 16) ? ps->ammo[ps->weapon] : 0;
    shm->header.pose.legsAnim       = ps->legsAnim;
    shm->header.pose.torsoAnim      = ps->torsoAnim;

    for (i = 0; i < 10; i++) {
        shm->header.pose.ammo[i] = ps->ammo[i];
    }

    shm->header.q3Pid = (uint32_t)GetCurrentProcessId();
    shm->header.q3HeartbeatNs = shm->header.pose.timestampNs;

    MemoryBarrier();
    shm->header.pose.seq = nextSeq + 1U;
}
