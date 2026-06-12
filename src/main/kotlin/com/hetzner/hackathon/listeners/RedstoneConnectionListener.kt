package com.hetzner.hackathon.listeners

import com.hetzner.hackathon.HCloudPlugin
import com.hetzner.hackathon.networking.NetworkManager
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.persistence.PersistentDataType

import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.block.data.type.RedstoneWire

class RedstoneConnectionListener(
    private val plugin: HCloudPlugin,
    private val networkManager: NetworkManager
) : Listener {

    @EventHandler
    fun onBlockPhysics(event: BlockPhysicsEvent) {
        val block = event.block
        if (block.type == Material.REDSTONE_WIRE) {
            updateWireConnections(block)
        }
    }

    private fun updateWireConnections(block: org.bukkit.block.Block) {
        val data = block.blockData as? RedstoneWire ?: return
        var changed = false

        for (face in listOf(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST)) {
            val neighbor = block.getRelative(face)
            if (isServerBlock(neighbor)) {
                if (data.getFace(face) == RedstoneWire.Connection.NONE) {
                    data.setFace(face, RedstoneWire.Connection.SIDE)
                    changed = true
                }
            }
        }

        if (changed) {
            block.setBlockData(data, false)
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val block = event.blockPlaced
        if (block.type == Material.REDSTONE_WIRE) {
            // Delay slightly to let Minecraft calculate its own connections first
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (block.type == Material.REDSTONE_WIRE) {
                    updateWireConnections(block)
                    networkManager.reconcile(block.location)
                }
            })
        } else {
            // Check if it's a server (using delay to ensure HCloudListener has tagged it)
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (isServerBlock(block)) {
                    // Check adjacent wires
                    listOf(org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH, org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST).forEach { face ->
                        val neighbor = block.getRelative(face)
                        if (neighbor.type == Material.REDSTONE_WIRE) {
                            updateWireConnections(neighbor)
                        }
                    }
                    networkManager.reconcile(block.location)
                }
            })
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type == Material.REDSTONE_WIRE || isServerBlock(block)) {
            // Reconcile from neighbors to ensure split clusters are updated
            listOf(
                org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST,
                org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.UP,
                org.bukkit.block.BlockFace.DOWN
            ).forEach { face ->
                networkManager.reconcile(block.getRelative(face).location)
            }
        }
    }

    private fun isServerBlock(block: org.bukkit.block.Block): Boolean {
        val state = block.state
        if (state is org.bukkit.block.TileState) {
            val pdc = state.persistentDataContainer
            return pdc.has(NamespacedKey(plugin, "server_id"), PersistentDataType.LONG) || 
                   pdc.has(NamespacedKey(plugin, "server_type"), PersistentDataType.STRING)
        }
        return false
    }
}
