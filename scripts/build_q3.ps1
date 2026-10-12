# QuakeCraft — Build Quake 3 Engine (quake3-bridge.exe) and Native Game Modules (qagamex86.dll, cgamex86.dll, uix86.dll)
# Strictly isolated inside C:\QuakeCraft\build\q3

$ErrorActionPreference = "Stop"
. "C:\QuakeCraft\scripts\env.ps1"

$CodeDir = "C:\QuakeCraft\Sources\Quake-III-Arena-master\code"
$OutDir  = "C:\QuakeCraft\build\q3"
$ObjDir  = "$OutDir\obj"
$HomeBaseQ3 = "C:\QuakeCraft\lab\q3home\baseq3"

New-Item -ItemType Directory -Force -Path "$ObjDir\engine", "$ObjDir\qagame", "$ObjDir\cgame", "$ObjDir\ui", "$OutDir\baseq3", $HomeBaseQ3 | Out-Null

$CommonFlags = @(
    "-std=gnu99",
    "-O2",
    "-fcommon",
    "-w",
    "-Wno-error=incompatible-pointer-types",
    "-Wno-error=int-conversion",
    "-Wno-error=implicit-function-declaration",
    "-DWIN32",
    "-D_WIN32",
    "-DNDEBUG",
    "-D_WINDOWS",
    "-DC_ONLY",
    "-I$CodeDir/game",
    "-I$CodeDir/qcommon",
    "-I$CodeDir/renderer",
    "-I$CodeDir/client",
    "-I$CodeDir/server",
    "-I$CodeDir/botlib",
    "-I$CodeDir/jpeg-6",
    "-I$CodeDir/win32",
    "-IC:/QuakeCraft/protocol"
)

function Compile-Objects {
    param(
        [string]$TargetName,
        [string[]]$Sources,
        [string[]]$ExtraFlags = @()
    )
    $objs = [System.Collections.Generic.List[string]]::new()
    $targetObjDir = "$ObjDir\$TargetName"
    $recompiled = 0
    foreach ($src in $Sources) {
        $base = [System.IO.Path]::GetFileNameWithoutExtension($src)
        $parent = [System.IO.Path]::GetFileName([System.IO.Path]::GetDirectoryName($src))
        $obj = "$targetObjDir\${parent}_${base}.o"
        $needBuild = $true
        if (Test-Path $obj) {
            $srcTime = (Get-Item $src).LastWriteTimeUtc
            $objTime = (Get-Item $obj).LastWriteTimeUtc
            if ($objTime -ge $srcTime) {
                $needBuild = $false
            }
        }
        if ($needBuild) {
            & gcc @CommonFlags @ExtraFlags -c $src -o $obj
            if ($LASTEXITCODE -ne 0) {
                throw "Failed compiling $src for $TargetName"
            }
            $recompiled++
        }
        $objs.Add($obj)
    }
    Write-Host "[$TargetName] $($Sources.Count) objects ready ($recompiled compiled)." -ForegroundColor Cyan
    return ,$objs.ToArray()
}

