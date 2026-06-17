package app.somasafe.device

import java.util.UUID

/**
 * GATT attribute UUIDs exposed by the firmware (see firmware/main/ble/gatt.c
 * and the firmware READMEs). Mirrors firmware/scripts/lib/ble_common.py.
 */
object SomaSafeUuids {
    // Client buffer service (model upload).
    val BUF_CHR: UUID = UUID.fromString("8f04f3a6-1dcb-8a86-c04d-6fa38c87f25a")
    val BUF_STATE_CHR: UUID = UUID.fromString("794d330a-8c90-229b-b94c-d1025b1c7d19")
    val BUF_SIZE_DSC: UUID = UUID.fromString("237384c8-bf34-5587-ab48-ae4b0a7e2585")
    val BUF_POS_DSC: UUID = UUID.fromString("f5565c9c-b6be-57af-484a-0f2079374bae")

    // ML service (inference results + errors).
    val ML_RESULTS_CHR: UUID = UUID.fromString("7228d086-4fc1-4b9c-fa4d-1f715ac23c54")
    val ML_ERRORS_CHR: UUID = UUID.fromString("9c8b8c42-5a25-6ea5-c642-8a7acf1f330e")

    // PPG service (raw sensor stream).
    val PPG_DATA_CHR: UUID = UUID.fromString("b8e9a347-5c12-4f89-a7d3-2e1f6b0c4a9d")
}
