// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

/**
 * Marks building blocks that exist for the Mobilerun stream modules and their tests: the session
 * engine, the transport seams and the reconnect controller. They may change in any release. Apps
 * use `DeviceStreamSession` and `StreamStatus`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is internal to the Mobilerun stream modules and may change without notice.",
)
@Retention(AnnotationRetention.BINARY)
public annotation class InternalStreamApi
