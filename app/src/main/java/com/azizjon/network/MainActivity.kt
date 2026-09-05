package com.azizjon.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.azizjon.network.ui.NetworkApp
import com.azizjon.network.ui.NetworkViewModel
import com.azizjon.network.ui.theme.NetworkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NetworkTheme {
                NetworkApp(viewModel<NetworkViewModel>())
            }
        }
    }
}
