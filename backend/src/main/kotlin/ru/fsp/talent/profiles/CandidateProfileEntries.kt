package ru.fsp.talent.profiles

/** Read projections for candidate-authored profile collections. Provenance is always server-issued. */
object CandidateProfileEntries {
    fun experience(data: Map<String, Any?>): List<Map<String, Any?>> = entries(data["experience_entries"]).mapNotNull { entry ->
        val id = entry["id"] as? String ?: return@mapNotNull null
        val role = entry["role"] as? String ?: return@mapNotNull null
        val start = entry["start_month"] as? String ?: return@mapNotNull null
        mapOf(
            "id" to id,
            "company" to entry["company"],
            "role" to role,
            "description" to (entry["description"] as? String).orEmpty(),
            "start_month" to start,
            "end_month" to entry["end_month"],
            "skill_ids" to stringList(entry["skill_ids"]),
            "provenance" to CANDIDATE_DECLARED,
        )
    }

    fun projects(data: Map<String, Any?>): List<Map<String, Any?>> = entries(data["project_entries"]).mapNotNull { entry ->
        val id = entry["id"] as? String ?: return@mapNotNull null
        val title = entry["title"] as? String ?: return@mapNotNull null
        mapOf(
            "id" to id,
            "title" to title,
            "description" to (entry["description"] as? String).orEmpty(),
            "url" to entry["url"],
            "skill_ids" to stringList(entry["skill_ids"]),
            "provenance" to CANDIDATE_DECLARED,
        )
    }

    private fun entries(value: Any?): List<Map<*, *>> = (value as? List<*>)
        .orEmpty()
        .mapNotNull { it as? Map<*, *> }

    private fun stringList(value: Any?): List<String> = (value as? List<*>)
        .orEmpty()
        .filterIsInstance<String>()

    private const val CANDIDATE_DECLARED = "candidate_declared"
}
