# VirtualCamNative (Android Companion App) 🚀

> **Ultra-Low-Latency, Hardware-Accelerated HEVC Wireless & USB Video & Audio Streamer for Windows Virtual Camera.**

<p align="center">
  <b>Language / Мова / Язык:</b><br>
  <a href="#virtualcamnative-android-companion-app-">English</a> • 
  <a href="#-virtualcamnative-android--руководство-на-русском">Русский</a> • 
  <a href="#-virtualcamnative-android--посібник-українською">Українська</a>
</p>

[![Android Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--35)-3DDC84.svg?style=for-the-badge&logo=android)](https://developer.android.com/)
[![Video Codec](https://img.shields.io/badge/Encoder-MediaCodec%20HEVC%20%2F%20H.265-orange.svg?style=for-the-badge)](https://developer.android.com/reference/android/media/MediaCodec)
[![Camera API](https://img.shields.io/badge/Capture-Camera2%20Zero--Copy-blue.svg?style=for-the-badge)](https://developer.android.com/reference/android/hardware/camera2/package-summary)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)
[![PC Client](https://img.shields.io/badge/PC%20Client-VirtualCamNative%20(C%2B%2B20)-0078D6.svg?style=for-the-badge&logo=windows)](https://github.com/dimalinau-lab/Virtual-Camera)

**VirtualCamNative Android** is a zero-cost, open-source, ultra-low latency mobile streaming engine designed to replace proprietary apps like DroidCam and Iriun. By leveraging **hardware-accelerated HEVC (H.265) encoding** via Android's `MediaCodec`, zero-copy **Camera2 API** surface pipelines, and the companion **C++20 Windows desktop driver** ([VirtualCamNative](https://github.com/dimalinau-lab/Virtual-Camera)), it streams smooth 1080p/4K 60 FPS video directly into **Discord, OBS Studio, Zoom, Telegram, and WebRTC browsers** with ultra-low latency (< 30 ms).

---

## 🚀 What's New in v2.2.0

- 🔄 **In-App Auto-Update System**: Automatically checks the GitHub Releases API on startup for newer versions (`UpdateManager`). Displays an animated notification card with a real-time download progress bar and invokes the native Android PackageInstaller via `FileProvider`.
- 📱 **Samsung Flagships & Display Cutout Safe Area Clearance**: Dynamically calculates punch-hole camera cutouts, curved display corners, and status bars using `WindowInsetsCompat`. Calibrated for **Samsung Galaxy S22, S23, S24, S22/S23/S24 Ultra, Note, Nothing Phone, and Pixel** devices so status badges ("В ЭФИРЕ", "32°C") never clip edges or collide with Samsung One UI green privacy camera dots.
- 🪟 **Floating PIP & Multi-App Overlay Widget**: Glassmorphic preview card running smoothly over any Android application with magnetic snap-to-edge dragging, one-tap mic mute, lens flip, eco blackout, and fullscreen restoration.
- 🌡️ **Real-Time Battery Thermal Guard**: Live hardware battery sensor temperature display (`🌡️ 32°C`) in header badges and telemetry JSON with severe overheating warnings (`🔥 ПЕРЕГРЕВ` at >= 42°C).
- 🎚️ **Pro Camera Manual Controls via REST API**:
  - `/api/manual_exposure`: Manual ISO control (100–3200) and exposure time in nanoseconds.
  - `/api/manual_focus`: Continuous manual focal distance control (macro to infinity).
  - `/api/awb`: Auto White Balance mode switching (auto, incandescent, fluorescent, daylight, cloudy, etc.).
- 📦 **Pre-Built Release APK**: Ready-to-install binary `VirtualCam-v2.2.0.apk` included directly in repository root.

---

## 🌟 Key Features

- ⚡ **Ultra-Low Latency (< 30 ms Glass-to-Glass)**  
  Custom raw TCP transport layer (`TCP_NODELAY`, optimized 64 KB non-blocking socket buffers) paired with advanced `MediaCodec` low-latency tuning (`KEY_LOW_LATENCY`, `KEY_LATENCY=0`, Zero B-Frames, real-time thread priority, and max operating rate).
- 🔍 **Multi-Lens Switching & Hardware Zoom**  
  Direct physical camera module selection: **0.5x Ultra-Wide**, **1x Wide**, and **2x / 3x Telephoto** via Camera2 `CONTROL_ZOOM_RATIO` and multi-camera physical sensor IDs.
- 🎚️ **Pro Manual Camera Controls (v2.2.0)**  
  Fine-grained control over exposure time, ISO sensitivity, manual focus distance, and Auto White Balance presets directly from the PC client without touching the phone.
- 🛡️ **Secure Device Pairing & Auth Tokens**  
  Interactive system pairing via `/api/pair`: new PC clients trigger an `AlertDialog` prompt on the phone screen. Upon approval, a persistent UUID `auth_token` is generated and saved in `SharedPreferences`. Wi-Fi streaming enforces token verification on `/api/connect`, while USB ADB is natively trusted.
- 🚀 **Hardware HEVC / H.265 Compression**  
  Uses Android's GPU `MediaCodec` hardware encoder (CBR mode with 1s I-frame interval) to maximize image sharpness and save network bandwidth while eliminating CPU thermal throttling on mobile devices.
- 📱 **Live Local Viewfinder & OLED Blackout Mode**  
  Simultaneously routes zero-copy frames to both the hardware encoder and the local phone screen (`SurfaceView`/`TextureView`). Includes a tap-to-wake hardware screen blackout overlay (`0.01f` backlight) to prevent OLED burn-in and conserve battery during long streaming sessions.
- 🔄 **Instant Orientation, FPS & Resolution Switching**  
  Switch between landscape/portrait aspect ratios, adjust resolutions (720p/1080p/4K), or toggle FPS (30/60) dynamically. The pure `Camera2` implementation hot-swaps active capture sessions without tearing down the underlying `CameraDevice` or dropping the TCP socket.
- 📡 **Dual Connection Modes & Auto-Discovery**  
  - **USB Mode:** Zero-lag streaming via automatic ADB port forwarding (`8080`, `8554`, `8555`).  
  - **Wi-Fi Mode:** Automatic local network device discovery via **UDP Beacon Broadcasting** (`255.255.255.255:8888`) every 1.5s sending device metadata — no manual IP entry required.
- 🎙️ **Crystal-Clear Raw PCM Audio Stream**  
  Captures 48 kHz 16-bit mono/stereo audio with hardware noise suppressor flags, transmitting raw PCM over a dedicated TCP stream directly to the Windows WASAPI / DirectShow audio filter.
- 🛡️ **Foreground Service & Thread Safety**  
  Runs inside a dedicated Android `Foreground Service` protected by `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF`. All hardware lifecycle events execute safely on an isolated `HandlerThread` to prevent Main Thread blocks and HAL crashes.
- 📱 **Samsung Flagships & Modern Cutout Safe Area Insets (v2.2.0)**  
  Dynamically adapts to display punch-hole camera cutouts, curved screen edges, and status bars using `WindowInsetsCompat`. Specially calibrated for modern flagships including **Samsung Galaxy S22, S23, S24, S22/S23/S24 Ultra, Note, and Pixel** devices so indicators never overlap with camera holes or One UI privacy dots.
- 🪟 **Floating PIP & Multi-App Overlay Widget (v2.2.0)**  
  Draggable, magnetic floating camera preview widget running smoothly above any other Android application, featuring one-tap mic mute, lens flip, eco blackout, and instant return to fullscreen.
- 🌡️ **Real-Time Battery Thermal Guard (v2.2.0)**  
  Monitors smartphone hardware battery temperature (`🌡️ 32°C`) directly in the header HUD and status badges, alerting when temperatures reach severe levels (`🔥 ПЕРЕГРЕВ` at >= 42°C).
- 🔘 **Minimalist Floating Status Widget**  
  Includes a draggable, magnetic snap-to-edge floating dot overlay with glowing pulsation indicators for streaming status (Yellow: Waiting, Green: Live, Red: Error).

---

## 📊 Comparison with Existing Solutions

| Feature | **VirtualCamNative (Android)** | DroidCam | Iriun Cam |
| :--- | :---: | :---: | :---: |
| **Video Codec** | **HEVC / H.265 (Hardware)** | H.264 / MJPEG | H.264 / MJPEG |
| **Glass-to-Glass Latency** | **Ultra-Low (< 30 ms)** | ~100 ms – 200 ms | ~80 ms – 150 ms |
| **Physical Multi-Lens (0.5x/1x/2x)** | **Yes (Camera2 Hardware API)** | No | No |
| **Native DirectShow Driver** | **Yes (Pure C++20 COM DLL)** | Yes (Proprietary) | Yes (Proprietary) |
| **Client Pairing & Security** | **Yes (Token Auth & Modal Alert)** | No | No |
| **OLED Battery Burn-in Saver** | **Yes (0.01f Backlight Overlay)** | Paid Feature | Not Available |
| **Wi-Fi Auto-Discovery** | **Yes (UDP Beacon :8888)** | Partial | Yes |
| **Ads / Resolution Limits** | **None (Full 1080p/4K Unlocked)** | HD Locked / Ads | Watermarked / Paid |
| **Instant Aspect / Config Switch** | **Yes (Zero-Disconnect)** | Requires Disconnect | Requires Restart |
| **License** | **100% Free & Open Source (MIT)** | Closed Source | Closed Source |

---

## 📐 System Architecture

```text
 +---------------------------------------------------------------------------------+
 |                        ANDROID CLIENT (HARDWARE SOURCE)                         |
 |              ( https://github.com/dimalinau-lab/Virtual-Camera-Android )        |
 |                                                                                 |
 | [ Pure Camera2 API Engine ] ---> [ Viewfinder Surface ] (Local Phone Screen)    |
 |         │                                                                       |
 |         └───► [ MediaCodec HEVC ] ---> [ Raw TCP Server :8554 ]                 |
 | [ AudioRecord ]          ────► [ Raw 48kHz PCM ]  ---> [ Audio TCP Server :8555 ]|
 |                                                                                 |
 | [ ControlServer :8080 ]  <─── REST API / Auth ──── [ UDP Beacon Broadcaster :8888]
 |   ├── /api/pair (Token Auth & System Dialog Prompt)                             |
 |   ├── /api/lens, /api/connect, /api/config, /api/action, /api/orientation       |
 |   └── /api/unpair, /api/status                                                  |
 +----------------------------------------|----------------------------------------+
                                          | TCP Video/Audio / HTTP REST / UDP Beacon
                                          v
 +---------------------------------------------------------------------------------+
 |                        VIRTUALCAMNATIVE PC CLIENT (C++20)                       |
 |                                                                                 |
 |  [ TcpReceiver ]               [ AudioReceiver (WASAPI / VB-Cable) ]            |
 |         │                                                                       |
 |         ▼ (Anti-Bufferbloat Queue Guard)                                        |
 |  [ NvdecDecoder (FFmpeg Low-Delay) ]                                            |
 |         │                                                                       |
 |         ▼                                                                       |
 |  [ 64x64 Block Rotator ] ───►  [ AVX2 NV12 Blender (60 FPS) ]                   |
 |         │                                   │                                   |
 |         ▼                                   ▼                                   |
 |  [ Local MJPEG Preview :8000 ]     [ Win32 Shared Memory MMF ]                  |
 |         │                                   │ Frame Buffer + Sync Events        |
 |  [ WebView2 Desktop GUI ]                   ▼                                   |
 +---------------------------------------------|-----------------------------------+
                                               v
 +---------------------------------------------------------------------------------+
 |                NATIVE VIRTUAL DRIVERS (DIRECTSHOW & MEDIA FOUNDATION)           |
 |                                                                                 |
 |  [ NativeMFVirtualCam.dll ]                                                     |
 |    ├── Video Capture Filter ("Native High-Speed Cam")                           |
 |    └── Audio Capture Filter ("VirtualCam Native Microphone")                    |
 +----------------------------------------|----------------------------------------+
                                          | DirectShow Capture Pins
                                          v
 +---------------------------------------------------------------------------------+
 |                              CONSUMING APPLICATIONS                             |
 |                                                                                 |
 |    Discord    |    OBS Studio    |    Zoom    |    Telegram    |    Browsers    |
 +---------------------------------------------------------------------------------+
```

---

## 🚀 Quick Start

### Installation
- 📦 **Pre-Built APK (Fastest):** Download and install [`VirtualCam-v2.2.0.apk`](VirtualCam-v2.2.0.apk) directly on your device (also available in [Releases](https://github.com/dimalinau-lab/Virtual-Camera-Android/releases)).
- 🛠️ **Build from Source:** See [Building from Source](#️-building-from-source).

### USB Mode (Lowest Latency & Maximum Stability)
1. Enable **USB Debugging** on your phone (*Settings -> Developer Options -> USB Debugging*).
2. Connect your phone to your PC via a USB cable.
3. Launch **VirtualCamNative** on your phone and open the [PC Client](https://github.com/dimalinau-lab/Virtual-Camera).
4. Click **USB Connect** on the PC client (ports `8080`, `8554`, `8555` are configured automatically via ADB).

### Wi-Fi Mode (Wireless)
1. Connect both your phone and PC to the same local Wi-Fi router (5 GHz recommended).
2. Open **VirtualCamNative** on your phone.
3. The [PC Client](https://github.com/dimalinau-lab/Virtual-Camera) automatically discovers your phone via **UDP Beacon** (`:8888`).
4. Select your device from the dropdown and click **Wi-Fi Connect**.
5. Tap **Allow** on the confirmation prompt on your phone screen.

---

## 🌐 Control Server REST API

The Android client runs an embedded HTTP REST server on port `8080` (`ControlServer`) to manage client authorization, remote configuration, and hardware execution without socket resets.

| Endpoint | Method | Request Payload / Params | Description |
| :--- | :---: | :--- | :--- |
| `/api/pair` | `POST` | `{"client_id": "...", "client_name": "PC"}` | Requests client pairing. Displays an interactive `AlertDialog` prompt on the phone (20s timeout). Returns UUID `auth_token` on approval. |
| `/api/unpair` | `POST` | `{"token": "...", "client_id": "..."}` | Revokes pairing for the specified client and removes the token from `SharedPreferences`. |
| `/api/status` | `GET` | - | Returns JSON status: streaming state, camera facing, torch, mic mute, battery level, battery temp (°C), thermal status, charging flag, manual ISO, exposure time ns, focus distance, and AWB mode. |
| `/api/connect` | `POST` | `{"mode": "usb" \| "wifi", "auth_token": "..."}` | Validates `auth_token` for Wi-Fi connections (USB ADB is trusted). Wakes video encoder and requests an immediate IDR keyframe. |
| `/api/disconnect` | `POST` | - | Safely pauses the video streamer and drops the client socket (Camera continues running in background). |
| `/api/config` | `POST` | `{"resolution": "1080p", "fps": 60, "bitrate": 10000000}` | Updates encoder resolution (`720p`, `1080p`, `4k`), FPS, and bitrate dynamically with 300ms debouncing. |
| `/api/action` | `POST` | `{"action": "switch_camera" \| "toggle_torch" \| "toggle_blackout" \| "toggle_mic_mute"}` | Toggles front/back camera, torch LED, screen blackout mode, or microphone mute. |
| `/api/lens` | `GET` / `POST` | `?lens=0.5x \| 1x \| 2x` | Switches physical camera sensor optics on the smartphone live. |
| `/api/orientation` | `GET` / `POST` | `?mode=vertical \| horizontal` | Instantly switches frame orientation without dropping the TCP socket. |
| `/api/manual_exposure` | `POST` | `{"iso": 400, "exposure_time_ns": 16666667}` | Configures manual ISO sensitivity (100–3200) and shutter exposure time in nanoseconds. |
| `/api/manual_focus` | `POST` | `{"focus_distance": 2.5}` | Configures manual optical focal distance (0.0 to max diopters). |
| `/api/awb` | `POST` | `{"awb_mode": "auto" \| "daylight" \| "cloudy" \| ...}` | Selects Auto White Balance camera preset. |

### Example REST Requests (Curl)

```bash
# 1. Request Pairing from PC
curl -X POST http://192.168.1.45:8080/api/pair \
     -H "Content-Type: application/json" \
     -d '{"client_id":"my-pc-uuid","client_name":"Gaming Desktop"}'

# 2. Connect with Auth Token
curl -X POST http://192.168.1.45:8080/api/connect \
     -H "Content-Type: application/json" \
     -d '{"mode":"wifi","auth_token":"YOUR_PAIRED_TOKEN"}'

# 3. Switch to Ultra-Wide Lens (0.5x)
curl -X POST "http://192.168.1.45:8080/api/lens?lens=0.5x"

# 4. Toggle Blackout Mode (Save OLED Battery)
curl -X POST http://192.168.1.45:8080/api/action -d '{"action":"toggle_blackout"}'
```

---

## 🛠️ Building from Source

### Requirements
- **Toolchain:** Android Studio Jellyfish / 2024.1+, JDK 17, Gradle 8.x.
- **Minimum SDK:** 26 (Android 8.0) | **Target SDK:** 35 (Android 15)

```bash
# Clone the repository
git clone https://github.com/dimalinau-lab/Virtual-Camera-Android.git
cd Virtual-Camera-Android

# Build Debug APK
./gradlew assembleDebug
```
The compiled APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

---

## 🔧 Troubleshooting

<details>
<summary><b>1. Pairing prompt does not appear on phone screen</b></summary>
<br>
Ensure <code>VirtualCamNative</code> has been granted <b>"Display over other apps"</b> (<code>SYSTEM_ALERT_WINDOW</code>) permission in Android system settings so the background service can show modal prompts.
</details>

<details>
<summary><b>2. Frame rate drops or stuttering on Wi-Fi</b></summary>
<br>
- Use a 5 GHz Wi-Fi network rather than congested 2.4 GHz.<br>
- Ensure battery optimization is disabled for VirtualCamNative so Android does not throttle background CPU performance.
</details>

<details>
<summary><b>3. Resolution error when switching to 4K</b></summary>
<br>
Ensure your device camera sensor and hardware HEVC encoder support 4K 60 FPS. If not supported by the hardware HAL, the app falls back to 1080p 60 FPS safely.
</details>

---

## 📄 License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for more information.

---
---

# 🇷🇺 VirtualCamNative (Android) — Руководство на русском

> **Мобильное приложение для Android: аппаратный HEVC-стример видео и звука со сверхнизкой задержкой для ПК.**

**VirtualCamNative для Android** — это бесплатное приложение с открытым исходным кодом, превращающее ваш смартфон в премиальную веб-камеру для ПК. В отличие от DroidCam и Iriun, приложение задействует аппаратный энкодер **HEVC (H.265)** через Android `MediaCodec`, прямой доступ к сенсорам через **Camera2 API** и передает видео и 48 кГц PCM-звук на ПК с задержкой менее 30 мс.

---

## 🚀 Что нового в версии v2.2.0

- 🔄 **Встроенная система автообновления**: Автоматическая проверка новых релизов через GitHub Releases API при старте (`UpdateManager`). Анимированный баннер с индикатором прогресса загрузки и бесшовная установка APK в один тап через `FileProvider` и системный установщик Android.
- 📱 **Полная адаптация под флагманы Samsung и Safe Area Insets**: Динамический учет вырезов фронтальных камер (punch-hole), скруглений экранов и системного статус-бара через `WindowInsetsCompat`. Откалибровано под **Samsung Galaxy S22, S23, S24, S22/S23/S24 Ultra, Note, Nothing Phone и Pixel** — бейджи «В ЭФИРЕ» и температуры больше не перекрываются зеленым индикатором приватности камеры Samsung One UI.
- 🪟 **Плавающий оверлей и режим поверх других окон (Floating Widget)**: Интерактивная плавающая карточка камеры поверх любого приложения с магнитным прилипанием к краям экрана, кнопками быстрого отключения микрофона, смены камеры, режима затемнения (Eco) и разворачивания на весь экран.
- 🌡️ **Аппаратный термомониторинг аккумулятора**: Отображение реальной температуры батареи (`🌡️ 32°C`) в верхней панели и JSON-телеметрии с предупреждениями о критическом нагреве (`🔥 ПЕРЕГРЕВ` при >= 42°C).
- 🎚️ **Ручное управление камерой (Pro Controls) через REST API**:
  - `/api/manual_exposure`: Ручной выбор ISO (100–3200) и выдержки затвора в наносекундах.
  - `/api/manual_focus`: Плавная установка дистанции фокусировки (от макро до бесконечности).
  - `/api/awb`: Переключение режимов баланса белого (авто, дневной свет, лампа накаливания, облачно и др.).
- 📦 **Готовый скомпилированный APK**: Файл `VirtualCam-v2.2.0.apk` добавлен прямо в корень репозитория для быстрой установки.

---

## 🌟 Ключевые возможности

- ⚡ **Сверхнизкая задержка (< 30 мс по USB и Wi-Fi 5 ГГц)**  
  Оптимизированный сокет TCP (`TCP_NODELAY`, буфер 64 КБ), тюнинг энкодера (`KEY_LOW_LATENCY`, нулевые B-кадры, максимальный приоритет потока).
- 🔍 **Переключение физических линз камеры (0.5x / 1x / 2x)**  
  Прямой доступ к сверхширокоугольному, основному и телефото-объективам смартфона через аппаратный `CONTROL_ZOOM_RATIO`.
- 🎚️ **Ручные настройки съемки Pro Manual (v2.2.0)**  
  Дистанционное управление выдержкой, ISO, дистанцией фокусировки и балансом белого прямо из интерфейса ПК без необходимости трогать телефон.
- 🛡️ **Безопасное сопряжение и токен авторизации**  
  При первой попытке подключения с нового ПК на экране телефона появляется системное диалоговое окно с подтверждением. Сгенерированный токен сохраняется в зашифрованных настройках.
- 📱 **OLED Blackout — защита экрана от выгорания**  
  Режим полного затемнения дисплея с минимальной яркостью (`0.01f`), предотвращающий нагрев батареи и выгорание OLED-матриц при долгих трансляциях.
- 🔄 **Мгновенная смена ориентации и разрешения без разрыва сокета**  
  Переключение между вертикальным/горизонтальным режимом, с разрешениями 720p, 1080p и 4K при 30 или 60 FPS прямо во время звонка.
- 🎙️ **Чистый PCM-звук 48 кГц**  
  Передача звука микрофона по отдельному TCP-порту `8555` с системным аппаратным шумоподавлением.
- 📡 **Автоматическое обнаружение в сети (UDP Beacon :8888)**  
  Приложение транслирует широковещательный маяк раз в 1.5 секунды — ПК сам находит телефон без ручного ввода IP-адресов.
- 📱 **Адаптация под флагманы Samsung и Safe Area Insets (v2.2.0)**  
  Динамический расчет безопасных зон (`WindowInsetsCompat`) под вырезы камер (punch-hole), скругленные углы и системный статус-бар. Идеально откалибровано под последние 3 поколения **Samsung Galaxy S22, S23, S24, S22/S23/S24 Ultra**, смартфоны Nothing, Pixel и Xiaomi — бейджи статуса «В ЭФИРЕ» и температуры больше не перекрываются индикатором приватности камеры One UI.
- 🪟 **Плавающий оверлей и режим PiP (Картинка в картинке) (v2.2.0)**  
  Возможность свернуть стример в компактный плавающий виджет поверх любых других приложений или стандартное системное окно PiP с быстрым переключением камеры и мутом микрофона.
- 🌡️ **Термомониторинг и защита от перегрева (v2.2.0)**  
  Отображение живой температуры аккумулятора (`🌡️ 32°C`) с автоматическим предупреждением о нагреве (`🔥 ПЕРЕГРЕВ` при >= 42°C).

---

## 🚀 Быстрый старт

### Способ установки
- 📦 **Готовый APK (Рекомендуется):** Скачайте и установите файл [`VirtualCam-v2.2.0.apk`](VirtualCam-v2.2.0.apk) на смартфон (также доступен в [Releases](https://github.com/dimalinau-lab/Virtual-Camera-Android/releases)).
- 🛠️ **Сборка из исходников:** Инструкция в разделе [Сборка APK из исходников](#️-сборка-apk-из-исходников).

### Режим USB (Минимальная задержка)
1. Включите **Отладку по USB** (*Настройки -> Для разработчиков -> Отладка по USB*).
2. Подключите телефон к компьютеру кабелем USB.
3. Откройте приложение на телефоне и запустите `VirtualCamNative.exe` на ПК.
4. Нажмите **USB Connect** на компьютере — порты пробросятся автоматически через ADB.

### Режим Wi-Fi (Без проводов)
1. Подключите ПК и смартфон к одной Wi-Fi сети (рекомендуется 5 ГГц).
2. Запустите приложение на телефоне.
3. В клиенте на ПК телефон появится в списке устройств автоматически.
4. Нажмите **Wi-Fi Connect** и подтвердите запрос сопряжения на телефоне.

---

## 🛠️ Сборка APK из исходников

- **Среда разработки:** Android Studio Jellyfish / Ladybug (2024.1+), JDK 17.
- **Минимальная версия Android:** 8.0 (API 26) | **Целевая версия:** Android 15 (API 35).

```bash
# Клонирование и сборка
git clone https://github.com/dimalinau-lab/Virtual-Camera-Android.git
cd Virtual-Camera-Android
./gradlew assembleDebug
```
Скомпилированный файл APK находится по пути: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 Лицензия

Проект распространяется под свободной лицензией **MIT**. Подробности в файле [`LICENSE`](LICENSE).

---
---

# 🇺🇦 VirtualCamNative (Android) — Посібник українською

> **Мобільний додаток для Android: апаратний HEVC-стрімер відео та звуку з наднизькою затримкою для ПК.**

**VirtualCamNative для Android** — це безкоштовний додаток з відкритим вихідним кодом, що перетворює ваш смартфон на професійну веб-камеру та мікрофон для комп'ютера. На відміну від застарілих комерційних рішень, додаток використовує апаратний енкодер **HEVC (H.265)** через Android `MediaCodec`, прямий доступ до модулів камер через **Camera2 API** та транслює відеоряд і звук 48 кГц PCM із затримкою менше 30 мс.

---

## 🚀 Що нового у версії v2.2.0

- 🔄 **Вбудована система автооновлення**: Автоматична перевірка нових версій через GitHub Releases API при запуску (`UpdateManager`). Анімований банер з індикатором прогресу завантаження та безшовне встановлення APK в один дотик через `FileProvider` і системний інсталятор Android.
- 📱 **Безпечні зони Safe Area Insets для флагманів Samsung**: Динамічний розрахунок вирізів камер (punch-hole), скруглених країв дисплеїв та статус-бара через `WindowInsetsCompat`. Калібрування для **Samsung Galaxy S22, S23, S24, S22/S23/S24 Ultra, Note, Nothing Phone та Pixel** — інформаційні бейджі «В ЕФІРІ» та температури ніколи не обрізаються краями екрана й не перекриваються зеленою крапкою індикатора приватності Samsung One UI.
- 🪟 **Плавучий оверлей-віджет та багатовіконний режим**: Інтерактивна плаваюча картка поверх будь-яких інших додатків із магнітним прилипанням до країв екрана, кнопками швидкого вимкнення мікрофона, зміни сенсорів камери, енергозбереження екрана (Eco) та повернення на весь екран.
- 🌡️ **Апаратний термомоніторинг акумулятора**: Живе відображення температури батареї (`🌡️ 32°C`) у верхньому барі та JSON-телеметрії з автоматичним сповіщенням про критичний перегрів (`🔥 ПЕРЕГРІВ` при >= 42°C).
- 🎚️ **Ручні налаштування зйомки Pro Manual через REST API**:
  - `/api/manual_exposure`: Ручне налаштування чутливості ISO (100–3200) та витримки в наносекундах.
  - `/api/manual_focus`: Плавне регулювання оптичного фокусу (від макро до нескінченності).
  - `/api/awb`: Зміна режимів балансу білого (авто, сонячно, хмарно, лампи розжарювання тощо).
- 📦 **Готовий скомпільований APK**: Файл `VirtualCam-v2.2.0.apk` доступний безпосередньо в кореневій теці репозиторію.

---

## 🌟 Головні можливості

- ⚡ **Наднизька затримка (< 30 мс через USB та Wi-Fi 5 ГГц)**  
  Оптимізований мережевий транспорт TCP (`TCP_NODELAY`, 64 КБ буфер), апаратний тюнінг низької затримки (`KEY_LOW_LATENCY`, відсутність B-кадрів).
- 🔍 **Апаратне перемикання фізичних об'єктивів (0.5x / 1x / 2x)**  
  Пряме керування сенсорами смартфона: надширококутний, основний або телеоб'єктив через Camera2 `CONTROL_ZOOM_RATIO`.
- 🎚️ **Професійні ручні параметри Pro Manual (v2.2.0)**  
  Віддалене керування витримкою, ISO, фокусною відстанню та балансом білого прямо з клієнта на ПК без торкання екрана телефона.
- 🛡️ **Безпечне сполучення та токени авторизації**  
  При спробі підключення нового комп'ютера на екрані телефона з'являється системний запит на доступ. Створений токен безпечно зберігається на пристрої.
- 📱 **Захист екрана OLED Blackout**  
  Режим глибокого затемнення екрана зі зниженням підсвічування до `0.01f`, що запобігає вигорянню AMOLED-дисплеїв та зберігає заряд акумулятора.
- 🔄 **Миттєва зміна роздільної здатності та орієнтації**  
  Зміна портретної або альбомної орієнтації, роздільної здатності (720p, 1080p, 4K) та частоти кадрів (30/60 FPS) без розриву з'єднання.
- 🎙️ **Якісний звук PCM 48 кГц**  
  Окремий звуковий потік TCP `:8555` з використанням системного апаратного шумопоглинання.
- 📡 **Автоматичне виявлення у мережі (UDP Beacon :8888)**  
  Додаток самостійно заявляє про себе у локальній мережі — ПК миттєво знаходить телефон без потреби вводити IP-адресу вручну.
- 📱 **Оптимізація під флагмани Samsung та Safe Area Insets (v2.2.0)**  
  Динамічний розрахунок безпечних зон (`WindowInsetsCompat`) для екранів з вирізами камер (punch-hole) та скругленими кутами дисплеїв **Samsung Galaxy S22, S23, S24, Ultra** та інших безрамкових пристроїв.
- 🪟 **Плавучий оверлей-віджет та інтерактивний PiP (v2.2.0)**  
  Можливість роботи поверх інших вікон із живим прев'ю, швидким вимкненням звуку та перемиканням камер.
- 🌡️ **Термомоніторинг акумулятора (v2.2.0)**  
  Відображення поточної температури батареї (`🌡️ 32°C`) та захист від перегріву смартфона під час тривалих стрімів.

---

## 🚀 Швидкий старт

### Спосіб встановлення
- 📦 **Готовий APK (Рекомендовано):** Завантажте та встановіть файл [`VirtualCam-v2.2.0.apk`](VirtualCam-v2.2.0.apk) на смартфон (також доступний у [Releases](https://github.com/dimalinau-lab/Virtual-Camera-Android/releases)).
- 🛠️ **Збирання з вихідного коду:** Інструкція у розділі [Збирання APK з вихідного коду](#️-збирання-apk-з-вихідного-коду).

### Підключення через USB (Рекомендовано)
1. Увімкніть **Налагодження через USB** (*Налаштування -> Для розробників -> Налагодження через USB*).
2. Підключіть телефон до ПК кабелем USB.
3. Запустіть додаток на телефоні та `VirtualCamNative.exe` на комп'ютері.
4. Натисніть **USB Connect** на ПК — порти прокидаються автоматично через ADB.

### Бездротове підключення через Wi-Fi
1. Підключіть ПК та смартфон до однієї Wi-Fi мережі (бажано 5 ГГц).
2. Відкрийте додаток на смартфоні.
3. У програмі на ПК пристрій з'явиться автоматично у верхньому списку.
4. Натисніть **Wi-Fi Connect** та дозвольте сполучення на екрані смартфона.

---

## 🛠️ Збирання APK з вихідного коду

- **Інструменти:** Android Studio (2024.1+), JDK 17, Gradle 8.x.
- **Підтримувані версії:** Android 8.0 (API 26) — Android 15 (API 35).

```bash
# Клонування та компіляція
git clone https://github.com/dimalinau-lab/Virtual-Camera-Android.git
cd Virtual-Camera-Android
./gradlew assembleDebug
```
Готовий APK буде розміщено за адресою: `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 Ліцензія

Проект поширюється за вільною ліцензією **MIT**. Перегляньте [`LICENSE`](LICENSE) для отримання детальної інформації.
