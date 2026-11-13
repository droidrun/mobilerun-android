// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

/** Fixed-size big/little-endian writer over a plain [ByteArray]. Keeps common code free of JVM-only buffers. */
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

    fun raw(source: ByteArray) {
        source.copyInto(bytes, offset)
        offset += source.size
    }
}
