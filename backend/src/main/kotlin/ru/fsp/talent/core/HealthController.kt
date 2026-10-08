package ru.fsp.talent.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val mapper: ObjectMapper, private val jdbc: JdbcTemplate) {
    @GetMapping("/api/v1/health")
    fun health(): Map<String, Any?> = mapOf("data" to mapOf("status" to "ok"))

    @GetMapping("/api/v1/ready")
    fun ready(): ResponseEntity<Map<String, Any?>> = try {
        jdbc.queryForObject("SELECT 1", Int::class.java)
        ResponseEntity.ok(mapOf("data" to mapOf("status" to "ok")))
    } catch (_: Exception) {
        ResponseEntity.status(503).body(mapOf("data" to mapOf("status" to "unavailable")))
    }

    @GetMapping("/api/v1/openapi.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun openApi(): ResponseEntity<JsonNode> {
        val yamlMapper = ObjectMapper(YAMLFactory())
        val contract = ClassPathResource("contracts/openapi.v1.yaml").inputStream.use(yamlMapper::readTree)
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(contract)
    }
}
