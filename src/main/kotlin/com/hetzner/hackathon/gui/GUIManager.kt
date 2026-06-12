package com.hetzner.hackathon.gui

import com.hetzner.hackathon.HCloudPlugin
import com.hetzner.hackathon.api.Location
import com.hetzner.hackathon.api.ServerType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.SkullMeta
import com.destroystokyo.paper.profile.ProfileProperty
import java.util.*

class GUIManager(private val plugin: HCloudPlugin) {

    private val playerSelections = mutableMapOf<Player, Selection>()
    private val pendingDeletions = mutableMapOf<Player, PendingDeletion>()

    data class Selection(
        var serverType: String? = null,
        var location: String? = null,
        var installMinecraft: Boolean = false,
        var price: String? = null,
        var cores: Int = 0,
        var memory: Float = 0f
    )

    data class PendingDeletion(
        val serverId: Long,
        val blockLocation: org.bukkit.Location
    )

    private fun plain(text: String, color: NamedTextColor) = 
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    private fun addBackButton(inv: Inventory) {
        val back = ItemStack(Material.ARROW)
        val meta = back.itemMeta
        meta.displayName(plain("Back", NamedTextColor.RED))
        meta.persistentDataContainer.set(NamespacedKey(plugin, "action"), PersistentDataType.STRING, "back")
        back.itemMeta = meta
        inv.setItem(45, back)
    }

    fun openLocationGUI(player: Player, locations: List<Location>) {
        playerSelections[player] = Selection()
        val inv = Bukkit.createInventory(null, 54, plain("Select Location", NamedTextColor.DARK_BLUE))
        
        locations.forEachIndexed { index, loc ->
            if (index >= 45) return@forEachIndexed
            val item = getCustomHead(loc.name)
            val meta = item.itemMeta
            meta.displayName(plain("${loc.name} (${loc.city})", NamedTextColor.GOLD))
            meta.lore(listOf(plain(loc.description, NamedTextColor.GRAY)))
            meta.persistentDataContainer.set(NamespacedKey(plugin, "location"), PersistentDataType.STRING, loc.name)
            item.itemMeta = meta
            inv.setItem(index, item)
        }
        player.openInventory(inv)
    }

    fun openServerTypeGUI(player: Player, types: List<ServerType>) {
        val selection = playerSelections[player] ?: return
        val inv = Bukkit.createInventory(null, 54, plain("Select Server Type", NamedTextColor.DARK_BLUE))
        
        val groups = types.groupBy { type ->
            val name = type.name.uppercase()
            when {
                name.startsWith("CPX") -> "CPX"
                name.startsWith("CX") -> "CX"
                name.startsWith("CAX") -> "CAX"
                name.startsWith("CCX") -> "CCX"
                else -> "Other"
            }
        }.toSortedMap()

        var slot = 0
        groups.forEach { (groupName, groupTypes) ->
            if (slot % 9 != 0) {
                slot = (slot / 9 + 1) * 9
            }

            val material = when (groupName) {
                "CPX" -> Material.BLUE_WOOL
                "CX" -> Material.LIGHT_BLUE_WOOL
                "CAX" -> Material.ORANGE_WOOL
                "CCX" -> Material.PURPLE_WOOL
                else -> Material.WHITE_WOOL
            }

            groupTypes.sortedBy { it.cores * 100 + it.memory }.forEach { type ->
                if (slot >= 45) return@forEach
                val item = ItemStack(material)
                val meta = item.itemMeta
                meta.displayName(plain(type.name, NamedTextColor.GOLD))
                
                val lore = mutableListOf(
                    plain(type.description, NamedTextColor.GRAY),
                    plain("Cores: ${type.cores}", NamedTextColor.WHITE),
                    plain("Memory: ${type.memory} GB", NamedTextColor.WHITE)
                )

                val price = type.prices?.find { it.location == selection.location }
                if (price != null) {
                    val monthlyRaw = price.priceMonthly.get("net").asString
                    val monthly = String.format("%.2f", monthlyRaw.toDouble())
                    lore.add(plain("Price: €$monthly / month", NamedTextColor.GREEN))
                    meta.persistentDataContainer.set(NamespacedKey(plugin, "price"), PersistentDataType.STRING, "€$monthly/mo")
                }


                meta.persistentDataContainer.set(NamespacedKey(plugin, "server_type"), PersistentDataType.STRING, type.name)
                meta.persistentDataContainer.set(NamespacedKey(plugin, "cores"), PersistentDataType.INTEGER, type.cores)
                meta.persistentDataContainer.set(NamespacedKey(plugin, "memory"), PersistentDataType.FLOAT, type.memory)
                meta.lore(lore)
                item.itemMeta = meta
                inv.setItem(slot, item)
                slot++
            }
        }
        
        addBackButton(inv)
        player.openInventory(inv)
    }

