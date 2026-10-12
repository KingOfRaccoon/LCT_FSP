package ru.fsp.talent.recruiting

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import ru.fsp.talent.core.DbTestBase
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RecruitingConsentReadTest : DbTestBase() {
    @Test
    fun `new record creation waits on user before locking the candidate profile`() {
        val employer = seedActor("employer")
        val candidate = seedActor()
        jdbc.update("UPDATE candidate_profiles SET visibility='public' WHERE user_id=?::uuid",candidate.id)
        val executor = Executors.newSingleThreadExecutor()
        jdbc.dataSource!!.connection.use { owner ->
            owner.autoCommit = false
            owner.prepareStatement("SELECT id FROM users WHERE id=?::uuid FOR UPDATE").use { statement ->
                statement.setString(1,candidate.id); statement.executeQuery().close()
            }
            val pending = executor.submit(java.util.concurrent.Callable {
                request(employer,HttpMethod.PUT,"/api/v1/recruiting/records/${candidate.id}",mapOf(
                    "expected_version" to 0,"need_id" to null,"stage" to "reviewing","note" to null),UUID.randomUUID().toString())
            })
            try {
                val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(4)
                while (System.nanoTime()<deadline && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FOR SHARE%'",Int::class.java)==0) Thread.sleep(20)
                assertTrue(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE '%FOR SHARE%'",Int::class.java)!!>0,"Creation must reach the lock gate")
                assertFalse(pending.isDone,"Creation should be waiting on the user row")
                jdbc.dataSource!!.connection.use { probe ->
                    probe.autoCommit=false
                    probe.prepareStatement("SELECT user_id FROM candidate_profiles WHERE user_id=?::uuid FOR UPDATE NOWAIT").use { statement ->
                        statement.setString(1,candidate.id); statement.executeQuery().close()
                    }
                    probe.rollback()
                }
            } finally {
                owner.rollback()
                executor.shutdown()
            }
            assertEquals(200,pending.get(10,TimeUnit.SECONDS).statusCode.value())
        }
    }
    @Test
    fun `legacy public intent cannot expose names or create records before current consent`() {
        val employer = seedActor("employer")
        val other = seedActor("employer")
        val candidate = seedActor(defaultConsents = false)
        jdbc.update("UPDATE candidate_profiles SET visibility='public',data=?::jsonb WHERE user_id=?::uuid",
            objectMapper.writeValueAsString(mapOf("display_name" to "Candidate legacy@example.test")),candidate.id)
        jdbc.update("INSERT INTO recruiting_records(id,company_id,candidate_id,version,stage,note) VALUES(?::uuid,?::uuid,?::uuid,1,'reviewing','Own private note')",
            UUID.randomUUID().toString(),employer.companyId,candidate.id)
        val listed = request(employer,HttpMethod.GET,"/api/v1/recruiting/records?candidate_id=${candidate.id}")
        assertEquals(200,listed.statusCode.value(),listed.body.toString())
        assertEquals("hidden",listed.body!!.path("data").path("items").first().path("candidate_visibility").asText())
        assertTrue(listed.body!!.path("data").path("items").first().path("candidate_display_name").isNull)
        val updated = request(employer,HttpMethod.PUT,"/api/v1/recruiting/records/${candidate.id}",mapOf(
            "expected_version" to 1,"need_id" to null,"stage" to "shortlisted","note" to "Own private note"),UUID.randomUUID().toString())
        assertEquals(200,updated.statusCode.value(),updated.body.toString())
        assertEquals("hidden",updated.body!!.path("data").path("candidate_visibility").asText())
        assertTrue(updated.body!!.path("data").path("candidate_display_name").isNull)
        val created = request(other,HttpMethod.PUT,"/api/v1/recruiting/records/${candidate.id}",mapOf(
            "expected_version" to 0,"need_id" to null,"stage" to "reviewing","note" to null),UUID.randomUUID().toString())
        assertEquals(404,created.statusCode.value(),created.body.toString())
        consentAccept(candidate,"processing")
        consentAccept(candidate,"publication")
        val visible = request(employer,HttpMethod.GET,"/api/v1/recruiting/records?candidate_id=${candidate.id}")
        val item = visible.body!!.path("data").path("items").first()
        assertEquals("public",item.path("candidate_visibility").asText())
        assertFalse(item.path("candidate_display_name").asText().contains("legacy@example.test"))
        assertEquals("Own private note",item.path("note").asText())
    }
}
