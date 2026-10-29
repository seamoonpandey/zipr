package app.zipr

/** JNI bridge to libzipr.so (Rust). All fds passed in are owned and closed by native code. */
object NativeZip {
    init { System.loadLibrary("zipr") }

    /** Takes ownership of [outFd]. Returns a handle, 0 on error. */
    @JvmStatic external fun open(outFd: Int): Long

    /** Takes ownership of [inFd] and closes it. Returns null on success, else an error message. */
    @JvmStatic external fun add(handle: Long, inFd: Int, name: String): String?

    /** Finalizes the archive and closes the output. Returns null on success. */
    @JvmStatic external fun finish(handle: Long): String?

    /** Frees the handle and closes the output without finishing. */
    @JvmStatic external fun abort(handle: Long)
}
