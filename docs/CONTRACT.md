# Bridge Contract: Quake III Arena ↔ Minecraft Java 26.3 (`QuakeCraft`)

- **Route:** Live Two-Process Passthrough + Depth-Aware Frame Compositing (`choosing-a-mashup-route.md`: Route 1 + Route 2)
- **Host (World & Entities):** Minecraft Java Edition `26.3` (unobfuscated Mojang names), LWJGL `3.4.3`, Fabric Loader `0.19.5`, Fabric API `0.162.0+26.3`, running on JDK `25.0.4` (`C:\Program Files\Java\jdk-25.0.4`).
- **Guest (Player Physics, Weapons, Combat, Audio & Frame Producer):** Quake III Arena (`id Tech 3`, GPL source `Sources/Quake-III-Arena-master`, compiled with `w64devkit` + `SDL2-2.32.10` as `quake3-bridge.exe`), loading user's owned `pak0.pk3` + `pak1.pk3` from `Sources/QUAKE3/baseq3`.
- **OS:** Windows x64 (`127.0.0.1` / Win32 Named Shared Memory).

---

## 1. Ownership Table

| System | Owner (Authority) | Hand-over Trigger | How Control Returns |
| :--- | :--- | :--- | :--- |
| **Player Movement & Physics** | **Quake 3 (`Pmove` in `bg_pmove.c`)** | Minecraft GUI open (`Minecraft.screen != null`), sleeping, riding vehicle/mount, spectator noclip, or explicit respawn/teleport in MC | When GUI closes or teleport completes, MC increments `teleportSeq` and sets `anchorPos` + `mcResetPos`; Q3 snaps `ps->origin` to the new position |
| **Camera & View Angles** | **Quake 3 (`cl.viewangles` / `cg.refdef`)** | Minecraft GUI / pause menu open | Immediately when `Minecraft.screen == null` |
| **World Collision (MC → Q3)** | **Minecraft (`Level`)** | Streamed continuously as a $64 \times 64 \times 64$ voxel cube centered on `voxelOrigin` | `CM_BoxTrace` and `CM_PointContents` in Q3 test swept AABBs against the voxel grid |
| **World Edits / Block Damage (Q3 → MC)** | **Quake 3 weapons → Minecraft Server** | Gauntlet drilling blocks, Rocket/Grenade/BFG explosions, Plasma/Railgun block impacts | Q3 writes `Q3BlockEvent` to ring buffer; MC server thread breaks/explodes blocks with native drops |
| **NPCs / Mobs** | **Minecraft (`ServerLevel`)** | Up to 128 nearby `LivingEntity` instances exported to `ActorTable` each tick | Q3 spawns matching `CONTENTS_BODY` proxy entities so hitscan, projectiles, and splash damage hit them |
| **Combat, Weapons & Projectiles** | **Quake 3 (`g_weapon.c`, `g_missile.c`)** | Switching to Minecraft build/use mode (`MODE_MC_INTERACT`) | Switching back to Quake weapon mode (`MODE_Q3_COMBAT`) or pressing weapon bind |
| **Health & Armor** | **Quake 3 (`STAT_HEALTH`, `STAT_ARMOR`)** | MC mob/hazard hits forwarded via `McHitEvent` to Q3 (`G_Damage` applies 66% armor absorption) | Q3 health ($100\text{ HP}$) mirrored to MC player health ($20\text{ HP}$, $\times 0.2$ scale); Q3 death triggers MC player death |
| **Menus / Inventory / Saves** | **Minecraft** | Pressing `E` (Inventory), `Esc` (Pause), or Right-Click on interactive MC blocks (chests, doors, crafting tables) | Closing the screen returns raw mouse/keyboard capture to Q3 passthrough |

---

## 2. Units, Axes, and Coordinate Mapping

- **Documented Quake 3 units (`code/game/bg_public.h`):**
  - Player bounding box: `MINS = (-15, -15, -24)`, `MAXS = (+15, +15, +32)` (standing height = $56$ units; crouch `CROUCH_MAXS_Z = 16`, height = $40$ units).
  - Eye height: `DEFAULT_VIEWHEIGHT = 26` ($50$ units above feet `origin.z - 24`).
  - Step height: `STEPSIZE = 18` units.
  - Gravity: `DEFAULT_GRAVITY = 800` units/$\text{s}^2$; Jump velocity: `JUMP_VELOCITY = 270` units/s ($\approx 45.5$ units jump height).
- **Documented Minecraft units:**
  - $1\text{ block} = 1\text{ meter}$.
  - Standing player height = $1.8\text{ blocks}$, width = $0.6\text{ blocks}$, eye height = $1.62\text{ blocks}$, slab height = $0.5\text{ blocks}$.
