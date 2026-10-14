# QuakeCraft: True Two-Process Passthrough
### Quake III Arena (id Tech 3) ↔ Minecraft Java Edition 26.3 (Fabric)

[![Video Demonstration](https://img.youtube.com/vi/l7rIfBAF9Cc/maxresdefault.jpg)](https://www.youtube.com/watch?v=l7rIfBAF9Cc)

> 📺 **[Смотреть видео демонстрации геймплея на YouTube](https://www.youtube.com/watch?v=l7rIfBAF9Cc)**  
> Полноценная бесшовная интеграция турнирной физики и оружия Quake III Arena (125 FPS) напрямую в мир Minecraft 26.3.

---

## 📌 О проекте (Architecture Truth)

**QuakeCraft** — это **настоящий двухпроцессный мост (Two-Process Passthrough)** между оригинальным движком **Quake III Arena** и **Minecraft Java Edition 26.3 (Fabric)**:

* 🚫 **Никаких фейковых Java-реимплементаций физики:** физика движения, распрыжка (strafe-jumping), рокет-джампы, все 9 видов оружия, урон, сплэш и баллистика рассчитываются в **оригинальном 64-битном C-движке Quake III Arena (`bg_pmove.c`)** на частоте 125 Гц.
* ⚡ **Межпроцессное взаимодействие без задержек (IPC):** обмен состоянием игрока, воксельной сеткой мира ($64 \times 64 \times 64$ блоков), таблицей мобов и событиями происходит через Win32 Named Shared Memory (`Local\QuakeCraftBridgeV1`) с использованием seqlock-снапшотов и Java 25 Foreign Function & Memory (FFM) API.
* 🎨 **Depth-Aware OpenGL Compositing:** 3D-модели (Sarge, ракеты, плазма, рельса) и 2D HUD Quake 3 отрисовываются прямо внутри окна Minecraft с честным попиксельным тестом глубины (Reversed-Z depth buffer). Ракеты и игроки реалистично скрываются за блоками Minecraft.
* ⚔️ **Полноценная боевая система и разрушение мира:** мобы Minecraft получают урон от оружия Quake (с расчленением/gibs, отбрасыванием и хит-звуками), а взрывы ракет, гранат и BFG разрушают блоки.

---

## ⚠️ Важное юридическое уведомление (Zero Game Assets)

В репозитории и релизных архивах **НЕТ проприетарных игровых файлов Quake III Arena или Minecraft**.
* Исходный код модификации Quake 3 основан на открытом GPL v2 исходном коде id Software.
* Для работы модификации **требуется лицензионный файл `pak0.pk3`** от вашей копии Quake III Arena (Steam, GOG или оригинальный диск).

---

## 🚀 Полный гайд по установке и запуску (Installation Guide)

### 1. Системные требования
* **ОС:** Windows 10 / 11 (64-bit)
* **Minecraft:** Java Edition `26.3`
* **Мод-загрузчик:** Fabric Loader `≥ 0.19.5`
* **Библиотека:** Fabric API `0.162.0+26.3`
* **Java:** **JDK / JRE 25** (например, [Eclipse Temurin 25](https://adoptium.net/) или Oracle JDK 25). *Обязательна поддержка Java 25 Foreign Function & Memory API!*
* **Ассеты Quake 3:** файл `pak0.pk3` (из Steam-папки `steamapps/common/Quake 3 Arena/baseq3` или GOG).

---

### 2. Пошаговая установка

#### Шаг 1. Скачайте релиз
1. Перейдите во вкладку [Releases](https://github.com/) текущего репозитория.
2. Скачайте архив `QuakeCraft-v0.1.0-windows-x64.zip` и распакуйте его в удобное место (например, `C:\Games\QuakeCraft`).

Внутри архива находятся:
```text
QuakeCraft/
├── mods/
│   └── quakecraft-bridge-0.1.0.jar      <- Мод для Minecraft
├── quake3/
│   ├── quake3-bridge.exe               <- 64-битный движок Quake 3
│   ├── start_quake3.bat                <- Скрипт быстрого запуска
│   └── baseq3/
│       ├── cgamex86.dll
│       ├── qagamex86.dll
│       ├── uix86.dll
│       └── PUT_YOUR_PAK0_PK3_HERE.txt  <- Сюда нужно положить pak0.pk3
└── README.txt
```

#### Шаг 2. Установка мода в Minecraft
1. Скопируйте файл `mods\quakecraft-bridge-0.1.0.jar` в папку `mods` вашего Minecraft:
   * **PrismLauncher:** `Экземпляр -> Папка экземпляра -> .minecraft/mods`
   * **Modrinth App:** `Профиль -> Открыть папку -> mods`
   * **Официальный лаунчер:** `%appdata%\.minecraft\mods`
2. Убедитесь, что в папке `mods` также установлен **Fabric API** версии `0.162.0+26.3`.

#### Шаг 3. Включение нативного доступа в Java 25 (КРИТИЧЕСКИ ВАЖНО!)
Поскольку мост использует прямой доступ к Shared Memory через Java 25 FFM API, Minecraft **должен быть запущен с флагом `--enable-native-access=ALL-UNNAMED`**. Без этого флага Java заблокирует доступ к разделяемой памяти:

* **PrismLauncher:**  
  `Настройки экземпляра -> Java -> Параметры JVM (JVM Arguments)` -> добавьте в конец строки:
  ```text
  --enable-native-access=ALL-UNNAMED
  ```
* **Modrinth App:**  
  `Параметры профиля -> Java Arguments` -> добавьте:
  ```text
  --enable-native-access=ALL-UNNAMED
  ```
* **Стандартный Minecraft Launcher:**  
  `Установки (Installations) -> Выберите профиль 26.3 Fabric -> Настроить -> Больше опций -> Параметры JVM` -> добавьте в строку аргументов:
  ```text
  --enable-native-access=ALL-UNNAMED
  ```

#### Шаг 4. Добавление ассетов Quake 3
1. Скопируйте файл `pak0.pk3` (а также при желании `pak1.pk3` .. `pak8.pk3`) из вашей папки Quake III Arena в:
   ```text
   quake3\baseq3\pak0.pk3
   ```

---

### 3. Запуск игры

1. Запустите скрипт `quake3\start_quake3.bat`.  
   *Откроется окно Quake 3 Bridge, которое автоматически подключится к разделяемой памяти `Local\QuakeCraftBridgeV1` и перейдет в режим ожидания Minecraft.*
2. Запустите Minecraft 26.3 с установленным Fabric-модом.
3. Зайдите в любой одиночный мир (Singleplayer) или на локальный сервер.
4. Готово! Вы увидите оригинальный HUD Quake 3, оружие в руках и мгновенный отклик турнирной физики со скоростью 125 FPS!

---

## 🎮 Управление и горячие клавиши (Controls)

| Клавиша | Действие |
| :--- | :--- |
| `1` .. `9` | **Выбор оружия Quake III** (`1`: Gauntlet, `2`: Machinegun, `3`: Shotgun, `4`: Grenade Launcher, `5`: Rocket Launcher, `6`: Lightning Gun, `7`: Railgun, `8`: Plasma Gun, `9`: BFG10K) |
| `Space` | **Прыжок / Авто-распрыжка (Auto-Hop)**. Поддерживает непрерывный bunny-hop без залипания в землю. |
| `Shift` / `C` | Приседание (Crouch) со сжатием хитбокса Quake. |
| `ЛКМ` (Left Click) | Огонь из текущего оружия Quake 3 (с выстрелами, сплэшем, отдачей и звуками). |
| `F5` | **Переключение вида:** 1-е лицо (оружие Quake) ↔ 3-е лицо (модель Sarge MD3; ванильная модель Стива/Алекса скрывается автоматически). |
| `F6` | **Переключение режима ввода:** Боевой режим Quake 3 ↔ Режим взаимодействия с миром Minecraft (строительство, открытие сундуков, копание). |
| `F7` | Включение / Полное отключение Passthrough (возврат к стандартному управлению Minecraft). |
| `F8` | **Чит-код раздачи:** Quad Damage + MegaHealth + Red Armor + Battle Suit + Полный боезапас всех орудий. |
| `E` | Открытие инвентаря Minecraft (управление мышью временно отдается Minecraft). |
| `Esc` | Пауза игры (звук и захват ввода Quake 3 корректно приостанавливаются). |

---

## 🔧 Решение проблем (Troubleshooting & FAQ)

#### 1. «[ERROR] Retail Quake III Arena assets not found!»
> **Причина:** В папке `quake3\baseq3` отсутствует файл `pak0.pk3`.  
> **Решение:** Скопируйте `pak0.pk3` из вашей лицензионной версии Quake III Arena.

#### 2. Ошибка при запуске Minecraft: `IllegalAccessError` или краш `SharedMemoryBridge`
> **Причина:** Не указан обязательный флаг доступа Java 25.  
> **Решение:** Проверьте настройки JVM в вашем лаунчере и убедитесь, что аргумент `--enable-native-access=ALL-UNNAMED` прописан без ошибок.

#### 3. Мод загрузился, но HUD и оружие не отображаются
> **Причина:** Движок `quake3-bridge.exe` не запущен или упал до запуска мира.  
> **Решение:** Запустите `quake3\start_quake3.bat` перед входом в мир Minecraft. Проверьте лог `quake3\q3home\baseq3\qconsole.log` на наличие ошибок.

#### 4. Звук Quake 3 не слышен при игре в Minecraft
> **Решение:** В QuakeCraft используется модифицированный DirectSound с флагом `DSBCAPS_GLOBALFOCUS`, благодаря чему звук воспроизводится в фоне, когда окно Minecraft находится в фокусе. Убедитесь, что громкость Quake 3 в микшере громкости Windows не выкручена на ноль.

---

## 🛠️ Сборка из исходников (Building from Source)

Если вы хотите собрать проект самостоятельно:

### Необходимые инструменты:
* **GCC (MinGW-w64 64-bit)** на PATH (например, w64devkit).
* **Gradle 8.x / 9.x** и **JDK 25**.

### Команды сборки:
```powershell
# 1. Сборка Quake 3 Bridge (quake3-bridge.exe и DLL)
powershell -ExecutionPolicy Bypass -File scripts\build_q3.ps1

# 2. Сборка Fabric-мода (quakecraft-bridge-0.1.0.jar)
powershell -ExecutionPolicy Bypass -File scripts\build_mc.ps1

# 3. Упаковка чистого релизного архива
powershell -ExecutionPolicy Bypass -File scripts\package_release.ps1
```
Скрипт автоматически создаст `build\release\QuakeCraft-v0.1.0-windows-x64.zip` и выполнит валидацию через `universal-modder publish check`.

---

## 📜 Лицензия и благодарности (Credits)

* **Quake III Arena Source Code:** © id Software, лицензировано под **GPL-2.0-only**.
* **QuakeCraft Fabric Mod:** лицензировано под **GPL-2.0-only**.
* **Разработка:** Проект создан и доведен до турнирной стабильности с использованием методологии и инструментов плагина **`universal-modder`** (`um`).
* **Демонстрация:** Видеоролик на YouTube подготовлен автором проекта: [https://www.youtube.com/watch?v=l7rIfBAF9Cc](https://www.youtube.com/watch?v=l7rIfBAF9Cc).
