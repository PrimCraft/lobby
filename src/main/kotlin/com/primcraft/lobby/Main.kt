package com.primcraft.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent
import net.minestom.server.event.player.PlayerMoveEvent
import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.block.Block
import net.minestom.server.network.NetworkBuffer
import net.minestom.server.timer.TaskSchedule
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

// Server configuration
data class ServerConfig(
    val name: String,
    val displayName: String,
    val address: String,
    val port: Int,
    val portalPos: Pos
)

data class ServerStatus(
    val online: Boolean,
    val playerCount: Int = 0
)

// Portal state
data class Portal(
    val config: ServerConfig,
    var status: ServerStatus = ServerStatus(false),
    var hologram: Entity? = null
)

val servers = listOf(
    ServerConfig(
        name = "survival",
        displayName = "Survival",
        address = "survival.primcraft.svc.cluster.local",
        port = 25565,
        portalPos = Pos(5.0, 1.0, 0.0)
    ),
    ServerConfig(
        name = "creative",
        displayName = "Creative",
        address = "creative.primcraft.svc.cluster.local",
        port = 25565,
        portalPos = Pos(-5.0, 1.0, 0.0)
    )
)

val portals = ConcurrentHashMap<String, Portal>()

fun main() {
    val server = MinecraftServer.init()
    val instanceManager = MinecraftServer.getInstanceManager()
    val instance = instanceManager.createInstanceContainer()

    // Initialize portals
    servers.forEach { config ->
        portals[config.name] = Portal(config)
    }

    // Generate flat world with border
    generateWorld(instance)

    // Place portal blocks
    placePortals(instance)

    // Spawn holograms
    spawnHolograms(instance)

    // Start health checker
    startHealthChecker(instance)

    // Event handlers
    val events = MinecraftServer.getGlobalEventHandler()

    // Player join
    events.addListener(AsyncPlayerConfigurationEvent::class.java) { event ->
        event.spawningInstance = instance
        event.player.respawnPoint = Pos(0.5, 2.0, 0.5)
    }

    // Portal detection
    events.addListener(PlayerMoveEvent::class.java) { event ->
        val player = event.player
        val pos = event.newPosition

        for ((name, portal) in portals) {
            val portalPos = portal.config.portalPos
            // Check if player is on the portal block (within the 1x1 area)
            if (pos.x >= portalPos.x - 0.5 && pos.x <= portalPos.x + 0.5 &&
                pos.z >= portalPos.z - 0.5 && pos.z <= portalPos.z + 0.5 &&
                pos.y >= portalPos.y && pos.y <= portalPos.y + 2
            ) {
                if (portal.status.online) {
                    player.sendMessage(
                        Component.text("Connecting to ", NamedTextColor.GREEN)
                            .append(Component.text(portal.config.displayName, NamedTextColor.WHITE))
                            .append(Component.text("...", NamedTextColor.GREEN))
                    )
                    transferPlayer(player, name)
                } else {
                    player.sendMessage(
                        Component.text("${portal.config.displayName} is currently offline!", NamedTextColor.RED)
                    )
                    // Push player back slightly
                    player.teleport(Pos(0.5, 2.0, 0.5))
                }
                break
            }
        }
    }

    // Start server
    server.start("0.0.0.0", 25565)
    println("Lobby server started on port 25565")
}

fun generateWorld(instance: InstanceContainer) {
    instance.setGenerator { unit ->
        val start = unit.absoluteStart()
        val size = unit.size()

        for (x in 0 until size.x().toInt()) {
            for (z in 0 until size.z().toInt()) {
                val worldX = start.x().toInt() + x
                val worldZ = start.z().toInt() + z

                // Create a 21x21 platform (-10 to 10)
                if (worldX >= -10 && worldX <= 10 && worldZ >= -10 && worldZ <= 10) {
                    unit.modifier().setBlock(worldX, 0, worldZ, Block.STONE)

                    // Border
                    if (worldX == -10 || worldX == 10 || worldZ == -10 || worldZ == 10) {
                        unit.modifier().setBlock(worldX, 1, worldZ, Block.STONE_BRICKS)
                        unit.modifier().setBlock(worldX, 2, worldZ, Block.STONE_BRICKS)
                    } else {
                        unit.modifier().setBlock(worldX, 1, worldZ, Block.GRASS_BLOCK)
                    }
                }
            }
        }
    }
}

fun placePortals(instance: InstanceContainer) {
    for ((_, portal) in portals) {
        val pos = portal.config.portalPos
        // Initial state: red wool (offline)
        instance.setBlock(pos.blockX(), pos.blockY(), pos.blockZ(), Block.RED_WOOL)
    }
}

fun spawnHolograms(instance: InstanceContainer) {
    for ((_, portal) in portals) {
        val hologram = Entity(EntityType.TEXT_DISPLAY)

        hologram.editEntityMeta(TextDisplayMeta::class.java) { meta ->
            meta.text = Component.text("${portal.config.displayName}\n", NamedTextColor.WHITE)
                .append(Component.text("Checking...", NamedTextColor.GRAY))
            meta.isSeeThrough = true
            meta.backgroundColor = 0x40000000 // Semi-transparent black
        }

        val holoPos = portal.config.portalPos.add(0.5, 2.5, 0.5)
        hologram.setInstance(instance, holoPos)
        portal.hologram = hologram
    }
}

fun startHealthChecker(instance: InstanceContainer) {
    MinecraftServer.getSchedulerManager().buildTask {
        for ((name, portal) in portals) {
            val online = checkServer(portal.config.address, portal.config.port)
            val oldStatus = portal.status
            portal.status = ServerStatus(online)

            // Update block color if status changed
            if (oldStatus.online != online) {
                val block = if (online) Block.LIME_WOOL else Block.RED_WOOL
                val pos = portal.config.portalPos
                instance.setBlock(pos.blockX(), pos.blockY(), pos.blockZ(), block)
            }

            // Update hologram
            portal.hologram?.editEntityMeta(TextDisplayMeta::class.java) { meta ->
                val statusText = if (online) {
                    Component.text("Online", NamedTextColor.GREEN)
                } else {
                    Component.text("Offline", NamedTextColor.RED)
                }

                meta.text = Component.text("${portal.config.displayName}\n", NamedTextColor.WHITE)
                    .append(statusText)
            }
        }
    }.repeat(TaskSchedule.seconds(5)).schedule()
}

fun checkServer(host: String, port: Int): Boolean {
    return try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2000)
            true
        }
    } catch (e: Exception) {
        false
    }
}

fun transferPlayer(player: net.minestom.server.entity.Player, server: String) {
    player.sendPluginMessage("bungeecord:main", NetworkBuffer.makeArray { buffer ->
        buffer.write(NetworkBuffer.STRING_IO_UTF8, "Connect")
        buffer.write(NetworkBuffer.STRING_IO_UTF8, server)
    })
}
