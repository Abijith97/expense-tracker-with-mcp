package com.abijith.expensetracker

import android.app.Application
import java.util.concurrent.Executors

/** Android creates this even when an SMS or worker, not the screen, starts the process. */
class FinanceTrackerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Keystore/disk work stays off the main thread. Receivers and workers also
        // wait for the same synchronized initialization before scheduling uploads.
        Executors.newSingleThreadExecutor().apply {
            execute { CloudClassificationSettings.initialize(this@FinanceTrackerApplication) }
            shutdown()
        }
    }
}
