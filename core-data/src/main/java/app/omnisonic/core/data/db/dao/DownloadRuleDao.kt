package app.omnisonic.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRuleDao {

    /** Duplicate identity is a strict no-op: the existing row keeps its variant and timestamps. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(rule: DownloadRuleEntity): Long

    @Query(
        """
        SELECT * FROM download_rules
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY providerId, kind, targetExternalId, id
        """,
    )
    suspend fun selectAllRaw(excludedProviderIdsCsv: String): List<DownloadRuleEntity>

    suspend fun selectAll(scope: ProfileScope): List<DownloadRuleEntity> =
        selectAllRaw(scope.excludedProviderIds.joinToString(","))

    /** Live rule rows for the downloads workspace; hidden providers stay out of the read. */
    @Query(
        """
        SELECT * FROM download_rules
        WHERE instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY providerId, kind, targetExternalId, id
        """,
    )
    fun observeAllRaw(excludedProviderIdsCsv: String): Flow<List<DownloadRuleEntity>>

    fun observeAll(scope: ProfileScope): Flow<List<DownloadRuleEntity>> =
        observeAllRaw(scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM download_rules
        WHERE providerId = :providerId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY kind, targetExternalId, id
        """,
    )
    suspend fun selectByProviderRaw(providerId: Long, excludedProviderIdsCsv: String): List<DownloadRuleEntity>

    suspend fun selectByProvider(providerId: Long, scope: ProfileScope): List<DownloadRuleEntity> =
        selectByProviderRaw(providerId, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM download_rules
        WHERE kind = :kind
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        ORDER BY providerId, targetExternalId, id
        """,
    )
    suspend fun selectByKindRaw(kind: String, excludedProviderIdsCsv: String): List<DownloadRuleEntity>

    suspend fun selectByKind(kind: String, scope: ProfileScope): List<DownloadRuleEntity> =
        selectByKindRaw(kind, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM download_rules
        WHERE providerId = :providerId AND kind = :kind AND targetExternalId = :targetExternalId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun selectByTargetRaw(
        providerId: Long,
        kind: String,
        targetExternalId: String,
        excludedProviderIdsCsv: String,
    ): DownloadRuleEntity?

    suspend fun selectByTarget(
        providerId: Long,
        kind: String,
        targetExternalId: String,
        scope: ProfileScope,
    ): DownloadRuleEntity? =
        selectByTargetRaw(providerId, kind, targetExternalId, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        SELECT * FROM download_rules
        WHERE id = :id
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun selectByIdRaw(id: Long, excludedProviderIdsCsv: String): DownloadRuleEntity?

    suspend fun selectById(id: Long, scope: ProfileScope): DownloadRuleEntity? =
        selectByIdRaw(id, scope.excludedProviderIds.joinToString(","))

    @Query(
        """
        UPDATE download_rules
        SET transcodeBitrateKbps = :transcodeBitrateKbps, format = :format, updatedAt = :updatedAt
        WHERE providerId = :providerId AND kind = :kind AND targetExternalId = :targetExternalId
            AND instr(',' || :excludedProviderIdsCsv || ',', ',' || providerId || ',') = 0
        """,
    )
    suspend fun updateVariantRaw(
        providerId: Long,
        kind: String,
        targetExternalId: String,
        transcodeBitrateKbps: Int?,
        format: String?,
        updatedAt: Long,
        excludedProviderIdsCsv: String,
    ): Int

    suspend fun updateVariant(
        providerId: Long,
        kind: String,
        targetExternalId: String,
        transcodeBitrateKbps: Int?,
        format: String?,
        updatedAt: Long,
        scope: ProfileScope,
    ): Int = updateVariantRaw(
        providerId = providerId,
        kind = kind,
        targetExternalId = targetExternalId,
        transcodeBitrateKbps = transcodeBitrateKbps,
        format = format,
        updatedAt = updatedAt,
        excludedProviderIdsCsv = scope.excludedProviderIds.joinToString(","),
    )

    @Query("DELETE FROM download_rules WHERE providerId = :providerId AND kind = :kind AND targetExternalId = :targetExternalId")
    suspend fun deleteByTarget(providerId: Long, kind: String, targetExternalId: String): Int
}
