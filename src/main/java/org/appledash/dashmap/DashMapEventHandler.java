package org.appledash.dashmap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.BlockEvent;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import static org.appledash.dashmap.MapManager.RADIUS;

public final class DashMapEventHandler {
    private final DashMap dashMap;
    private ChunkPos lastChunkPos;

    private DashMapEventHandler(DashMap dashMap) {
        this.dashMap = dashMap;
    }

    /**
     * Called when the player updates, and handles checking if the player has moved since the last update,
     * and rebuilding the map around them if so.
     */
    @SubscribeEvent
    public void onPlayerUpdate(TickEvent.PlayerTickEvent evt) {
        if (evt.phase != TickEvent.Phase.END ||
            !evt.player.level.isClientSide()) { /* In single player, we get this event once for the server tick and once for the client tick. */
            return;
        }

        final Player player = evt.player;
        final ChunkPos chunkPos = player.chunkPosition();
        final MapManager mapManager = this.dashMap.getMapManager();

        /* Player moved chunks, so rebuild the map. */
        if (!chunkPos.equals(this.lastChunkPos)) {
            this.lastChunkPos = chunkPos;
            mapManager.setCenterPosition(chunkPos);

            for (int chunkX = chunkPos.x - RADIUS; chunkX <= chunkPos.x + RADIUS; chunkX++) {
                for (int chunkZ = chunkPos.z - RADIUS; chunkZ <= chunkPos.z + RADIUS; chunkZ++) {
                    mapManager.markChunkDirty(player.level.getChunk(chunkX, chunkZ));
                }
            }
        }

        mapManager.rebuildChunks();
    }

    /**
     * Main rendering event handler, actually gets the map on the screen.
     */
    @SubscribeEvent
    public void onIngameRender(RenderGameOverlayEvent.Post evt) {
        final Minecraft mc = Minecraft.getInstance();
        final MapManager mapManager = this.dashMap.getMapManager();

        /* Don't show the minimap in F3 */
        if (mc.options.renderDebug) {
            return;
        }

        /* Haven't built the map yet (ie: getting frames before player ticks) */
        if (mapManager.getUpperLeftPosition() == null) {
            return;
        }

        this.dashMap.getMapRenderer().renderMap(mc, mapManager, evt.getMatrixStack());
    }

    /**
     * Clean up data we have tracked for a chunk when it is unloaded.
     */
    @SubscribeEvent
    public void onChunkUnload(ChunkEvent.Unload evt) {
        if (evt.getWorld().isClientSide()) {
            this.dashMap.getMapManager().removeChunk(evt.getChunk());
        }
    }

    /**
     * Clear all map data for the server when we disconnect.
     */
    @SubscribeEvent
    public void onPlayerLogOut(ClientPlayerNetworkEvent.LoggedOutEvent evt) {
        this.dashMap.getMapManager().clearMap();
    }

    /**
     * Update a chunk whenever a block is changed in it. BlockEvent is a common superclass for all events pertaining to block modification.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockChange(BlockEvent evt) {
        final ChunkPos mapStart = this.dashMap.getMapManager().getUpperLeftPosition();

        /* Don't care yet if the map hasn't been built. */
        if (!evt.getWorld().isClientSide() || evt.isCanceled() || mapStart == null) {
            return;
        }

        final BlockPos pos = evt.getPos();
        final int maxDistance = ((RADIUS * 2) + 1) * MapManager.CHUNK_SIZE;
        final int offsetX = pos.getX() - mapStart.getMinBlockX();
        final int offsetZ = pos.getZ() - mapStart.getMinBlockZ();

        /* We can get block modification events outside the map area, and we don't care about those. */
        if (offsetX >= 0 && offsetZ >= 0 && offsetX < maxDistance && offsetZ < maxDistance) {
            this.dashMap.getMapManager().markChunkDirty(evt.getWorld().getChunk(evt.getPos()));
        }
    }

    /**
     * Register an instance of this event handler on Forge's event bus.
     *
     * An explanation for this pattern, and the warning suppression, can be found
     * <a href="https://mcforge.readthedocs.io/en/latest/concepts/sides/#distexecutor">in the Forge docs</a>.
     */
    @SuppressWarnings("StaticMethodOnlyUsedInOneClass")
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new DashMapEventHandler(DashMap.instance));
    }
}
