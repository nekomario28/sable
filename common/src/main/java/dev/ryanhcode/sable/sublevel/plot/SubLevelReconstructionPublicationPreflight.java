package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only publication preflight for target plot chunks.
 *
 * <p>An empty SubLevel slot is insufficient evidence that its chunk coordinates are safe to reuse.
 * A failed or still-unloading previous load can leave ChunkMap state at the exact target positions.
 * Reconstruction therefore refuses to overwrite any target position that is updating, visible, or
 * explicitly present in ChunkMap's keyed pending-drop set.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionPublicationPreflight {
    private SubLevelReconstructionPublicationPreflight() {
    }

    public enum Failure {
        NOT_SERVER_THREAD,
        CONTAINER_UNAVAILABLE,
        MISSING_CHUNK_PAYLOAD,
        INVALID_CHUNK_KEY,
        TARGET_CHUNK_UPDATING,
        TARGET_CHUNK_VISIBLE,
        TARGET_CHUNK_PENDING_UNLOAD
    }

    public record Result(Set<Failure> failures, Set<Long> blockedChunkKeys) {
        public Result {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(blockedChunkKeys, "blockedChunkKeys");
            final EnumSet<Failure> failureCopy = EnumSet.noneOf(Failure.class);
            failureCopy.addAll(failures);
            failures = Collections.unmodifiableSet(failureCopy);
            blockedChunkKeys = Set.copyOf(blockedChunkKeys);

            if (failures.isEmpty() && !blockedChunkKeys.isEmpty()) {
                throw new IllegalArgumentException("Accepted publication preflight cannot contain blocked chunks");
            }
            final boolean hasChunkCollisionFailure = failures.stream().anyMatch(failure ->
                    failure == Failure.TARGET_CHUNK_UPDATING ||
                            failure == Failure.TARGET_CHUNK_VISIBLE ||
                            failure == Failure.TARGET_CHUNK_PENDING_UNLOAD
            );
            if (hasChunkCollisionFailure && blockedChunkKeys.isEmpty()) {
                throw new IllegalArgumentException("Chunk publication failure requires blocked chunk evidence");
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty();
        }
    }

    /**
     * Checks current ChunkMap publication state without mutating it.
     */
    public static Result validate(final ServerLevel level, final SubLevelReconstructionPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        if (!level.getServer().isSameThread()) {
            return rejected(Failure.NOT_SERVER_THREAD);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return rejected(Failure.CONTAINER_UNAVAILABLE);
        }

        final CompoundTag plotTag = plan.fullTag().getCompound("plot");
        if (!plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            return rejected(Failure.MISSING_CHUNK_PAYLOAD);
        }

        final ChunkMap chunkMap = level.getChunkSource().chunkMap;
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        final HashSet<Long> blocked = new HashSet<>();
        final CompoundTag chunks = plotTag.getCompound("chunks");
        final SubLevelReconstructionPreflight.TargetSlot target = plan.targetSlot();
        final int originX = container.getOrigin().x;
        final int originZ = container.getOrigin().y;
        final int logPlotSize = container.getLogPlotSize();

        for (final String serializedKey : chunks.getAllKeys()) {
            final long localKey;
            try {
                localKey = Long.parseLong(serializedKey);
            } catch (final NumberFormatException invalidKey) {
                failures.add(Failure.INVALID_CHUNK_KEY);
                continue;
            }

            final long globalKey = targetGlobalChunkKey(
                    target.plotX(),
                    target.plotZ(),
                    originX,
                    originZ,
                    logPlotSize,
                    localKey
            );
            boolean blockedHere = false;
            if (chunkMap.updatingChunkMap.containsKey(globalKey)) {
                failures.add(Failure.TARGET_CHUNK_UPDATING);
                blockedHere = true;
            }
            if (chunkMap.visibleChunkMap.containsKey(globalKey)) {
                failures.add(Failure.TARGET_CHUNK_VISIBLE);
                blockedHere = true;
            }
            if (chunkMap.toDrop.contains(globalKey)) {
                failures.add(Failure.TARGET_CHUNK_PENDING_UNLOAD);
                blockedHere = true;
            }
            if (blockedHere) {
                blocked.add(globalKey);
            }
        }

        return new Result(failures, blocked);
    }

    /** Package-private pure seam for coordinate mapping tests. */
    static long targetGlobalChunkKey(
            final int targetPlotX,
            final int targetPlotZ,
            final int originX,
            final int originZ,
            final int logPlotSize,
            final long localChunkKey
    ) {
        if (logPlotSize < 0 || logPlotSize > 30) {
            throw new IllegalArgumentException("Invalid log plot size: " + logPlotSize);
        }
        final int localX = ChunkPos.getX(localChunkKey);
        final int localZ = ChunkPos.getZ(localChunkKey);
        final int globalPlotX = Math.addExact(targetPlotX, originX);
        final int globalPlotZ = Math.addExact(targetPlotZ, originZ);
        final int baseChunkX = Math.multiplyExact(globalPlotX, 1 << logPlotSize);
        final int baseChunkZ = Math.multiplyExact(globalPlotZ, 1 << logPlotSize);
        return ChunkPos.asLong(
                Math.addExact(baseChunkX, localX),
                Math.addExact(baseChunkZ, localZ)
        );
    }

    private static Result rejected(final Failure failure) {
        return new Result(EnumSet.of(failure), Set.of());
    }
}
