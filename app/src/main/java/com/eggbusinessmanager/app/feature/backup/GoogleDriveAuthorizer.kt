package com.eggbusinessmanager.app.feature.backup

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.eggbusinessmanager.domain.backup.BackupException
import com.eggbusinessmanager.domain.backup.BackupFailure
import com.eggbusinessmanager.domain.backup.DriveAuthorizer
import com.eggbusinessmanager.domain.logging.AppLogger
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google authorization with the narrowest Drive scope, `drive.appdata` (research R11): the app can only
 * see its own hidden folder, never the owner's files. Google Identity Services' AuthorizationClient picks
 * the account and shows consent once; later tokens come silently. No client secret is in the app — Google
 * recognises it by package name and signing certificate (registered in the Google Cloud project).
 */
@Singleton
class GoogleDriveAuthorizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger,
) : DriveAuthorizer {

    private val client by lazy { Identity.getAuthorizationClient(context) }
    private val request: AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val connected = MutableStateFlow(false)
    private var loaded = false

    override fun observeConnected(): Flow<Boolean> {
        if (!loaded) {
            connected.value = prefs.getBoolean(KEY_CONNECTED, false)
            loaded = true
        }
        return connected.asStateFlow()
    }

    override suspend fun setConnected(connected: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECTED, connected).apply()
        this.connected.value = connected
        loaded = true
    }

    /** Silent: throws AUTH_REQUIRED when the owner must grant (or re-grant) access in the UI. */
    override suspend fun accessToken(): String {
        val result = authorize()
        if (result.hasResolution()) {
            setConnected(false)
            throw BackupException(BackupFailure.AUTH_REQUIRED, "Drive access needs consent")
        }
        return result.accessToken ?: throw BackupException(BackupFailure.AUTH_REQUIRED, "No access token")
    }

    /** Starts connecting: the consent screen to show, or null when access is already granted. */
    suspend fun beginConnect(): PendingIntent? {
        val result = authorize()
        return if (result.hasResolution()) {
            result.pendingIntent
        } else {
            setConnected(result.accessToken != null)
            null
        }
    }

    /** How connecting ended, so the screen can say so instead of silently staying disconnected. */
    sealed interface ConnectResult {
        data object Connected : ConnectResult
        data object Cancelled : ConnectResult
        data class Failed(val failure: BackupFailure, val statusCode: Int?) : ConnectResult
    }

    /** Finishes connecting with the account chooser / consent screen's result. */
    suspend fun completeConnect(resultCode: Int, data: Intent?): ConnectResult {
        val result = try {
            if (client.getAuthorizationResultFromIntent(data).accessToken != null) {
                ConnectResult.Connected
            } else {
                ConnectResult.Failed(BackupFailure.AUTH_REQUIRED, null)
            }
        } catch (e: ApiException) {
            when {
                e.statusCode == CommonStatusCodes.CANCELED || resultCode == Activity.RESULT_CANCELED && data == null ->
                    ConnectResult.Cancelled
                else -> ConnectResult.Failed(failureFor(e.statusCode), e.statusCode)
            }
        }
        if (result is ConnectResult.Failed) logger.warn(TAG) { "Drive authorization failed: status ${result.statusCode}" }
        setConnected(result == ConnectResult.Connected)
        return result
    }

    private suspend fun authorize(): AuthorizationResult = try {
        client.authorize(request).await()
    } catch (e: ApiException) {
        logger.warn(TAG) { "Drive authorization failed: status ${e.statusCode}" }
        throw BackupException(failureFor(e.statusCode), "Authorization failed (${e.statusCode})", e)
    }

    private fun failureFor(statusCode: Int): BackupFailure = when (statusCode) {
        CommonStatusCodes.NETWORK_ERROR -> BackupFailure.NO_NETWORK
        CommonStatusCodes.DEVELOPER_ERROR -> BackupFailure.GOOGLE_SETUP
        else -> BackupFailure.AUTH_REQUIRED
    }

    private companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
        const val PREFS = "backup_drive"
        const val KEY_CONNECTED = "connected"
        const val TAG = "DriveAuth"
    }
}
