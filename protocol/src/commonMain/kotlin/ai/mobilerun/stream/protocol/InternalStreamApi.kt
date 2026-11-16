// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.protocol

/**
 * Marks implementation building blocks shared across the stream modules: connection engines,
 * transport seams, raw-channel sends and input mapping/routing. These are not app-facing APIs
 * and may change in any release. Apps use the session, Android view or Compose wrapper.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to the Mobilerun stream modules and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalStreamApi
