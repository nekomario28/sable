package dev.ryanhcode.sable.sublevel.storage.serialization;

import dev.ryanhcode.sable.platform.SablePlotPlatform;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * Repairs the per-chunk metadata layout emitted by {@link ServerLevelPlot#save()} before the plot
 * snapshot leaves Sable.
 *
 * <p>The current plot serializer writes `isLightOn` and platform metadata using the plot root tag,
 * while the loader reads those values from each chunk tag. Re-emitting metadata here makes the
 * `SubLevelSerializer.toData()` path symmetric without changing live plot/chunk behavior.</p>
 */
@ApiStatus.Internal
public final class SubLevelSerializationChunkMetadata {
    private SubLevelSerializationChunkMetadata() {
    }

    /**
     * Ensures every loaded chunk's metadata is written into the exact chunk tag consumed by load.
     *
     * @return the same mutable plot tag after normalization
     */
    public static CompoundTag normalize(final ServerLevelPlot plot, final CompoundTag plotTag) {
        Objects.requireNonNull(plot, "plot");
        Objects.requireNonNull(plotTag, "plotTag");

        final CompoundTag chunks = requireChunks(plotTag);
        final ServerLevel level = plot.getSubLevel().getLevel();

        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = Objects.requireNonNull(holder.getChunk(), "serialized plot chunk");
            final ChunkPos local = plot.toLocal(holder.getPos());
            final CompoundTag chunkTag = writeLightState(chunks, local, chunk.isLightCorrect());

            SablePlotPlatform.INSTANCE.writeLightData(chunkTag, level.registryAccess(), chunk);
            SablePlotPlatform.INSTANCE.writeChunkAttachments(chunkTag, level.registryAccess(), chunk);
        }

        removeLegacyRootLightState(plotTag);
        return plotTag;
    }

    static CompoundTag requireChunks(final CompoundTag plotTag) {
        if (!plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Serialized plot is missing chunk data");
        }
        return plotTag.getCompound("chunks");
    }

    static CompoundTag requireChunkTag(final CompoundTag chunks, final ChunkPos local) {
        final String key = String.valueOf(ChunkPos.asLong(local.x, local.z));
        if (!chunks.contains(key, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Serialized plot is missing chunk metadata target " + key);
        }
        return chunks.getCompound(key);
    }

    static CompoundTag writeLightState(
            final CompoundTag chunks,
            final ChunkPos local,
            final boolean isLightOn
    ) {
        final CompoundTag chunkTag = requireChunkTag(chunks, local);
        chunkTag.putBoolean("isLightOn", isLightOn);
        return chunkTag;
    }

    static void removeLegacyRootLightState(final CompoundTag plotTag) {
        plotTag.remove("isLightOn");
    }
}
