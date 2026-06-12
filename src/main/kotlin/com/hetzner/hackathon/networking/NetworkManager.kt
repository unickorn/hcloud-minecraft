package com.hetzner.hackathon.networking

import com.hetzner.hackathon.HCloudPlugin
import com.hetzner.hackathon.api.Network
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.TextDisplay
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.CompletableFuture

class NetworkManager(private val plugin: HCloudPlugin) {
    private val scanner = RedstoneScanner(plugin)
    private val debounceTasks = mutableMapOf<Location, org.bukkit.scheduler.BukkitTask>()

    fun reconcile(location: Location) {
        // Simple debounce: cancel existing task for this location if it exists
        debounceTasks[location]?.cancel()
        
        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            debounceTasks.remove(location)
            performReconcile(location)
        }, 20L) // 1 second debounce
        
        debounceTasks[location] = task
    }

    private fun performReconcile(location: Location) {
        val cluster = scanner.scanCluster(location)
        
        if (cluster.serverIds.size < 2) {
            cluster.serverIds.forEach { serverId ->
                detachAndRemoveStatus(serverId, location)
            }
            setWiresPower(cluster.wires, 0)
            cleanupEmptyNetworks()
            return
        }

        // 1. Check if any server in the cluster is already in a network
        val networkChecks = cluster.serverIds.map { serverId ->
            plugin.client?.getServerNetworkInfo(serverId) ?: CompletableFuture.completedFuture(emptyList())
        }

        CompletableFuture.allOf(*networkChecks.toTypedArray()).thenCompose {
            val attachments = networkChecks.flatMap { it.join() }
            val existingNetworkIds = attachments.map { it.networkId }.toSet()
            
            plugin.client?.getNetworks()?.thenCompose { allNetworks ->
                val mcNetworks = allNetworks.filter { it.name.startsWith("minecraft-net-") }
                val clusterNetworks = mcNetworks.filter { it.id in existingNetworkIds }

                val targetNetwork = clusterNetworks.firstOrNull() ?: run {
                    val indices = mcNetworks.mapNotNull { it.name.removePrefix("minecraft-net-").toIntOrNull() }
                    val nextIndex = (indices.maxOrNull() ?: 0) + 1
                    // join() is not ideal here, but simpler for pick-one logic
                    createNewNetwork(nextIndex).join()
                }

                // 2. Detach servers from ANY minecraft-net-* that isn't our target
                val detachTasks = cluster.serverIds.flatMap { serverId ->
                    val otherAttachments = attachments.filter { 
                        it.networkId != targetNetwork.id && 
                        mcNetworks.any { net -> net.id == it.networkId } 
                    }
                    otherAttachments.map { 
                        plugin.client?.detachServerFromNetwork(serverId, it.networkId) ?: CompletableFuture.completedFuture(null)
                    }
                }

                CompletableFuture.allOf(*detachTasks.toTypedArray()).thenApply { targetNetwork }
            }
        }.thenAccept { targetNetwork ->
            setWiresPower(cluster.wires, 15)
            cluster.serverIds.forEach { serverId ->
                attachAndShowStatus(serverId, targetNetwork, location)
            }
            // 3. Cleanup empty networks after a short delay
            plugin.server.scheduler.runTaskLater(plugin, Runnable { cleanupEmptyNetworks() }, 200L)
        }
    }

    private fun setWiresPower(wires: Set<org.bukkit.block.Block>, power: Int) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            wires.forEach { wire ->
                if (wire.type == Material.REDSTONE_WIRE) {
                    val data = wire.blockData as org.bukkit.block.data.type.RedstoneWire
                    data.power = power
                    wire.setBlockData(data, false) // false to avoid triggering more physics
                }
            }
        })
    }

    private fun detachAndRemoveStatus(serverId: Long, scanOrigin: Location) {
        plugin.client?.getServerNetworkInfo(serverId)?.thenAccept { netInfos ->
            netInfos.forEach { info ->
                // Only detach from our managed networks
                plugin.client?.getNetworks()?.thenAccept { networks ->
                    val net = networks.find { it.id == info.networkId && it.name.startsWith("minecraft-net-") }
                    if (net != null) {
                        plugin.client?.detachServerFromNetwork(serverId, net.id)?.thenAccept {
                            updateStatus(serverId, scanOrigin, null, NamedTextColor.GRAY)
                        }
                    }
                }
            }
        }
    }

    private fun cleanupEmptyNetworks() {
        plugin.client?.getNetworks()?.thenAccept { networks ->
            networks.filter { it.name.startsWith("minecraft-net-") }.forEach { net ->
                if (net.serverIds.isEmpty()) {
                    plugin.client?.deleteNetwork(net.id)
                } else {
                    pollAndDeleteIfEmpty(net.id)
                }
            }
        }
    }

    private fun pollAndDeleteIfEmpty(networkId: Long) {
        val maxAttempts = 24 // 2 minutes (every 5 seconds)
        var attempts = 0
        
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                attempts++
                if (attempts > maxAttempts) {
                    this.cancel()
                    return
                }

                plugin.client?.getNetworks()?.thenAccept { networks ->
                    val net = networks.find { it.id == networkId }
                    if (net == null) {
                        this.cancel()
                        return@thenAccept
                    }

                    if (net.serverIds.isEmpty()) {
                        plugin.client?.deleteNetwork(networkId)?.thenAccept {
                            this.cancel()
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L) // Check every 5 seconds
    }

    private fun createNewNetwork(index: Int): CompletableFuture<Network> {
        val name = "minecraft-net-$index"
        val ipRange = "10.$index.0.0/16"
        return plugin.client?.createNetwork(name, ipRange)?.thenCompose { net ->
            plugin.client?.addSubnet(net.id, "10.$index.0.0/24")?.thenApply { net }
        } ?: CompletableFuture.failedFuture(Exception("Client not initialized"))
    }

    private fun attachAndShowStatus(serverId: Long, network: Network, scanOrigin: Location) {
        updateStatus(serverId, scanOrigin, "Adding to ${network.name}...", NamedTextColor.YELLOW)

        plugin.client?.attachServerToNetwork(serverId, network.id)?.handle { _, ex ->
            if (ex != null && ex.message?.contains("already_attached") == false) {
                return@handle
            }
            
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                plugin.client?.getServerNetworkInfo(serverId)?.thenAccept { netInfos ->
                    val myInfo = netInfos.find { it.networkId == network.id }
                    if (myInfo != null) {
                        updateStatus(serverId, scanOrigin, "in ${network.name} (${myInfo.privateIp})", NamedTextColor.GREEN)
                    }
                }
            }, 60L)
        }
    }

    private fun updateStatus(serverId: Long, searchLoc: Location, status: String?, color: NamedTextColor) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            searchLoc.world.getNearbyEntities(searchLoc, 100.0, 100.0, 100.0)
                .filterIsInstance<TextDisplay>()
                .forEach { display ->
                    val pdc = display.persistentDataContainer
                    if (!pdc.has(NamespacedKey(plugin, "status_display"), PersistentDataType.BOOLEAN)) return@forEach
                    
                    val ownerX = pdc.get(NamespacedKey(plugin, "owner_x"), PersistentDataType.INTEGER) ?: return@forEach
                    val ownerY = pdc.get(NamespacedKey(plugin, "owner_y"), PersistentDataType.INTEGER) ?: return@forEach
                    val ownerZ = pdc.get(NamespacedKey(plugin, "owner_z"), PersistentDataType.INTEGER) ?: return@forEach
                    
                    val block = searchLoc.world.getBlockAt(ownerX, ownerY, ownerZ)
                    val state = block.state
                    if (state is org.bukkit.block.TileState) {
                        val id = state.persistentDataContainer.get(NamespacedKey(plugin, "server_id"), PersistentDataType.LONG)
                        if (id == serverId) {
                            // Save status to block PDC for recovery during UI updates
                            if (status != null) {
                                state.persistentDataContainer.set(NamespacedKey(plugin, "net_status"), PersistentDataType.STRING, status)
                                state.persistentDataContainer.set(NamespacedKey(plugin, "net_color"), PersistentDataType.STRING, color.toString())
                            } else {
                                state.persistentDataContainer.remove(NamespacedKey(plugin, "net_status"))
                                state.persistentDataContainer.remove(NamespacedKey(plugin, "net_color"))
                            }
                            state.update()

                            val current = display.text()
                            
                            // Remove existing network line if present
                            val cleanChildren = current.children().filterNot { child ->
                                val content = (child as? net.kyori.adventure.text.TextComponent)?.content() ?: ""
                                content.startsWith("\n[in minecraft-net") || content.startsWith("\n[Adding to")
                            }
                            
                            var newComponent = current.children(cleanChildren)
                            if (status != null) {
                                val statusLine = Component.text("\n[$status]", color)
                                newComponent = newComponent.append(statusLine)
                            }
                            
                            display.text(newComponent)
                        }
                    }
                }
        })
    }
}
