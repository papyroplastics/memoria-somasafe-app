# SomaSafe (Android Client)

Android app intended to be the on-device federated learning client: ingest BLE data from ESP32, train locally with LiteRT, and share only model updates with the server.

## Role in the full thesis system

See [`shared/docs/architecture.md`](shared/docs/architecture.md) for the full system
design. In short:

- Connects to the ESP32 BLE peripheral (`firmware/`) and acts as its exclusive relay to the outside world — the ESP32 never touches the internet.
- Hosts the on-device training/inference runtime and local model state: a float32 trainable model for local LiteRT training plus the int8 quantized shell that gets relayed to the ESP32.
- Acts as the federated-learning participant that sends model updates (not raw user data), gated behind sign-in and device ownership (see [`shared/docs/authentication.md`](shared/docs/authentication.md) and [`shared/docs/device-attestation.md`](shared/docs/device-attestation.md)).

## Current implementation status

Implemented now:

- Jetpack Compose app shell with Material 3 and Jetpack Navigation (per-tab back stacks).
- Runtime Bluetooth permission flow (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`).
- BLE scan UI for discovered + paired devices.
- GATT connection view with:
  - connect/disconnect lifecycle handling (thread-safe via `MutableStateFlow`)
  - MTU requests
  - service discovery
  - read/write against discovered characteristics
- Backend tab with server sign-in (stateful token session) gating versioned model downloads and federated uploads (which also require an attested device); models incompatible with the app (`min_app_version`) are surfaced as such.
- Firmware distribution: the Backend tab lists the builds published for the app's `BLE_INTERFACE_VERSION` (version, supported model contracts, size, release date) with download + local management (list/delete), feeding the BLE OTA install flow.
- Device attestation on the connected-device screen: read the device serial, have the ESP sign a server challenge, and register ownership with the backend to unlock model downloads.
- Model inspector (under the Backend tab): lists downloaded `.tflite` files and introspects their signatures, tensor shapes, and quantization via LiteRT JNI bridge.
- Backend URL via the required `backend.url` gradle property (the build fails if it is unset).
- Firmware BLE contract on the connected-device screen (mirrors `firmware/scripts/`; see [`shared/docs/ble-protocol.md`](shared/docs/ble-protocol.md) for the protocol itself):
  - Coroutine GATT wrapper (`BleConnection`) with suspend reads/writes and notification flows.
  - Model upload over the client-buffer service, then mark it READY for the on-device ML task.
  - Device attestation: upload the challenge payload to the device sign buffer and receive the DER signature (`DeviceService`), mirroring `test_sign.py`.
  - Reconstruction-layer reassembly (`TransactionReassembler`) shared by all services.
  - PPG window parsing (raw PPG/ACC + on-device timestamps) and ML result parsing (features + score).
  - OTA firmware updates (`FirmwareUpdate`): pick a downloaded image on the install screen,
    stream it plus its server signature to the device's OTA service with progress, and wait
    for the on-device verification before it reboots into the new build (mirrors `test_ota.py`).
  - Capture sessions persisted with Room: raw samples and inference results stored side by side, matched by sequence number within a sample group.
- Captures tab: list / delete stored capture groups, **Process** a group through the on-device preprocessing pipeline, and import a subject dataset (`.ssds`) exported by `backend/scripts/export_subject_data.py`. The `.ssds` file is a `somasafe.capture.CaptureDataset` protobuf (schema in `shared/dataset.proto`); imported windows are stored as ordinary samples — sequence number, on-device timestamps, a back-dated receive time, raw PPG/ACC, raw float32 features, optional float32 context, label in the score field — indistinguishable from ESP captures, so they preprocess and train identically without streaming over BLE. The export can also model packet loss: a window may carry signal but no ML result (the phone recomputes features), a result but no signal, or be dropped entirely (a hole in the sequence numbers), so import handles each missing-data case just as a lossy live capture would.
- On-device preprocessing pipeline (capture module): replicate the firmware's 17-feature `extract_features` (via JDSP for the FFT) so windows whose ML result was lost still get a feature vector (stored without a score), and compute each window's raw activity context from the prior windows in its trailing two minutes.
- On-device training (training module): pick a downloaded model, run a local epoch over a processed capture group (starting from the weights baked into the trainable artifact, or from a previous local epoch's `trained_weights.bin`), and write the trained weights to `trained_weights.bin` (with the training baseline in `base_weights.bin`) — from where the upload actions submit the delta (`trained − global`) as the federated update ("Upload & quantize" returns a personalized signed int8 artifact; "Submit only" sends the update and nothing comes back). Reached from the model detail screen ("Train on capture…"). Inputs are fed **raw**; the trainable model z-scores its own `signal` and `cond` in the `train`/`eval` signatures, so no normalization params are pulled or applied on the phone.
- Device payload assembly: the app frames the signed quantized model (signature + contract version + norm params + tflite, per the BLE interface version — see `shared/docs/model-signing.md`) before staging it onto the ESP32.
- Demographics: an editable form (Captures tab) sets the default static (6-d demographics) vector — the on-device stand-in for the DaLiA questionnaire. It is stamped onto each capture group as it is recorded, backfilled onto older groups that predate it when the form is saved, and returned as the fallback when a group has no static of its own; imported datasets instead carry their own subject's static (embedded by `export_subject_data.py`).

Not implemented yet:

- Upload retry strategy.

## Build

- **minSdk**: 34
- **targetSdk / compileSdk**: 35
- **Kotlin**: 2.1.0
- **Java target**: 17
- Version catalog: `gradle/libs.versions.toml`

```bash
make shared     # link or clone the shared/ repo (see below)
make assemble   # ./gradlew assembleDebug; make install runs installDebug
```

`Makefile` helpers:

- `make shared` → links `../shared` (monorepo layout) or, when absent, clones
  [`memoria-somasafe-shared`](https://github.com/papyroplastics/memoria-somasafe-shared.git)
  into `shared/` (gitignored) so the project builds standalone.
- `make assemble` / `make install` → `./gradlew assembleDebug` / `installDebug` (both depend on `shared`).
- `make uninstall`, `make clean`.

Set the backend URL (required) in `gradle.properties` or `local.properties`:
```properties
backend.url=http://your-server:8000
```

Debug builds permit cleartext HTTP traffic to any host (via `src/debug/res/xml/network_security_config.xml`), so no LAN IP needs hardcoding. Release builds deny cleartext.

### Generated code

The capture-import schema is the shared protobuf at `shared/dataset.proto`, linked or cloned by `make shared` (see [Build](#build)). The `com.google.protobuf` Gradle plugin compiles it with `protoc` (java-lite) on every build into `app/build/generated/source/proto/` — no manual step, nothing checked in. The generated classes live in `app.somasafe.capture.proto`.

## Project structure

Each feature module is split into an MVC trio: `ui/` (Compose screens, UI elements and
state), `domain/` (business logic and operations) and `data/` (everything outside the app:
APIs, databases, Bluetooth and the file system).

```
app/src/main/
  kotlin/app/somasafe/
    App.kt, MainScreen.kt    Activity entry point + Compose theme; NavHost scaffold (one nested graph per tab)
    bluetooth/               BLE relay to the ESP32: GATT connection, the firmware's BLE contract
                             (reconstruction-layer reassembly, PPG/ML parsing, model upload/staging,
                             device attestation) and capture-session orchestration
    capture/                 Capture storage + on-device preprocessing (Room db, 17-feature extraction
                             mirroring the firmware, activity-context computation, .ssds dataset import,
                             demographics form) — no device interaction
    training/                On-device LiteRT training: JNI wrapper + local-epoch trainer over a
                             processed capture group
    backend/                 Server session (sign-in, token store), model list/download/introspection,
                             upload actions
  cpp/
    litert/                  Git submodule with LiteRT abstractions (own README.md)
    jni_bridge.cpp           JNI glue code for the litert submodule
```

The native methods of `training/domain/LiteRtModel.kt` are bound by package name, so
`cpp/jni_bridge.cpp` references `app/somasafe/training/domain/*` for its `FindClass` lookups
and `Java_app_somasafe_training_domain_LiteRtModel_*` symbols.

## Navigation

`MainScreen.kt` hosts a Jetpack `NavHost` with type-safe (`@Serializable`) routes, split into
one nested graph per bottom-nav tab so each tab keeps its own back stack. Tapping a tab navigates
to that graph with `saveState`/`restoreState`/`launchSingleTop`, so switching away and back
restores where you left off (e.g. straight back to the connected-device screen). The top bar shows
a back arrow on the non-root destinations (`DeviceDetail`, `FirmwareInstall`, `ModelList`,
`FirmwareList`, `ModelDetail`, …).

```
BluetoothTab  → DeviceList (FindDevicesScreen), DeviceDetail (ConnectDeviceScreen),
                FirmwareInstall (FirmwareInstallScreen)
CapturesTab   → Captures (CaptureScreen), Demographics (DemographicsScreen)
BackendTab    → BackendLogin (BackendLoginScreen), BackendHome (BackendDownloadScreen),
                ModelList (ModelListScreen), FirmwareList (FirmwareListScreen),
                ModelDetail(key) (ModelDetailScreen), TrainingRoute(key) (TrainingScreen)
```

The `BackendTab` graph starts at `BackendLogin`, which redirects to `BackendHome` when a session
already exists; signing in advances to `BackendHome`, logging out returns to `BackendLogin`.
`BackendHome` has a "Models" and a "Firmware" section, each listing what the server publishes
with download actions and an arrow into its local-management screen: `ModelList` (and from there
a model's `ModelDetail`) and `FirmwareList` (downloaded images with a delete action). The
connected-device screen's "Update firmware" opens `FirmwareInstall`, which lists the downloaded
images, streams the selected one over the OTA service with progress, and pops back once the
device verifies it (the device then reboots, dropping the connection).

The live Bluetooth session (`device` + `connection` + `controller`) is owned by the bluetooth
module via `rememberBluetoothSession`, provided above the `NavHost` through
`LocalBluetoothSession` so it survives tab switches; it is cleared (GATT closed) only when the user
navigates back off `DeviceDetail`, not on tab switches. `MainScreen` no longer wires BLE internals.

The Backend tab's `/model/list` fetch is cached in `BackendModelsViewModel`, scoped to the
`BackendHome` nav entry (retained across tab switches), so it runs once per launch rather than on
every re-entry; pull-to-refresh reloads it. The list is intentionally left stale until refreshed.

## On-device model storage

Models are stored under `context.filesDir/models/`. Each model gets its own subdirectory named after the model key:

```
models/
  <key>/
    trainable.tflite     # trainable LiteRT flatbuffer with the global weights baked in, zstd-compressed (TRAINABLE_FILENAME)
    base_weights.bin     # global snapshot training started from, raw LE float32 (BASE_WEIGHTS_FILENAME)
    trained_weights.bin  # absolute trained weights, raw LE float32 (TRAINED_WEIGHTS_FILENAME)
    quantized.tflite     # int8 tflite downloaded or produced by the backend, zstd-compressed (QUANTIZED_FILENAME)
    quantized.json       # signed fields delivered with it: signature, contract version, norm params (QUANTIZED_META_FILENAME)
    meta.json            # RemoteModel snapshot incl. the downloaded weights_id / weights_version (META_FILENAME)
```

The backend serves `trainable.tflite`, `quantized.tflite` and the firmware image
zstd-compressed and they are stored on disk exactly as served (the signed fields cover
the *raw* bytes, so compression is transport-only). Consumers never read these files
directly — they go through the decompressing readers (`readTrainableBytes`,
`readQuantizedBytes` in `ModelDownloader.kt`, `readFirmwareImage` in `FirmwareDownloader.kt`),
which zstd-decompress on load; the trainable model's raw bytes are handed to the native
LiteRT loader (`LiteRtModel(ByteArray)`), which builds the model from a buffer. The two
`.bin` weight blobs are written locally by training and are not compressed.

The file-name constants and all path helpers (`modelsDir`, `modelDir`, `metaFile`, `trainableFile`, `baseWeightsFile`, `trainedWeightsFile`, `quantizedFile`, `quantizedMetaFile`) live in `ModelDownloader.kt`. `ModelListScreen` lists the subdirectories that have a trainable model and shows whether a quantized variant exists and whether it is outdated relative to `trained_weights.bin`. `ModelDetailScreen` inspects the trainable model and exposes the download-quantized / upload actions; the same actions are available per-model on the Backend tab.

Firmware images live alongside the models under `context.filesDir/firmware/<version>/`:
`firmware.bin` (the app image, zstd-compressed as served; decompressed via
`readFirmwareImage` before the OTA service streams it) plus `meta.json` (the
`RemoteFirmware` snapshot — interface version, supported contracts, release date — and the
base64 `X-Firmware-Signature` the device verifies). Helpers (`firmwareDir`, `listLocalFirmware`,
`downloadFirmware`, `deleteFirmware`, `readFirmwareImage`) live in `FirmwareDownloader.kt`; `FirmwareListScreen`
manages the downloads and `FirmwareInstallScreen` (Bluetooth tab) installs them.

### Weights ride the trainable artifact

The backend bakes the current global weights into every trainable `.tflite` it serves, so downloading the model *is* downloading the weights — there is no separate weights pull. `downloadTrainable` records the snapshot the artifact carries (the `X-Weights-ID` / `X-Weights-Timestamp` / `X-Model-Fingerprint` / `X-Model-Version` headers) in `meta.json`; when the version or fingerprint moved relative to what was stored, the local federated state (`base_weights.bin`, `trained_weights.bin`, quantized artifact) is deleted first, since it belongs to the superseded version.

The two `.bin` files hold only a **locally trained update**, written solely by on-device training as raw little-endian float32 blobs: `trained_weights.bin` (the absolute trained weights) and `base_weights.bin` (the global snapshot baked into the trainable that training started from). The submission body is the **delta** `trained − base` (see [federated uploads](#quantized-model-and-federated-uploads)), computed at upload time and never stored; the absolute weights are kept so a later epoch can resume from them, and the base so the delta stays correct even if the trainable is re-downloaded. The base snapshot's id is not stored with the weights — it is read from `meta.json` (`weights_id`) at upload time. `weightsStatus` (in `Weights.kt`) reports it `MISSING` (not trained yet — not an error), `OUTDATED` (the trainable file is newer than `trained_weights.bin`, i.e. it was re-downloaded after training, matching how `quantStatus` compares mtimes) or `CURRENT`. Whether the *model* is up to date is a separate check on the download screen: `meta.json`'s `version`/`fingerprint`/`weights_version` against the live `/model/list` entry.

### Quantized model and federated uploads

The device runs int8 inference, so it is the quantized model that gets staged during capture. There are two ways to obtain it, matching the backend's two submission paths:

- **Download quantized** — plain `GET /model/download/quantized/<key>`: the current global int8 artifact. No training required.
- **Upload & quantize** — `uploadAndQuantize` POSTs the trained update as a weight **delta** (`trained − global`, a little-endian float32 body of `weight_count` floats) to `POST /model/submit/quantize/<key>/<weights_id>` (the base `weights_id` rides the URL and pins the snapshot the update was trained against; that submission is the federated update). The backend enqueues a job and replies `202 {"job_id"}`; the app polls `GET /model/quantize/result/<job_id>` (`202` pending, `200` done, `422` failed) and stores the personalized int8 result.
- **Submit only** — `submitOnly` POSTs the same delta body to `POST /model/submit/raw/<key>/<weights_id>`; the update feeds aggregation and nothing comes back (validation is server-side and silent).

Which paths a model accepts is set per model by its `submission_type` (from `/model/list`): a `quantize` model accepts both, a `raw` model accepts only "Submit only" (the quantize path returns `404` for it). The app hides "Upload & quantize" for `raw` models. Server-side, aggregation adds the averaged delta back onto the global weights, so a client that trained nothing submits ≈0 and moves the global model not at all.

In both download paths the response body is the bare int8 `.tflite` and the signed fields travel in headers (`X-Model-Signature`, `X-Contract-Version`, `X-Norm-Params`, base64 where binary); they are stored as `quantized.tflite` + `quantized.json`. The app itself assembles the device payload from them at staging time (`bluetooth/domain/ModelPayload.kt`, per `shared/docs/model-signing.md`) — the server signature is transport-independent and verified by the firmware.

Both the download screen and the model detail screen (both under the Backend tab) expose these actions ("Upload & quantize" only for `quantize`-type models); the upload buttons stay disabled until on-device training has produced `trained_weights.bin`. Weight handling lives in `backend/data` and has no dependency on the LiteRT code; `quantStatus` (also in `Weights.kt`) flags the quantized artifact as stale when `trained_weights.bin` is newer than it, and missing when the signed fields (`quantized.json`) are absent.

## Backend API (expected endpoints)

All `/model/*` routes require a bearer token (see [Authentication](#authentication)) and
the caller to be a verified device owner ([`shared/docs/device-attestation.md`](shared/docs/device-attestation.md)).

| Method | Path | Response |
|--------|------|----------|
| GET | `/model/list` | JSON array of `RemoteModel` objects (latest version of each model) |
| GET | `/model/download/trainable/<key>` | Trainable `.tflite` with the global weights baked in; `X-Model-Fingerprint` / `X-Model-Version` / `X-Weights-ID` / `X-Weights-Timestamp` headers |
| GET | `/model/download/quantized/<key>` | Global int8 `.tflite`; version headers plus the signed fields (`X-Model-Signature` / `X-Contract-Version` / `X-Norm-Params`) |
| POST | `/model/submit/quantize/<key>/<weights_id>` | LE float32 weight-delta body → `202` `{"job_id"}` (federated update + quantize job; `quantize`-type models only, else `404`) |
| GET | `/model/quantize/result/<job_id>` | Long-polls the job: `202` while pending/running (the request may block server-side up to ~30 s waiting on the task), `200` int8 `.tflite` + signed headers when done, `422` on failure |
| POST | `/model/submit/raw/<key>/<weights_id>` | LE float32 weight-delta body → `202` `{"submission_id"}` (submit-only federated update) |
| GET | `/ota/versions/<interface>` | JSON array of firmware builds published for a BLE interface version, newest first |
| GET | `/ota/download/<interface>/<version>` | Raw firmware image; the server's ECDSA over it in `X-Firmware-Signature` (base64), forwarded verbatim to the device's OTA service |

`RemoteModel` JSON fields: `key`, `name`, `purpose`, `firmware_id` (int or null), `min_app_version`, `fingerprint`, `version` (int, hand-bumped server-side), `contract_version`, `weight_count`, `submission_type` (`"raw"` | `"quantize"` — which upload path the model accepts, per model rather than per deployment), `weights_version` (timestamp or null). A model is "up to date" locally when `version`, `fingerprint` and `weights_version` match upstream; a moved `version`/`fingerprint` invalidates the local federated state (the app resets it on the next download) — see [`shared/docs/versioning.md`](shared/docs/versioning.md) for what each of these means. Submitting against stale base weights — a superseded version, or an older weights snapshot of the current version — returns `409`; the delta is only accepted against the active weights the trainable was downloaded with, so the client must re-download the latest weights first. The app also checks `min_app_version` against its own version and disables incompatible models.

`RemoteFirmware` JSON fields: `version` (arbitrary build string), `interface_version`,
`supported_contracts` (int array — the model contract versions the build can run), `size`,
`created_at`. The app only ever asks for its own interface (`BLE_INTERFACE_VERSION` in
`bluetooth/data/SomaSafeUuids.kt`, mirroring `firmware/main/ble/host.h`); the phone is
authoritative for what gets installed — the device performs no version checks.

Rate limiting is per-model (`backend/README.md` has the limits): model and firmware downloads
have a short cooldown and the two upload paths are capped per day, so a repeat on the same model
returns `429` until it clears (the client surfaces this with the `Retry-After` hint).

## Authentication

See [`shared/docs/authentication.md`](shared/docs/authentication.md) for the session
model. On the app side: tokens and the username are kept in
`EncryptedSharedPreferences` (`AuthStore`); requests attach the access token and
transparently refresh it once on a `401`. `Auth.kt` owns the token store and the
`/auth/*` networking; the model-download helpers in `ModelDownloader.kt` route through a
shared authed-request wrapper.

## Manifest BLE configuration

- `BLUETOOTH_SCAN` (`neverForLocation`)
- `BLUETOOTH_CONNECT`
- `uses-feature android.hardware.bluetooth_le` required

## Capture and persistence

A capture session is driven from the connected-device screen:

1. Pick a downloaded model and **Load** it — the bytes are staged into the device's client buffer and marked READY; the model's input/output tensor sizes are read via the LiteRT bridge to know the result layout.
2. **Start capture** opens a `sample_groups` row and subscribes to the PPG and ML services. Each PPG window and each inference result is parsed and written into the `samples` table, merged into one row by the service-layer sequence number (raw data and result can arrive in either order).
3. **Stop capture** closes the group (`endedAt`).

Sequence numbers are unique only within a group — the firmware resets them on reboot — so matching is always scoped to a single group. Samples may be lost mid-group without affecting the rest. Each sample stores the raw PPG/ACC floats, the on-device start/end timestamps, the receive time, and (when present) the raw float32 features, the int8 model score, and the raw float32 activity context. Each group also carries a `static` (6-d demographics) blob: the default demographics for a live capture, or the imported dataset's own subject. Groups with no static of their own (e.g. captures recorded before demographics were set) are backfilled with the default when the form is saved, and fall back to it on read; only when there is no default either does the group read back as having no static. The Room database lives at `capture.db` (v2 adds the `context` column; v3 the group `static`).

## On-device preprocessing (Process)

The **Process** action on a capture group (`capture/domain/CapturePipeline.kt`) runs two passes over the group's stored windows, and is idempotent (only fills what's missing):

1. **Features** — the device may not return an ML result for every window (an error, or a dropped packet). For any complete window (raw PPG + ACC) without a feature vector, the app recomputes the same 17 features the firmware echoes (`WindowFeatures.kt`, matching `firmware/main/ml/features.c` / `extract_features`; JDSP supplies the FFT) and stores them **without a score** — running the model just to fill the score would waste computation.
2. **Context** — each window's activity context (`WindowContext.kt`) is the mean/std of the raw ACC magnitude over the **prior** windows that ended within the trailing two minutes (by device time, `deviceStartMs`/`deviceEndMs`). A capture can be discontinuous, so this is lenient: it needs at least two-thirds of the expected ~15 context windows present (≥10), otherwise the window has too little context and is left without one (excluded from training).

Both features and context are stored **un-normalized** and fed to the model raw; the model z-scores its own inputs in the `train`/`eval` signatures. This mirrors `backend/ml/data.py`, where features are stored raw and the activity context is computed from the raw signal, with normalization owned by the model rather than the load path.

The `.ssds` import is a `somasafe.capture.CaptureDataset` protobuf (`shared/dataset.proto`, generated into the build dir by the protobuf Gradle plugin — see [Generated code](#generated-code)). Each window carries its sequence number, and — alongside any signal data — faked device timestamps (a contiguous 8 s grid from a random boot offset) and, when `export_subject_data.py --include-context` was used, its raw context. A window may legitimately arrive with signal but no features, features but no signal (no device timestamps, exactly like a result-only row), or be absent entirely (a gap in the sequence numbers). Because windows that carry signal have device timestamps just like ESP samples, **Process** computes context for them too (re-run only when context wasn't embedded); the receive time is stamped by the phone at import, back-dated per sequence step so dropped windows widen the gap.

## On-device training (Train on capture)

Reached from a model's detail screen ("Train on capture…" → `TrainingScreen`). `training/domain/Trainer.kt` runs one local epoch:

1. Loads the trainable LiteRT model (its baked-in weights are the global snapshot) and, when a previous epoch left a `trained_weights.bin`, restores those on top.
2. Reads the train signature via `describe()` for its batch size and input order.
3. Assembles windows from a processed capture group — those with PPG + ACC + context. Per window it builds the raw `[BVP, ACC]` signal frame (ACC resampled 256→512, matching `backend/ml/data.py` `_interp_acc`) and the 8-d conditioning vector `[static(6), context(2)]`, both fed raw — the model z-scores them. The score/label is unused (the autoencoder is self-supervised). Windows are order-independent, so it trains whichever full batches of context-bearing windows exist and drops the remainder.
4. Writes the trained weights to `trained_weights.bin` and the starting global snapshot to `base_weights.bin`, which marks the quantized artifact outdated. The "Upload & quantize" / "Submit only" actions then submit the delta `trained − base` (pinned to the base `weights_id` from `meta.json`) — that submission is the federated update.

It requires the model to be downloaded; `TrainingScreen` gates on that. The default static comes from the Demographics form; imported groups use their own.

