// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

/** Fixed-size big/little-endian writer over a plain [ByteArray], so common code needs no JVM-only buffers. */
internal class ByteWriter(size: Int) {
    val bytes: ByteArray = ByteArray(size)
    private var offset = 0

    fun u8(value: Int) {
        bytes[offset++] = value.toByte()
    }

    fun i16Le(value: Int) {
        bytes[offset++] = value.toByte()
        bytes[offset++] = (value shr 8).toByte()
    }

    fun i32Le(value: Int) {
        for (shift in 0 until 32 step 8) bytes[offset++] = (value shr shift).toByte()
    }

    fun i32Be(value: Int) {
        for (shift in 24 downTo 0 step 8) bytes[offset++] = (value shr shift).toByte()
    }

    fun i64Be(value: Long) {
        for (shift in 56 downTo 0 step 8) bytes[offset++] = (value shr shift).toByte()
    }

    /** Position shared by touch, scroll and hover: `i32 x`, `i32 y` (rounded), `u16 width`, `u16 height`. */
    fun position(x: Double, y: Double, videoWidth: Int, videoHeight: Int) {
        i32Le(jsToInt32(jsRound(x)))
        i32Le(jsToInt32(jsRound(y)))
        i16Le(videoWidth)
        i16Le(videoHeight)
    }

    fun raw(source: ByteArray) {
        source.copyInto(bytes, offset)
        offset += source.size
    }
}

/**
 * UTF-8 bytes of the string like `TextEncoder`: an unpaired surrogate is encoded as U+FFFD
 * (`efbfbd`). Kotlin's own encoder would write `?` on the JVM.
 */
internal fun String.encodeUtf8(): ByteArray {
    var fixed: StringBuilder? = null
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            fixed?.append(c)?.append(this[i + 1])
            i += 2
            continue
        }
        if (c.isSurrogate()) {
            if (fixed == null) fixed = StringBuilder(length).appendRange(this, 0, i)
            fixed.append('\uFFFD')
        } else {
            fixed?.append(c)
        }
        i += 1
    }
    return (fixed?.toString() ?: this).encodeToByteArray()
}
