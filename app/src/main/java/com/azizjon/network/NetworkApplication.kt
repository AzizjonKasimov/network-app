package com.azizjon.network

import android.app.Application
import com.azizjon.network.ai.AgentClient
import com.azizjon.network.ai.CaptureDraftStore
import com.azizjon.network.ai.GatewaySettings
import com.azizjon.network.backup.GitHubBackupSettings
import com.azizjon.network.backup.GitHubEncryptedBackupManager
import com.azizjon.network.data.AssistantStore
import com.azizjon.network.data.NetworkDatabase
import com.azizjon.network.data.NetworkRepository

class NetworkApplication : Application() {
    val repository: NetworkRepository by lazy {
        NetworkRepository(NetworkDatabase.get(this).networkDao())
    }
    val assistantStore: AssistantStore by lazy { AssistantStore(NetworkDatabase.get(this)) }
    val backupSettings: GitHubBackupSettings by lazy { GitHubBackupSettings(this) }
    val backupManager: GitHubEncryptedBackupManager by lazy {
        GitHubEncryptedBackupManager(repository, backupSettings)
    }
    val gatewaySettings: GatewaySettings by lazy { GatewaySettings(this) }
    val agentClient: AgentClient by lazy { AgentClient(gatewaySettings::token) }
    val captureDraftStore: CaptureDraftStore by lazy { CaptureDraftStore(this) }
}
