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
        if (start[0] > cmin[0] + 0.125f && start[0] < cmax[0] - 0.125f &&
            start[1] > cmin[1] + 0.125f && start[1] < cmax[1] - 0.125f &&
            start[2] > cmin[2] + 0.125f && start[2] < cmax[2] - 0.5f) {
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
            if (d1 >= -QCB_SURFACE_CLIP_EPSILON) startout = qtrue;
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
            if (d1 >= -QCB_SURFACE_CLIP_EPSILON) startout = qtrue;
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
        VectorClear(mins);
    }
    if (maxsIn) {
        VectorCopy(maxsIn, maxs);
    } else {
        VectorClear(maxs);
    }

    for (i = 0; i < 3; i++) {
        if (start[i] < end[i]) {
            traceMin[i] = start[i] + mins[i];
            traceMax[i] = end[i] + maxs[i];
        } else {
            traceMin[i] = end[i] + mins[i];
            traceMax[i] = start[i] + maxs[i];
        }
    }

    /* Retry up to 4 times if Minecraft updates the voxel seqlock mid-sweep */
    int attempt;
    for (attempt = 0; attempt < 4; attempt++) {
        uint32_t vseq1 = 0, vseq2 = 0;
        int spin = 0;
        while (spin < 10000) {
            vseq1 = shm->voxels.seq;
            if ((vseq1 & 1U) == 0U) {
                break;
            }
            YieldProcessor();
            spin++;
        }
        if ((vseq1 & 1U) != 0U) {
            continue;
        }

        memset(results, 0, sizeof(*results));
        results->fraction = 1.0f;
        VectorCopy(end, results->endpos);

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
        qboolean abortedMidSweep = qfalse;
        for (dy = dy0; dy <= dy1 && !abortedMidSweep; dy++) {
            int by = origY + dy;
            float bminZ = ((float)by - anchorY) * QCB_UNITS_PER_BLOCK;
            float bmaxZ = bminZ + QCB_UNITS_PER_BLOCK;

            for (dz = dz0; dz <= dz1 && !abortedMidSweep; dz++) {
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
                        MemoryBarrier();
                        vseq2 = shm->voxels.seq;
                        if (vseq1 == vseq2) {
                            return;
                        }
                        abortedMidSweep = qtrue;
                        break;
                    }
                }
            }
        }

        if (abortedMidSweep) {
            continue;
        }

        if (results->fraction == 1.0f) {
            VectorCopy(end, results->endpos);
        } else {
            for (i = 0; i < 3; i++) {
                results->endpos[i] = start[i] + results->fraction * (end[i] - start[i]);
            }
        }

        MemoryBarrier();
        vseq2 = shm->voxels.seq;
        if (vseq1 == vseq2) {
            return;
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

    uint32_t iseq1 = 0, iseq2 = 0;
    int attempt;
    int32_t fwd = 0, right = 0, up = 0, wsel = 0;
    uint32_t btns = 0, iflags = 0;
    float mcYaw = 0.0f, mcPitch = 0.0f;

    for (attempt = 0; attempt < 4; attempt++) {
        iseq1 = shm->header.input.seq;
        if (iseq1 == 0 || (iseq1 & 1U) != 0U) {
            continue;
        }
        MemoryBarrier();
        iflags  = shm->header.input.flags;
        fwd     = shm->header.input.forwardmove;
        right   = shm->header.input.rightmove;
        up      = shm->header.input.upmove;
        btns    = shm->header.input.buttons;
        wsel    = shm->header.input.weaponSelect;
        mcYaw   = shm->header.input.mcYaw;
        mcPitch = shm->header.input.mcPitch;
        MemoryBarrier();
        iseq2 = shm->header.input.seq;
        if (iseq1 == iseq2) {
            break;
        }
    }
    if (iseq1 == 0 || iseq1 != iseq2 || !(iflags & QCB_INPUT_ACTIVE)) {
        return;
    }

    if (btns & 1024U) {
        up = 127;
    } else if (btns & 2048U) {
        up = -127;
    }

    cmd->forwardmove = QCB_ClampChar(fwd);
    cmd->rightmove   = QCB_ClampChar(right);
    cmd->upmove      = QCB_ClampChar(up);
    cmd->buttons     = (int)(btns & 0xFFU);

    if (wsel >= 1 && wsel <= 9) {
        if (cgameWeaponSelect) {
            *cgameWeaponSelect = wsel;
        }
        cmd->weapon = (byte)wsel;
    }

    /* Convert Minecraft camera yaw/pitch to Quake 3 viewangles:
     * q3Yaw = -90.0f - mcYaw, q3Pitch = mcPitch
     */
    float q3Yaw = -90.0f - mcYaw;
    while (q3Yaw <= -180.0f) q3Yaw += 360.0f;
    while (q3Yaw > 180.0f) q3Yaw -= 360.0f;

    float q3Pitch = mcPitch;
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

/* ============================================================================
 * Stage 3: Triple-Buffered Frame & Linear Depth Capture
 * ============================================================================ */

static int      s_activeWriteSlot = -1;
static uint32_t s_frameCounter    = 0;

void QCB_GetCaptureDimensions(int vidWidth, int vidHeight, int *outW, int *outH) {
    int w = vidWidth;
    int h = vidHeight;
    if (w > QCB_MAX_FRAME_W) w = QCB_MAX_FRAME_W;
    if (h > QCB_MAX_FRAME_H) h = QCB_MAX_FRAME_H;
    if (w < 1) w = 1;
    if (h < 1) h = 1;
    *outW = w;
    *outH = h;
}

void QCB_BeginFrameCapture(
    int width,
    int height,
    float zNear,
    float zFar,
    float fovX,
    float fovY,
    const float viewOrg[3],
    const float viewAngles[3]
) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || width <= 0 || height <= 0) {
        s_activeWriteSlot = -1;
        return;
    }

    uint32_t latest = shm->header.latestFrameSlot;
    uint32_t nextSlot = (latest + 1U) % QCB_FRAME_SLOTS;
    s_activeWriteSlot = (int)nextSlot;

    QcbFrameSlotDesc *desc = &shm->header.frames[nextSlot];
    uint32_t seq = (desc->seq + 1U) | 1U;
    desc->seq = seq;
    MemoryBarrier();

    uint32_t baseOff = QCB_OFFSET_FRAME_DATA + nextSlot * QCB_FRAME_SLOT_BYTES;
    desc->flags       = 1U | 2U | 4U | 8U; /* valid | has_depth | has_hud | bottom_up */
    desc->width       = (uint32_t)width;
    desc->height      = (uint32_t)height;
    desc->nearClipQ3  = zNear;
    desc->farClipQ3   = zFar;
    desc->fovX        = fovX;
    desc->fovY        = fovY;
    if (viewOrg) {
        desc->renderQ3Origin[0] = viewOrg[0];
        desc->renderQ3Origin[1] = viewOrg[1];
        desc->renderQ3Origin[2] = viewOrg[2];
    }
    if (viewAngles) {
        desc->renderQ3Angles[0] = viewAngles[0];
        desc->renderQ3Angles[1] = viewAngles[1];
        desc->renderQ3Angles[2] = viewAngles[2];
    }
    desc->rgbaOffset  = baseOff;
    desc->depthOffset = baseOff + QCB_FRAME_RGBA_BYTES;
    desc->hudOffset   = baseOff + QCB_FRAME_RGBA_BYTES + QCB_FRAME_DEPTH_BYTES;
}

