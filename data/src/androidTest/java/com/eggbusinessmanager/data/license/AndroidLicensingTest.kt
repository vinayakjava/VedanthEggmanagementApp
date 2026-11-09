package com.eggbusinessmanager.data.license

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eggbusinessmanager.domain.license.LicenseFormat
import com.eggbusinessmanager.domain.license.StoredLicense
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** The Keystore device identity is stable on this phone; the activated key is stored privately and read back. */
@RunWith(AndroidJUnit4::class)
class AndroidLicensingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deviceIdentityIsStableAndShort() = runTest {
        val first = KeystoreDeviceIdentity(Dispatchers.IO).deviceHash()
        val again = KeystoreDeviceIdentity(Dispatchers.IO).deviceHash() // a new instance reads the same Keystore key
        assertThat(first).hasLength(LicenseFormat.DEVICE_HASH_BYTES)
        assertThat(again).isEqualTo(first)
        assertThat(LicenseFormat.deviceCode(first)).matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}")
    }

    @Test
    fun licenseIsSavedInNoBackupStorageAndReadBack() = runTest {
        val store = FileLicenseStore(context, Dispatchers.IO)
        File(context.noBackupFilesDir, FileLicenseStore.DIR).deleteRecursively()
        assertThat(store.load()).isNull()
        val saved = StoredLicense("EV1-TEST-KEY", Instant.ofEpochMilli(1_794_000_000_000))
        store.save(saved)
        assertThat(store.load()).isEqualTo(saved)
        assertThat(File(File(context.noBackupFilesDir, FileLicenseStore.DIR), FileLicenseStore.FILE).exists()).isTrue()
    }
}
