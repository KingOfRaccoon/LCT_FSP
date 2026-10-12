package ru.fsp.talent.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class Contracts(private val mapper: ObjectMapper) {
    private val yamlMapper = ObjectMapper(YAMLFactory())
    private val openApi: JsonNode by lazy {
        ClassPathResource("contracts/openapi.v1.yaml").inputStream.use(yamlMapper::readTree)
    }
    private val schemaFactory by lazy { JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012) }

    fun validate(requestSchema: String, payload: Map<String, Any?>) {
        val components = openApi.path("components").path("schemas")
        if (!components.has(requestSchema)) {
            throw IllegalArgumentException("Unknown OpenAPI request schema: $requestSchema")
        }
        val wrapper = mapper.createObjectNode().apply {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("\$ref", "#/components/schemas/$requestSchema")
            set<JsonNode>("components", openApi.path("components"))
        }
        val schema = schemaFactory.getSchema(wrapper)
        val violations = schema.validate(mapper.writeValueAsString(payload), InputFormat.JSON)
        if (violations.isNotEmpty()) {
            val first = violations.first()
            throw ApiException(
                status = 400,
                code = "VALIDATION_ERROR",
                message = first.message ?: "Request does not match the API contract",
                field = (first.instanceLocation.toString().removePrefix("/"))
                    .replace("/", ".").takeIf(String::isNotBlank),
            )
        }
    }

    fun envelope(data: Any?): Map<String, Any?> = mapOf("data" to data)

    fun iso(value: Instant?): String? = value?.toString()

    fun assertVersion(current: Int, expected: Int) {
        if (current != expected) {
            throw ApiException(
                status = 409,
                code = "VERSION_CONFLICT",
                message = "The resource changed; reload it before retrying",
                currentVersion = current,
            )
        }
    }
}
