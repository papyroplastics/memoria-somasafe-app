# SomaSafe (Android Client)

Android app that acts as the on-device federated learning client: it recieves PPG data
from the ESP32 over BLE, runs local training with LiteRT, and shares only model updates
with the server — the ESP32 never touches the internet directly, and raw signal never
leaves the phone.

## Role in the full thesis system

See [`shared/docs/architecture.md`](../shared/docs/architecture.md) for the full system
design. In short:

- Connects to the ESP32 BLE peripheral (`firmware/`) and is its exclusive relay to the
  outside world.
- Hosts the on-device training/inference runtime and local model state: a float32
  trainable model for local LiteRT training, plus the int8 quantized shell relayed to the
  ESP32.
- Sends model updates (never raw data) to the server, gated behind sign-in and device
  ownership — see [`shared/docs/authentication.md`](../shared/docs/authentication.md) and
  [`shared/docs/device-attestation.md`](../shared/docs/device-attestation.md).

## Current implementation status

- Jetpack Compose UI (Material 3) with per-tab navigation: a Bluetooth tab (scan, connect,
  firmware install), a Captures tab, and a Backend tab (sign-in, model/firmware
  management, training).
- Full BLE relay to the firmware's contract — PPG streaming, model upload + staging,
  device attestation, OTA firmware updates — mirroring `firmware/scripts/`; see
  [`shared/docs/ble-protocol.md`](../shared/docs/ble-protocol.md).
- Capture storage (Room) and on-device preprocessing: recomputes missing features,
  derives the wearer's own z-score normalization parameters per capture group, and can
  import a subject dataset exported by the backend to test without streaming over BLE.
- On-device training: runs one local epoch over a processed capture group and reports
  reconstruction-error and loss metrics, from which the upload actions submit a
  personalized signed model or a raw federated update.
- Backend integration: versioned model + firmware downloads (gated on app compatibility),
  model inspection via the LiteRT JNI bridge, and both federated upload paths
  ("Upload & quantize" and "Submit only").

See [`shared/docs/app-internals.md`](../shared/docs/app-internals.md) for the project
structure, navigation graph, on-device model/capture storage layout, and the
capture → preprocess → train pipeline in detail.

## Build

- **minSdk**: 34, **targetSdk / compileSdk**: 35, **Kotlin**: 2.1.0, **Java target**: 17

```bash
make shared     # link or clone the shared/ repo
make assemble   # ./gradlew assembleDebug; make install runs installDebug
```

Set the backend URL (required) in `gradle.properties` or `local.properties`:

```properties
backend.url=http://your-server:8000
```

Debug builds permit cleartext HTTP to any host, so no LAN IP needs hardcoding; release
builds deny it. The capture-import schema (`shared/dataset.proto`) is compiled by the
protobuf Gradle plugin on every build — nothing checked in.

## Backend API

The app talks to the backend's `/model/*`, `/ota/*`, `/auth/*` and `/device/*` routes; see
[`shared/docs/server-internals.md`](../shared/docs/server-internals.md) for the endpoint
tables and rate limits, and
[`shared/docs/submission-type.md`](../shared/docs/submission-type.md) for which upload
actions a given model accepts.

## Manifest BLE configuration

- `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`
- `uses-feature android.hardware.bluetooth_le` required
