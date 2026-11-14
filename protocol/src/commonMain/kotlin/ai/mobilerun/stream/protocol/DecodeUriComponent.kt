// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

/**
 * ECMAScript `decodeURIComponent`: decodes `%XX` escapes as UTF-8 and leaves everything else
 * (including `+`) untouched. Returns `null` where JavaScript would throw `URIError`
 * (truncated or non-hex escape, invalid UTF-8).
 */
internal fun decodeUriComponent(input: String): String? {
    if (input.indexOf('%') < 0) return input
    val out = StringBuilder(input.length)
    val pending = ArrayList<Byte>()

    fun flush(): Boolean {
        if (pending.isEmpty()) return true
        val decoded = try {
            pending.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return false
        }
        out.append(decoded)
        pending.clear()
        return true
    }

    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '%') {
            if (i + 2 >= input.length) return null
            val hi = hexValue(input[i + 1])
            val lo = hexValue(input[i + 2])
            if (hi < 0 || lo < 0) return null
            pending.add(((hi shl 4) or lo).toByte())
            i += 3
        } else {
            if (!flush()) return null
            out.append(c)
            i += 1
        }
    }
    if (!flush()) return null
    return out.toString()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
