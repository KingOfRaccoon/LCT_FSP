package ru.fsp.talent.core

data class Actor(
    val id: String,
    val email: String,
    val role: String,
    val companyId: String? = null,
    val sessionId: String,
    val emailVerified: Boolean,
)
