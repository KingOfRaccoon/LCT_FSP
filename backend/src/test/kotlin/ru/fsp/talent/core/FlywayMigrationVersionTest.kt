package ru.fsp.talent.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

class FlywayMigrationVersionTest {
    @Test
    fun `migration versions are unique and contiguous through current schema`() {
        val migrations = PathMatchingResourcePatternResolver()
            .getResources("classpath:db/migration/V*.sql")
            .map { resource ->
                val filename = requireNotNull(resource.filename)
                val match = Regex("V(\\d+)__.+\\.sql").matchEntire(filename)
                    ?: error("Invalid Flyway migration filename: $filename")
                match.groupValues[1].toInt() to filename
            }

        assertEquals((1..14).toList(), migrations.map { it.first }.sorted())
        assertEquals(14, migrations.map { it.first }.toSet().size)
        assertEquals(
            "V9__assessment_workbench.sql",
            migrations.single { it.first == 9 }.second,
        )
        assertEquals(
            "V10__vacancies_and_applications.sql",
            migrations.single { it.first == 10 }.second,
        )
        assertEquals(
            "V11__vacancy_application_contact_access.sql",
            migrations.single { it.first == 11 }.second,
        )
    }
}
