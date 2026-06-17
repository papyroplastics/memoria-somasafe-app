package app.somasafe.device

/**
 * Client side of the firmware reconstruction layer (notif_transaction).
 *
 * Reassembles arbitrary-length payloads ("transactions") fragmented across GATT
 * notifications. Each notification starts with a 3-byte header:
 *
 *   flags          (1 byte): bit0 START, bit1 END (both set on a 1-packet txn)
 *   transaction_id (1 byte): equal across a transaction, for error checking
 *   sequence_n     (1 byte): 0 on the first packet, +1 per packet, wraps at 256
 *
 * Service-specific code lives on top: feed every notification to [feed] and
 * parse the payload it returns on END. Mirrors
 * firmware/scripts/lib/notif_transaction.py.
 */
class TransactionReassembler {
    private var active = false
    private var transactionId = 0
    private var nextSeq = 0
    private var buffer = ByteArray(0)

    /**
     * Feed one notification. Returns the completed payload on the transaction's
     * END packet, or null while in progress, dropped, or ignored. Errors (id
     * mismatch, sequence gap, stray continuation) are dropped silently; a START
     * packet always begins a fresh transaction, discarding any incomplete one.
     */
    fun feed(data: ByteArray): ByteArray? {
        if (data.size < HEADER_LEN) {
            reset()
            return null
        }

        val flags = data[0].toInt() and 0xFF
        val txnId = data[1].toInt() and 0xFF
        val seq = data[2].toInt() and 0xFF
        val body = data.copyOfRange(HEADER_LEN, data.size)
        val start = flags and FLAG_START != 0
        val end = flags and FLAG_END != 0

        if (start) {
            active = true
            transactionId = txnId
            nextSeq = (seq + 1) and 0xFF
            buffer = body
        } else {
            if (!active) return null
            if (txnId != transactionId || seq != nextSeq) {
                reset()
                return null
            }
            nextSeq = (seq + 1) and 0xFF
            buffer += body
        }

        if (end) {
            val payload = buffer
            reset()
            return payload
        }
        return null
    }

    private fun reset() {
        active = false
        transactionId = 0
        nextSeq = 0
        buffer = ByteArray(0)
    }

    companion object {
        const val FLAG_START = 0x01
        const val FLAG_END = 0x02
        const val HEADER_LEN = 3
    }
}