- **Chosen Scale (`UNITS_PER_BLOCK = 32.0`):**
  - $32\text{ Quake units} = 1\text{ Minecraft block (1 m)}$.
  - Quake standing height: $56 / 32 = 1.75\text{ blocks}$ (clears 2-block doorways).
  - Horizontal collision box in passthrough mode: `mins = (-9.5, -9.5, -24)`, `maxs = (+9.5, +9.5, +32)` ($19 / 32 = 0.59375\text{ blocks}$ wide, matching Steve's $0.6\text{ block}$ width so 1-block corridors and doorways work smoothly).
  - Quake `STEPSIZE`: $18 / 32 = 0.5625\text{ blocks}$ (automatically steps up $0.5\text{-block}$ slabs and stairs; requires jumping for $1.0\text{-block}$ full blocks).
  - Quake jump height: $45.5 / 32 = 1.42\text{ blocks}$ (clears 1-block ledges easily).
- **Right-Handed Coordinate Bijection (Determinant $= +1$, no handedness flip):**
  - Quake 3: $+X$ East, $+Y$ North, $+Z$ Up.
  - Minecraft: $+X$ East, $+Y$ Up, $+Z$ South.
  - Relative to session origin anchor $(O_x, O_y, O_z)$ in Minecraft block coordinates:
    $$M_x = O_x + \frac{Q_x}{32.0}, \qquad M_y = O_y + \frac{Q_z + 24.0}{32.0}, \qquad M_z = O_z - \frac{Q_y}{32.0}$$
    $$Q_x = 32.0 \cdot (M_x - O_x), \qquad Q_y = -32.0 \cdot (M_z - O_z), \qquad Q_z = 32.0 \cdot (M_y - O_y) - 24.0$$
- **View Angles Mapping:**
  - Quake 3 yaw $0^\circ$ is $+X$ (East), $+90^\circ$ is $+Y$ (North, CCW from above).
  - Minecraft yaw $0^\circ$ is $+Z$ (South), $-90^\circ$ is $+X$ (East), $+180^\circ$ is $-Z$ (North).
  - Therefore:
    $$Q_{\text{yaw}} = -M_{\text{yaw}} - 90.0^\circ, \qquad M_{\text{yaw}} = -Q_{\text{yaw}} - 90.0^\circ, \qquad Q_{\text{pitch}} = M_{\text{pitch}}$$

---

## 3. Shared Memory Channels (`Local\QuakeCraftBridgeV1`)

- **Magic:** `0x51434252` (`"QCBR"`), **Version:** `1`, **Byte Order:** Little-Endian (`x86_64`).
- **Compile-time layout verification:** Static assertions in C (`_Static_assert`) and offset verification test in Java (`BridgeProtocolTest`).

| Region | Kind | Direction | Rate | Policy When Full / Late |
| :--- | :--- | :--- | :--- | :--- |
| **`BridgeHeader`** (`0..4095`) | Seqlock state + heartbeats | Bidirectional | Every tick / frame | Seqlock odd while writing, even when complete; reader retries up to 3 times |
| **`PlayerStateSnapshot`** | Seqlock snapshot | Q3 → MC | 125 Hz (Q3 frame rate) | Latest pose wins; includes `origin`, `velocity`, `viewangles`, `health`, `armor`, `weapon`, `ammo[16]`, `powerups` |
| **`InputSnapshot`** | Seqlock snapshot | MC → Q3 | Every frame / poll | Carries `forwardmove`, `rightmove`, `upmove`, `buttons`, `weaponSelect`, `deltaYaw`, `deltaPitch`, `TeleportEvent` |
| **`VoxelGrid`** ($64\times 64\times 64$) | Seqlock grid (`262,144` B) | MC → Q3 | On chunk/block change or player move $\ge 8$ blocks | Centered on `(voxelOriginX, voxelOriginY, voxelOriginZ)`; unreceived regions treated as solid floor safety guard |
| **`ActorTable`** ($128$ slots) | Seqlock table | MC → Q3 | 20 Hz (MC tick) + client interp | Each slot holds `(entityId, generation, q3_x, q3_y, q3_z, halfWidth, height, health, maxHealth, flags)` |
| **`Q3ToMcEventRing`** ($64$ slots) | Bounded ring (`head`/`tail`) | Q3 → MC | Immediate on hit / explosion | Carries `EVENT_DAMAGE_ENTITY`, `EVENT_EXPLOSION`, `EVENT_BREAK_BLOCK`, `EVENT_PICKUP`; drops oldest only if $>64$ unconsumed |
| **`McToQ3EventRing`** ($64$ slots) | Bounded ring (`head`/`tail`) | MC → Q3 | Immediate on MC hit / pickup | Carries `EVENT_PLAYER_HURT`, `EVENT_GIVE_ITEM`, `EVENT_GIVE_AMMO`, `EVENT_TELEPORT` |
| **`FrameSlots[3]`** ($1920\times 1080$ max) | Triple-buffered seqlock slots | Q3 → MC | 60–125 FPS | Each slot contains `RGBA8` World Layer + `R32F` Linear Depth (meters) + `RGBA8` Weapon/HUD Overlay + exact render pose |

---

## 4. Lifecycle & Watchdog

1. **Start order:** Either process may start first. Whichever starts first creates or opens `Local\QuakeCraftBridgeV1`.
2. **Stale / Pause guard:** Both sides write `QueryPerformanceCounter` timestamps (nanoseconds). If `quake3-bridge.exe` is paused or disconnected ($> 250\text{ ms}$ without heartbeat), Minecraft fails closed to normal vanilla rendering and movement without ghost frames.
3. **Clean shutdown:** Killing or closing either game releases the mapping handle cleanly.
