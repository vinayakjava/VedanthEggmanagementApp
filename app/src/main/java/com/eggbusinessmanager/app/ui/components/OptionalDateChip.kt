package com.eggbusinessmanager.app.ui.components

import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.domain.validation.CustomerValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** A chip that opens a date picker; "Clear" sets the date back to "any". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionalDateChip(label: String, date: LocalDate?, today: LocalDate, onDate: (LocalDate?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null) },
    )
    if (!open) return
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = (date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = CustomerValidator.EARLIEST_DATE.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcTimeMillis.toUtcDate().isAfter(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                pickerState.selectedDateMillis?.let { onDate(it.toUtcDate()) }
                open = false
            }) { Text(stringResource(R.string.action_ok)) }
        },
        dismissButton = {
            TextButton(onClick = {
                onDate(null)
                open = false
            }) { Text(stringResource(R.string.tx_clear)) }
        },
    ) { DatePicker(state = pickerState) }
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
