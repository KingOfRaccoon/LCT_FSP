package ru.fsp.talent.core

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

@Configuration
@Profile("test")
class TestDatabaseGuard {
    @Bean
    fun testOnlyFlywayMigrationStrategy(dataSource: DataSource): FlywayMigrationStrategy =
        FlywayMigrationStrategy { flyway ->
            val database = dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT current_database()").use { result ->
                        check(result.next()) { "Could not identify integration-test database" }
                        result.getString(1)
                    }
                }
            }
            check(database == "fsp_test") {
                "Integration tests may migrate only fsp_test; connected to $database"
            }
            flyway.migrate()
        }
}
