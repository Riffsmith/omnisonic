package app.omnisonic.core.data.downloads

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.ProfileScope
import app.omnisonic.core.data.db.entity.DownloadRuleEntity

/** Persisted automatic-rule kinds; [name] is the stored value. */
enum class AutomaticRuleKind { FAVORITES, PLAYLIST, ALBUM }

/**
 * One stored rule. Identity is [providerId] + [kind] + [targetExternalId];
 * [id] is a row handle only and may change if a rule is removed and re-added.
 * FAVORITES always carries [FAVORITES_TARGET_EXTERNAL_ID].
 */
data class AutomaticRule(
    val id: Long,
    val providerId: Long,
    val kind: AutomaticRuleKind,
    val targetExternalId: String,
    val transcodeBitrateKbps: Int?,
    val format: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class AutomaticRuleAddOutcome { ADDED, DUPLICATE, INVALID }

/**
 * Rule persistence only (Phase 7 task 7a, D-47). Expansion into queue rows is
 * task 7b and pruning is task 8; nothing here touches downloads or files.
 * Reads take [ProfileScope] with the Raw+wrapper DAO convention; internal
 * callers pass [ProfileScope.Unrestricted].
 */
class AutomaticRuleRepository(
    private val db: OmnisonicDatabase,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    suspend fun add(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String = FAVORITES_TARGET_EXTERNAL_ID,
        transcodeBitrateKbps: Int? = null,
        format: String? = null,
    ): AutomaticRuleAddOutcome {
        val target = normalizedTarget(kind, targetExternalId) ?: return AutomaticRuleAddOutcome.INVALID
        if (providerId <= 0L ||
            transcodeBitrateKbps?.let { it <= 0 } == true ||
            format?.isBlank() == true
        ) {
            return AutomaticRuleAddOutcome.INVALID
        }
        val now = nowMs()
        val inserted = db.downloadRuleDao().insertIgnoring(
            DownloadRuleEntity(
                providerId = providerId,
                kind = kind.name,
                targetExternalId = target,
                transcodeBitrateKbps = transcodeBitrateKbps,
                format = format,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return if (inserted == -1L) AutomaticRuleAddOutcome.DUPLICATE else AutomaticRuleAddOutcome.ADDED
    }

    /** Explicit variant change; a duplicate [add] never rewrites the existing rule. */
    suspend fun setVariant(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String,
        transcodeBitrateKbps: Int?,
        format: String?,
        scope: ProfileScope,
    ): Boolean {
        val target = normalizedTarget(kind, targetExternalId) ?: return false
        if (transcodeBitrateKbps?.let { it <= 0 } == true || format?.isBlank() == true) return false
        return db.downloadRuleDao().updateVariant(
            providerId = providerId,
            kind = kind.name,
            targetExternalId = target,
            transcodeBitrateKbps = transcodeBitrateKbps,
            format = format,
            updatedAt = nowMs(),
            scope = scope,
        ) > 0
    }

    suspend fun remove(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String,
    ): Boolean {
        val target = normalizedTarget(kind, targetExternalId) ?: return false
        return db.downloadRuleDao().deleteByTarget(providerId, kind.name, target) > 0
    }

    suspend fun list(scope: ProfileScope): List<AutomaticRule> =
        db.downloadRuleDao().selectAll(scope).mapNotNull { it.toAutomaticRule() }

    suspend fun listForProvider(providerId: Long, scope: ProfileScope): List<AutomaticRule> =
        db.downloadRuleDao().selectByProvider(providerId, scope).mapNotNull { it.toAutomaticRule() }

    suspend fun listByKind(kind: AutomaticRuleKind, scope: ProfileScope): List<AutomaticRule> =
        db.downloadRuleDao().selectByKind(kind.name, scope).mapNotNull { it.toAutomaticRule() }

    suspend fun find(
        providerId: Long,
        kind: AutomaticRuleKind,
        targetExternalId: String,
        scope: ProfileScope,
    ): AutomaticRule? {
        val target = normalizedTarget(kind, targetExternalId) ?: return null
        return db.downloadRuleDao().selectByTarget(providerId, kind.name, target, scope)?.toAutomaticRule()
    }

    suspend fun findById(id: Long, scope: ProfileScope): AutomaticRule? =
        db.downloadRuleDao().selectById(id, scope)?.toAutomaticRule()

    private fun normalizedTarget(kind: AutomaticRuleKind, targetExternalId: String): String? =
        when (kind) {
            AutomaticRuleKind.FAVORITES -> FAVORITES_TARGET_EXTERNAL_ID
            AutomaticRuleKind.PLAYLIST, AutomaticRuleKind.ALBUM -> targetExternalId.takeIf { it.isNotBlank() }
        }

    private fun DownloadRuleEntity.toAutomaticRule(): AutomaticRule? {
        val ruleKind = AutomaticRuleKind.entries.firstOrNull { it.name == kind } ?: return null
        return AutomaticRule(
            id = id,
            providerId = providerId,
            kind = ruleKind,
            targetExternalId = targetExternalId,
            transcodeBitrateKbps = transcodeBitrateKbps,
            format = format,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    companion object {
        /** Favorites have no external target; the sentinel keeps the unique key total. */
        const val FAVORITES_TARGET_EXTERNAL_ID = ""
    }
}