# 1. Engine (quake3-bridge.exe)
$EngineSources = @(
    "$CodeDir/client/cl_cgame.c",
    "$CodeDir/client/cl_cin.c",
    "$CodeDir/client/cl_console.c",
    "$CodeDir/client/cl_input.c",
    "$CodeDir/client/cl_keys.c",
    "$CodeDir/client/cl_main.c",
    "$CodeDir/client/cl_net_chan.c",
    "$CodeDir/client/cl_parse.c",
    "$CodeDir/client/cl_scrn.c",
    "$CodeDir/client/cl_ui.c",
    "$CodeDir/client/snd_adpcm.c",
    "$CodeDir/client/snd_dma.c",
    "$CodeDir/client/snd_mem.c",
    "$CodeDir/client/snd_mix.c",
    "$CodeDir/client/snd_wavelet.c",

    "$CodeDir/server/sv_bot.c",
    "$CodeDir/server/sv_ccmds.c",
    "$CodeDir/server/sv_client.c",
    "$CodeDir/server/sv_game.c",
    "$CodeDir/server/sv_init.c",
    "$CodeDir/server/sv_main.c",
    "$CodeDir/server/sv_net_chan.c",
    "$CodeDir/server/sv_snapshot.c",
    "$CodeDir/server/sv_world.c",

    "$CodeDir/qcommon/cm_load.c",
    "$CodeDir/qcommon/cm_patch.c",
    "$CodeDir/qcommon/cm_polylib.c",
    "$CodeDir/qcommon/cm_test.c",
    "$CodeDir/qcommon/cm_trace.c",
    "$CodeDir/qcommon/cmd.c",
    "$CodeDir/qcommon/common.c",
    "$CodeDir/qcommon/cvar.c",
    "$CodeDir/qcommon/files.c",
    "$CodeDir/qcommon/huffman.c",
    "$CodeDir/qcommon/md4.c",
    "$CodeDir/qcommon/msg.c",
    "$CodeDir/qcommon/net_chan.c",
    "$CodeDir/qcommon/unzip.c",
    "$CodeDir/qcommon/vm.c",
    "$CodeDir/qcommon/vm_interpreted.c",

    "$CodeDir/game/q_math.c",
    "$CodeDir/game/q_shared.c",

    "$CodeDir/botlib/be_aas_bspq3.c",
    "$CodeDir/botlib/be_aas_cluster.c",
    "$CodeDir/botlib/be_aas_debug.c",
    "$CodeDir/botlib/be_aas_entity.c",
    "$CodeDir/botlib/be_aas_file.c",
    "$CodeDir/botlib/be_aas_main.c",
    "$CodeDir/botlib/be_aas_move.c",
    "$CodeDir/botlib/be_aas_optimize.c",
    "$CodeDir/botlib/be_aas_reach.c",
    "$CodeDir/botlib/be_aas_route.c",
    "$CodeDir/botlib/be_aas_routealt.c",
    "$CodeDir/botlib/be_aas_sample.c",
    "$CodeDir/botlib/be_ai_char.c",
    "$CodeDir/botlib/be_ai_chat.c",
    "$CodeDir/botlib/be_ai_gen.c",
    "$CodeDir/botlib/be_ai_goal.c",
    "$CodeDir/botlib/be_ai_move.c",
    "$CodeDir/botlib/be_ai_weap.c",
    "$CodeDir/botlib/be_ai_weight.c",
    "$CodeDir/botlib/be_ea.c",
    "$CodeDir/botlib/be_interface.c",
    "$CodeDir/botlib/l_crc.c",
    "$CodeDir/botlib/l_libvar.c",
    "$CodeDir/botlib/l_log.c",
    "$CodeDir/botlib/l_memory.c",
    "$CodeDir/botlib/l_precomp.c",
    "$CodeDir/botlib/l_script.c",
    "$CodeDir/botlib/l_struct.c",

    "$CodeDir/jpeg-6/jcapimin.c",
    "$CodeDir/jpeg-6/jccoefct.c",
    "$CodeDir/jpeg-6/jccolor.c",
    "$CodeDir/jpeg-6/jcdctmgr.c",
    "$CodeDir/jpeg-6/jchuff.c",
    "$CodeDir/jpeg-6/jcinit.c",
    "$CodeDir/jpeg-6/jcmainct.c",
    "$CodeDir/jpeg-6/jcmarker.c",
    "$CodeDir/jpeg-6/jcmaster.c",
    "$CodeDir/jpeg-6/jcomapi.c",
    "$CodeDir/jpeg-6/jcparam.c",
    "$CodeDir/jpeg-6/jcphuff.c",
    "$CodeDir/jpeg-6/jcprepct.c",
    "$CodeDir/jpeg-6/jcsample.c",
    "$CodeDir/jpeg-6/jdapimin.c",
    "$CodeDir/jpeg-6/jdapistd.c",
    "$CodeDir/jpeg-6/jdatasrc.c",
    "$CodeDir/jpeg-6/jdcoefct.c",
    "$CodeDir/jpeg-6/jdcolor.c",
    "$CodeDir/jpeg-6/jddctmgr.c",
    "$CodeDir/jpeg-6/jdhuff.c",
    "$CodeDir/jpeg-6/jdinput.c",
    "$CodeDir/jpeg-6/jdmainct.c",
    "$CodeDir/jpeg-6/jdmarker.c",
    "$CodeDir/jpeg-6/jdmaster.c",
    "$CodeDir/jpeg-6/jdpostct.c",
    "$CodeDir/jpeg-6/jdsample.c",
    "$CodeDir/jpeg-6/jdtrans.c",
    "$CodeDir/jpeg-6/jerror.c",
    "$CodeDir/jpeg-6/jfdctflt.c",
    "$CodeDir/jpeg-6/jidctflt.c",
    "$CodeDir/jpeg-6/jmemmgr.c",
    "$CodeDir/jpeg-6/jmemnobs.c",
    "$CodeDir/jpeg-6/jutils.c",

    "$CodeDir/renderer/tr_animation.c",
    "$CodeDir/renderer/tr_backend.c",
    "$CodeDir/renderer/tr_bsp.c",
    "$CodeDir/renderer/tr_cmds.c",
    "$CodeDir/renderer/tr_curve.c",
    "$CodeDir/renderer/tr_flares.c",
    "$CodeDir/renderer/tr_font.c",
    "$CodeDir/renderer/tr_image.c",
    "$CodeDir/renderer/tr_init.c",
    "$CodeDir/renderer/tr_light.c",
    "$CodeDir/renderer/tr_main.c",
    "$CodeDir/renderer/tr_marks.c",
    "$CodeDir/renderer/tr_mesh.c",
    "$CodeDir/renderer/tr_model.c",
    "$CodeDir/renderer/tr_noise.c",
    "$CodeDir/renderer/tr_scene.c",
    "$CodeDir/renderer/tr_shade.c",
    "$CodeDir/renderer/tr_shade_calc.c",
    "$CodeDir/renderer/tr_shader.c",
    "$CodeDir/renderer/tr_shadows.c",
    "$CodeDir/renderer/tr_sky.c",
    "$CodeDir/renderer/tr_surface.c",
    "$CodeDir/renderer/tr_world.c",

    "$CodeDir/win32/win_gamma.c",
    "$CodeDir/win32/win_glimp.c",
    "$CodeDir/win32/win_input.c",
    "$CodeDir/win32/win_main.c",
    "$CodeDir/win32/win_net.c",
    "$CodeDir/win32/win_qgl.c",
    "$CodeDir/win32/win_shared.c",
    "$CodeDir/win32/win_snd.c",
    "$CodeDir/win32/win_syscon.c",
    "$CodeDir/win32/win_wndproc.c"
)

