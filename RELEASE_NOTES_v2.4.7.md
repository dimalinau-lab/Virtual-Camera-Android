# VirtualCam Companion (Android) v2.4.7

## 🚀 What's New in v2.4.7

### 1. 🔄 Gravity Vectoring Auto-Orientation & Desk Stand Support
- **Full Desk Stand & Tripod Reliability:** Standard Android `OrientationEventListener` disabled orientation detection at tilt angles > 30° (`ORIENTATION_UNKNOWN`). Replaced with direct accelerometer gravity vectoring (`atan2(-y, x)`), guaranteeing rock-solid detection at any tilt angle on charging stands, desks, and tripods.
- **Symmetric ±55° Hysteresis:** Prevents borderline flipping or jitter when transitioning between portrait and landscape.
- **Single Unified Sensor Pipeline:** Eliminated duplicate sensor listener race conditions and continuous 180° rotation loops.
- **Immediate First-Frame Posture Dispatch:** Sends the exact current device orientation in the very first TCP/UDP packet upon connection without waiting for physical movement.
- **16:9 Horizon Correction:** Fixed reverse landscape inversion formula for upright display in landscape mode.

### 2. ⚡ Subnet Broadcasts & 2-Way UDP Discovery
- **Subnet Directed Broadcasts:** Android beacon broadcaster queries network interfaces and broadcasts directly to local subnets (`192.168.x.255`, `10.x.x.255`) and `255.255.255.255`, piercing router Wi-Fi isolation.
- **Fast Startup Burst:** Emits initial 10 beacon packets at 200ms intervals for sub-second recognition on Windows.
- **Active Probe Response:** Listens on UDP port 8889 and responds to PC active probes in < 5ms.
- **QoS Low-Latency Sockets:** Configures `IPTOS_LOWDELAY` on streaming sockets for prioritized network routing.
