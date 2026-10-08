package ru.fsp.talent.core

import java.time.Instant

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    val field: String? = null,
    val retryable: Boolean = false,
    val currentVersion: Int? = null,
    val eligibleAt: Instant? = null,
    val activeAttemptId: String? = null,
) : RuntimeException(message)
