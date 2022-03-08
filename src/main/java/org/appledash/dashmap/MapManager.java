package org.appledash.dashmap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MaterialColor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Handles maintaining a color map for chunks on the map, as well as building a NativeImage texture containing the map data.
 */
public class MapManager {
    public static final int RADIUS = 3; /* Radius of chunks around the player's chunk that the map will be updated for */
    public static final int CHUNK_SIZE = 16; /* Number of blocks in a chunk. */

    private final NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, ((RADIUS * 2) + 1) * CHUNK_SIZE, ((RADIUS * 2) + 1) * CHUNK_SIZE, false);
    private final Map<ChunkPos, int[][]> colorMap = new HashMap<>(); /* map of chunk positions to array of topY map colors for that chunk */
    private final Set<ChunkAccess> dirtyChunks = new CopyOnWriteArraySet<>(); /* chunks that we need to update the map texture for - collection is concurrent because we remove items from it when unloading chunks, which happens on another thread. */
    private final DynamicTexture texture = new DynamicTexture(this.nativeImage);

    private ResourceLocation textureLocation;

    private boolean textureChanged;
    private ChunkPos upperLeftPosition; /* ChunkPos that represents the chunk at the upper-left of the map */

    public void registerTexture(TextureManager textureManager) {
        this.textureLocation = textureManager.register("dashmap/minimap", this.texture);
    }

    /**
     * Rebuild the color and image data for all chunks that have been marked dirty.
     */
    public void rebuildChunks() {
        if (!this.dirtyChunks.isEmpty()) {
            this.dirtyChunks.forEach(this::rebuildChunk);
            this.dirtyChunks.clear();
            this.textureChanged = true;
        }
    }

    /**
     * Mark a chunk as needing to have its color and image data rebuilt.
     *
     * @param chunk Chunk that needs rebuilding.
     */
    public void markChunkDirty(ChunkAccess chunk) {
        this.dirtyChunks.add(chunk);
    }

    /**
     * Clear all map data - used when logging out of a server to clear the map.
     */
    public void clearMap() {
        this.colorMap.clear();
        this.dirtyChunks.clear();
    }

    /**
     * Remove data for a given chunk when we don't care about it anymore, such as when the chunk is unloaded.
     */
    public void removeChunk(ChunkAccess chunk) {
        this.dirtyChunks.remove(chunk);
        this.colorMap.remove(chunk.getPos());
    }

    /**
     * Upload the map texture image to the GPU, if it has been changed since the last upload.
     */
    public void uploadTexture() {
        if (this.textureChanged) {
            this.texture.upload();
            this.textureChanged = false;
        }
    }

    public NativeImage getImage() {
        return this.nativeImage;
    }

    public ResourceLocation getTextureLocation() {
        return this.textureLocation;
    }

    public ChunkPos getUpperLeftPosition() {
        return this.upperLeftPosition;
    }

    public void setCenterPosition(ChunkPos centerPosition) {
        /* We actually care about the upper-left position, but it's easier to set the center position. We want this value
         * a lot, so we cache it in a field.
         */
        this.upperLeftPosition = new ChunkPos(centerPosition.x - RADIUS, centerPosition.z - RADIUS);
    }

    private void rebuildChunk(ChunkAccess chunk) {
        final ChunkPos chunkPos = chunk.getPos();
        final int maxDistance = (RADIUS * 2) + 1;
        final int xDistance = chunkPos.x - this.upperLeftPosition.x;
        final int zDistance = chunkPos.z - this.upperLeftPosition.z;

        /* do not rebuild chunks outside our map - these can get in here if the upperLeftPosition changes
         * just before we rebuild chunks.
         */
        if (xDistance < 0 || xDistance > maxDistance ||
            zDistance < 0 || zDistance > maxDistance) {
            return;
        }

        this.rebuildChunkColorMap(chunk);
        this.rebuildChunkTexture(chunk.getPos());
    }

    /**
     * Rebuild the section of the map texture corresponding to the given ChunkPos.
     *
     * @param chunkPos ChunkPos to rebuild.
     */
    private void rebuildChunkTexture(ChunkPos chunkPos) {
        /* Hmm, that's a bit weird - RANGE too big or view distance way too small? */
        if (!this.colorMap.containsKey(chunkPos)) {
            return;
        }

        final int[][] colorData = this.colorMap.get(chunkPos);

        /* The world coordinates represented by the upper-left corner of the image */
        final int imageStartXWorld = this.upperLeftPosition.getMinBlockX(); // (this.centerPosition.x - RADIUS) * CHUNK_SIZE;
        final int imageStartYWorld = this.upperLeftPosition.getMinBlockZ(); // (this.centerPosition.z - RADIUS) * CHUNK_SIZE;

        for (int offsetX = 0; offsetX < CHUNK_SIZE; offsetX++) {
            for (int offsetZ = 0; offsetZ < CHUNK_SIZE; offsetZ++) {
                /* World X and Z that correspond to this block */
                final int absoluteX = chunkPos.getBlockX(offsetX);
                final int absoluteZ = chunkPos.getBlockZ(offsetZ);

                /* Image X and Y that correspond to this block */
                final int imageX = absoluteX - imageStartXWorld;
                final int imageY = absoluteZ - imageStartYWorld;

                this.nativeImage.setPixelRGBA(imageX, imageY, colorData[offsetX][offsetZ]);
            }
        }
    }

    /**
     * Rebuild the top-Y color map for a given Chunk.
     *
     * @param chunk Chunk to rebuild color map for.
     */
    private void rebuildChunkColorMap(ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();

        LevelAccessor level = chunk.getWorldForge();
        int[][] colorData;

        if (this.colorMap.containsKey(chunkPos)) {
            colorData = this.colorMap.get(chunkPos);
        } else {
            colorData = new int[CHUNK_SIZE][CHUNK_SIZE];
            this.colorMap.put(chunkPos, colorData);
        }

        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(0, 0, 0); /* Reuse this to avoid constructing a lot of new objects */

        assert level != null;

        for (int offsetX = 0; offsetX < CHUNK_SIZE; offsetX++) {
            for (int offsetZ = 0; offsetZ < CHUNK_SIZE; offsetZ++) {
                int absoluteX = chunkPos.getBlockX(offsetX);
                int absoluteZ = chunkPos.getBlockZ(offsetZ);

                /* Use the height of this block and the adjacent block to calculate a brightness for a shadow effect. */
                blockPos.set(absoluteX, 0, absoluteZ - 1);
                int adjacentTopY = this.getRealTopY(level, blockPos);

                blockPos.set(absoluteX, 0, absoluteZ);
                int thisTopY = this.getRealTopY(level, blockPos);

                MaterialColor.Brightness brightness;

                if (thisTopY == adjacentTopY) {
                    brightness = MaterialColor.Brightness.NORMAL;
                } else if (thisTopY > adjacentTopY) {
                    brightness = MaterialColor.Brightness.HIGH;
                } else {
                    brightness = MaterialColor.Brightness.LOW;
                }

                colorData[offsetX][offsetZ] = level.getBlockState(blockPos).getMapColor(level, blockPos).calculateRGBColor(brightness);
            }
        }
    }

    /**
     * Get top Y at the given position in the world, excluding blocks that lack a map color.
     * In addition, if we are dealing with a pool of water, this will return the Y of the lowest water source in the pool.
     *
     * @param level World we are working in.
     * @param pos BlockPos to get the top Y at.
     * @return Top Y.
     */
    private int getRealTopY(LevelAccessor level, BlockPos.MutableBlockPos pos) {
        int color;
        int topY = this.getInitialTopY(level, pos);
        int fluidBlockCount = 0;
        boolean keepLooking;

        /* If we encountered fluid, we want to return the height of the lowest fluid. This is used so the shadow effect takes into account
         * the depth of the fluid.
         */
        do {
            pos.setY(topY);

            BlockState blockState = level.getBlockState(pos);
            color = blockState.getMapColor(level, pos).col;

            if (color == 0) { /* Sometimes the official top Y doesn't have a map color (eg: double tall grass), so just keep going down until we find one. */
                keepLooking = true;
            } else if (blockState.getFluidState().isSource()) {
                keepLooking = true;
                fluidBlockCount++;
            } else { /* These blocks look bad on the map, and this could be replaced with a better, more dynamic calculation. */
                keepLooking = blockState.getBlock() == Blocks.GRASS || blockState.getBlock() == Blocks.TALL_GRASS;
            }

            topY--;
        } while (keepLooking && topY >= level.getMinBuildHeight());

        if (fluidBlockCount > 0) {
            pos.setY(pos.getY() + 1);
        }

        return pos.getY();
    }

    /**
     * Get the top Y-level to begin searching for a valid "surface" block at.
     * In the overworld, this is just the surface according to the LevelAccessor, but in the nether we have to do a bit of manual work.
     *
     * @param levelAccessor LevelAccessor for the world.
     * @param blockPos Position to find the top Y-level at.
     * @return Top y-level, possibly guessed based on heuristics.
     */
    private int getInitialTopY(LevelAccessor levelAccessor, BlockPos.MutableBlockPos blockPos) {
        if (levelAccessor.dimensionType().hasCeiling()) {
            /* I do not like this static call reaching into the Minecraft instance */
            final int originY = Math.round((float) Minecraft.getInstance().player.getEyeY()) + 3;

            /* do a manual search for the first solid block around where the player is standing.
             * this is absolutely not very performant, but unfortunately as far as I know it is the best we can do.
             * It could be improved by caching the height map and updating it as necessary.
             */
            for (int y = originY; y >= levelAccessor.getMinBuildHeight(); y--) {
                blockPos.setY(y);

                if (!levelAccessor.getBlockState(blockPos).isAir()) {
                    return y;
                }
            }

            return levelAccessor.getMaxBuildHeight();
        }

        /* Not the nether, just use the height map we already have. */
        return levelAccessor.getHeight(Heightmap.Types.WORLD_SURFACE, blockPos.getX(), blockPos.getZ());
    }
}
