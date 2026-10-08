package ru.fsp.talent.matching

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest
import java.util.TreeMap
import kotlin.math.ln
import kotlin.math.pow

/** Frozen synthetic relevance benchmark. Its labels are not human relevance judgments. */
class MatchingBenchmarkTest {
    private companion object {
        const val FROZEN_DATASET_SHA256 = "127862d1cef31492cc2702904e13f7adbfed7f3f6f35a38519c258f0698abb3f"
    }

    private val mapper = ObjectMapper()
    private val repositoryRoot = sequenceOf(File("."), File(".."))
        .first { File(it, "docs/validation/matching-benchmark.v1.json").isFile }
    private val datasetFile = File(repositoryRoot, "docs/validation/matching-benchmark.v1.json")
    private val dataset = mapper.readValue(datasetFile, Map::class.java).stringMap()
    private val needs = dataset.listOfMaps("needs")
    private val judgmentSets = dataset.listOfMaps("judgment_sets")
    private val policyFile = File(repositoryRoot, "docs/contracts/matching-policy.v1.json")

    @Test
    fun `frozen benchmark has complete disjoint splits and covers hard exclusions`() {
        val withoutDigest = TreeMap(dataset).apply { remove("content_sha256") }
        assertEquals(FROZEN_DATASET_SHA256, dataset["content_sha256"])
        assertEquals(dataset["content_sha256"], sha256(canonicalJson(withoutDigest)))
        assertEquals(dataset["policy_sha256"], sha256(policyFile.readBytes()))
        assertEquals("matching-baseline-v1", dataset["policy_id"])
        assertEquals(MatchingPolicy.POLICY_VERSION, dataset["policy_version"])
        assertEquals(2026100801, (dataset["seed"] as Number).toInt())

        assertEquals(12, needs.size)
        assertEquals(12, judgmentSets.size)
        val needById = needs.associateBy { it.string("id") }
        val candidateIdsBySplit = mutableMapOf<String, MutableSet<String>>()
        val candidateTemplatesBySplit = mutableMapOf<String, MutableSet<String>>()
        val needTemplatesBySplit = mutableMapOf<String, MutableSet<String>>()
        val expectedHardReasons = setOf("visibility", "email_confirmed", "target_grade", "specialization", "work_mode", "city", "workload_hours_per_week")
        val allowedDeclared = dataset.map("taxonomy").getValue("all_allowed_declared_skill_ids").asStringSet()
        val testEvidence = dataset.map("taxonomy").getValue("test_evidenced_skill_ids_only").asStringSet()
        val allRows = judgmentSets.flatMap { it.listOfMaps("profiles") }

        judgmentSets.forEachIndexed { index, set ->
            val split = set.string("split")
            assertEquals(if (index < 6) "development" else "holdout", split)
            assertEquals(20, set.listOfMaps("profiles").size)
            val profiles = set.listOfMaps("profiles")
            assertEquals(20, profiles.map { it.string("candidate_id") }.toSet().size)
            assertEquals(20, profiles.map { it.map("profile").string("synthetic_template_instance") }.toSet().size)
            assertTrue(profiles.all { row -> row.map("profile").list("test_evidenced_skills").map { it.toString() }.all(testEvidence::contains) })
            assertTrue(profiles.all { row -> row.map("profile").list("candidate_declared_skills").map { it.toString() }.all(allowedDeclared::contains) })

            val ids = profiles.mapTo(mutableSetOf()) { it.string("candidate_id") }
            candidateIdsBySplit.getOrPut(split) { mutableSetOf() }.addAll(ids)
            candidateTemplatesBySplit.getOrPut(split) { mutableSetOf() }.addAll(
                profiles.map { it.map("profile").string("synthetic_template_instance") },
            )
            needTemplatesBySplit.getOrPut(split) { mutableSetOf() }
                .add(needById.getValue(set.string("need_id")).string("synthetic_template_instance"))
            assertTrue(profiles.all { it.string("label_provenance").contains("no human independent/job-quality labels") })
        }
        assertTrue(candidateIdsBySplit.getValue("development").intersect(candidateIdsBySplit.getValue("holdout")).isEmpty())
        assertTrue(candidateTemplatesBySplit.getValue("development").intersect(candidateTemplatesBySplit.getValue("holdout")).isEmpty())
        assertTrue(needTemplatesBySplit.getValue("development").intersect(needTemplatesBySplit.getValue("holdout")).isEmpty())
        assertEquals(240, candidateIdsBySplit.values.flatten().toSet().size)
        assertEquals(expectedHardReasons, allRows.filter { (it["label"] as Number).toInt() == 0 }
            .mapNotNull { it["hard_incompatibility"] as? String }.toSet())
        assertTrue(needs.flatMap { it.listOfMaps("requirements") }.all { it.string("skill_id") in allowedDeclared })
        assertEquals(allowedDeclared, needs.flatMap { it.listOfMaps("requirements").map { row -> row.string("skill_id") } }.toSet())

        val byScenario = needs.associateBy { it["benchmark_scenario"]?.toString() }
        val postgresNeed = byScenario.getValue("required_unassessed_postgres_with_declared_optional_skills")!!
        val postgresRows = judgmentSets.first { it.string("need_id") == postgresNeed.string("id") }.listOfMaps("profiles")
        val postgresEligible = postgresRows.filter { it["hard_incompatibility"] == null }
        assertTrue(postgresEligible.isNotEmpty())
        assertTrue(postgresEligible.all { (it["label"] as Number).toInt() == 1 })
        assertTrue(postgresEligible.all { "postgres" !in it.map("profile").list("test_evidenced_skills") })
        listOf("postgres", "docker", "fastapi").forEach { skill ->
            assertTrue(postgresEligible.any { skill in it.map("profile").list("candidate_declared_skills") }, "$skill declaration must be represented")
        }

        val emptyNeed = byScenario.getValue("all_hard_incompatible_empty_result")!!
        val emptyRows = judgmentSets.first { it.string("need_id") == emptyNeed.string("id") }.listOfMaps("profiles")
        assertEquals(20, emptyRows.count { it["hard_incompatibility"] != null })
        assertTrue(emptyRows.all { (it["label"] as Number).toInt() == 0 })

        val paddingNeed = byScenario.getValue("two_relevant_eligible_results_padding_case")!!
        val paddingRows = judgmentSets.first { it.string("need_id") == paddingNeed.string("id") }.listOfMaps("profiles")
        assertEquals(2, paddingRows.count { (it["label"] as Number).toInt() >= 2 })
        assertEquals(18, paddingRows.count { it["hard_incompatibility"] != null })
        assertTrue(dataset.string("status").startsWith("frozen_"))
        assertFalse(dataset.string("labeling").contains("human-validated", ignoreCase = true))
    }