if (Test-Path "$CodeDir/bridge/q3_bridge.c") {
    $EngineSources += "$CodeDir/bridge/q3_bridge.c"
}

$EngineObjs = Compile-Objects -TargetName "engine" -Sources $EngineSources -ExtraFlags @("-DBOTLIB")
$ExePath = "$OutDir\quake3-bridge.exe"
& gcc @EngineObjs -o $ExePath -lwinmm -lwsock32 -lws2_32 -lgdi32 -lole32 -ladvapi32 -luser32 -ldinput -ldxguid -mwindows
if ($LASTEXITCODE -ne 0) {
    throw "Failed linking $ExePath"
}
Write-Host "Linked $ExePath ($((Get-Item $ExePath).Length) bytes)" -ForegroundColor Green

# 2. Server Game Module (qagamex86.dll)
$QaGameSources = @(
    "$CodeDir/game/ai_chat.c",
    "$CodeDir/game/ai_cmd.c",
    "$CodeDir/game/ai_dmnet.c",
    "$CodeDir/game/ai_dmq3.c",
    "$CodeDir/game/ai_main.c",
    "$CodeDir/game/ai_team.c",
    "$CodeDir/game/ai_vcmd.c",
    "$CodeDir/game/bg_misc.c",
    "$CodeDir/game/bg_pmove.c",
    "$CodeDir/game/bg_slidemove.c",
    "$CodeDir/game/g_active.c",
    "$CodeDir/game/g_arenas.c",
    "$CodeDir/game/g_bot.c",
    "$CodeDir/game/g_client.c",
    "$CodeDir/game/g_cmds.c",
    "$CodeDir/game/g_combat.c",
    "$CodeDir/game/g_items.c",
    "$CodeDir/game/g_main.c",
    "$CodeDir/game/g_mem.c",
    "$CodeDir/game/g_misc.c",
    "$CodeDir/game/g_missile.c",
    "$CodeDir/game/g_mover.c",
    "$CodeDir/game/g_session.c",
    "$CodeDir/game/g_spawn.c",
    "$CodeDir/game/g_svcmds.c",
    "$CodeDir/game/g_syscalls.c",
    "$CodeDir/game/g_target.c",
    "$CodeDir/game/g_team.c",
    "$CodeDir/game/g_trigger.c",
    "$CodeDir/game/g_utils.c",
    "$CodeDir/game/g_weapon.c",
    "$CodeDir/game/q_math.c",
    "$CodeDir/game/q_shared.c"
)
$QaGameObjs = Compile-Objects -TargetName "qagame" -Sources $QaGameSources
$QaGameDll = "$OutDir\baseq3\qagamex86.dll"
& gcc -shared "-Wl,--export-all-symbols" @QaGameObjs -o $QaGameDll
if ($LASTEXITCODE -ne 0) {
    throw "Failed linking $QaGameDll"
}
Copy-Item -Force $QaGameDll "$OutDir\qagamex86.dll"
Copy-Item -Force $QaGameDll "$HomeBaseQ3\qagamex86.dll"
Write-Host "Linked $QaGameDll ($((Get-Item $QaGameDll).Length) bytes)" -ForegroundColor Green