uint8_t *QCB_GetActiveWorldRgbaBuffer(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || s_activeWriteSlot < 0 || s_activeWriteSlot >= QCB_FRAME_SLOTS) {
        return NULL;
    }
    return (uint8_t *)shm + shm->header.frames[s_activeWriteSlot].rgbaOffset;
}

float *QCB_GetActiveDepthBuffer(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || s_activeWriteSlot < 0 || s_activeWriteSlot >= QCB_FRAME_SLOTS) {
        return NULL;
    }
    return (float *)((uint8_t *)shm + shm->header.frames[s_activeWriteSlot].depthOffset);
}

uint8_t *QCB_GetActiveOverlayRgbaBuffer(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || s_activeWriteSlot < 0 || s_activeWriteSlot >= QCB_FRAME_SLOTS) {
        return NULL;
    }
    return (uint8_t *)shm + shm->header.frames[s_activeWriteSlot].hudOffset;
}

void QCB_ConvertWindowDepthToLinearQ3Units(int width, int height, float zNear, float zFar) {
    float *depthBuf = QCB_GetActiveDepthBuffer();
    if (!depthBuf || width <= 0 || height <= 0) {
        return;
    }
    if (zNear <= 0.01f) zNear = 4.0f;
    if (zFar <= zNear + 1.0f) zFar = 4096.0f;

    const float twoNearFar = 2.0f * zNear * zFar;
    const float sumNearFar = zFar + zNear;
    const float diffNearFar = zFar - zNear;
    const int totalPixels = width * height;
    uint32_t *u = (uint32_t *)depthBuf;
    int i;

    for (i = 0; i < totalPixels; i++) {
        /* In IEEE 754 float, 1.0f is 0x3F800000. Any value >= 0.99999f (0x3F7FFFE0) is sky */
        if (u[i] >= 0x3F7FFFE0U) {
            depthBuf[i] = 0.0f; /* Sky / background sentinel (no Q3 geometry) */
        } else {
            float zw = depthBuf[i];
            float ndcZ = 2.0f * zw - 1.0f;
            float denom = sumNearFar - ndcZ * diffNearFar;
            depthBuf[i] = (denom > 0.0001f) ? (twoNearFar / denom) : 0.0f;
        }
    }
}

