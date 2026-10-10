package ru.fsp.talent.profiles

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.fsp.talent.core.ApiException
import ru.fsp.talent.core.AuthAccess
import ru.fsp.talent.core.Store
import ru.fsp.talent.consents.ConsentService

@RestController
class ResumeController(
    private val auth: AuthAccess,
    private val parserService: ResumeParserService,
    private val consents: ConsentService,
    private val store: Store,
) {

    @PostMapping("/api/v1/me/resume/parse", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun parseResume(
        request: HttpServletRequest,
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<Map<String, Any?>> {
        val actor = auth.verified(auth.candidate(request))
        if (file.isEmpty) {
            throw ApiException(400, "INVALID_FILE", "Файл резюме не должен быть пустым", "file")
        }
        if (file.size > 15 * 1024 * 1024) {
            throw ApiException(400, "FILE_TOO_LARGE", "Размер файла не должен превышать 15 МБ", "file")
        }

        val parsed = store.tx.execute {
            consents.requireProcessing(actor.id)
            parserService.parse(file.bytes)
        }!!
        return ResponseEntity.ok(
            mapOf(
                "data" to mapOf(
                    "display_name" to parsed.displayName,
                    "email" to parsed.email,
                    "phone" to parsed.phone,
                    "headline" to parsed.headline,
                    "summary" to parsed.summary,
                    "declared_grade" to parsed.declaredGrade,
                    "skills" to parsed.skills,
                    "soft_skills" to parsed.softSkills,
                    "role_preferences" to parsed.rolePreferences,
                    "cities" to parsed.cities,
                    "raw_preview" to parsed.rawTextSnippet
                )
            )
        )
    }
}
