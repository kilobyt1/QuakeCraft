# QuakeCraft v0.1.0 — Initial GitHub Release (True Two-Process Passthrough)

**Quake III Arena (id Tech 3) ↔ Minecraft Java Edition 26.3 (Fabric 0.19.5)**

📺 **Демонстрация геймплея на YouTube:** [https://www.youtube.com/watch?v=l7rIfBAF9Cc](https://www.youtube.com/watch?v=l7rIfBAF9Cc)

---

### 📦 Что включено в релизный архив (`QuakeCraft-v0.1.0-windows-x64.zip`):
1. **`mods/quakecraft-bridge-0.1.0.jar`** — трансмиттер и depth-композитор для Minecraft 26.3 (Fabric).
2. **`quake3/quake3-bridge.exe`** — 64-битный порт оригинального движка id Tech 3 Quake III Arena с поддержкой разделяемой памяти (`Local\QuakeCraftBridgeV1`), воксельной коллизией и экспортом кадров.
3. **`quake3/baseq3/`** — скомпилированные игровые библиотеки (`cgamex86.dll`, `qagamex86.dll`, `uix86.dll`).
4. **`quake3/start_quake3.bat`** — скрипт быстрого запуска Quake 3 Bridge с оптимизированными турнирными параметрами (125 FPS).

> ⚠️ **Внимание:** Архив НЕ содержит проприетарных игровых файлов Quake III Arena. Перед запуском скопируйте файл `pak0.pk3` из вашей лицензионной копии Quake III Arena в папку `quake3/baseq3/`.

---

### ⚡ Быстрый старт:

1. **Скачайте и распакуйте** `QuakeCraft-v0.1.0-windows-x64.zip`.
2. **Скопируйте** `mods/quakecraft-bridge-0.1.0.jar` в вашу папку `.minecraft/mods` (требуется Minecraft 26.3 + Fabric Loader >= 0.19.5 + Fabric API 0.162.0+26.3).
3. **ОБЯЗАТЕЛЬНО добавьте параметр JVM в лаунчере:**
   ```text
   --enable-native-access=ALL-UNNAMED
   ```
   *(Необходимо для работы Java 25 Foreign Function & Memory API с Win32 Shared Memory).*
4. **Положите ваш `pak0.pk3`** в папку `quake3/baseq3/pak0.pk3`.
5. **Запустите `quake3/start_quake3.bat`**, затем запустите Minecraft 26.3 и войдите в любой мир!

---

### 🎮 Управление:
* `1` .. `9`: Выбор оружия Quake III (Gauntlet, Machinegun, Shotgun, Grenade Launcher, Rocket Launcher, Lightning Gun, Railgun, Plasma Gun, BFG10K).
* `Space`: Прыжок / авто-распрыжка (Auto-Hop Bunny Jumping).
* `F5`: Переключение вида (1-е лицо / 3-е лицо с моделью Sarge).
* `F6`: Переключение режима боя Quake 3 <-> взаимодействие с блоками/интерфейсом Minecraft.
* `F7`: Включение/выключение пасс-тру моста.
* `F8`: Чит-код (Quad Damage + MegaHealth + Red Armor + Battle Suit + Full Ammo).
* `E`: Инвентарь Minecraft.
* `Esc`: Меню паузы.
