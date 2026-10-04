package com.abijith.expensetracker

import android.content.Context
import androidx.compose.runtime.Composable

/** Release builds have no simulator UI or synthetic-input entry point. */
@Composable
internal fun SmsSimulationControls(enabled: Boolean, cloudEnabled: Boolean, cloudLoading: Boolean,
                                   onSimulate: suspend (String, String, Boolean) -> Boolean) = Unit

internal fun submitSimulatedSms(context: Context, store: SmsStore, sender: String, body: String,
                                allowCloud: Boolean): Boolean = false
