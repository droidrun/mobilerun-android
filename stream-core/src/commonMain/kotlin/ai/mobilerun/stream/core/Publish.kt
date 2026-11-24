// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.InternalStreamApi

/** Device support supplied by the integrator's backend; this never grants publish permission. */
public class DevicePublishCapabilities(
    public val microphoneInjection: Boolean,
    public val cameraInjection: Boolean,
    public val mediaInjectionTransport: String? = null,
) {
    /** Whether direct microphone injection is supported. */
    public val supportsMicrophone: Boolean get() = microphoneInjection && mediaInjectionTransport == "direct"
}

/** Audio is required; camera is optional and never silently downgraded. */
public class PublishOptions(public val audio: Boolean, public val video: Boolean = false)

/** Requested local input. */
public enum class PublishKind(public val wireValue: String) { Audio("audio"), Video("video") }

/** Publish progress, separate from the viewer connection status. */
public enum class PublishPhase { Idle, Capturing, Starting, Accepted, Active, Stopped, Error }

/** Machine-readable publish failure. */
public class PublishFailure(
    public val reason: StreamErrorReason,
    public val message: String,
    public val detail: String? = null,
    public val serverReason: String? = null,
)

/** A failed or cancelled start, carrying the same failure as the error/state observers. */
public class PublishException(public val failure: PublishFailure) : Exception(failure.message)

/** Immutable publish snapshot. [reason] uses contract snake_case values, including stopped_by_client. */
public class PublishState(
    public val phase: PublishPhase = PublishPhase.Idle,
    public val mediaSessionId: String? = null,
    kinds: List<PublishKind> = emptyList(),
    public val reason: String? = null,
    public val message: String? = null,
    public val detail: String? = null,
    public val serverReason: String? = null,
) {
    /** Inputs requested for this operation, in audio/video order. */
    public val kinds: List<PublishKind> = kinds.toList()
}

/** Platform capture resources, owned exclusively by one publish operation. */
@InternalStreamApi
public interface PublishCapture {
    /** Attach the requested input to its already negotiated sender. */
    public suspend fun attach(kind: PublishKind)
    /** Detach only this capture's tracks, leaving subsequent captures untouched. */
    public fun detach()
    /** Stop/release every local input. Idempotent and independent of peer availability. */
    public fun stop()
}

/** Optional media implementation on the existing viewer peer; never creates another connection. */
@InternalStreamApi
public interface PublishPeerTransport {
    /** Supported trackless first-offer reservations, verified against generated SDP. */
    public val publishKinds: Set<PublishKind>
    /** Capture exact inputs, checking permissions first. On failure release partial resources. */
    public suspend fun capture(options: PublishOptions, onEnded: () -> Unit): PublishCapture
}