    @Test
    fun `precision at three divides by three and pads missing results with zero`() {
        assertEquals(1.0 / 3.0, precisionAt3(listOf("relevant"), mapOf("relevant" to 3)), 1e-12)
        assertEquals(0.0, precisionAt3(emptyList(), emptyMap()), 0.0)
    }

    @Test
    fun `NDCG at five gives label one positive gain and returns null only at zero ideal gain`() {
        assertEquals(1.0, ndcgAt5(listOf("label-one"), mapOf("label-one" to 1))!!, 1e-12)
        assertNull(ndcgAt5(listOf("zero-gain"), mapOf("zero-gain" to 0)))
        assertNull(ndcgAt5(emptyList(), emptyMap()))
        val judgments = mapOf("a" to 2, "b" to 3)
        assertEquals(1.0, ndcgAt5(listOf("b", "a"), judgments)!!, 1e-12)
        val reversedExpected = (3.0 + 7.0 * ln(2.0) / ln(3.0)) / (7.0 + 3.0 * ln(2.0) / ln(3.0))
        val reversedActual = ndcgAt5(listOf("a", "b"), judgments)!!
        assertTrue(reversedActual < 1.0)
        assertEquals(reversedExpected, reversedActual, 1e-12)
    }

    @Test
    fun `frozen matching benchmark reports production and declared-keyword diagnostics`() {
        val results = judgmentSets.map { set -> evaluateNeed(set) }
        assertEquals(12, results.size)
        assertTrue(results.all { it.hardErrorCount == 0 })
        val scenarios = needs.associateBy({ it["benchmark_scenario"]?.toString() }, { it.string("id") })
        val byNeed = results.associateBy { it.needId }
        val unassessedPostgres = byNeed.getValue(scenarios.getValue("required_unassessed_postgres_with_declared_optional_skills"))
        assertEquals(0, unassessedPostgres.productionConfirmedCount)
        assertTrue(unassessedPostgres.productionEligibleCount > 0)
        assertEquals(unassessedPostgres.productionEligibleCount, unassessedPostgres.label1EligibleCount)
        assertEquals(0, unassessedPostgres.positiveEligibleCount)
        assertEquals(0.0, unassessedPostgres.productionPAt3, 0.0)
        assertEquals(1.0, unassessedPostgres.productionNdcgAt5!!, 1e-12)
        assertFalse(unassessedPostgres.productionEmpty)
        assertTrue(unassessedPostgres.label1Only)
        val allHardEmpty = byNeed.getValue(scenarios.getValue("all_hard_incompatible_empty_result"))
        assertEquals(0, allHardEmpty.productionEligibleCount)
        assertEquals(0, allHardEmpty.productionConfirmedCount)
        assertEquals(0.0, allHardEmpty.productionPAt3, 0.0)
        assertNull(allHardEmpty.productionNdcgAt5)
        assertTrue(allHardEmpty.productionEmpty)
        assertTrue(allHardEmpty.confirmedEmpty)
        val twoRelevant = byNeed.getValue(scenarios.getValue("two_relevant_eligible_results_padding_case"))
        assertEquals(2, twoRelevant.productionEligibleCount)
        assertEquals(2, twoRelevant.positiveEligibleCount)
        assertEquals(2.0 / 3.0, twoRelevant.productionPAt3, 1e-12)

        val config = dataset.mapOf("metric_protocol")
        val report = mapOf(
            "report_id" to "matching-benchmark-2026-10-08",
            "status" to "synthetic_diagnostic_no_human_validation",
            "benchmark_id" to dataset["benchmark_id"],
            "dataset_sha256" to dataset["content_sha256"],
            "policy_sha256" to dataset["policy_sha256"],
            "metric_config_sha256" to sha256(canonicalJson(config)),
            "seed" to dataset["seed"],
            "counts" to dataset["counts"],
            "metric_definition" to config,
            "edge_cases" to dataset["edge_scenarios"],
            "baseline" to "Production hard eligibility is reused; across eligible groups, rank by count of candidate-declared overlaps with role skills, then stable candidate UUID. No group priority, test evidence, FSP, or other score.",
            "labeling_limit" to dataset["labeling"],
            "metrics" to mapOf(
                "development" to summarize(results.filter { it.split == "development" }),
                "holdout" to summarize(results.filter { it.split == "holdout" }),
                "overall" to summarize(results),
            ),
            "per_need" to results.map { it.toReportRow() },
            "notes" to listOf(
                "Synthetic label review state and provenance are recorded in the frozen input.",
                "No human independent relevance judgments or observed job-quality outcomes are present.",
                "The diagnostic baseline is not a proposed replacement ranking policy; no thresholds were tuned against holdout.",
                "P@3 uses fixed denominator 3 with zero padding. NDCG@5 is null when eligible IDCG is zero.",
            ),
        )
        val output = File(repositoryRoot, "backend/build/reports/matching-benchmark-2026-10-08.json")
        output.parentFile.mkdirs()
        mapper.writerWithDefaultPrettyPrinter().writeValue(output, report)
        assertTrue(output.isFile)
    }

