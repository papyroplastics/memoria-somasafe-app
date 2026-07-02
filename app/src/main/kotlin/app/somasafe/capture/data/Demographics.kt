package app.somasafe.capture.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The user's raw demographics — the 6-d static half of the autoencoder's conditioning
 * vector, in the field order the backend's `user_description_vector` uses: gender
 * (0 = m, 1 = f), age, height (cm), weight (kg), skin type, sport level. Stored
 * un-normalized; the trainer z-scores it at load with the model's static params
 * (pulled from `/model/norm`), exactly as the backend does globally.
 *
 * This is the **default** static data: it is stamped onto each capture group as it is
 * created from the ESP, backfilled onto older groups that predate it when saved, and
 * returned as the fallback when a group has no static of its own. Imported datasets
 * instead carry their own static (their subject's demographics), embedded by
 * `export_subject_data.py`.
 */
data class Demographics(
    val female: Boolean,
    val age: Float,
    val height: Float,
    val weight: Float,
    val skin: Float,
    val sport: Float,
) {
    /** Raw 6-d vector, matching backend `user_description_vector` field order. */
    fun toVector(): FloatArray =
        floatArrayOf(if (female) 1f else 0f, age, height, weight, skin, sport)

    /** Little-endian float32 layout, as stored on a capture group / in the `.ssds`. */
    fun toBytes(): ByteArray {
        val buf = ByteBuffer.allocate(6 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        toVector().forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("female", female)
        put("age", age.toDouble())
        put("height", height.toDouble())
        put("weight", weight.toDouble())
        put("skin", skin.toDouble())
        put("sport", sport.toDouble())
    }

    companion object {
        /** Mirrors the defaults `user_description_vector` falls back to. */
        val DEFAULT = Demographics(female = false, age = 30f, height = 150f, weight = 70f, skin = 3f, sport = 3f)

        fun fromJson(o: JSONObject) = Demographics(
            female = o.getBoolean("female"),
            age = o.getDouble("age").toFloat(),
            height = o.getDouble("height").toFloat(),
            weight = o.getDouble("weight").toFloat(),
            skin = o.getDouble("skin").toFloat(),
            sport = o.getDouble("sport").toFloat(),
        )
    }
}

private fun demographicsFile(context: Context): File = File(context.filesDir, "demographics.json")

/** The stored default demographics, or null if the user has not set them yet. */
fun loadDemographics(context: Context): Demographics? =
    runCatching { Demographics.fromJson(JSONObject(demographicsFile(context).readText())) }.getOrNull()

fun saveDemographics(context: Context, demographics: Demographics) {
    demographicsFile(context).writeText(demographics.toJson().toString())
}
