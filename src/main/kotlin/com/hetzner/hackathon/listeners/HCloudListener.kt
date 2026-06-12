package com.hetzner.hackathon.listeners

import com.hetzner.hackathon.HCloudPlugin
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Villager
import org.bukkit.entity.Display
import org.bukkit.entity.Mob
import org.bukkit.entity.Husk
import org.bukkit.entity.LivingEntity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.Color
import org.bukkit.Bukkit
import com.destroystokyo.paper.profile.ProfileProperty
import java.net.InetAddress
import java.net.Socket
import java.util.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.bukkit.block.TileState

class HCloudListener(private val plugin: HCloudPlugin) : Listener {

    private val cloudInitUrl = "https://gist.githubusercontent.com/unickorn/808fcb9df43108fe7aa6b996e94c7a73/raw"
    private var cachedCloudInit: String? = null
    private val technicianNames = listOf("Markus", "Sarah", "Martin", "Jonas", "Oliver", "Aaron", "Philipp", "Dominik")

    private fun plain(text: String, color: NamedTextColor, bold: Boolean = false): Component {
        var comp = Component.text(text, color).decoration(TextDecoration.ITALIC, false)
        if (bold) comp = comp.decoration(TextDecoration.BOLD, true)
        return comp
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return

        val item = event.currentItem ?: return
        val meta = item.itemMeta ?: return
        val pdc = meta.persistentDataContainer
        val choice = pdc.get(NamespacedKey(plugin, "choice"), PersistentDataType.STRING)

        if (choice == "delete_confirm") {
            event.isCancelled = true
            val deletion = plugin.guiManager.getDeleteSelection(player) ?: return
            player.sendMessage(plain("Deleting server ${deletion.serverId}...", NamedTextColor.YELLOW))
            plugin.client?.deleteServer(deletion.serverId)?.thenAccept {
                plugin.server.scheduler.runTask(plugin, Runnable {
                    deletion.blockLocation.block.type = Material.AIR

                    // Remove floating text, grass block and technicians
                    deletion.blockLocation.world.getNearbyEntities(
                        deletion.blockLocation.clone().add(0.5, 0.0, 0.5),
                        10.0,
                        5.0,
                        10.0
                    )
                        .forEach { entity ->
                            val epdc = entity.persistentDataContainer
                            val matches = epdc.get(NamespacedKey(plugin, "owner_x"), PersistentDataType.INTEGER) == deletion.blockLocation.blockX &&
                                    epdc.get(NamespacedKey(plugin, "owner_y"), PersistentDataType.INTEGER) == deletion.blockLocation.blockY &&
                                    epdc.get(NamespacedKey(plugin, "owner_z"), PersistentDataType.INTEGER) == deletion.blockLocation.blockZ

                            if (matches) {
                                if (entity is Mob) {
                                    removeTechnician(entity)
                                } else {
                                    entity.remove()
                                }
                            }
                        }

                    player.sendMessage(plain("Server deleted successfully!", NamedTextColor.GREEN))
                    player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
                    plugin.guiManager.clearDeleteSelection(player)
                    player.closeInventory()
                })
            }?.exceptionally { ex ->
                player.sendMessage(plain("Failed to delete server: ${ex.message}", NamedTextColor.RED))
                null
            }
            return
        } else if (choice == "delete_cancel") {
            event.isCancelled = true
            plugin.guiManager.clearDeleteSelection(player)
            player.sendMessage(plain("Deletion cancelled.", NamedTextColor.GRAY))
            player.closeInventory()
            return
        }

        val selection = plugin.guiManager.getSelection(player) ?: return

        event.isCancelled = true

        if (pdc.get(NamespacedKey(plugin, "action"), PersistentDataType.STRING) == "back") {
            handleBack(player, event.view.title())
            return
        }

        if (item.type.name.endsWith("_WOOL")) {
            val type = pdc.get(NamespacedKey(plugin, "server_type"), PersistentDataType.STRING) ?: return
            selection.serverType = type
            selection.price = pdc.get(NamespacedKey(plugin, "price"), PersistentDataType.STRING)
            selection.cores = pdc.get(NamespacedKey(plugin, "cores"), PersistentDataType.INTEGER) ?: 0
            selection.memory = pdc.get(NamespacedKey(plugin, "memory"), PersistentDataType.FLOAT) ?: 0f
            plugin.guiManager.openInstallMinecraftGUI(player)
        } else if (item.type == Material.PLAYER_HEAD) {
            if (choice == "yes") {
                selection.installMinecraft = true
                plugin.guiManager.openConfirmationGUI(player)
            } else if (choice == "no") {
                selection.installMinecraft = false
                plugin.guiManager.openConfirmationGUI(player)
            } else if (choice == "confirm") {
                plugin.guiManager.finalize(player)
                player.closeInventory()
            } else {
                val loc = pdc.get(NamespacedKey(plugin, "location"), PersistentDataType.STRING) ?: return
                selection.location = loc
                player.sendMessage(plain("Fetching server types for $loc...", NamedTextColor.YELLOW))
                plugin.client?.getServerTypes(loc)?.thenAccept { types ->
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.guiManager.openServerTypeGUI(player, types)
                    })
                }
            }
        }
    }

    private fun handleBack(player: Player, title: Component) {
        // Adventure components are tricky to compare directly by text, using selection state
        val selection = plugin.guiManager.getSelection(player) ?: return

        // Simple logic based on current state
        if (selection.serverType == null) {
            // In Server Type GUI or something went wrong
            player.sendMessage(plain("Fetching locations...", NamedTextColor.YELLOW))
            plugin.client?.getLocations()?.thenAccept { locs ->
                plugin.server.scheduler.runTask(plugin, Runnable {
                    plugin.guiManager.openLocationGUI(player, locs)
                })
            }
        } else if (selection.price != null && selection.location != null && selection.serverType != null) {
            // We are likely in MC Install or Confirmation
            // If we have price and serverType, we might be in MC Install
            // This is a bit heuristic, but works for the current flow
            val titleStr = (title as? net.kyori.adventure.text.TextComponent)?.content() ?: ""
            if (titleStr.contains("Confirm", ignoreCase = true)) {
                plugin.guiManager.openInstallMinecraftGUI(player)
            } else if (titleStr.contains("Minecraft", ignoreCase = true)) {
                player.sendMessage(plain("Fetching server types...", NamedTextColor.YELLOW))
                plugin.client?.getServerTypes(selection.location)?.thenAccept { types ->
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.guiManager.openServerTypeGUI(player, types)
                    })
                }
            } else {
                // Back from Server Type to Location
                player.sendMessage(plain("Fetching locations...", NamedTextColor.YELLOW))
                plugin.client?.getLocations()?.thenAccept { locs ->
                    plugin.server.scheduler.runTask(plugin, Runnable {
                        plugin.guiManager.openLocationGUI(player, locs)
                    })
                }
            }
        }
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val state = block.state
        if (state is TileState) {
            val serverId =
                state.persistentDataContainer.get(NamespacedKey(plugin, "server_id"), PersistentDataType.LONG)
            if (serverId != null) {
                event.isCancelled = true
                plugin.guiManager.openDeleteConfirmationGUI(event.player, serverId, block.location)
            }
        }
    }

    @EventHandler
    fun onEntityDamageByEntity(event: org.bukkit.event.entity.EntityDamageByEntityEvent) {
        val damager = event.damager
        if (damager.persistentDataContainer.has(NamespacedKey(plugin, "technician"), PersistentDataType.BOOLEAN)) {
            event.isCancelled = true
        }
    }

    private fun getTechnicianTeam(): org.bukkit.scoreboard.Team {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        var team = board.getTeam("Technicians")
        if (team == null) {
            team = board.registerNewTeam("Technicians")
            team.setOption(
                org.bukkit.scoreboard.Team.Option.COLLISION_RULE,
                org.bukkit.scoreboard.Team.OptionStatus.NEVER
            )
            team.setOption(
                org.bukkit.scoreboard.Team.Option.DEATH_MESSAGE_VISIBILITY,
                org.bukkit.scoreboard.Team.OptionStatus.NEVER
            )
        }
        return team
    }

    @EventHandler
    fun onEntityTarget(event: EntityTargetEvent) {
        val entity = event.entity
        if (entity.persistentDataContainer.has(NamespacedKey(plugin, "technician"), PersistentDataType.BOOLEAN)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        val pdc = item.itemMeta.persistentDataContainer
        val type = pdc.get(NamespacedKey(plugin, "server_type"), PersistentDataType.STRING) ?: return
        val loc = pdc.get(NamespacedKey(plugin, "location"), PersistentDataType.STRING) ?: return
        val installMc = pdc.get(NamespacedKey(plugin, "install_mc"), PersistentDataType.BOOLEAN) ?: false
        val price = pdc.get(NamespacedKey(plugin, "price"), PersistentDataType.STRING) ?: "N/A"
        val cores = pdc.get(NamespacedKey(plugin, "cores"), PersistentDataType.INTEGER) ?: 0
        val memory = pdc.get(NamespacedKey(plugin, "memory"), PersistentDataType.FLOAT) ?: 0f

        val block = event.blockPlaced
        val player = event.player

        // Tag block immediately
        val state = block.state
        if (state is TileState) {
            state.persistentDataContainer.set(NamespacedKey(plugin, "server_type"), PersistentDataType.STRING, type)
            state.update()
        }

        player.sendMessage(plain("Deployment initiated! Technicians are arriving...", NamedTextColor.YELLOW))

        // Spawn Status Text
        val statusDisplay = block.location.clone().add(0.5, 1.0, 0.5).world.spawn(
            block.location.clone().add(0.5, 1.0, 0.5),
            TextDisplay::class.java
        )
        statusDisplay.text(plain("Initializing...", NamedTextColor.GRAY))
        statusDisplay.setBillboard(Display.Billboard.CENTER)
        statusDisplay.persistentDataContainer.set(NamespacedKey(plugin, "status_display"), PersistentDataType.BOOLEAN, true)
        tagEntity(statusDisplay, block.location)

        // Spawn Technicians
        val serverTech = spawnTechnician(block.location.clone().add(2.0, 0.0, 2.0), block.location, technicianNames.random(), false)
        val mcTech = if (installMc) spawnTechnician(
            block.location.clone().add(-2.0, 0.0, -2.0),
            block.location,
            technicianNames.random(),
            true
        ) else null

        // Move to block and start working
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            val serverOffset = block.location.clone().add(0.8, 0.0, 0.8)
            serverTech.getPathfinder().moveTo(serverOffset)
            startWorkAnimation(serverTech, block.location, true)
            mcTech?.let {
                val mcOffset = block.location.clone().add(-0.8, 0.0, -0.8)
                it.getPathfinder().moveTo(mcOffset)
                startWorkAnimation(it, block.location, false)
            }
        }, 10L)

        // Start server creation
        fetchCloudInit().thenCompose { userData ->
            val finalUserData = if (installMc) userData else null

            // Try to find a pre-baked image first
            plugin.client?.getImages("minecraft-ready=true")?.thenCompose { images ->
                val imageToUse = images.firstOrNull()?.id?.toString() ?: "ubuntu-26.04"
                // Don't send user data if we are using a pre-baked image that already has everything
                // Or maybe we still want it for final tweaks? Let's keep it for now but maybe minimize it.
                val finalUserDataForImage = if (imageToUse == "ubuntu-26.04") finalUserData else null

                plugin.client?.getSshKeys()?.thenCompose { keys ->
                    val keyNames = keys.map { it.name }
                    plugin.client?.createServer(
                        "hcloud-${UUID.randomUUID().toString().take(8)}", 
                        type, 
                        loc, 
                        finalUserDataForImage,
                        keyNames,
                        imageToUse
                    )
                }
            }
        }?.thenAccept { createdServer ->

            val ip = createdServer.ip
            val id = createdServer.id

            // Save ID to block
            plugin.server.scheduler.runTask(plugin, Runnable {
                val state = block.state
                if (state is TileState) {
                    state.persistentDataContainer.set(NamespacedKey(plugin, "server_id"), PersistentDataType.LONG, id)
                    state.update()
                    // Re-check redstone now that we have an ID
                    plugin.networkManager.reconcile(block.location)
                }
            })

            statusDisplay.text(plain("Server Provisioning...", NamedTextColor.YELLOW))
            monitorDeployment(
                createdServer,
                player,
                serverTech,
                mcTech,
                statusDisplay,
                block.location,
                installMc,
                type,
                loc,
                price,
                cores,
                memory
            )
        }?.exceptionally { ex ->
            player.sendMessage(plain("Failed to create server: ${ex.message}", NamedTextColor.RED))
            plugin.server.scheduler.runTask(plugin, Runnable {
                removeTechnician(serverTech)
                mcTech?.let { removeTechnician(it) }
                statusDisplay.remove()
            })
            null
        }
    }

    private fun tagEntity(entity: org.bukkit.entity.Entity, blockLoc: org.bukkit.Location) {
        val pdc = entity.persistentDataContainer
        pdc.set(NamespacedKey(plugin, "owner_x"), PersistentDataType.INTEGER, blockLoc.blockX)
        pdc.set(NamespacedKey(plugin, "owner_y"), PersistentDataType.INTEGER, blockLoc.blockY)
        pdc.set(NamespacedKey(plugin, "owner_z"), PersistentDataType.INTEGER, blockLoc.blockZ)
    }

    private fun removeTechnician(mob: Mob) {
        mob.persistentDataContainer.remove(NamespacedKey(plugin, "technician"))
        mob.passengers.forEach { it.remove() }
        mob.remove()
    }

    private fun spawnTechnician(loc: org.bukkit.Location, ownerLoc: org.bukkit.Location, name: String, isMc: Boolean): Mob {
        val spawnLoc = loc.clone()
        spawnLoc.y = spawnLoc.world.getHighestBlockYAt(spawnLoc).toDouble() + 1
        val villager = spawnLoc.world.spawnEntity(spawnLoc, EntityType.VILLAGER) as Villager
        villager.setAI(true)
        villager.isInvulnerable = true
        villager.isSilent = true
        villager.profession = if (isMc) Villager.Profession.FARMER else Villager.Profession.TOOLSMITH
        villager.villagerType = Villager.Type.PLAINS
        villager.setAdult()
        
        villager.persistentDataContainer.set(NamespacedKey(plugin, "technician"), PersistentDataType.BOOLEAN, true)
        tagEntity(villager, ownerLoc)

        // Remove ALL AI goals to ensure total control over behavior
        try {
            val goals = Bukkit.getMobGoals()
            goals.removeAllGoals(villager)
        } catch (e: Exception) {
            // ignore
        }

        // Make passive and ignore players
        getTechnicianTeam().addEntry(villager.uniqueId.toString())

        // Visible Name Tag using TextDisplay
        val nameTag = villager.world.spawn(villager.location.clone().add(0.0, 2.0, 0.0), TextDisplay::class.java) {
            it.text(plain(name, NamedTextColor.GOLD))
            it.setBillboard(Display.Billboard.CENTER)
            it.isPersistent = false
            tagEntity(it, ownerLoc)
        }
        villager.addPassenger(nameTag)

        return villager
    }

    private fun getTechnicianHead(isMc: Boolean): ItemStack {
        val texture = if (isMc) {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNTI2NTViYTA1ZTk4Njc5OTVkYjg3YjNlOTkyYjI4MGNlYTk2NmZkYmUwOTI1OTNhNDhhYjljYzdmMWI4YmNhOSJ9fX0="
        } else {
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvODdiN2Y0MGRlZTM4YmU3OGE5M2U1Y2I4NWU4YTBhY2I3Y2EwYmU0ZWE3NTA3N2FjMDhjNWRkYmQ1Y2RkYzk0MCJ9fX0="
        }
        val item = ItemStack(Material.PLAYER_HEAD)
        val meta = item.itemMeta as SkullMeta
        val uuid = UUID.nameUUIDFromBytes(texture.toByteArray())
        val profile = Bukkit.createProfile(uuid, "Technician")
        profile.setProperty(ProfileProperty("textures", texture))
        meta.playerProfile = profile
        item.itemMeta = meta
        return item
    }

    private fun getColoredLeather(material: Material, color: Color): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta as LeatherArmorMeta
        meta.setColor(color)
        item.itemMeta = meta
        return item
    }

    private fun startWorkAnimation(mob: Mob, blockLoc: org.bukkit.Location, isServer: Boolean) {
        val center = blockLoc.clone().add(0.5, 0.0, 0.5)
        // Tighter offset for closer walk
        val offset = if (isServer) 0.6 else -0.6
        val points = listOf(
            center.clone().add(offset, 0.0, offset),
            center.clone().add(offset, 0.0, -offset),
            center.clone().add(-offset, 0.0, -offset),
            center.clone().add(-offset, 0.0, offset)
        )

        var currentPoint = 0

        // Task to strictly look at the block (1 tick for absolute fixation)
        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (!mob.isValid || mob.persistentDataContainer.has(
                    NamespacedKey(plugin, "exiting"),
                    PersistentDataType.BOOLEAN
                )
            ) {
                task.cancel()
                return@runTaskTimer
            }
            mob.lookAt(center)
        }, 1L, 1L)

        plugin.server.scheduler.runTaskTimer(plugin, { task ->
            if (!mob.isValid || mob.persistentDataContainer.has(
                    NamespacedKey(plugin, "exiting"),
                    PersistentDataType.BOOLEAN
                )
            ) {
                task.cancel()
                return@runTaskTimer
            }

            // Only move if we are close to our current target or taking too long
            if (mob.location.distanceSquared(points[currentPoint % points.size]) < 0.4) {
                currentPoint++
                mob.getPathfinder().moveTo(points[currentPoint % points.size], 0.4) // Slower walk
            } else {
                // Re-send move command occasionally just in case
                mob.getPathfinder().moveTo(points[currentPoint % points.size], 0.4)
            }
        }, 40L, 40L)
    }

    private fun getDeploymentComponent(
        type: String,
        ip: String,
        cores: Int,
        memory: Float,
        loc: String,
        price: String,
        blockLoc: org.bukkit.Location,
        mcStatus: Component? = null
    ): Component {
        val builder = Component.text()
            .append(plain("Server $type", NamedTextColor.GREEN, bold = true))
            .append(Component.newline())
            .append(plain("$ip", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(plain("$cores", color = NamedTextColor.RED))
            .append(plain(" vCPU", color = NamedTextColor.WHITE))
            .append(plain(", ", color = NamedTextColor.GRAY))
            .append(plain("$memory", color = NamedTextColor.AQUA))
            .append(plain(" GB", color = NamedTextColor.WHITE))
            .append(Component.newline())
            .append(plain("$loc", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(plain("$price", NamedTextColor.YELLOW))

        if (mcStatus != null) {
            builder.append(mcStatus)
        }

        // Add network status from block if it exists
        val state = blockLoc.block.state
        if (state is TileState) {
            val netStatus = state.persistentDataContainer.get(NamespacedKey(plugin, "net_status"), PersistentDataType.STRING)
            if (netStatus != null) {
                val colorStr = state.persistentDataContainer.get(NamespacedKey(plugin, "net_color"), PersistentDataType.STRING)
                val color = if (colorStr != null) NamedTextColor.NAMES.value(colorStr.lowercase()) ?: NamedTextColor.GREEN else NamedTextColor.GREEN
                builder.append(Component.text("\n[$netStatus]", color))
            }
        }

        return builder.build()
    }

    private fun monitorDeployment(
        createdServer: com.hetzner.hackathon.api.CreatedServer,
        player: Player,
        serverTech: Mob,
        mcTech: Mob?,
        statusDisplay: TextDisplay,
        blockLoc: org.bukkit.Location,
        installMc: Boolean,
        type: String,
        loc: String,
        price: String,
        cores: Int,
        memory: Float
    ) {
        val id = createdServer.id
        val ip = createdServer.ip
        var stage = 0 // 0: Waiting for OS, 1: Waiting 30s, 2: Waiting for MC, 3: Done
        var attempts = 0
        var runningSince = 0L
        var isApiChecking = false
        var floatingGrass: org.bukkit.entity.Entity? = null

        val monitorTask = object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                if (!player.isOnline) {
                    cleanup()
                    this.cancel()
                    return
                }

                attempts++
                if (attempts > 120) { // 10 minutes timeout
                    player.sendMessage(plain("Deployment timed out.", NamedTextColor.RED))
                    cleanup()
                    this.cancel()
                    return
                }

                when (stage) {
                    0 -> {
                        if (!isApiChecking) {
                            isApiChecking = true
                            plugin.client?.getServerStatus(id)?.thenAccept { status ->
                                plugin.server.scheduler.runTask(plugin, Runnable {
                                    isApiChecking = false
                                    if (status == "running" && stage == 0) {
                                        stage = 1
                                        runningSince = System.currentTimeMillis()
                                        
                                        // Server Tech exit
                                        triggerExit(serverTech, true)

                                        if (installMc) {
                                            statusDisplay.text(
                                                getDeploymentComponent(
                                                    type, ip, cores, memory, loc, price, blockLoc,
                                                    plain("\n[Minecraft Installing...]", NamedTextColor.RED)
                                                )
                                            )
                                            floatingGrass =
                                                blockLoc.world.spawn(blockLoc.clone().add(0.3, 0.5, 0.3), BlockDisplay::class.java) {
                                                    it.block = Material.GRASS_BLOCK.createBlockData()
                                                    val trans = it.transformation
                                                    trans.scale.set(0.4f, 0.4f, 0.4f)
                                                    it.transformation = trans
                                                    tagEntity(it, blockLoc)
                                                }
                                        } else {
                                            finish()
                                            this.cancel()
                                        }
                                    }
                                })
                            }?.exceptionally { 
                                isApiChecking = false
                                null 
                            }
                        }
                    }
                    1 -> {
                        if (System.currentTimeMillis() - runningSince > 30_000) {
                            stage = if (installMc) 2 else 3
                            if (stage == 3) {
                                finish()
                                this.cancel()
                            }
                        }
                    }
                    2 -> {
                        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
                            if (isPortOpen(ip, 25565, 1000)) {
                                plugin.server.scheduler.runTask(plugin, Runnable {
                                    if (stage == 2) {
                                        stage = 3
                                        mcTech?.let { triggerExit(it, false) }
                                        finish()
                                        this.cancel()
                                    }
                                })
                            }
                        })
                    }
                }
            }

            private fun triggerExit(mob: Mob, isServer: Boolean) {
                mob.world.playSound(mob.location, Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.0f)
                // This tag immediately kills the work tasks (lookAt and Patrol)
                mob.persistentDataContainer.set(NamespacedKey(plugin, "exiting"), PersistentDataType.BOOLEAN, true)
                
                // Clear any current pathfinding
                mob.getPathfinder().stopPathfinding()
                
                val exitLoc = mob.location.clone().add(if (isServer) 30.0 else -30.0, 0.0, if (isServer) 30.0 else -30.0)
                exitLoc.y = exitLoc.world.getHighestBlockYAt(exitLoc).toDouble() + 1
                
                // Final walk away
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (mob.isValid) {
                        mob.getPathfinder().moveTo(exitLoc, 1.2) // Brisk pace
                    }
                }, 2L)
                
                // Force removal after 10 seconds (200 ticks)
                plugin.server.scheduler.runTaskLater(plugin, Runnable { 
                    if (mob.isValid) removeTechnician(mob) 
                }, 200L)
            }

            private fun finish() {
                completeDeployment(player, ip, statusDisplay, type, loc, price, cores, memory, installMc, blockLoc)
                floatingGrass?.remove()
            }

            private fun cleanup() {
                removeTechnician(serverTech)
                mcTech?.let { removeTechnician(it) }
                statusDisplay.remove()
                floatingGrass?.remove()
            }
        }
        monitorTask.runTaskTimer(plugin, 100L, 100L)
    }

    private fun completeDeployment(
        player: Player,
        ip: String,
        statusDisplay: TextDisplay,
        type: String,
        loc: String,
        price: String,
        cores: Int,
        memory: Float,
        installMc: Boolean,
        blockLoc: org.bukkit.Location
    ) {
        val mcText = if (installMc) "\n[Minecraft Installed]" else ""
        val detailText = getDeploymentComponent(type, ip, cores, memory, loc, price, blockLoc, plain(mcText, NamedTextColor.GREEN))

        statusDisplay.text(detailText)
        player.sendMessage(plain("Server is ready! IP: $ip", NamedTextColor.GREEN, bold = true))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
    }

    private fun isPortOpen(ip: String, port: Int, timeout: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(ip, port), timeout)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun fetchCloudInit(): java.util.concurrent.CompletableFuture<String> {
        if (cachedCloudInit != null) return java.util.concurrent.CompletableFuture.completedFuture(cachedCloudInit)
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder().uri(URI.create(cloudInitUrl)).GET().build()
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply {
            cachedCloudInit = it.body()
            it.body()
        }
    }
}
