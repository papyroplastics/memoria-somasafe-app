package app.somasafe.bluetooth.data

import java.util.UUID

/**
 * GATT attribute UUIDs exposed by the firmware (see firmware/main/ble/gatt.c
 * and the firmware READMEs). Mirrors firmware/scripts/lib/ble_common.py.
 */
object SomaSafeUuids {
    // Client buffer attributes. The same generic UUIDs appear in every service
    // that embeds a client buffer (ML model upload, device signing), so they
    // must be resolved within a specific service (see BleConnection).
    val BUF_CHR: UUID = UUID.fromString("8f04f3a6-1dcb-8a86-c04d-6fa38c87f25a")
    val BUF_STATE_CHR: UUID = UUID.fromString("794d330a-8c90-229b-b94c-d1025b1c7d19")
    val BUF_SIZE_DSC: UUID = UUID.fromString("237384c8-bf34-5587-ab48-ae4b0a7e2585")
    val BUF_POS_DSC: UUID = UUID.fromString("f5565c9c-b6be-57af-484a-0f2079374bae")

    // ML service (inference results + errors) — embeds the model-upload buffer.
    val ML_SVC: UUID = UUID.fromString("a4523840-7543-2492-fe43-b7dad4432738")
    val ML_RESULTS_CHR: UUID = UUID.fromString("7228d086-4fc1-4b9c-fa4d-1f715ac23c54")
    val ML_ERRORS_CHR: UUID = UUID.fromString("9c8b8c42-5a25-6ea5-c642-8a7acf1f330e")

    // Device service — attestation: serial readout, signing (buffer + signature
    // notify characteristic).
    val DEVICE_SVC: UUID = UUID.fromString("d7385c91-b20d-6ea1-3d4f-847be1559a2c")
    val DEVICE_SIGN_CHR: UUID = UUID.fromString("0af214b8-6c7e-3390-a947-05c2638b1d4f")
    val DEVICE_SERIAL_CHR: UUID = UUID.fromString("d1330f9b-7e1c-a588-524e-0c3d71f4296b")

    // PPG service (raw sensor stream).
    val PPG_DATA_CHR: UUID = UUID.fromString("b8e9a347-5c12-4f89-a7d3-2e1f6b0c4a9d")
}
