package com.symphonia.gate2.contracts

enum class FailureCategory { CAPTURE, MEDIA_TRANSPORT, SIGNALING, VALIDATION, INTERNAL }

enum class Gate2Failure(
    val wireCode: String,
    val category: FailureCategory,
    val retryable: Boolean,
) {
    CAPTURE_PERMISSION_REQUIRED("G2-CAP-01", FailureCategory.CAPTURE, false),
    CAPTURE_SOURCE_UNAVAILABLE("G2-CAP-02", FailureCategory.CAPTURE, true),
    CAPTURE_SCOPE_UNVERIFIED("G2-CAP-03", FailureCategory.CAPTURE, false),
    MEDIA_TRANSPORT_UNSUPPORTED("G2-MED-01", FailureCategory.MEDIA_TRANSPORT, false),
    MEDIA_TRANSPORT_UNAVAILABLE("G2-MED-02", FailureCategory.MEDIA_TRANSPORT, true),
    SIGNALING_PROTOCOL_MISMATCH("G2-SIG-01", FailureCategory.SIGNALING, false),
    SIGNALING_MESSAGE_INVALID("G2-SIG-02", FailureCategory.SIGNALING, false),
    BENCHMARK_INVALID_TRIAL_COUNT("G2-VAL-01", FailureCategory.VALIDATION, false),
    BENCHMARK_GATE_FAILED("G2-VAL-02", FailureCategory.VALIDATION, false),
    INTERNAL_ERROR("G2-INT-01", FailureCategory.INTERNAL, false),
    ;

    companion object {
        fun fromWireCode(code: String): Gate2Failure =
            entries.firstOrNull { it.wireCode == code }
                ?: throw IllegalArgumentException("Unknown Gate 2 failure code: $code")
    }
}

data class FailureReport(
    val failure: Gate2Failure,
    val occurredAtEpochMs: Long,
    val operation: String,
) {
    init {
        require(occurredAtEpochMs >= 0) { "Failure timestamp must be non-negative" }
        require(operation.isNotBlank()) { "Failure operation must not be blank" }
    }
}