# 3. Client Game Module (cgamex86.dll)
$CGameSources = @(
    "$CodeDir/game/bg_misc.c",
    "$CodeDir/game/bg_pmove.c",
    "$CodeDir/game/bg_slidemove.c",
    "$CodeDir/cgame/cg_consolecmds.c",
    "$CodeDir/cgame/cg_draw.c",
    "$CodeDir/cgame/cg_drawtools.c",
    "$CodeDir/cgame/cg_effects.c",
    "$CodeDir/cgame/cg_ents.c",
    "$CodeDir/cgame/cg_event.c",
    "$CodeDir/cgame/cg_info.c",
    "$CodeDir/cgame/cg_localents.c",
    "$CodeDir/cgame/cg_main.c",
    "$CodeDir/cgame/cg_marks.c",
    "$CodeDir/cgame/cg_players.c",
    "$CodeDir/cgame/cg_playerstate.c",
    "$CodeDir/cgame/cg_predict.c",
    "$CodeDir/cgame/cg_scoreboard.c",
    "$CodeDir/cgame/cg_servercmds.c",
    "$CodeDir/cgame/cg_snapshot.c",
    "$CodeDir/cgame/cg_syscalls.c",
    "$CodeDir/cgame/cg_view.c",
    "$CodeDir/cgame/cg_weapons.c",
    "$CodeDir/game/q_math.c",
    "$CodeDir/game/q_shared.c"
)
$CGameObjs = Compile-Objects -TargetName "cgame" -Sources $CGameSources -ExtraFlags @("-I$CodeDir/cgame")
$CGameDll = "$OutDir\baseq3\cgamex86.dll"
& gcc -shared "-Wl,--export-all-symbols" @CGameObjs -o $CGameDll
if ($LASTEXITCODE -ne 0) {
    throw "Failed linking $CGameDll"
}
Copy-Item -Force $CGameDll "$OutDir\cgamex86.dll"
Copy-Item -Force $CGameDll "$HomeBaseQ3\cgamex86.dll"
Write-Host "Linked $CGameDll ($((Get-Item $CGameDll).Length) bytes)" -ForegroundColor Green