    private fun getCustomHead(name: String): ItemStack {
        val item = ItemStack(Material.PLAYER_HEAD)
        val meta = item.itemMeta as SkullMeta
        
        val texture = when (name.lowercase()) {
            "nbg1", "fsn1" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNWU3ODk5YjQ4MDY4NTg2OTdlMjgzZjA4NGQ5MTczZmU0ODc4ODY0NTM3NzQ2MjZiMjRiZDhjZmVjYzc3YjNmIn19fQ==" // Germany
            "hel1" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTlmMjM0OTcyOWE3ZWM4ZDRiMTQ3OGFkZmU1Y2E4YWY5NjQ3OWU5ODNmYmFkMjM4Y2NiZDgxNDA5YjRlZCJ9fX0=" // Finland
            "ash", "hil" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNGNhYzk3NzRkYTEyMTcyNDg1MzJjZTE0N2Y3ODMxZjY3YTEyZmRjY2ExY2YwY2I0YjM4NDhkZTZiYzk0YjQifX19" // USA
            "sin" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOGI1ZWQxMWY3OTdmM2ZjNjFlYWY4ZGFmYjZiZjMyMzRkMzFiOTZhYjc1OTZiZDJkZjcyMmQyZWYzNDczYzI3In19fQ==" // Singapore
            "tick" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZDk5ODBjMWQyMTE4MDlhOWI2NTY1MDg4ZjU2YTM4ZjJlZjQ5MTE1YzEwNTRmYTY2MjQ1MTIyZTllZWVkZWNjMiJ9fX0="
            "cross" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzEwNTkxZTY5MDllNmEyODFiMzcxODM2ZTQ2MmQ2N2EyYzc4ZmEwOTUyZTkxMGYzMmI0MWEyNmM0OGMxNzU3YyJ9fX0="
            "server" -> "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjg5MWFmZDM5ZTJlNjczOGJjNmE4Yzg4YzI0OWZkYmNmNGE0NWM0YTI0MjQ3ZjFkMTBiYWUwYzY0ZDk5OTFlMSJ9fX0="
            else -> null
        }

        if (texture != null) {
            val uuid = UUID.nameUUIDFromBytes(texture.toByteArray())
            val profile = Bukkit.createProfile(uuid, name)
            profile.setProperty(ProfileProperty("textures", texture))
            meta.playerProfile = profile
        }
        
        item.itemMeta = meta
        return item
    }

    fun openInstallMinecraftGUI(player: Player) {
        val inv = Bukkit.createInventory(null, 27, plain("Install Minecraft?", NamedTextColor.DARK_BLUE))
        val header = ItemStack(Material.GRASS_BLOCK)
        val headerMeta = header.itemMeta
        headerMeta.displayName(plain("Server Environment", NamedTextColor.GOLD))
        header.itemMeta = headerMeta
        inv.setItem(4, header)

        val yes = getCustomHead("tick")
        val yesMeta = yes.itemMeta
        yesMeta.displayName(plain("Yes, Install Paper", NamedTextColor.GREEN))
        yesMeta.persistentDataContainer.set(NamespacedKey(plugin, "choice"), PersistentDataType.STRING, "yes")
        yes.itemMeta = yesMeta
        inv.setItem(11, yes)

        val no = getCustomHead("cross")
        val noMeta = no.itemMeta
        noMeta.displayName(plain("No, Just Ubuntu", NamedTextColor.RED))
        noMeta.persistentDataContainer.set(NamespacedKey(plugin, "choice"), PersistentDataType.STRING, "no")
        no.itemMeta = noMeta
        inv.setItem(15, no)
        
        val back = ItemStack(Material.ARROW)
        val backMeta = back.itemMeta
        backMeta.displayName(plain("Back", NamedTextColor.RED))
        backMeta.persistentDataContainer.set(NamespacedKey(plugin, "action"), PersistentDataType.STRING, "back")
        back.itemMeta = backMeta
        inv.setItem(18, back)
        
        player.openInventory(inv)
    }

