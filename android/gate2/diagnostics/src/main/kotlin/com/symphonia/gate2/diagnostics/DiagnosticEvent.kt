package com.symphonia.gate2.diagnostics

import com.symphonia.gate2.contracts.FailureReport

/** Metadata-only diagnostics. This API intentionally has no audio payload field. */
sealed interface DiagnosticEvent {
    val occurredAtEpochMs: Long

    data class StateTransition(
        override val occurredAtEpochMs: Long,
        val component: String,
        val from: String,
        val to: String,
    ) : DiagnosticEvent

    data class Failure(
        override val occurredAtEpochMs: Long,
        val report: FailureReport,
    ) : DiagnosticEvent

    data class Counter(
        override val occurredAtEpochMs: Long,
        val name: String,
        val value: Long,
    ) : DiagnosticEvent
}
