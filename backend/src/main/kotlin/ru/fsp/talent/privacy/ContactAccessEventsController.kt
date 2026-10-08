package ru.fsp.talent.privacy

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Contracts
import ru.fsp.talent.core.Store
import ru.fsp.talent.invitations.PageCursor
import ru.fsp.talent.invitations.checkPageLimit

@RestController
@RequestMapping("/api/v1")
class ContactAccessEventsController(
    private val auth: AuthAccess,
    private val contracts: Contracts,
    private val store: Store,
) {
    @GetMapping("/me/contact-access-events")
    fun events(
        request: HttpServletRequest,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) cursor: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.candidate(request)
        val size = checkPageLimit(limit)
        val after = PageCursor.decode(cursor)
        val afterClause = if (after == null) "" else "AND (e.occurred_at, e.id) < (:afterAt, :afterId)"
        val params = buildMap<String, Any?> {
            put("candidateId", actor.id)
            put("fetchLimit", size + 1)
            if (after != null) {
                put("afterAt", after.at)
                put("afterId", after.id)
            }
        }
        val rows = store.rows(
            """SELECT e.id, e.company_id, c.data ->> 'name' AS company_name,
                      e.grant_id, e.occurred_at, e.kind
               FROM contact_access_events e JOIN companies c ON c.id = e.company_id
               WHERE e.candidate_id = :candidateId $afterClause
               ORDER BY e.occurred_at DESC, e.id DESC LIMIT :fetchLimit""".trimIndent(),
            params,
        )
        val selected = rows.take(size)
        val next = if (rows.size > size) selected.lastOrNull()?.let { PageCursor(it.getValue("occurred_at") as java.time.Instant, it.getValue("id").toString()).encode() } else null
        val data = mapOf(
            "items" to selected.map { row ->
                mapOf(
                    "id" to row.getValue("id").toString(),
                    "company_id" to row.getValue("company_id").toString(),
                    "company_name" to row["company_name"]?.toString().orEmpty(),
                    "grant_id" to row.getValue("grant_id").toString(),
                    "occurred_at" to (row.getValue("occurred_at") as java.time.Instant).toString(),
                    "kind" to row.getValue("kind").toString(),
                )
            },
            "next_cursor" to next,
        )
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(contracts.envelope(data))
    }
}
