package com.hetzner.hackathon.commands

import com.hetzner.hackathon.HCloudPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class HCloudCommand(private val plugin: HCloudPlugin) : CommandExecutor {

    private fun plain(text: String, color: NamedTextColor) = 
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("This command can only be used by players.")
            return true
        }

        if (args.isNotEmpty()) {
            if (args[0].equals("token", ignoreCase = true)) {
                if (args.size < 2) {
                    sender.sendMessage(plain("Usage: /hcloud token <token>", NamedTextColor.RED))
                    return true
                }
                plugin.updateToken(args[1])
                sender.sendMessage(plain("API token updated successfully!", NamedTextColor.GREEN))
                return true
            }
        }

        val client = plugin.client
        if (client == null || plugin.apiToken.isNullOrBlank()) {
            sender.sendMessage(plain("API token not set. Use /hcloud token <your_token> first.", NamedTextColor.RED))
            return true
        }

        sender.sendMessage(plain("Fetching locations...", NamedTextColor.YELLOW))
        client.getLocations().thenAccept { locations ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.guiManager.openLocationGUI(sender, locations)
            })
        }.exceptionally { ex ->
            sender.sendMessage(plain("Failed to fetch locations: ${ex.message}", NamedTextColor.RED))
            null
        }

        return true
    }
}
