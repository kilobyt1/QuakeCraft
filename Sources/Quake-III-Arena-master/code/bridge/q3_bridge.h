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

/* Frame capture & depth export (Stage 3) */
void QCB_GetCaptureDimensions(int vidWidth, int vidHeight, int *outW, int *outH);
void QCB_BeginFrameCapture(int width, int height, float zNear, float zFar, float fovX, float fovY, const float viewOrg[3], const float viewAngles[3]);
uint8_t *QCB_GetActiveWorldRgbaBuffer(void);
float   *QCB_GetActiveDepthBuffer(void);
uint8_t *QCB_GetActiveOverlayRgbaBuffer(void);
void QCB_ConvertWindowDepthToLinearQ3Units(int width, int height, float zNear, float zFar);
void QCB_PublishCapturedFrame(void);

/* Stage 4: Two-Way Combat, Proxy Entities & World Events */
#define QCB_PROXY_BASE_ENTNUM   512

void QCB_PushEventToMc(const QcbEvent *ev);
qboolean QCB_PopEventFromMc(QcbEvent *outEv);
void QCB_EmitDamageEntity(int mcEntityId, int damageQ3, int weaponId, int isGib, const float knockbackQ3[3], const float hitPosQ3[3]);
void QCB_EmitExplosion(int weaponId, float radiusQ3, const float posQ3[3]);
void QCB_EmitBreakBlock(int weaponId, const float hitPosQ3[3], const float normalQ3[3]);

#ifdef __cplusplus
}
#endif

#endif /* Q3_BRIDGE_H */