    private fun evaluateNeed(set: Map<String, Any?>): NeedMetrics {
        val needId = set.string("need_id")
        val need = needs.first { it.string("id") == needId }
        val rows = set.listOfMaps("profiles")
        val profiles = rows.map { it.map("profile") }
        val judgmentById = rows.associate { it.string("candidate_id") to (it["label"] as Number).toInt() }
        val rowById = rows.associateBy { it.string("candidate_id") }
        val result = MatchingPolicy.computeMatches(profiles, need)
        val productionOrder = result.groups.confirmed + result.groups.needsClarification + result.groups.needsConfirmation
        val productionIds = productionOrder.map { it.candidateId }
        val explicitlyHardIncompatible = rows.filter { it["hard_incompatibility"] != null }.mapTo(mutableSetOf()) { it.string("candidate_id") }
        val hardErrors = productionIds.count(explicitlyHardIncompatible::contains)
        assertEquals(0, hardErrors, "$needId must never return an explicitly hard-incompatible profile")
        assertTrue(explicitlyHardIncompatible.all { it in result.excludedCandidateIds }, "$needId must exclude every frozen hard negative")

        val roleSkills = need.listOfMaps("requirements").mapTo(mutableSetOf()) { it.string("skill_id") }
        val baselineOrder = productionIds.sortedWith(
            compareByDescending<String> { id ->
                rowById.getValue(id).map("profile").list("candidate_declared_skills").toSet().intersect(roleSkills).size
            }.thenBy { it },
        )
        return NeedMetrics(
            needId = needId,
            split = set.string("split"),
            productionEligibleCount = productionIds.size,
            baselineEligibleCount = baselineOrder.size,
            productionConfirmedCount = result.groups.confirmed.size,
            label1EligibleCount = productionIds.count { judgmentById[it] == 1 },
            positiveEligibleCount = productionIds.count { (judgmentById[it] ?: 0) >= 2 },
            hardErrorCount = hardErrors,
            productionPAt3 = precisionAt3(productionIds, judgmentById),
            baselinePAt3 = precisionAt3(baselineOrder, judgmentById),
            productionNdcgAt5 = ndcgAt5(productionIds, judgmentById.filterKeys { it in productionIds }),
            baselineNdcgAt5 = ndcgAt5(baselineOrder, judgmentById.filterKeys { it in baselineOrder }),
            productionEmpty = productionIds.isEmpty(),
            confirmedEmpty = result.groups.confirmed.isEmpty(),
            label1Only = productionIds.isNotEmpty() && productionIds.all { judgmentById[it] == 1 },
            baselineEmpty = baselineOrder.isEmpty(),
        )
    }

