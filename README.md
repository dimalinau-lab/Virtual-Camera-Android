# VirtualCamNative 🚀

> **Ultra-Low-Latency, High-Quality Virtual Camera Solution for Windows using Android as a High-Definition Hardware Source.**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=for-the-badge&logo=kotlin)](https://kotlinlang.org/)
[![C++20](https://img.shields.io/badge/C++-20-blue.svg?style=for-the-badge&logo=cplusplus)](https://isocpp.org/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg?style=for-the-badge&logo=android)](https://developer.android.com/)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D6.svg?style=for-the-badge&logo=windows)](https://www.microsoft.com/)
[![PC Client](https://img.shields.io/badge/PC%20Client-Virtual--Camera-007ACC.svg?style=for-the-badge&logo=github)](https://github.com/dimalinau-lab/Virtual-Camera)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)

**VirtualCamNative** is a zero-cost, open-source, ultra-low latency system designed to replace commercial proprietary apps like DroidCam and Iriun. By leveraging **hardware-accelerated HEVC (H.265) encoding** via Android's `MediaCodec`, pure zero-copy **Camera2 API** surface pipelines, and a native **C++20 DirectShow / Media Foundation virtual camera driver** ([dimalinau-lab/Virtual-Camera](https://github.com/dimalinau-lab/Virtual-Camera)), VirtualCamNative streams pristine, smooth 1080p/60fps video directly into **Discord, OBS Studio, Zoom, Telegram, and WebRTC browsers** with under **30–40ms glass-to-glass latency**.

---

## 🌟 Key Features

- ⚡ **Ultra-Low Latency (< 40ms Glass-to-Glass)**  
  Custom raw TCP transport layer (`TCP_NODELAY`, optimized 64KB socket buffers) paired with advanced `MediaCodec` tuning (Intra Refresh, `KEY_LOW_LATENCY`, Zero B-Frames, and maximum thread priority) for instantaneous responsiveness.
- 🎬 **Hardware HEVC / H.265 Compression**  
  Uses Android's GPU `MediaCodec` hardware encoder (CBR mode) to maximize image sharpness and save network bandwidth while eliminating CPU thermal throttling on mobile devices.
- 📱 **Live Local Viewfinder & Blackout Mode**  
  Simultaneously routes zero-copy frames to both the PC hardware encoder and the local smartphone screen (`SurfaceView`/`TextureView`) so the operator can see the live feed. Includes a tap-to-wake hardware screen blackout overlay (`0.01f` backlight) to prevent OLED burn-in and conserve battery.
- 🛰️ **Dual Connection Modes & Auto-Discovery**  
  - **USB Mode:** Zero-lag streaming via automatic ADB port forwarding.  
  - **Wi-Fi Mode:** Automatic local network device discovery via **UDP Beacon Broadcasting** (`255.255.255.255:8888`) — no manual IP entry required.
- 🔄 **Instant Orientation, FPS & Resolution Switching**  
  Switch between landscape/portrait aspect ratios, adjust resolutions (720p/1080p/4K), or toggle FPS (30/60) dynamically. The pure `Camera2` implementation hot-swaps active capture sessions without tearing down the underlying `CameraDevice` or dropping the TCP socket. Network requests are safely debounced.
- 🔋 **Foreground Service & Thread Safety**  
  Runs inside a dedicated Android `Foreground Service` protected by `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF`. All hardware lifecycle events execute safely on an isolated `HandlerThread` to prevent Main Thread blocks and HAL crashes (e.g., MediaTek `onError: 4`).
- 🎯 **Minimalist Floating Status Widget**  
  Includes a draggable, magnetic snap-to-edge floating dot overlay with glowing pulsation indicators for streaming status (Yellow: Waiting, Green: Live, Red: Error).
- 🎥 **Native Windows DirectShow / Media Foundation Driver**  
  A light C++ virtual camera DLL (`NativeMFVirtualCam.dll`) registering a virtual hardware camera device recognized natively by Discord, OBS, Zoom, and browsers.

---

## 📊 Comparison with Existing Solutions

| Feature | **VirtualCamNative** | DroidCam | Iriun Cam |
| :--- | :---: | :---: | :---: |
| **Video Codec** | **HEVC / H.265 (Hardware)** | H.264 / MJPEG | H.264 / MJPEG |
| **Glass-to-Glass Latency** | **Ultra-Low (< 40 ms)** | ~100ms – 200ms | ~80ms – 150ms |
| **Native DirectShow Driver** | **Yes (Native C++ DLL)** | Yes (Proprietary) | Yes (Proprietary) |
| **Open Source** | **100% Free & Open Source (MIT)** | Closed Source | Closed Source |
| **Live Viewfinder & Screen Burn-in Protection** | **Yes (0.01f Backlight Overlay)** | Paid Feature | Not Available |
| **Wi-Fi Auto-Discovery** | **Yes (UDP Beacon :8888)** | Partial | Yes |
| **Ads / Resolution Limits** | **None (Full 1080p/4K Unlocked)** | HD Locked / Ads | Watermarked / Paid |
| **Instant Aspect / Config Switch** | **Yes (Zero-Disconnect)** | Requires Disconnect | Requires App Restart |

---

## 📐 System Architecture

```text
 +---------------------------------------------------------------------------------+
 |                        ANDROID CLIENT (HARDWARE SOURCE)                         |
 |              ( [https://github.com/dimalinau-lab/Virtual-Camera-Android](https://github.com/dimalinau-lab/Virtual-Camera-Android) )        |
 |                                                                                 |
 | [ CameraX Source ] ---> [ MediaCodec H.265 ]  ---> [ Raw TCP Server :8554 ]     |
 | [ AudioRecord ]    ---> [ Raw 48kHz PCM ]     ---> [ Audio TCP Server :8555 ]   |
 | [ NanoHTTPD :8080 ] <--- REST Commands ------- [ UDP Discovery Beacon :8888 ]   |
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
 |  [ 64x64 Block Rotator ] ───► [ AVX2 NV12 Blender (60 FPS) ]                    |
 |         │                                   │                                   |
 |         ▼                                   ▼                                   |
 |  [ Local MJPEG Preview :8000 ]     [ Win32 Shared Memory MMF ]                  |
 |         │                                   │                                   |
 |         ▼                                   │                                   |
 |  [ WebView2 Desktop GUI ]                   │ Frame Buffer + Sync Events        |
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

### USB Mode (Lowest Latency)
1. Enable **USB Debugging** on your Android phone (*Settings -> Developer Options -> USB Debugging*).
2. Connect your phone to your PC via USB cable.
3. Launch **VirtualCamNative** on your phone and open the [PC Client](https://github.com/dimalinau-lab/Virtual-Camera).
4. Select **USB Mode** and click **Connect** (the PC client configures ADB port forwarding automatically).

### Wi-Fi Mode (Wireless)
1. Connect both your phone and PC to the same local Wi-Fi router network.
2. Open **VirtualCamNative** on your phone.
3. The [PC Client](https://github.com/dimalinau-lab/Virtual-Camera) will automatically detect your device via **UDP Discovery** (`255.255.255.255:8888`).
4. Click **Connect** on the PC client to begin streaming.

---

## 🌐 Control Server REST API

The Android client runs an embedded HTTP REST server on port `8080` (`ControlServer`) to allow remote configuration and execution of hardware commands without socket resets.

| Endpoint | Method | Request Payload / Params | Description |
| :--- | :---: | :--- | :--- |
| `/api/status` | `GET` | - | Returns JSON status: streaming state, camera facing, torch state, and orientation mode. |
| `/api/connect` | `POST` | `{"mode": "usb" \| "wifi"}` | Wakes the stream encoder, requests an immediate H.265 I-Frame (IDR), and hooks up the PC socket. |
| `/api/disconnect` | `POST` | - | Safely pauses the video streamer and drops the client socket (Camera continues running in background). |
| `/api/config` | `POST` | `{"resolution": "1080p", "fps": 30, "bitrate": 7000000}` | Updates encoder resolution (`720p`, `1080p`, `4k`), FPS, and bitrate dynamically. Handles 300ms debouncing. |
| `/api/action` | `POST` | `{"action": "switch_camera" \| "toggle_torch" \| "toggle_blackout" \| "toggle_mic_mute"}` | Toggles front/back camera, torch LED, screen blackout mode, or microphone mute. |
| `/api/orientation` | `GET` / `POST` | `?mode=vertical \| horizontal` | Instantly switches frame orientation without dropping the TCP socket. |

### Example REST Request (Curl)
```bash
# Toggle Blackout Mode
curl -X POST http://192.168.1.45:8080/api/action -d '{"action":"toggle_blackout"}'

# Switch to Landscape Aspect Ratio
curl "http://192.168.1.45:8080/api/orientation?mode=horizontal"
```

---

## 🛠️ Building from Source

### Android Client
- **Toolchain:** Android Studio Jellyfish / 2024.1+, JDK 17, Gradle 8.x.
- **Minimum SDK:** 26 (Android 8.0) | **Target SDK:** 35 (Android 15)

```bash
# Clone the repository
git clone https://github.com/dimalinau-lab/Virtual-Camera-Android.git
cd Virtual-Camera-Android

# Build Debug APK
./gradlew assembleDebug
```

### Windows Client & Virtual Camera Driver
The Windows PC client software and C++ virtual camera driver source code are hosted separately:
👉 **[Virtual-Camera Repository on GitHub](https://github.com/dimalinau-lab/Virtual-Camera)**

- **Toolchain:** Visual Studio 2022 (Desktop development with C++ v143, C++20 standard), CMake 3.22+, `vcpkg`.
- **Dependencies:** FFmpeg (`libavcodec`, `libavutil`, `libswscale`), Microsoft Edge WebView2.

```cmd
# Register DirectShow / Media Foundation Virtual Camera DLL (Admin Command Prompt)
regsvr32.exe /s NativeMFVirtualCam.dll
```

---

## 🔧 Troubleshooting

<details>
<summary><b>1. Discord / OBS does not detect VirtualCamNative as a camera device</b></summary>
<br>

Ensure that `NativeMFVirtualCam.dll` was registered with Administrator privileges:
```cmd
regsvr32.exe "C:\Path\To\NativeMFVirtualCam.dll"
```
After registering, restart Discord or OBS Studio.
</details>

<details>
<summary><b>2. Frame rate drops to 1-3 FPS on Wi-Fi</b></summary>
<br>

- Check that your Wi-Fi router operates on the **5 GHz band** to avoid 2.4 GHz channel congestion.
- Ensure `VirtualCamNative` has been granted `SYSTEM_ALERT_WINDOW` and `POST_NOTIFICATIONS` permissions so the OS does not throttle the background thread.
</details>

<details>
<summary><b>3. Video image appears distorted or horizontally striped</b></summary>
<br>

Ensure that target resolutions sent via `/api/config` use dimensions divisible by 16 (e.g. `1280x720` or `1088x1920`) to satisfy hardware HEVC encoder alignment constraints.
</details>

---

## 🤝 Contributing

Contributions, bug reports, and feature requests are welcome! Feel free to check the [Issues](https://github.com/dimalinau-lab/Virtual-Camera-Android/issues) page.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git checkout -b feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for more information.
