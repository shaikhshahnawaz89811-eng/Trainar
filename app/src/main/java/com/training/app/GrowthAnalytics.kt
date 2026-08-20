package com.training.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private val Context.growthStore by preferencesDataStore("growth_analytics")

/** Persisted, user-owned analytics. No sample/demo records are ever inserted. */
data class EvaluationRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val score: Int,
    val categories: Map<String, Int>,
    val source: String,
    val verified: Boolean
)

enum class LoopState { IDLE, TRAINING, EVALUATING, FINDING_WEAK_AREAS, QUEUING_DATA, RETRAINING, COMPLETED, PAUSED }

data class ImprovementLoop(
    val state: LoopState = LoopState.IDLE,
    val cycle: Int = 0,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

class GrowthAnalytics(private val context: Context) {
    private val recordsKey = stringPreferencesKey("evaluation_records_v1")
    private val loopKey = stringPreferencesKey("improvement_loop_v1")
    private val queueKey = stringPreferencesKey("training_queue_v1")

    suspend fun records(): List<EvaluationRecord> {
        val raw = context.growthStore.data.first()[recordsKey] ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) add(fromJson(array.getJSONObject(i)))
            }.sortedBy { it.timestamp }
        }.getOrDefault(emptyList())
    }

    suspend fun recordVerified(record: EvaluationRecord) {
        require(record.verified) { "Only verified evaluation results can enter analytics history" }
        require(record.score in 0..100)
        require(record.categories.isNotEmpty())
        val updated = (records() + record).takeLast(200)
        val array = JSONArray().apply { updated.forEach { put(toJson(it)) } }
        context.growthStore.edit { it[recordsKey] = array.toString() }
    }

    suspend fun clear() { context.growthStore.edit { it.remove(recordsKey) } }

    suspend fun loop(): ImprovementLoop {
        val raw = context.growthStore.data.first()[loopKey] ?: return ImprovementLoop()
        return runCatching {
            val o = JSONObject(raw)
            ImprovementLoop(
                state = LoopState.valueOf(o.optString("state", LoopState.IDLE.name)),
                cycle = o.optInt("cycle", 0),
                lastError = o.optString("lastError", null),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }.getOrDefault(ImprovementLoop())
    }

    suspend fun transition(state: LoopState, cycle: Int, error: String? = null) {
        val value = JSONObject()
            .put("state", state.name)
            .put("cycle", cycle.coerceAtLeast(0))
            .put("lastError", error)
            .put("updatedAt", System.currentTimeMillis())
        context.growthStore.edit { it[loopKey] = value.toString() }
    }

    suspend fun queueTraining(categories: Set<String>) {
        val cleaned = categories.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (cleaned.isEmpty()) return
        context.growthStore.edit { it[queueKey] = JSONArray(cleaned.toList()).toString() }
    }

    suspend fun queuedTraining(): Set<String> {
        val raw = context.growthStore.data.first()[queueKey] ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            buildSet { for (i in 0 until array.length()) add(array.getString(i)) }
        }.getOrDefault(emptySet())
    }

    suspend fun clearTrainingQueue() { context.growthStore.edit { it.remove(queueKey) } }

    private fun toJson(r: EvaluationRecord): JSONObject = JSONObject()
        .put("id", r.id).put("timestamp", r.timestamp).put("score", r.score)
        .put("source", r.source).put("verified", r.verified)
        .put("categories", JSONObject(r.categories.mapValues { it.value }))

    private fun fromJson(o: JSONObject): EvaluationRecord {
        val categoriesObject = o.optJSONObject("categories") ?: JSONObject()
        val categories = buildMap {
            categoriesObject.keys().forEach { key -> put(key, categoriesObject.optInt(key).coerceIn(0, 100)) }
        }
        return EvaluationRecord(
            id = o.optString("id", UUID.randomUUID().toString()),
            timestamp = o.optLong("timestamp", System.currentTimeMillis()),
            score = o.optInt("score", 0).coerceIn(0, 100),
            categories = categories,
            source = o.optString("source", "unknown"),
            verified = o.optBoolean("verified", false)
        )
    }
}

/** Weak areas are computed only from persisted, verified category scores. */
fun findWeakAreas(records: List<EvaluationRecord>, threshold: Int = 70): Map<String, Int> {
    if (records.isEmpty()) return emptyMap()
    val sums = mutableMapOf<String, Int>()
    val counts = mutableMapOf<String, Int>()
    records.forEach { record ->
        record.categories.forEach { (category, score) ->
            sums[category] = (sums[category] ?: 0) + score
            counts[category] = (counts[category] ?: 0) + 1
        }
    }
    return sums.mapValues { (category, sum) -> sum / (counts[category] ?: 1) }
        .filterValues { it < threshold }
        .toList().sortedBy { it.second }.toMap(LinkedHashMap())
}
