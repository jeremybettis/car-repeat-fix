# Car Repeat Fix (Android)

Some car Bluetooth head units have an AVRCP firmware bug where, immediately upon connecting to a phone, they send a command that enables **Repeat One Track** mode (`REPEAT_MODE_ONE`).

**Car Repeat Fix** is a lightweight Android utility that detects when your car connects over Bluetooth, watches active media sessions (including **YouTube Music**, **Castbox**, Spotify, and any standard Android `MediaSession`), and automatically switches Repeat One back to **Repeat Off**.

---

## Features

- **Targeted Car Bluetooth Detection:** Select your specific car Bluetooth device(s) from your paired list so the app only intervenes when you're in your car (or choose to guard on any Bluetooth connection / at all times).
- **Post-Connect Burst & Watch Window:** Car head units often send the bogus `REPEAT_MODE_ONE` command anywhere from 0.5s to 30s after Bluetooth A2DP/ACL connects. Car Repeat Fix runs a high-frequency burst check over a configurable post-connect watch window (15–180 seconds, default 60s) and optionally guards continuously for the entire drive.
- **Multi-Layer Repeat Detection & Reset:**
  1. **Standard `MediaSession` / `MediaControllerCompat`:** Listens for `onRepeatModeChanged(REPEAT_MODE_ONE)` and inspects `controller.repeatMode` and playback state extras, immediately sending `transportControls.setRepeatMode(REPEAT_MODE_NONE)`.
  2. **`PlaybackState` Custom Actions Fallback:** Apps like **YouTube Music** and **Castbox** sometimes expose repeat state via custom transport actions on `PlaybackState`. When enabled, Car Repeat Fix detects custom actions labeled *Repeat One* and triggers them to cycle repeat off.
  3. **Media Notification Action Fallback:** Inspects active media notifications for explicit *Repeat One* action buttons if a player doesn't report standard repeat flags.
- **Zero Battery Drain in Standby:** Uses Android's system-managed `NotificationListenerService` and manifest Bluetooth broadcasts (`ACL_CONNECTED`, `A2DP`, `HEADSET`)—no persistent foreground notification banner required.
- **Built-in Testing & Activity Log:** Includes a **Turn Off Repeat One Now** button, a **Simulate Car Connect** test button, and a timestamped activity log showing every time the app caught and disabled Repeat One.

---

## Installation & Setup

1. **Install the APK:**
   - Download the prebuilt APK directly: [**car-repeat-fix-v1.0.0.apk**](https://github.com/jeremybettis/car-repeat-fix/raw/main/release/car-repeat-fix-v1.0.0.apk) (or build from source below).
2. **Grant Notification Access (Required):**
   - Open **Car Repeat Fix** and tap **Open Notification Access Settings**.
   - Enable **Car Repeat Fix** (`Car Repeat One Blocker`). Android requires this permission to allow an app to inspect and control active `MediaSession`s across other media apps.
   - *Note on Android 13+ sideloaded APKs:* If Android says *"Restricted setting"* when enabling Notification Access, open **System Settings → Apps → Car Repeat Fix → ⋮ (top-right menu) → Allow restricted settings**, then enable Notification Access.
3. **Select Your Car Bluetooth Device:**
   - Tap **Grant Bluetooth Permission to List Paired Devices** and check the box next to your car's Bluetooth system.

---

## Building from Source

```bash
./gradlew test assembleDebug
```

The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`
