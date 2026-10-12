/*
 * ============================================================================
 * QuakeCraft Bridge — Quake 3 Engine & Game Module Interface (q3_bridge.h)
 * ============================================================================
 */
#ifndef Q3_BRIDGE_H
#define Q3_BRIDGE_H

#include "../game/q_shared.h"
#include "../../../../protocol/bridge_protocol.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Attach or create the Local\QuakeCraftBridgeV1 shared memory segment */
void QCB_Init(void);
void QCB_Shutdown(void);

/* Access shared memory pointer (lazy-initializes if called from DLL) */
QcbSharedMemory *QCB_GetShm(void);

/* Returns qtrue if shared memory is mapped and bridge is active */
qboolean QCB_IsActive(void);

/* Returns qtrue if shared memory has a valid voxel grid (voxels.seq > 0) */
qboolean QCB_HasVoxels(void);

/* Update engine heartbeat and process per-frame bridge state */
void QCB_Frame(void);

/* Swept AABB trace against the 64x64x64 Minecraft voxel grid */
void QCB_VoxelTrace(trace_t *results, const vec3_t start, const vec3_t end,
                    const vec3_t mins, const vec3_t maxs, int brushmask);

/* Point contents query against the 64x64x64 Minecraft voxel grid */
int QCB_VoxelPointContents(const vec3_t p);

/* Override client usercmd_t and viewangles from Minecraft input */
void QCB_ApplyClientInput(usercmd_t *cmd, vec3_t viewangles, int *cgameWeaponSelect);

/* Publish authoritative playerState_t from qagame/server to shared memory */
void QCB_PublishPlayerPose(const playerState_t *ps);

/* Check and apply any pending teleport from Minecraft to playerState_t */
qboolean QCB_CheckPendingTeleport(vec3_t outOrigin, float *outYaw, uint32_t *outSeq);
void QCB_AckTeleport(uint32_t seq);

/* High-resolution monotonic clock in nanoseconds */
uint64_t QCB_NowNs(void);

#ifdef __cplusplus
}
#endif

#endif /* Q3_BRIDGE_H */
