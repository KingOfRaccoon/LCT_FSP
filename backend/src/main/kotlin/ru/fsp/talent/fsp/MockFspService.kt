package ru.fsp.talent.fsp

import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.MutationService
import ru.fsp.talent.core.Store

@Service
class MockFspService(
    private val store: Store,
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val mutations: MutationService,
    @Value("\${fsp.demo-mode:true}") private val demoMode: Boolean,
) {
    fun getOwnEvidence(request: HttpServletRequest): Map<String, Any?> {
        val actor = auth.candidate(request)
        return linkDto(actor.id)
    }

    fun link(request: HttpServletRequest, body: Map<String, Any?>): Map<String, Any?> {
        contracts.validate("FspLinkRequest", body)
        if (!demoMode) throw ApiException(404, "NOT_FOUND", "FSP mock is unavailable")
        val actor = auth.candidate(request)
        val externalId = body.getValue("external_id").toString().trim()
        if (externalId.isBlank()) throw ApiException(400, "VALIDATION_ERROR", "External FSP ID is required", "external_id")
        val bindingMethod = body.getValue("binding_method").toString()
        val bindingStatus = if (bindingMethod == "test_fixture") "linked_confirmed" else "linked_unverified"
        return mutations.run(request, "fsp:${actor.id}", body, status = 201) {
            store.tx.execute {
                store.exec(
                    """INSERT INTO fsp_links(id, user_id, external_id, binding_status, source, data)
                       VALUES (:id, :userId, :externalId, :status, 'fsp_mock', :data)
                       ON CONFLICT (user_id) DO UPDATE SET external_id = EXCLUDED.external_id,
                           binding_status = EXCLUDED.binding_status, source = 'fsp_mock',
                           retrieved_at = now(), data = EXCLUDED.data""".trimIndent(),
                    mapOf(
                        "id" to store.id(), "userId" to actor.id, "externalId" to externalId,
                        "status" to bindingStatus,
                        "data" to store.json(mapOf("binding_method" to bindingMethod, "demo" to true)),
                    ),
                )
                store.exec("DELETE FROM achievements WHERE user_id = :id", mapOf("id" to actor.id))
                store.exec(
                    """INSERT INTO achievements(id, user_id, source_record_id, validity, data)
                       VALUES (:id, :userId, :record, 'valid', :data)""".trimIndent(),
                    mapOf(
                        "id" to store.id(), "userId" to actor.id, "record" to "demo-${store.id()}",
                        "data" to store.json(mapOf(
                            "event_name" to "Demo FSP programming contest",
                            "discipline" to null,
                            "result" to "Participant",
                            "participation_status" to if (bindingStatus == "linked_confirmed") "confirmed" else "unconfirmed",
                            "participant_linked" to (bindingStatus == "linked_confirmed"),
                            "team_event" to false,
                            "role" to null,
                            "source" to "fsp_mock",
                            "demo" to true,
                            "retrieved_at" to store.now().toString(),
                        )),
                    ),
                )
                linkDto(actor.id)
            } ?: throw IllegalStateException("FSP link transaction returned no data")
        }.body!!.getValue("data") as Map<String, Any?>
    }

    fun unlink(request: HttpServletRequest): Map<String, Any?> {
        val actor = auth.candidate(request)
        return mutations.run(request, "fsp:${actor.id}", emptyMap()) {
            store.tx.execute {
                store.exec("DELETE FROM achievements WHERE user_id = :id", mapOf("id" to actor.id))
                store.exec("DELETE FROM fsp_links WHERE user_id = :id", mapOf("id" to actor.id))
                mapOf("unlinked" to true)
            } ?: throw IllegalStateException("FSP unlink transaction returned no result")
        }.body!!.getValue("data") as Map<String, Any?>
    }

    private fun linkDto(userId: String): Map<String, Any?> {
        val link = store.one(
            "SELECT binding_status, data, retrieved_at FROM fsp_links WHERE user_id = :id",
            mapOf("id" to userId),
        ) ?: return mapOf("binding_status" to "unlinked", "demo" to demoMode, "evidence" to emptyList<Any>())
        val data = jsonMap(link["data"])
        val evidence = store.rows(
            "SELECT data FROM achievements WHERE user_id = :id AND validity = 'valid' ORDER BY source_record_id",
            mapOf("id" to userId),
        ).map { row -> jsonMap(row["data"]) }
        return mapOf(
            "binding_status" to link.getValue("binding_status").toString(),
            "binding_method" to data["binding_method"],
            "demo" to true,
            "evidence" to evidence,
        )
    }

    private fun jsonMap(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { it.key.toString() to it.value }
        else -> emptyMap()
    }
}
