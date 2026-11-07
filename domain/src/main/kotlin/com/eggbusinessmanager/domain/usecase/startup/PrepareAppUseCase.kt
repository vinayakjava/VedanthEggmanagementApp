package com.eggbusinessmanager.domain.usecase.startup

import com.eggbusinessmanager.domain.logging.AppLogger
import com.eggbusinessmanager.domain.model.StartupState
import com.eggbusinessmanager.domain.repository.StartupRepository
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Prepares local storage once per process. Never throws: every failure becomes a [StartupState]. */
class PrepareAppUseCase @Inject constructor(
    private val startupRepository: StartupRepository,
    private val logger: AppLogger,
) {
    suspend operator fun invoke(): StartupState = try {
        startupRepository.prepare().also { state ->
            logger.info(TAG) { "Startup finished: ${state::class.simpleName}" }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(TAG, e) { "Startup failed unexpectedly" }
        StartupState.Failed(StartupState.Failed.Reason.UNKNOWN)
    }

    private companion object {
        const val TAG = "PrepareApp"
    }
}
