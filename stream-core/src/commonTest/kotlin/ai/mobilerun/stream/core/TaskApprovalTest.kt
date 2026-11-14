// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskApprovalTest {
    @Test fun acceptsLabelPackageAndHost() {
        assertEquals(PaymentCardDetails("Visa 4242", "com.shop", "shop.com"),
            parsePaymentCardDetails(mapOf("label" to "Visa 4242", "targetPackage" to "com.shop", "targetHost" to "shop.com")))
    }
    @Test fun fallsBackToCredentialNameAndOmitsHost() {
        assertEquals(PaymentCardDetails("corp", "com.shop"),
            parsePaymentCardDetails(mapOf("credentialName" to "corp", "targetPackage" to "com.shop")))
        assertEquals(PaymentCardDetails("corp", "com.shop"),
            parsePaymentCardDetails(mapOf("label" to "", "credentialName" to "corp", "targetPackage" to "com.shop")))
    }
    @Test fun missingAndWrongTypes() {
        assertNull(parsePaymentCardDetails(mapOf("label" to "x")))
        assertNull(parsePaymentCardDetails(mapOf("targetPackage" to "com.shop")))
        assertNull(parsePaymentCardDetails(mapOf("label" to 1, "targetPackage" to "a")))
        assertEquals(PaymentCardDetails("x", "a"), parsePaymentCardDetails(mapOf("label" to "x", "targetPackage" to "a", "targetHost" to 3)))
        assertNull(parsePaymentCardDetails(null))
        assertNull(parsePaymentCardDetails("card"))
    }
    @Test fun remainingTimeHandlesPastAndInvalid() {
        val base = 1_767_226_200_000L // 2026-01-01T00:10:00Z
        assertEquals(5000, remainingMs("2026-01-01T00:10:00.000Z", base - 5000))
        assertEquals(-1, remainingMs("2026-01-01T00:10:00.000Z", base + 1))
        assertEquals(0, remainingMs("nope", base))
    }
    @Test fun formatRemainingMatchesEveryReferenceCase() {
        mapOf(0L to "0s", 59_001L to "1m 0s", 3_725_000L to "1h 2m 5s", 298_000L to "4m 58s",
            45_000L to "45s", 3_600_000L to "1h 0m 0s", -5L to "0s").forEach { (ms, text) -> assertEquals(text, formatRemaining(ms)) }
    }
}
