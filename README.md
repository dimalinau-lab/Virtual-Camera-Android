# VirtualCamNative 🚀

> **Ultra-Low-Latency, High-Quality Virtual Camera Solution for Windows using Android as a High-Definition Hardware Source.**

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-purple.svg?style=for-the-badge&logo=kotlin)](https://kotlinlang.org/)
[![C++20](https://img.shields.io/badge/C++-20-blue.svg?style=for-the-badge&logo=cplusplus)](https://isocpp.org/)
[![Android](https://img.shields.io/badge/Android-8.0%2B-green.svg?style=for-the-badge&logo=android)](https://developer.android.com/)
[![Windows](https://img.shields.io/badge/Windows-10%2F11-0078D6.svg?style=for-the-badge&logo=windows)](https://www.microsoft.com/)
[![DirectShow](https://img.shields.io/badge/Driver-DirectShow%20%2F%20MF-orange.svg?style=for-the-badge)](https://docs.microsoft.com/en-us/windows/win32/directshow/directshow)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)

**VirtualCamNative** is a zero-cost, open-source, ultra-low latency system designed to replace commercial proprietary apps like DroidCam and Iriun. By leveraging **hardware-accelerated HEVC (H.265) encoding** via Android's `MediaCodec`, zero-copy CameraX surface pipelines, and a native **C++20 DirectShow / Media Foundation virtual camera driver**, VirtualCamNative streams pristine, smooth 1080p/60fps video directly into **Discord, OBS Studio, Zoom, Telegram, and WebRTC browsers** with under **30–40ms glass-to-glass latency**.

---

## 🌟 Key Features

- ⚡ **Ultra-Low Latency (< 40ms Glass-to-Glass)**  
  Custom raw TCP transport layer (`TCP_NODELAY`, 256KB non-blocking socket buffers, single-frame TCP flushes) paired with hardware HEVC decoding for real-time responsiveness.
- 🎬 **Hardware HEVC / H.265 Compression**  
  Uses Android's GPU `MediaCodec` hardware encoder with CBR/VBR modes to maximize image sharpness while minimizing CPU usage and thermal throttling on mobile devices.
- 📱 **Hardware Screen Blackout Mode**  
  Prevents OLED/AMOLED screen burn-in and conserves battery during long streams by reducing backlight intensity (`0.01f`) with a full-screen tap-to-wake overlay.
- 🛰️ **Dual Connection Modes & Auto-Discovery**  
  - **USB Mode:** Zero-lag streaming via `adb forward`.  
  - **Wi-Fi Mode:** Automatic local network device discovery via **UDP Beacon Broadcasting** (`255.255.255.255:8888`) — no manual IP entry required.
- 🔄 **Instant Orientation & Camera Switching**  
  Switch between landscape/portrait aspect ratios or front/back cameras dynamically without disconnecting the RTSP/TCP socket or recreating the video encoder.
- 🔋 **Foreground Service Protection**  
  Runs inside a dedicated Android `Foreground Service` (`camera` type) protected by `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF` to ensure rock-solid stability even when minimized.
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
| **Screen Burn-in Protection (Blackout)** | **Yes (0.01f Backlight Overlay)** | Paid Feature | Not Available |
| **Wi-Fi Auto-Discovery** | **Yes (UDP Beacon :8888)** | Partial | Yes |
| **Ads / Resolution Limits** | **None (Full 1080p/4K Unlocked)** | HD Locked / Ads | Watermarked / Paid |
| **Instant Aspect Switch** | **Yes (Zero-Disconnect)** | Requires Disconnect | Requires App Restart |

---

## 📐 System Architecture

```
 +-------------------------------------------------------------------------------+
 |                           ANDROID CLIENT (KOTLIN)                             |
 |                                                                               |
 | [ CameraX Pipeline ] ---> [ MediaCodec HEVC ] ---> [ Raw TCP Streamer :8554 ]  |
 |                                                                               |
 | [ ControlServer :8080 ] <--- REST API --- [ UDP Beacon Broadcaster :8888 ]    |
 +-------------------------------------|-----------------------------------------+
                                       | TCP Video Stream / HTTP REST / UDP
                                       v
 +-------------------------------------------------------------------------------+
 |                        WINDOWS NATIVE CLIENT (C++20)                          |
 |                                                                               |
 |  [ Socket Receiver ] ---> [ FFmpeg libavcodec ] ---> [ Fast Frame Rotator ]   |
 |                                                                               |
 |  [ WebView2 GUI ] <--- IPC ---> [ Shared Memory (Win32 MMF + Events) ]       |
 +-------------------------------------|-----------------------------------------+
                                       |
                                       v
 +-------------------------------------------------------------------------------+
 |                  NATIVE VIRTUAL CAMERA DRIVER (DIRECTSHOW / MF)               |
 |                                                                               |
 |  [ NativeMFVirtualCam.dll ] <--- Reads Shared Memory Frame Buffer             |
 +-------------------------------------|-----------------------------------------+
                                       |
                                       v
 +-------------------------------------------------------------------------------+
 |                            CONSUMING APPLICATIONS                             |
 |                                                                               |
 |  Discord  |  OBS Studio  |  Zoom  |  Telegram  |  WebRTC Browsers             |
 +-------------------------------------------------------------------------------+
```

---

## 🚀 Quick Start

### USB Mode (Lowest Latency)
1. Enable **USB Debugging** on your Android phone (*Settings -> Developer Options -> USB Debugging*).
2. Connect your phone to your PC via USB cable.
3. Setup ADB port forwarding:
   ```bash
   adb forward tcp:8080 tcp:8080
   adb forward tcp:8554 tcp:8554
   ```
4. Launch **VirtualCamNative** on your phone and open the Windows Desktop Client.
5. Select **USB Mode** and click **Connect**.

### Wi-Fi Mode (Wireless)
1. Connect both your phone and PC to the same local Wi-Fi router network.
2. Open **VirtualCamNative** on your phone.
3. The PC client will automatically detect your device via **UDP Discovery** (`255.255.255.255:8888`).
4. Click **Connect** on the PC client to begin streaming.

---

## 🌐 Control Server REST API

The Android client runs an embedded HTTP REST server on port `8080` (`ControlServer`) to allow remote configuration and execution of hardware commands.

| Endpoint | Method | Request Payload / Params | Description |
| :--- | :---: | :--- | :--- |
| `/api/status` | `GET` | - | Returns JSON status: streaming state, camera facing, torch state, and orientation mode. |
| `/api/connect` | `POST` | `{"mode": "usb" \| "wifi"}` | Triggers streaming pipeline initialization and requests an immediate H.265 I-Frame. |
| `/api/disconnect` | `POST` | - | Safely stops video streamer and releases video encoding resources. |
| `/api/config` | `POST` | `{"resolution": "1080p", "fps": 30, "bitrate": 7000000}` | Updates encoder resolution (`720p`, `1080p`, `4k`), FPS, and bitrate dynamically. |
| `/api/action` | `POST` | `{"action": "switch_camera" \| "toggle_torch" \| "toggle_blackout"}` | Toggles front/back camera, torch LED, or screen blackout mode. |
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
git clone https://github.com/your-username/Virtual-Camera-Android.git
cd Virtual-Camera-Android

# Build Debug APK
./gradlew assembleDebug
```

### Windows Client & Virtual Camera Driver
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

Contributions, bug reports, and feature requests are welcome! Feel free to check the [Issues](https://github.com/your-username/Virtual-Camera-Android/issues) page.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git checkout -b feature/AmazingFeature`)
5. Open a Pull Request

---

## 📄 License

Distributed under the **MIT License**. See [`LICENSE`](LICENSE) for more information.
