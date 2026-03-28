# NetSense Mesh

NetSense Mesh is an Android-first peer discovery and messaging prototype built with Kotlin Multiplatform (KMP), Jetpack Compose UI, and BLE transport logic.

The current implementation focuses on:
- discovering nearby app peers over BLE,
- establishing a BLE GATT-based logical connection flow,
- sending and receiving short text payloads between devices,
- showing live mesh state and event telemetry in the UI.

## Key Features

- BLE scan + advertise discovery pipeline
	- filtered scan by service UUID
	- automatic fallback to unfiltered scan for OEM compatibility
	- runtime permission checks and request flow
	- location-services guard for scan reliability

- Peer identity and de-duplication
	- device-specific local node ID generation
	- service-data-based peer IDs
	- self-advertisement filtering

- Connection and messaging
	- GATT client connect and service discovery
	- GATT server characteristic write handling
	- send/receive text payload path across two devices
	- explicit connect/disconnect actions in UI

- UI/UX
	- Compose-based dashboard
	- peer list with RSSI and state
	- selected peer actions (Connect, Send, Disconnect)
	- event timeline for debugging and user feedback

## Modules

- `androidApp/`
	- Android application module
	- `MainActivity` + splash/theme/resources
	- app-level permissions and runtime entrypoint

- `shared/`
	- KMP shared logic
	- common models/events/viewmodel/screens
	- Android BLE manager and service implementation

- `app/`
	- Separate Android + native/CMake/JNI experiment module
	- not required for `androidApp` BLE UI flow

- `include/`, `src/cpp/`, `src/android/`
	- native SDK/JNI core code paths and earlier integration work

## Architecture (High Level)

1. `MainActivity` creates `MeshManager` with a stable local node ID.
2. `AndroidMeshService` wraps manager callbacks and updates `MeshUiState`.
3. `MeshViewModel` exposes state/events to Compose.
4. `MeshHomeScreen` renders peers, status, actions, and logs.
5. BLE layer performs scan/advertise and GATT connect/write.

## Current User Flow

1. Launch app on two devices.
2. Tap `Start Discovery` on both devices.
3. Select discovered peer.
4. Tap `Connect`.
5. Once status is `Connected`, send message text.
6. Check event feed on both sides for send/receive traces.

## Build and Run

From project root:

```bash
./gradlew :androidApp:assembleDebug
```

Install resulting APK on two devices and grant requested Bluetooth/location permissions.

## Required Runtime Conditions

- Bluetooth enabled.
- Nearby devices permissions granted (Android 12+).
- Location permission granted.
- Location services enabled (important on many OEMs).

## Troubleshooting

- No peers discovered:
	- verify both devices granted permissions
	- verify location services ON
	- confirm `Start Discovery` on both devices

- Advertising errors in event log:
	- check `MeshManager` logs in logcat
	- some OEMs may require repeated start/stop or BLE reset

- Connected but no receive:
	- verify selected peer ID is the remote node
	- inspect `MeshManager` and `AndroidMeshService` logs for GATT write callbacks

## Known Limitations

- Prototype-level transport and state model.
- No message ACK/retry queue yet.
- No encryption/auth handshake yet.
- No persistent peer trust/profile store yet.
- Wi-Fi Direct path is not finalized in app UX.

## Next Recommended Improvements

- Add ACK + retry with timeout and delivery status.
- Add secure session setup (key exchange + encryption).
- Add per-peer connection health and reconnect strategy.
- Add integration tests for two-device messaging scenarios.
- Add production analytics and crash-safe telemetry.
