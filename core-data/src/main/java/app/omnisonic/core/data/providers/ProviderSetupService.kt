package app.omnisonic.core.data.providers

import app.omnisonic.core.data.db.OmnisonicDatabase
import app.omnisonic.core.data.db.entity.ProviderEntity
import app.omnisonic.core.data.db.entity.ProviderKind
import app.omnisonic.core.data.security.CredentialCodec
import app.omnisonic.core.data.security.SecretPayload
import app.omnisonic.core.model.log.OmnisonicLogger
import app.omnisonic.provider.api.ProviderAuthException
import app.omnisonic.provider.api.ProviderCapabilities
import app.omnisonic.provider.api.ProviderConnection
import app.omnisonic.provider.api.ProviderEndpoint
import app.omnisonic.provider.api.ProviderException
import app.omnisonic.provider.api.ProviderHttpError
import app.omnisonic.provider.api.ProviderTransportException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Raw first-connection form. Optional secondary URL mirrors the persisted failover block. */
data class ProviderSetupForm(
    val serverUrl: String,
    val username: String,
    val password: String,
    val secondaryUrl: String = "",
)

enum class ProviderSetupIssue {
    SERVER_URL_REQUIRED,
    SERVER_URL_INVALID,
    SECONDARY_URL_INVALID,
    USERNAME_REQUIRED,
    PASSWORD_REQUIRED,
}

data class ProviderSetupPlan(
    val primary: ParsedServerUrl,
    val secondary: ParsedServerUrl?,
)

sealed interface ProviderSetupValidation {
    data class Valid(val plan: ProviderSetupPlan) : ProviderSetupValidation
    data class Invalid(val issues: List<ProviderSetupIssue>) : ProviderSetupValidation
}

/**
 * Shared form rules for the setup screen and the persistence call: the UI shows
 * these issues under the fields, the service re-checks them before writing a
 * row. A blank optional secondary URL means "no failover block".
 */
object ProviderSetupRules {

    fun validate(form: ProviderSetupForm): ProviderSetupValidation {
        val issues = mutableListOf<ProviderSetupIssue>()

        val primary = when {
            form.serverUrl.isBlank() -> {
                issues += ProviderSetupIssue.SERVER_URL_REQUIRED
                null
            }

            else -> parseServerUrl(form.serverUrl).also {
                if (it == null) issues += ProviderSetupIssue.SERVER_URL_INVALID
            }
        }

        val secondary = if (form.secondaryUrl.isBlank()) {
            null
        } else {
            parseServerUrl(form.secondaryUrl).also {
                if (it == null) issues += ProviderSetupIssue.SECONDARY_URL_INVALID
            }
        }

        if (form.username.isBlank()) issues += ProviderSetupIssue.USERNAME_REQUIRED
        if (form.password.isEmpty()) issues += ProviderSetupIssue.PASSWORD_REQUIRED

        return if (issues.isEmpty() && primary != null) {
            ProviderSetupValidation.Valid(ProviderSetupPlan(primary, secondary))
        } else {
            ProviderSetupValidation.Invalid(issues)
        }
    }
}

sealed interface ProviderProbeResult {
    data class Success(val capabilities: ProviderCapabilities) : ProviderProbeResult

    enum class FailureReason { INVALID_INPUT, NETWORK, AUTHENTICATION, SERVER_ERROR, UNSUPPORTED, UNEXPECTED }

    data class Failure(
        val reason: FailureReason,
        val httpCode: Int? = null,
    ) : ProviderProbeResult
}

sealed interface ProviderSetupOutcome {
    data class Saved(val providerId: Long) : ProviderSetupOutcome
    data class Invalid(val issues: List<ProviderSetupIssue>) : ProviderSetupOutcome
}

/**
 * Connection test + first-save path for the setup screen. The probe rides the
 * same client factory and registry the rest of the app uses (transient, never
 * cached); saving writes one provider row with the password sealed through the
 * existing credential codec and starts the initial library walk off the setup
 * screen's coroutine scope.
 */