    fun openConfirmationGUI(player: Player) {
        val selection = playerSelections[player] ?: return
        val inv = Bukkit.createInventory(null, 27, plain("Confirm Deployment", NamedTextColor.DARK_BLUE))
        
        val info = ItemStack(Material.PAPER)
        val infoMeta = info.itemMeta
        infoMeta.displayName(plain("Configuration Summary", NamedTextColor.GOLD))
        infoMeta.lore(listOf(
            plain("Location: ${selection.location}", NamedTextColor.GRAY),
            plain("Server Type: ${selection.serverType}", NamedTextColor.GRAY),
            plain("Monthly Price: ${selection.price}", NamedTextColor.GREEN),
            plain("Minecraft Installed: ${selection.installMinecraft}", NamedTextColor.GRAY)
        ))
        info.itemMeta = infoMeta
        inv.setItem(13, info)

        val confirm = getCustomHead("tick")
        val confirmMeta = confirm.itemMeta
        confirmMeta.displayName(plain("Confirm & Get Block", NamedTextColor.GREEN))
        confirmMeta.persistentDataContainer.set(NamespacedKey(plugin, "choice"), PersistentDataType.STRING, "confirm")
        confirm.itemMeta = confirmMeta
        inv.setItem(15, confirm)

        val back = ItemStack(Material.ARROW)
        val backMeta = back.itemMeta
        backMeta.displayName(plain("Back", NamedTextColor.RED))
        backMeta.persistentDataContainer.set(NamespacedKey(plugin, "action"), PersistentDataType.STRING, "back")
        back.itemMeta = backMeta
        inv.setItem(18, back)

        player.openInventory(inv)
    }

    fun finalize(player: Player) {
        val selection = playerSelections[player] ?: return
        val item = getCustomHead("server")
        val meta = item.itemMeta
        meta.displayName(plain("Deploy HCloud Server", NamedTextColor.AQUA))
        meta.lore(listOf(
            plain("Type: ${selection.serverType}", NamedTextColor.GRAY),
            plain("Location: ${selection.location}", NamedTextColor.GRAY),
            plain("Price: ${selection.price ?: "N/A"}", NamedTextColor.GREEN),
            plain("Install Minecraft: ${selection.installMinecraft}", NamedTextColor.GRAY),
            plain("Place to initiate deployment!", NamedTextColor.YELLOW)
        ))
        
        val pdc = meta.persistentDataContainer
        pdc.set(NamespacedKey(plugin, "server_type"), PersistentDataType.STRING, selection.serverType ?: "")
        pdc.set(NamespacedKey(plugin, "location"), PersistentDataType.STRING, selection.location ?: "")
        pdc.set(NamespacedKey(plugin, "install_mc"), PersistentDataType.BOOLEAN, selection.installMinecraft)
        pdc.set(NamespacedKey(plugin, "price"), PersistentDataType.STRING, selection.price ?: "")
        pdc.set(NamespacedKey(plugin, "cores"), PersistentDataType.INTEGER, selection.cores)
        pdc.set(NamespacedKey(plugin, "memory"), PersistentDataType.FLOAT, selection.memory)
        
        item.itemMeta = meta
        player.inventory.addItem(item)
        player.sendMessage(plain("Server deployment block received!", NamedTextColor.GREEN))
        playerSelections.remove(player)
    }

    fun getSelection(player: Player) = playerSelections[player]

    fun openDeleteConfirmationGUI(player: Player, serverId: Long, blockLocation: org.bukkit.Location) {
        pendingDeletions[player] = PendingDeletion(serverId, blockLocation)
        val inv = Bukkit.createInventory(null, 27, plain("Delete Server?", NamedTextColor.DARK_RED))
        
        val info = ItemStack(Material.PAPER)
        val infoMeta = info.itemMeta
        infoMeta.displayName(plain("Server Info", NamedTextColor.GOLD))
        infoMeta.lore(listOf(
            plain("ID: $serverId", NamedTextColor.GRAY),
            plain("Location: ${blockLocation.blockX}, ${blockLocation.blockY}, ${blockLocation.blockZ}", NamedTextColor.GRAY)
        ))
        info.itemMeta = infoMeta
        inv.setItem(13, info)

        val yes = getCustomHead("tick")
        val yesMeta = yes.itemMeta
        yesMeta.displayName(plain("Yes, Delete Server", NamedTextColor.RED))
        yesMeta.persistentDataContainer.set(NamespacedKey(plugin, "choice"), PersistentDataType.STRING, "delete_confirm")
        yes.itemMeta = yesMeta
        inv.setItem(11, yes)

        val no = getCustomHead("cross")
        val noMeta = no.itemMeta
        noMeta.displayName(plain("No, Keep it", NamedTextColor.GREEN))
        noMeta.persistentDataContainer.set(NamespacedKey(plugin, "choice"), PersistentDataType.STRING, "delete_cancel")
        no.itemMeta = noMeta
        inv.setItem(15, no)
        
        player.openInventory(inv)
    }

    fun getDeleteSelection(player: Player) = pendingDeletions[player]
    fun clearDeleteSelection(player: Player) { pendingDeletions.remove(player) }
}