void QCB_PublishCapturedFrame(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || s_activeWriteSlot < 0 || s_activeWriteSlot >= QCB_FRAME_SLOTS) {
        return;
    }
    QcbFrameSlotDesc *desc = &shm->header.frames[s_activeWriteSlot];
    s_frameCounter++;
    desc->frameNumber = s_frameCounter;
    desc->timestampNs = QCB_NowNs();
    MemoryBarrier();
    desc->seq = (desc->seq + 1U) & ~1U;
    shm->header.latestFrameSlot = (uint32_t)s_activeWriteSlot;
    shm->header.q3HeartbeatNs = desc->timestampNs;
    MemoryBarrier();
    s_activeWriteSlot = -1;
}

/* ============================================================================
 * Stage 4: Two-Way Combat, Proxy Entities & World Events
 * ============================================================================ */

void QCB_PushEventToMc(const QcbEvent *ev) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || !ev) {
        return;
    }
    uint32_t w = shm->q3ToMcEvents.writeIdx;
    uint32_t r = shm->q3ToMcEvents.readIdx;
    if (w - r >= QCB_MAX_EVENTS) {
        /* Ring full: advance readIdx to overwrite oldest */
        shm->q3ToMcEvents.readIdx = w - QCB_MAX_EVENTS + 1U;
    }
    uint32_t slot = w & (QCB_MAX_EVENTS - 1U);
    shm->q3ToMcEvents.events[slot] = *ev;
    shm->q3ToMcEvents.events[slot].timestampNs = QCB_NowNs();
    MemoryBarrier();
    shm->q3ToMcEvents.writeIdx = w + 1U;
    MemoryBarrier();
}

qboolean QCB_PopEventFromMc(QcbEvent *outEv) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || !outEv) {
        return qfalse;
    }
    uint32_t r = shm->mcToQ3Events.readIdx;
    uint32_t w = shm->mcToQ3Events.writeIdx;
    if (r == w) {
        return qfalse;
    }
    MemoryBarrier();
    uint32_t slot = r & (QCB_MAX_EVENTS - 1U);
    *outEv = shm->mcToQ3Events.events[slot];
    MemoryBarrier();
    shm->mcToQ3Events.readIdx = r + 1U;
    return qtrue;
}

