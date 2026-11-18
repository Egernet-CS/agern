package dk.egernet.factory.db

import dk.egernet.factory.AppJson
import dk.egernet.factory.DEFAULT_PIPELINE_MODELS
import dk.egernet.factory.EffectiveModelDto
import dk.egernet.factory.EffectivePreferencesDto
import dk.egernet.factory.EffectiveRoundsDto
import dk.egernet.factory.PIPELINE_ROLES
import dk.egernet.factory.PreferenceOverridesDto
import dk.egernet.factory.PreferencesDto
import dk.egernet.factory.SettingsDto
import dk.egernet.factory.badRequest
import dk.egernet.factory.unprocessable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

/** Model names: an alias (`opus`, `sonnet`, …) or a full model id. Same rule as the admin settings. */
val MODEL_RE = Regex("^[A-Za-z0-9._\\[\\]-]{1,100}$")
val MODEL_SUGGESTIONS = listOf("opus", "sonnet", "haiku", "fable")

/** Effective choices for one user's agents. */
data class EffectiveConfig(val chatModel: String, val pipelineModels: Map<String, String>, val maxFixRounds: Int) {
    fun model(role: String): String = pipelineModels[role] ?: DEFAULT_PIPELINE_MODELS[role] ?: "sonnet"
}

/**
 * Per-user preferences. The effective value of anything is the user's own choice if set, else the admin default;
 * `maxFixRounds` is additionally capped by the admin default, which is also the maximum.
 */
class PreferenceStore(private val db: Db, private val settings: SettingsStore) {
    private val mapSer = MapSerializer(String.serializer(), String.serializer())

    fun overrides(userId: UUID): PreferenceOverridesDto = db.queryOne(
        "SELECT chat_model, pipeline_models::text AS pm, max_fix_rounds FROM user_preferences WHERE user_id = ?", userId,
    ) {
        PreferenceOverridesDto(
            it.getString("chat_model"), AppJson.decodeFromString(mapSer, it.getString("pm")),
            it.getInt("max_fix_rounds").let { n -> if (it.wasNull()) null else n },
        )
    } ?: PreferenceOverridesDto()

    fun effective(userId: UUID): EffectiveConfig = resolve(overrides(userId), settings.get())

    fun dto(userId: UUID): PreferencesDto {
        val o = overrides(userId)
        val s = settings.get()
        val e = resolve(o, s)
        return PreferencesDto(
            overrides = o,
            effective = EffectivePreferencesDto(
                chatModel = EffectiveModelDto(e.chatModel, if (o.chatModel != null) "user" else "default"),
                pipelineModels = PIPELINE_ROLES.associateWith { EffectiveModelDto(e.model(it), if (o.pipelineModels[it] != null) "user" else "default") },
                maxFixRounds = EffectiveRoundsDto(
                    e.maxFixRounds, if (o.maxFixRounds != null) "user" else "default", s.maxFixRounds,
                    clamped = o.maxFixRounds != null && o.maxFixRounds > s.maxFixRounds,
                ),
            ),
            suggestions = MODEL_SUGGESTIONS,
        )
    }

    /** Replaces the user's overrides (validated). */
    fun put(userId: UUID, req: PreferenceOverridesDto): PreferencesDto {
        val chat = req.chatModel?.trim()?.takeIf { it.isNotEmpty() }
        if (chat != null && !MODEL_RE.matches(chat)) throw badRequest("Invalid chatModel")
        val unknown = req.pipelineModels.keys - PIPELINE_ROLES.toSet()
        if (unknown.isNotEmpty()) throw badRequest("Unknown roles in pipelineModels: $unknown")
        val models = req.pipelineModels.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        models.values.forEach { if (!MODEL_RE.matches(it)) throw badRequest("Invalid model '$it'") }
        val max = settings.get().maxFixRounds
        if (req.maxFixRounds != null && req.maxFixRounds !in 0..max) {
            throw unprocessable("maxFixRounds must be between 0 and $max (the factory's maximum)", "max_fix_rounds_out_of_range")
        }
        db.update(
            """INSERT INTO user_preferences(user_id, chat_model, pipeline_models, max_fix_rounds, updated_at) VALUES (?, ?, ?, ?, now())
               ON CONFLICT (user_id) DO UPDATE SET chat_model = EXCLUDED.chat_model, pipeline_models = EXCLUDED.pipeline_models,
                 max_fix_rounds = EXCLUDED.max_fix_rounds, updated_at = now()""",
            userId, chat, JsonbParam(AppJson.encodeToString(mapSer, models)), req.maxFixRounds,
        )
        return dto(userId)
    }

    companion object {
        fun resolve(o: PreferenceOverridesDto, s: SettingsDto) = EffectiveConfig(
            chatModel = o.chatModel ?: s.chatModel,
            pipelineModels = PIPELINE_ROLES.associateWith { o.pipelineModels[it] ?: s.pipelineModels[it] ?: DEFAULT_PIPELINE_MODELS[it] ?: "sonnet" },
            maxFixRounds = minOf(o.maxFixRounds ?: s.maxFixRounds, s.maxFixRounds),
        )
    }
}
