package com.azizjon.network

import android.app.Application
import com.azizjon.network.ai.GatewayClient
import com.azizjon.network.ai.GatewaySettings
import com.azizjon.network.backup.GitHubBackupSettings
import com.azizjon.network.backup.GitHubEncryptedBackupManager
import com.azizjon.network.data.NetworkDatabase
import com.azizjon.network.data.NetworkRepository

class NetworkApplication : Application() {
    val repository: NetworkRepository by lazy {
        NetworkRepository(NetworkDatabase.get(this).networkDao())
    }
    val backupSettings: GitHubBackupSettings by lazy { GitHubBackupSettings(this) }
    val backupManager: GitHubEncryptedBackupManager by lazy {
        GitHubEncryptedBackupManager(repository, backupSettings)
    }
    val gatewaySettings: GatewaySettings by lazy { GatewaySettings(this) }
    val gatewayClient: GatewayClient by lazy { GatewayClient(gatewaySettings::token) }
}