void QCB_EmitDamageEntity(
    int mcEntityId,
    int damageQ3,
    int weaponId,
    int isGib,
    const float knockbackQ3[3],
    const float hitPosQ3[3]
) {
    QcbEvent ev;
    memset(&ev, 0, sizeof(ev));
    ev.type        = QCB_EV_DAMAGE_ENTITY;
    ev.targetId    = mcEntityId;
    ev.intParam1   = damageQ3;
    ev.intParam2   = weaponId;
    ev.intParam3   = isGib;
    ev.floatParam1 = 0.0f;
    if (knockbackQ3) {
        ev.vecA[0] = knockbackQ3[0];
        ev.vecA[1] = knockbackQ3[1];
        ev.vecA[2] = knockbackQ3[2];
    }
    if (hitPosQ3) {
        ev.vecB[0] = hitPosQ3[0];
        ev.vecB[1] = hitPosQ3[1];
        ev.vecB[2] = hitPosQ3[2];
    }
    QCB_PushEventToMc(&ev);
}

void QCB_EmitExplosion(int weaponId, float radiusQ3, const float posQ3[3]) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || !posQ3) {
        return;
    }
    float anchorX = shm->voxels.anchorMcX;
    float anchorY = shm->voxels.anchorMcY;
    float anchorZ = shm->voxels.anchorMcZ;

    int bx = (int)floorf(anchorX + posQ3[0] * QCB_BLOCKS_PER_UNIT);
    int by = (int)floorf(anchorY + posQ3[2] * QCB_BLOCKS_PER_UNIT);
    int bz = (int)floorf(anchorZ - posQ3[1] * QCB_BLOCKS_PER_UNIT);

    QcbEvent ev;
    memset(&ev, 0, sizeof(ev));
    ev.type        = QCB_EV_EXPLOSION;
    ev.targetId    = weaponId;
    ev.intParam1   = bx;
    ev.intParam2   = by;
    ev.intParam3   = bz;
    ev.floatParam1 = radiusQ3;
    ev.vecA[0]     = posQ3[0];
    ev.vecA[1]     = posQ3[1];
    ev.vecA[2]     = posQ3[2];
    QCB_PushEventToMc(&ev);
}

void QCB_EmitBreakBlock(int weaponId, const float hitPosQ3[3], const float normalQ3[3]) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || !hitPosQ3) {
        return;
    }
    float probe[3];
    probe[0] = hitPosQ3[0];
    probe[1] = hitPosQ3[1];
    probe[2] = hitPosQ3[2];
    if (normalQ3) {
        /* Step 4 Q3 units inside the hit block along -normal */
        probe[0] -= normalQ3[0] * 4.0f;
        probe[1] -= normalQ3[1] * 4.0f;
        probe[2] -= normalQ3[2] * 4.0f;
    }

    float anchorX = shm->voxels.anchorMcX;
    float anchorY = shm->voxels.anchorMcY;
    float anchorZ = shm->voxels.anchorMcZ;

    int bx = (int)floorf(anchorX + probe[0] * QCB_BLOCKS_PER_UNIT);
    int by = (int)floorf(anchorY + probe[2] * QCB_BLOCKS_PER_UNIT);
    int bz = (int)floorf(anchorZ - probe[1] * QCB_BLOCKS_PER_UNIT);

    QcbEvent ev;
    memset(&ev, 0, sizeof(ev));
    ev.type        = QCB_EV_BREAK_BLOCK;
    ev.targetId    = weaponId;
    ev.intParam1   = bx;
    ev.intParam2   = by;
    ev.intParam3   = bz;
    ev.vecA[0]     = probe[0];
    ev.vecA[1]     = probe[1];
    ev.vecA[2]     = probe[2];
    QCB_PushEventToMc(&ev);
}

qboolean QCB_ShouldPlayAudio(void) {
    QcbSharedMemory *shm = QCB_GetShm();
    if (!shm || shm->header.magic != QCB_MAGIC) {
        return qtrue;
    }
    if (!(shm->header.input.flags & QCB_INPUT_PASSTHROUGH_ON)) {
        return qfalse;
    }
    uint64_t now = QCB_NowNs();
    uint64_t hb  = shm->header.mcHeartbeatNs;
    if (hb == 0 || (now > hb && (now - hb) > 500000000ULL)) {
        return qfalse;
    }
    return qtrue;
}


