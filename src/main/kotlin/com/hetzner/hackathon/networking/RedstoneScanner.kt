package com.hetzner.hackathon.networking

import com.hetzner.hackathon.HCloudPlugin
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.TileState
import org.bukkit.persistence.PersistentDataType
import java.util.*

data class ClusterResult(val serverIds: Set<Long>, val wires: Set<Block>)

class RedstoneScanner(private val plugin: HCloudPlugin) {

    fun scanCluster(start: Location): ClusterResult {
        val visited = mutableSetOf<Block>()
        val queue: Queue<Block> = LinkedList()
        val serverIds = mutableSetOf<Long>()
        val wires = mutableSetOf<Block>()

        queue.add(start.block)
        visited.add(start.block)

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            
            if (current.type == Material.REDSTONE_WIRE) {
                wires.add(current)
            }
            getServerId(current)?.let { serverIds.add(it) }

            for (face in org.bukkit.block.BlockFace.entries) {
                if (face.isCartesian || face == org.bukkit.block.BlockFace.UP || face == org.bukkit.block.BlockFace.DOWN) {
                    val neighbor = current.getRelative(face)
                    if (neighbor !in visited && isConnectable(neighbor)) {
                        visited.add(neighbor)
                        queue.add(neighbor)
                    }
                }
            }
        }

        return ClusterResult(serverIds, wires)
    }

    private fun isConnectable(block: Block): Boolean {
        return block.type == Material.REDSTONE_WIRE || getServerId(block) != null
    }

    private fun getServerId(block: Block): Long? {
        val state = block.state
        if (state is TileState) {
            return state.persistentDataContainer.get(NamespacedKey(plugin, "server_id"), PersistentDataType.LONG)
        }
        return null
    }
}
