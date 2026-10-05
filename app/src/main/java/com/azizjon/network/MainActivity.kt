package com.azizjon.network

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.azizjon.network.checkin.EveningCheckin
import com.azizjon.network.ui.AppSection
import com.azizjon.network.ui.NetworkApp
import com.azizjon.network.ui.NetworkViewModel
import com.azizjon.network.ui.theme.NetworkTheme

class MainActivity : ComponentActivity() {
    private val viewModel: NetworkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity already handled the intent that started it.
        if (savedInstanceState == null) handle(intent)
        setContent {
            NetworkTheme {
                NetworkApp(viewModel)
            }
        }
    }

    /** The activity is single-task, so a share or a notification tap arrives here once it is running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> viewModel.receiveShare(
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                photos = listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
                    .takeIf { intent.type?.startsWith("image/") == true }
                    .orEmpty(),
            )
            Intent.ACTION_SEND_MULTIPLE -> viewModel.receiveShare(
                text = intent.getStringExtra(Intent.EXTRA_TEXT),
                photos = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty(),
            )
        }
        when (intent.getStringExtra(EveningCheckin.EXTRA_OPEN)) {
            EveningCheckin.OPEN_CHECKIN -> viewModel.openSection(AppSection.CHECKIN)
            EveningCheckin.OPEN_ASSISTANT -> viewModel.openSection(AppSection.CHAT)
        }
    }
}
