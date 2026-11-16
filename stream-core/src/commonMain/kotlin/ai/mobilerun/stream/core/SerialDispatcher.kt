// Copyright 2026 Mobilerun
// SPDX-License-Identifier: Apache-2.0

package ai.mobilerun.stream.core

import ai.mobilerun.stream.protocol.InternalStreamApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.ContinuationInterceptor

/** One serial context for each controller; timers and callbacks use the same dispatcher. */
@OptIn(ExperimentalCoroutinesApi::class)
@InternalStreamApi
public fun serialDispatcher(scope: CoroutineScope): CoroutineDispatcher {
    val base = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
    return base.limitedParallelism(1)
}
