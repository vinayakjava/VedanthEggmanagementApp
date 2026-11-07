package com.eggbusinessmanager.app.feature.auth.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.components.DropdownField
import com.eggbusinessmanager.app.ui.components.FormTextField
import com.eggbusinessmanager.app.ui.error.FormFeedback
import com.eggbusinessmanager.app.ui.text.ReferenceLabels
import com.eggbusinessmanager.domain.error.Field
import com.eggbusinessmanager.domain.model.account.SecurityAnswerInput
import com.eggbusinessmanager.domain.model.account.SecurityQuestion

/**
 * Three question pickers with answers (FR-012). A question already chosen in another slot is not
 * offered again, so duplicates are prevented as well as validated.
 */
@Composable
fun SecurityQuestionsEditor(
    answers: List<SecurityAnswerInput>,
    feedback: FormFeedback,
    onQuestion: (Int, SecurityQuestion) -> Unit,
    onAnswer: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        feedback.error(Field.SECURITY_QUESTIONS)?.let {
            Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        answers.forEachIndexed { index, item ->
            val takenElsewhere = answers.filterIndexed { i, _ -> i != index }.mapNotNull { it.question }.toSet()
            DropdownField(
                label = stringResource(R.string.label_security_question, index + 1),
                options = SecurityQuestion.entries.filter { it !in takenElsewhere },
                selected = item.question,
                optionLabel = { stringResource(ReferenceLabels.question(it)) },
                onSelect = { onQuestion(index, it) },
            )
            FormTextField(
                value = item.answer,
                onValueChange = { onAnswer(index, it) },
                label = stringResource(R.string.label_security_answer, index + 1),
                error = feedback.error(Field.securityAnswer(index)),
            )
        }
        Text(
            stringResource(R.string.hint_security_answers),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
