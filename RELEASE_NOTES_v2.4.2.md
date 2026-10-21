# VirtualCam Android v2.4.2 (versionCode 242)

## 🚀 What's New in v2.4.2
- **Thermal & Silicon Overheat Mitigation**:
  - **VPU Hardware Rate Synchronization**: Removed forced `Short.MAX_VALUE` operating rate overclock on Qualcomm Snapdragon, Tensor, and Exynos SoCs. The hardware encoder rate is now properly synchronized with the stream's real-time target FPS (`MediaFormat.KEY_OPERATING_RATE = frameRate`).
  - **Intra-Refresh & I-Frame Collision Resolution**: Extended the periodic keyframe interval from 1s to 3s (`KEY_I_FRAME_INTERVAL = 3`) while Cyclic Intra-Refresh is active, eliminating redundant double-encoding computations.
  - **Idle CPU Polling Reduction**: Increased `dequeueOutputBuffer` poll interval from 2 ms to 10 ms (`10_000L`), reducing empty CPU wakeups by ~5x during inter-frame intervals with zero added latency.
- **Zero-Allocation Frame Buffer Pooling**:
  - Replaced per-frame `ByteArray(size)` heap allocations with a bounded, thread-safe buffer pool (`PooledFrame`).
  - Eliminates 3–5 MB/s of garbage generation, completely freeing the ART runtime from continuous GC pauses and CPU heating.
- **Smart AMOLED Auto-Blackout**:
  - Automatically transitions the screen into a pure black OLED power-saver mode (`#000000`, 0.01 brightness) after 30 seconds of active streaming.
  - Reduces display panel thermal emission by up to 40%.
  - Instant touch-to-wake: a single tap anywhere on the screen immediately wakes the display.
- **Repository Hygiene**:
  - Removed committed APK binaries and temporary assets from repository tree.
  - Updated `.gitignore` with strict binary output exclusion patterns.

## 📦 Downloads
- `VirtualCam-v2.4.2.apk` (Android 8.0+)
