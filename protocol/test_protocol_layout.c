#include <stdio.h>
#include <stddef.h>
#include <string.h>
#include <windows.h>
#include "bridge_protocol.h"

int main(int argc, char **argv) {
    printf("QuakeCraft Bridge Protocol v%u layout check PASSED!\n", QCB_VERSION);
    printf("  Magic           : 0x%08X\n", QCB_MAGIC);
    printf("  Total SHM Bytes : %u (%.2f MB)\n", QCB_TOTAL_SHM_BYTES, (double)QCB_TOTAL_SHM_BYTES / (1024.0 * 1024.0));
    printf("  Offset Pose     : %zu\n", offsetof(QcbSharedMemory, header.pose));
    printf("  Offset Input    : %zu\n", offsetof(QcbSharedMemory, header.input));
    printf("  Offset Frames   : %zu\n", offsetof(QcbSharedMemory, header.frames));
    printf("  Offset Voxels   : %zu\n", offsetof(QcbSharedMemory, voxels));
    printf("  Offset Actors   : %zu\n", offsetof(QcbSharedMemory, actors));
    printf("  Offset Q3->MC   : %zu\n", offsetof(QcbSharedMemory, q3ToMcEvents));
    printf("  Offset MC->Q3   : %zu\n", offsetof(QcbSharedMemory, mcToQ3Events));
    printf("  Offset Pixels   : %zu\n", offsetof(QcbSharedMemory, frameBytes));

    if (argc > 1 && strcmp(argv[1], "--verify-shm") == 0) {
        const wchar_t *mapName = L"Local\\QuakeCraftBridgeV1_Test";
        HANDLE hMap = CreateFileMappingW(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE, 0, QCB_TOTAL_SHM_BYTES, mapName);
        if (!hMap) {
            fprintf(stderr, "CreateFileMappingW failed: %lu\n", GetLastError());
            return 1;
        }
        QcbSharedMemory *shm = (QcbSharedMemory *)MapViewOfFile(hMap, FILE_MAP_ALL_ACCESS, 0, 0, QCB_TOTAL_SHM_BYTES);
        if (!shm) {
            fprintf(stderr, "MapViewOfFile failed: %lu\n", GetLastError());
            CloseHandle(hMap);
            return 1;
        }
        if (shm->header.magic != QCB_MAGIC || shm->header.version != QCB_VERSION) {
            fprintf(stderr, "Unexpected magic/version from Java: 0x%08X v%u\n", shm->header.magic, shm->header.version);
            return 1;
        }
        if (shm->header.input.forwardmove != 127 || shm->header.input.upmove != 127 || shm->header.input.buttons != 1024U) {
            fprintf(stderr, "Unexpected input from Java: fwd=%d up=%d buttons=%u\n",
                shm->header.input.forwardmove, shm->header.input.upmove, shm->header.input.buttons);
            return 1;
        }
        if (shm->voxels.originBlockX != 100 || shm->voxels.originBlockY != 64 || shm->voxels.originBlockZ != -200 ||
            shm->voxels.cells[32 * 4096 + 32 * 64 + 32] != QCB_VOXEL_SOLID) {
            fprintf(stderr, "Unexpected voxel state from Java\n");
            return 1;
        }
        /* Write C pose back to Java */
        shm->header.pose.seq = 1;
        MemoryBarrier();
        shm->header.pose.q3Origin[0] = 64.0f;
        shm->header.pose.q3Origin[1] = -96.0f;
        shm->header.pose.q3Origin[2] = 56.0f;
        shm->header.pose.health = 125;
        shm->header.pose.armor = 100;
        shm->header.pose.weapon = 5; /* WP_ROCKET_LAUNCHER */
        shm->header.pose.currentAmmo = 25;
        MemoryBarrier();
        shm->header.pose.seq = 2;
        printf("C<->Java Shared Memory verification PASSED!\n");
        UnmapViewOfFile(shm);
        CloseHandle(hMap);
    }
    return 0;
}