    private fun summarize(rows: List<NeedMetrics>): Map<String, Any?> = mapOf(
        "needs" to rows.size,
        "macro_denominators" to mapOf("p_at_3" to rows.size, "ndcg_at_5" to rows.count { it.productionNdcgAt5 != null }),
        "production" to mapOf(
            "macro_p_at_3" to rows.map { it.productionPAt3 }.averageOrNull(),
            "macro_ndcg_at_5" to rows.mapNotNull { it.productionNdcgAt5 }.averageOrNull(),
            "empty_list_count" to rows.count { it.productionEmpty },
            "empty_confirmed_count" to rows.count { it.confirmedEmpty },
            "label1_only_need_count" to rows.count { it.label1Only },
            "need_count_with_label_ge_2_candidate" to rows.count { it.positiveEligibleCount > 0 },
            "hard_error_count" to rows.sumOf { it.hardErrorCount },
            "eligible_count_by_need" to rows.associate { it.needId to it.productionEligibleCount },
        ),
        "declared_keyword_baseline" to mapOf(
            "macro_p_at_3" to rows.map { it.baselinePAt3 }.averageOrNull(),
            "macro_ndcg_at_5" to rows.mapNotNull { it.baselineNdcgAt5 }.averageOrNull(),
            "empty_list_count" to rows.count { it.baselineEmpty },
            "eligible_count_by_need" to rows.associate { it.needId to it.baselineEligibleCount },
        ),
    )

