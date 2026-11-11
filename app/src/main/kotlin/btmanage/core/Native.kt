package btmanage.core

/**
 * JNI bridge to the Rust core (libbtmanage_ffi.so). The package and the names here are part
 * of the contract with `crates/btmanage-ffi/src/android.rs` in btManage-core: renaming
 * either side breaks the link.
 *
 * Handles travel as `Long`; every time is Unix time in milliseconds. Functions that take in
 * new information return the device list as JSON when what the user sees has changed, and
 * null otherwise.
 */
object Native {
    /** False when the application was packaged without the core library. */
    val available: Boolean = try {
        System.loadLibrary("btmanage_ffi")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun version(): String

    external fun hubNew(): Long

    external fun hubFree(handle: Long)

    /** [kind] 0: manufacturer data, [id] is the company. 1: 16-bit service data, [id] is the UUID. */
    external fun hubAdvertisement(
        handle: Long,
        address: Long,
        rssi: Int,
        kind: Int,
        id: Int,
        data: ByteArray,
        nowMs: Long,
    ): String?

    /** [devicesJson] is an array of the core's `SystemDevice`. */
    external fun hubSetSystemDevices(handle: Long, devicesJson: String, nowMs: Long): String?

    /** [source] 2: GATT, 3: HID, 4: game controller interface. */
    external fun hubBattery(
        handle: Long,
        deviceId: String,
        source: Int,
        percent: Int,
        charging: Boolean,
        nowMs: Long,
    ): String?

    external fun hubSnapshot(handle: Long, nowMs: Long): String?

    external fun hubHistory(handle: Long, deviceId: String, sinceMs: Long): String?

    external fun hubForget(handle: Long, deviceId: String)

    external fun hubExport(handle: Long): String?

    external fun hubImport(handle: Long, state: String): Boolean

    /** Describes the GitHub release in [releaseJson] if it is newer than [current]. */
    external fun updateCheck(current: String, releaseJson: String, assetHint: String): String?

    // The single-model tracker the first version of this application used. Kept because the
    // core still exports it.
    external fun trackerNew(): Long

    external fun trackerFree(handle: Long)

    external fun trackerIngest(
        handle: Long,
        address: Long,
        rssi: Int,
        companyId: Int,
        data: ByteArray,
        nowMs: Long,
    ): String?

    external fun trackerSnapshot(handle: Long, nowMs: Long): String?
}
