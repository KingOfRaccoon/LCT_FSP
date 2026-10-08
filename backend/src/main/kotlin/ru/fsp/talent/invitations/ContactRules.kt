package ru.fsp.talent.invitations

internal object ContactRules {
    private val channelTypes = setOf("email", "phone", "website")

    fun validateChannels(channels: List<Map<String, Any?>>) {
        if (channels.size > 5 || channels.map { it["type"] }.toSet().size != channels.size ||
            channels.any { it["type"] !in channelTypes || it["value"] !is String || it["enabled"] !is Boolean }
        ) {
            throw IllegalArgumentException("Contact channels must have unique supported types and explicit opt-in")
        }
    }

    fun grantedChannels(channels: List<Map<String, Any?>>): List<Map<String, String>> = channels
        .filter { it["enabled"] == true }
        .mapNotNull { channel ->
            val type = channel["type"] as? String ?: return@mapNotNull null
            val value = channel["value"] as? String ?: return@mapNotNull null
            mapOf("type" to type, "value" to value)
        }
}
