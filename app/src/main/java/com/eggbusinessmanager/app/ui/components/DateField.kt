package com.eggbusinessmanager.app.ui.components

import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.eggbusinessmanager.app.R
import com.eggbusinessmanager.app.ui.error.UiText
import com.eggbusinessmanager.app.ui.format.IndianFormatters
import com.eggbusinessmanager.domain.validation.CustomerValidator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Read-only business-date field with a picker limited to 2000-01-01 … today (no future dates). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, today: LocalDate, error: UiText?, onDate: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    FormTextField(
        value = IndianFormatters.date(date, locale),
        onValueChange = {},
        label = label,
        error = error,
        trailingIcon = {
            IconButton(onClick = { open = true }) {
                Icon(painterResource(R.drawable.ic_calendar), contentDescription = stringResource(R.string.action_choose_date))
            }
        },
        readOnly = true,
    )
    if (open) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.toUtcMillis(),
            yearRange = CustomerValidator.EARLIEST_DATE.year..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = utcTimeMillis.toUtcDate()
                    return !day.isAfter(today) && !day.isBefore(CustomerValidator.EARLIEST_DATE)
                }
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
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state = pickerState) }
    }
}

private fun LocalDate.toUtcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