# 4. UI Module (uix86.dll)
$UiSources = @(
    "$CodeDir/game/bg_misc.c",
    "$CodeDir/q3_ui/ui_addbots.c",
    "$CodeDir/q3_ui/ui_atoms.c",
    "$CodeDir/q3_ui/ui_cdkey.c",
    "$CodeDir/q3_ui/ui_cinematics.c",
    "$CodeDir/q3_ui/ui_confirm.c",
    "$CodeDir/q3_ui/ui_connect.c",
    "$CodeDir/q3_ui/ui_controls2.c",
    "$CodeDir/q3_ui/ui_credits.c",
    "$CodeDir/q3_ui/ui_demo2.c",
    "$CodeDir/q3_ui/ui_display.c",
    "$CodeDir/q3_ui/ui_gameinfo.c",
    "$CodeDir/q3_ui/ui_ingame.c",
    "$CodeDir/q3_ui/ui_loadconfig.c",
    "$CodeDir/q3_ui/ui_main.c",
    "$CodeDir/q3_ui/ui_menu.c",
    "$CodeDir/q3_ui/ui_mfield.c",
    "$CodeDir/q3_ui/ui_mods.c",
    "$CodeDir/q3_ui/ui_network.c",
    "$CodeDir/q3_ui/ui_options.c",
    "$CodeDir/q3_ui/ui_playermodel.c",
    "$CodeDir/q3_ui/ui_players.c",
    "$CodeDir/q3_ui/ui_playersettings.c",
    "$CodeDir/q3_ui/ui_preferences.c",
    "$CodeDir/q3_ui/ui_qmenu.c",
    "$CodeDir/q3_ui/ui_removebots.c",
    "$CodeDir/q3_ui/ui_saveconfig.c",
    "$CodeDir/q3_ui/ui_serverinfo.c",
    "$CodeDir/q3_ui/ui_servers2.c",
    "$CodeDir/q3_ui/ui_setup.c",
    "$CodeDir/q3_ui/ui_sound.c",
    "$CodeDir/q3_ui/ui_sparena.c",
    "$CodeDir/q3_ui/ui_specifyserver.c",
    "$CodeDir/q3_ui/ui_splevel.c",
    "$CodeDir/q3_ui/ui_sppostgame.c",
    "$CodeDir/q3_ui/ui_spskill.c",
    "$CodeDir/q3_ui/ui_startserver.c",
    "$CodeDir/ui/ui_syscalls.c",
    "$CodeDir/q3_ui/ui_team.c",
    "$CodeDir/q3_ui/ui_teamorders.c",
    "$CodeDir/q3_ui/ui_video.c",
    "$CodeDir/game/q_math.c",
    "$CodeDir/game/q_shared.c"
)
$UiObjs = Compile-Objects -TargetName "ui" -Sources $UiSources -ExtraFlags @("-I$CodeDir/q3_ui", "-I$CodeDir/ui")
$UiDll = "$OutDir\baseq3\uix86.dll"
& gcc -shared "-Wl,--export-all-symbols" @UiObjs -o $UiDll
if ($LASTEXITCODE -ne 0) {
    throw "Failed linking $UiDll"
}
Copy-Item -Force $UiDll "$OutDir\uix86.dll"
Copy-Item -Force $UiDll "$HomeBaseQ3\uix86.dll"
Write-Host "Linked $UiDll ($((Get-Item $UiDll).Length) bytes)" -ForegroundColor Green
