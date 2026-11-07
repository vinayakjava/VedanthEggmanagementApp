package com.eggbusinessmanager.domain.validation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SecurityAnswerNormalizerTest {

    @Test
    fun `trims, collapses spaces and lower-cases`() {
        assertThat(SecurityAnswerNormalizer.normalize("  Saraswati \t  Vidyalaya \n")).isEqualTo("saraswati vidyalaya")
    }

    @Test
    fun `applies NFKC so compatibility forms match`() {
        // Full-width Latin letters (some keyboards) normalize to ASCII.
        assertThat(SecurityAnswerNormalizer.normalize("ＰＵＮＥ")).isEqualTo("pune")
    }

    @Test
    fun `Hindi text is kept and compared consistently`() {
        assertThat(SecurityAnswerNormalizer.normalize(" नासिक  शहर ")).isEqualTo("नासिक शहर")
    }
}