    private fun NeedMetrics.toReportRow(): Map<String, Any?> = mapOf(
        "need_id" to needId,
        "split" to split,
        "eligible_count" to productionEligibleCount,
        "confirmed_count" to productionConfirmedCount,
        "confirmed_empty" to confirmedEmpty,
        "label1_eligible_count" to label1EligibleCount,
        "label_ge_2_eligible_count" to positiveEligibleCount,
        "label1_only" to label1Only,
        "hard_error_count" to hardErrorCount,
        "production" to mapOf("p_at_3" to productionPAt3, "ndcg_at_5" to productionNdcgAt5, "empty" to productionEmpty),
        "declared_keyword_baseline" to mapOf("p_at_3" to baselinePAt3, "ndcg_at_5" to baselineNdcgAt5, "empty" to baselineEmpty),
    )

    private data class NeedMetrics(
        val needId: String,
        val split: String,
        val productionEligibleCount: Int,
        val baselineEligibleCount: Int,
        val productionConfirmedCount: Int,
        val label1EligibleCount: Int,
        val positiveEligibleCount: Int,
        val hardErrorCount: Int,
        val productionPAt3: Double,
        val baselinePAt3: Double,
        val productionNdcgAt5: Double?,
        val baselineNdcgAt5: Double?,
        val productionEmpty: Boolean,
        val confirmedEmpty: Boolean,
        val label1Only: Boolean,
        val baselineEmpty: Boolean,
    )

    private fun precisionAt3(rankedIds: List<String>, labels: Map<String, Int>): Double =
        rankedIds.take(3).count { (labels[it] ?: 0) >= 2 } / 3.0

    private fun ndcgAt5(rankedIds: List<String>, eligibleLabels: Map<String, Int>): Double? {
        fun gain(label: Int) = 2.0.pow(label.toDouble()) - 1.0
        fun dcg(ids: List<String>) = ids.take(5).mapIndexed { index, id ->
            gain(eligibleLabels.getValue(id)) / (ln(index + 2.0) / ln(2.0))
        }.sum()
        val actual = dcg(rankedIds.filter(eligibleLabels::containsKey))
        val idealLabels = eligibleLabels.values.sortedDescending().take(5)
        val ideal = idealLabels.mapIndexed { index, label -> gain(label) / (ln(index + 2.0) / ln(2.0)) }.sum()
        return if (ideal == 0.0) null else actual / ideal
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun canonicalJson(value: Any?): ByteArray = mapper.writeValueAsBytes(canonicalValue(value))

    private fun canonicalValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> TreeMap<String, Any?>().apply {
            value.forEach { (key, item) -> put(key.toString(), canonicalValue(item)) }
        }
        is List<*> -> value.map(::canonicalValue)
        else -> value
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("UNCHECKED_CAST")
    private fun Map<*, *>.stringMap(): Map<String, Any?> = entries.associate { it.key.toString() to it.value as Any? }

    private fun Map<String, Any?>.string(key: String): String = getValue(key).toString()

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = (getValue(key) as Map<*, *>).stringMap()

    private fun Map<String, Any?>.list(key: String): List<Any?> = get(key) as? List<Any?> ?: emptyList()

    private fun Any?.asStringSet(): Set<String> = (this as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()

    private fun Map<String, Any?>.listOfMaps(key: String): List<Map<String, Any?>> = list(key).map { (it as Map<*, *>).stringMap() }

    private fun Map<String, Any?>.mapOf(key: String): Map<String, Any?> = map(key)
}
