package app.somasafe.capture.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow


/**
 * A capture group: the set of samples collected between one Start and Stop
 * press. Samples may be lost mid-group; what matters is that they were received
 * together, regardless of their (non-contiguous) sequence numbers.
 *
 * Preprocessing also derives the wearer's z-score parameters from the group's own
 * windows and stores them here; [pickedAt] marks the group whose parameters get
 * staged with a model.
 */
@Entity(tableName = "sample_groups")
data class SampleGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,             // epoch millis when capture started
    val endedAt: Long? = null,       // epoch millis when capture stopped
    val featureMean: ByteArray? = null,  // LE float32, one per feature
    val featureStd: ByteArray? = null,
    val signalMean: ByteArray? = null,   // LE float32, one value for the whole BVP stream
    val signalStd: ByteArray? = null,
    val pickedAt: Long? = null,      // epoch millis this group was picked for staging
)

/**
 * One window of data within a group, holding the raw sensor stream and the
 * matching inference result side by side, keyed by the service-layer sequence
 * number. Raw data and result can arrive in either order and are merged into
 * the same row. Sequence numbers are unique only within a group (they reset on
 * device reset), hence the (groupId, sequenceN) unique index.
 */
@Entity(
    tableName = "samples",
    foreignKeys = [
        ForeignKey(
            entity = SampleGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["groupId", "sequenceN"], unique = true)],
)
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val sequenceN: Long,
    val receivedAt: Long,            // epoch millis of first packet received for this sample
    val deviceStartMs: Long? = null, // on-device acquisition start (uptime ms)
    val deviceEndMs: Long? = null,   // on-device acquisition end (uptime ms)
    val ppg: ByteArray? = null,      // raw little-endian float32 PPG samples
    val features: ByteArray? = null, // raw little-endian float32 input features (echoed or computed on-device)
    val score: ByteArray? = null,    // int8 model output (from ML result)
)

/** Per-group rollup for the capture history UI. */
data class GroupSummary(
    @ColumnInfo(name = "groupId") val groupId: Long,
    @ColumnInfo(name = "startedAt") val startedAt: Long,
    @ColumnInfo(name = "endedAt") val endedAt: Long?,
    @ColumnInfo(name = "sampleCount") val sampleCount: Int,
    @ColumnInfo(name = "resultCount") val resultCount: Int,
    @ColumnInfo(name = "featureCount") val featureCount: Int,
    @ColumnInfo(name = "signalCount") val signalCount: Int,
    @ColumnInfo(name = "hasNormParams") val hasNormParams: Boolean,
    @ColumnInfo(name = "picked") val picked: Boolean,
)

@Dao
interface CaptureDao {
    @Insert
    suspend fun insertGroup(group: SampleGroup): Long

    @Query("UPDATE sample_groups SET endedAt = :endedAt WHERE id = :groupId")
    suspend fun endGroup(groupId: Long, endedAt: Long)

    @Query("SELECT * FROM sample_groups WHERE id = :groupId")
    suspend fun group(groupId: Long): SampleGroup?

    @Query(
        """
        UPDATE sample_groups
        SET featureMean = :featureMean, featureStd = :featureStd,
            signalMean = :signalMean, signalStd = :signalStd
        WHERE id = :groupId
        """
    )
    suspend fun setNormParams(groupId: Long, featureMean: ByteArray?, featureStd: ByteArray?,
                              signalMean: ByteArray?, signalStd: ByteArray?)

    @Query("UPDATE sample_groups SET pickedAt = NULL")
    suspend fun clearPicked()

    @Query("UPDATE sample_groups SET pickedAt = :at WHERE id = :groupId")
    suspend fun markPicked(groupId: Long, at: Long)

    /** Only one group is ever picked, so picking one clears the rest. */
    @Transaction
    suspend fun pickGroup(groupId: Long, at: Long) {
        clearPicked()
        markPicked(groupId, at)
    }

    /** The group a model stages its norm params from: the picked one, else the most
     *  recently started group that has been preprocessed. */
    @Query(
        """
        SELECT * FROM sample_groups
        WHERE featureMean IS NOT NULL AND featureStd IS NOT NULL
        ORDER BY pickedAt IS NULL, startedAt DESC
        LIMIT 1
        """
    )
    suspend fun stagingGroup(): SampleGroup?

    @Query("SELECT * FROM samples WHERE groupId = :groupId AND sequenceN = :sequenceN LIMIT 1")
    suspend fun findSample(groupId: Long, sequenceN: Long): Sample?

    @Query("SELECT * FROM samples WHERE groupId = :groupId ORDER BY deviceStartMs, sequenceN")
    suspend fun samplesForGroup(groupId: Long): List<Sample>

    @Insert
    suspend fun insertSample(sample: Sample): Long

    @Insert
    suspend fun insertSamples(samples: List<Sample>)

    @Update
    suspend fun updateSample(sample: Sample)

    /** Store computed features for a sample without touching its (possibly absent) score. */
    @Query("UPDATE samples SET features = :features WHERE id = :id")
    suspend fun setFeatures(id: Long, features: ByteArray)

    @Query("DELETE FROM sample_groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)

    /** Insert a group and its samples atomically. [samples] is given the new
     *  group id so callers can stamp it onto each row. */
    @Transaction
    suspend fun insertGroupWithSamples(group: SampleGroup, samples: (Long) -> List<Sample>): Long {
        val groupId = insertGroup(group)
        insertSamples(samples(groupId))
        return groupId
    }

    @Query(
        """
        SELECT g.id AS groupId, g.startedAt AS startedAt, g.endedAt AS endedAt,
               COUNT(s.id) AS sampleCount,
               SUM(CASE WHEN s.score IS NOT NULL THEN 1 ELSE 0 END) AS resultCount,
               SUM(CASE WHEN s.features IS NOT NULL THEN 1 ELSE 0 END) AS featureCount,
               SUM(CASE WHEN s.ppg IS NOT NULL THEN 1 ELSE 0 END) AS signalCount,
               g.featureMean IS NOT NULL AS hasNormParams,
               g.pickedAt IS NOT NULL AS picked
        FROM sample_groups g
        LEFT JOIN samples s ON s.groupId = g.id
        GROUP BY g.id
        ORDER BY g.startedAt DESC
        """
    )
    fun groupSummaries(): Flow<List<GroupSummary>>
}

@Database(entities = [SampleGroup::class, Sample::class], version = 5, exportSchema = true)
abstract class CaptureDatabase : RoomDatabase() {
    abstract fun captureDao(): CaptureDao

    companion object {
        @Volatile
        private var instance: CaptureDatabase? = null

        fun get(context: Context): CaptureDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CaptureDatabase::class.java,
                "capture.db",
            ).build().also { instance = it }
        }
    }
}
