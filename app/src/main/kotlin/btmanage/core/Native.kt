package btmanage.core

/**
 * JNI bridge to the Rust core (libbtmanage_ffi.so). The package and the names here are part
 * of the contract with `crates/btmanage-ffi/src/android.rs` in btManage-core: renaming
 * either side breaks the link.
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

    external fun trackerNew(): Long

    external fun trackerFree(handle: Long)

    /** Returns the new state as JSON when what the user sees has changed, otherwise null. */
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
