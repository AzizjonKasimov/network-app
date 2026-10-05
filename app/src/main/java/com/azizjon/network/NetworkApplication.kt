package com.azizjon.network

import android.app.Application
import com.azizjon.network.ai.AgentClient
import com.azizjon.network.ai.CaptureDraftStore
import com.azizjon.network.ai.GatewaySettings
import com.azizjon.network.backup.GitHubBackupSettings
import com.azizjon.network.backup.GitHubEncryptedBackupManager
import com.azizjon.network.checkin.CheckinRepository
import com.azizjon.network.checkin.CheckinSettings
import com.azizjon.network.checkin.ContactsScanner
import com.azizjon.network.checkin.EveningCheckin
import com.azizjon.network.data.AssistantStore
import com.azizjon.network.data.NetworkDatabase
import com.azizjon.network.data.NetworkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NetworkApplication : Application() {
    /** For work that must finish even when no screen is open, such as recording a chat notification. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    val checkinSettings: CheckinSettings by lazy { CheckinSettings(this) }
    val checkins: CheckinRepository by lazy {
        CheckinRepository(NetworkDatabase.get(this), ContactsScanner(this), appScope)
    }

    override fun onCreate() {
        super.onCreate()
        // Re-creates the evening reminder if its schedule was ever lost; leaves a live one alone.
        EveningCheckin.schedule(this, checkinSettings.state, replace = false)
    }
}
