# VirtualCam Companion (Android) v2.4.6

## 🚀 What's New in v2.4.6

### 1. Seamless 0.5x Ultra-Wide Switching (Samsung Galaxy A34 / Dimensity / Exynos)
- Fixed complete camera pipeline deadlock and video freeze when switching to 0.5x ultra-wide on Samsung devices.
- Switched to Camera2 Logical Multi-Camera continuous zoom control (`CONTROL_ZOOM_RATIO = 0.5f` with Samsung vendor key `samsung.android.scaler.zoomMapRatio = 500`) without tearing down camera sessions.
- Automatically clamps capture FPS to 30 FPS (`[30, 30]`) on ultra-wide lenses to match hardware ISP throughput limits.
- Fires immediate IDR keyframes upon zoom transition for zero-stutter rendering on the Windows companion host.

### 2. Ergonomic Floating Panel Pills & Motion Physics
- Replaced 24dp chevron toggle with touch-friendly 36dp/40dp pill buttons ("Скрыть панель" / "Панель управления").
- Frosted glass design tokens and Emil Kowalski spring easing (< 220ms) for responsive one-thumb operation.

### 3. Front Camera Zoom Reset
- Cleanly resets zoom back to 1.0x when toggling between rear telephoto and front selfie cameras.

