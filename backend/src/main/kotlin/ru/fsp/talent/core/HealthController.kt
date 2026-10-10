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

    @GetMapping(value = ["/swagger-ui", "/swagger-ui.html", "/api-docs"], produces = [MediaType.TEXT_HTML_VALUE])
    fun swaggerUi(): String = """
<!DOCTYPE html>
<html lang="ru">
<head>
  <meta charset="UTF-8">
  <title>ФСП · OpenAPI Swagger UI</title>
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css" />
  <link rel="icon" type="image/svg+xml" href="/brand/fsp-logo-compact.svg" />
  <style>
    body { margin: 0; background: #fafafa; font-family: sans-serif; }
    .topbar-fsp { background: #402FFF; color: #fff; padding: 12px 24px; font-weight: bold; font-size: 15px; display: flex; align-items: center; justify-content: space-between; }
    .topbar-fsp a { color: #fff; text-decoration: none; font-size: 14px; opacity: 0.9; }
    .topbar-fsp a:hover { opacity: 1; text-decoration: underline; }
  </style>
</head>
<body>
  <div class="topbar-fsp">
    <span>ФСП · Таланты — Интерактивная спецификация API (OpenAPI 3.0)</span>
    <a href="/">← Вернуться в платформу</a>
  </div>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
  <script>
    window.onload = () => {
      SwaggerUIBundle({
        url: '/api/v1/openapi.json',
        dom_id: '#swagger-ui',
        deepLinking: true,
        presets: [
          SwaggerUIBundle.presets.apis,
          SwaggerUIBundle.SwaggerUIStandalonePreset
        ],
        layout: "BaseLayout"
      });
    };
  </script>
</body>
</html>
""".trimIndent()
}

