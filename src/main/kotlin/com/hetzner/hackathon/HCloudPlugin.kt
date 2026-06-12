package com.hetzner.hackathon

import org.bukkit.plugin.java.JavaPlugin
import com.hetzner.hackathon.commands.HCloudCommand
import com.hetzner.hackathon.listeners.HCloudListener
import com.hetzner.hackathon.api.HCloudClient
import com.hetzner.hackathon.gui.GUIManager
import com.hetzner.hackathon.networking.NetworkManager
import com.hetzner.hackathon.listeners.RedstoneConnectionListener

class HCloudPlugin : JavaPlugin() {

    var apiToken: String? = null
    var client: HCloudClient? = null
    lateinit var guiManager: GUIManager
    lateinit var networkManager: NetworkManager

    override fun onEnable() {
        saveDefaultConfig()
        apiToken = config.getString("api_token")
        if (!apiToken.isNullOrBlank()) {
            client = HCloudClient(apiToken!!)
        }
        guiManager = GUIManager(this)
        networkManager = NetworkManager(this)

        getCommand("hcloud")?.setExecutor(HCloudCommand(this))
        server.pluginManager.registerEvents(HCloudListener(this), this)
        server.pluginManager.registerEvents(RedstoneConnectionListener(this, networkManager), this)

        logger.info("HCloudPlugin enabled!")
    }

    fun updateToken(token: String) {
        apiToken = token
        client = HCloudClient(token)
        config.set("api_token", token)
        saveConfig()
    }
}