@Singleton
class ProviderSetupService @Inject constructor(
    private val database: OmnisonicDatabase,
    private val codec: CredentialCodec,
    private val clients: ProviderClientRegistry,
    private val initialSync: InitialLibrarySync,
    private val logger: OmnisonicLogger,
) {

    suspend fun probe(form: ProviderSetupForm): ProviderProbeResult {
        val plan = when (val validation = ProviderSetupRules.validate(form)) {
            is ProviderSetupValidation.Invalid -> {
                return ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.INVALID_INPUT)
            }

            is ProviderSetupValidation.Valid -> validation.plan
        }

        val provider = clients.buildTransientProvider(API_SUBSONIC, form.toConnection(plan))
            ?: return ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.UNSUPPORTED)

        return try {
            ProviderProbeResult.Success(provider.probe())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ProviderAuthException) {
            logger.warn(TAG, "setup probe rejected the credentials")
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.AUTHENTICATION)
        } catch (failure: ProviderHttpError) {
            logger.warn(TAG, "setup probe returned HTTP ${failure.code}")
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.SERVER_ERROR, failure.code)
        } catch (failure: ProviderTransportException) {
            logger.warn(TAG, "setup probe could not reach the server", failure)
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.NETWORK)
        } catch (failure: IOException) {
            logger.warn(TAG, "setup probe transport failure", failure)
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.NETWORK)
        } catch (failure: ProviderException) {
            logger.warn(TAG, "setup probe failed at the provider layer", failure)
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.SERVER_ERROR)
        } catch (failure: Exception) {
            logger.warn(TAG, "setup probe failed unexpectedly", failure)
            ProviderProbeResult.Failure(ProviderProbeResult.FailureReason.UNEXPECTED)
        }
    }

    suspend fun save(form: ProviderSetupForm): ProviderSetupOutcome {
        val plan = when (val validation = ProviderSetupRules.validate(form)) {
            is ProviderSetupValidation.Invalid -> return ProviderSetupOutcome.Invalid(validation.issues)
            is ProviderSetupValidation.Valid -> validation.plan
        }
        val timestamp = System.currentTimeMillis()
        val row = ProviderEntity(
            name = plan.primary.displayName,
            api = API_SUBSONIC,
            kind = ProviderKind.REMOTE_SERVER.name,
            displayOrder = database.providerDao().count(),
            primaryScheme = plan.primary.block.scheme,
            primaryHost = plan.primary.block.host,
            primaryPort = plan.primary.block.port,
            primaryPath = plan.primary.block.path,
            secondaryScheme = plan.secondary?.block?.scheme,
            secondaryHost = plan.secondary?.block?.host,
            secondaryPort = plan.secondary?.block?.port,
            secondaryPath = plan.secondary?.block?.path,
            useSecondary = plan.secondary != null,
            username = form.username.trim(),
            secretBlob = codec.seal(
                SecretPayload(mapOf(ProviderConfigMapper.SECRET_PASSWORD to form.password)),
            ),
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        val providerId = database.providerDao().upsert(row)
        clients.invalidate(providerId)
        logger.info(TAG, "saved provider $providerId for ${plan.primary.block.host}")
        return ProviderSetupOutcome.Saved(providerId)
    }

    /** Fire-and-forget first library walk; setup never blocks on the sync. */
    fun startInitialSync(providerId: Long) = initialSync.start(providerId)

    private fun ProviderSetupForm.toConnection(plan: ProviderSetupPlan): ProviderConnection =
        ProviderConnection(
            providerId = UNSAVED_PROVIDER_ID,
            displayName = plan.primary.displayName,
            endpoint = ProviderEndpoint.Remote(plan.primary.block, plan.secondary?.block),
            username = username.trim(),
            password = password,
        )

    companion object {
        const val API_SUBSONIC = "subsonic"
        const val UNSAVED_PROVIDER_ID = 0L
        private const val TAG = "ProviderSetup"
    }
}
