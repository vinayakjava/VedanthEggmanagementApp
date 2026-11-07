package com.eggbusinessmanager.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `stored names round-trip`() {
        ThemeMode.entries.forEach { mode ->
            assertThat(ThemeMode.fromStored(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `unknown or missing values fall back to SYSTEM`() {
        assertThat(ThemeMode.fromStored(null)).isEqualTo(ThemeMode.SYSTEM)
        assertThat(ThemeMode.fromStored("PURPLE")).isEqualTo(ThemeMode.SYSTEM)
    }
}
