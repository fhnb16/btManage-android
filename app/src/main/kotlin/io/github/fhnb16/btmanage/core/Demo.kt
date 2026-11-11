package io.github.fhnb16.btmanage.core

/**
 * Synthetic advertisements, fed through the same path real ones will take. They stand in
 * until Bluetooth scanning is implemented.
 */
object Demo {
    private const val APPLE = 0x004C
    private const val SECONDS_PER_STEP = 6

    /** What one second of a nearby pair of AirPods Pro and a pair of AirPods Max would send. */
    fun feed(tracker: Tracker, tick: Long) {
        val left = 9 - (tick / SECONDS_PER_STEP % 9).toInt()
        // AirPods Pro: left draining and worn, right at 70 %, case at 50 % and charging.
        tracker.ingest(0xA1, -48, APPLE, message(0x0E, 0x20, status = 0x22, levels = 0x70 or left, chargeAndCase = 0x45))
        // AirPods Max further away, at 60 %.
        tracker.ingest(0xB2, -62, APPLE, message(0x0A, 0x20, status = 0x20, levels = 0xF6, chargeAndCase = 0x0F))
    }

    /** A proximity pairing message; apple/proximity.rs in the core documents the layout. */
    private fun message(model0: Int, model1: Int, status: Int, levels: Int, chargeAndCase: Int): ByteArray {
        val data = ByteArray(27)
        intArrayOf(0x07, 0x19, 0x01, model0, model1, status, levels, chargeAndCase)
            .forEachIndexed { index, value -> data[index] = value.toByte() }
        return data
    }
}
