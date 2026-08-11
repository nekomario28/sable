package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, canonical reconstruction payload captured after mutation-free validation succeeds.
 *
 * <p>The staged payload separates plot metadata from chunk payloads and resolves each serialized
 * local chunk key to its exact target global chunk key once. Future materialization code must
 * consume this object instead of re-reading or re-mapping the original NBT.</p>
 *
 * <p>Capturing this object does not allocate a SubLevel, consume a runtime ID, create chunks,
 * invoke platform callbacks, publish entities/players, or touch physics.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionStagedPayload {
    private final CompoundTag plotMetadata;
    private final List<ChunkSnapshot> chunks;

    private SubLevelReconstructionStagedPayload(
            final CompoundTag plotMetadata,
            final List<ChunkSnapshot> chunks
    ) {
        this.plotMetadata = Objects.requireNonNull(plotMetadata, "plotMetadata").copy();
        this.chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }

    public enum Failure {
        NOT_SERVER_THREAD,
        CONTAINER_UNAVAILABLE,
        MISSING_PLOT_PAYLOAD,
        MISSING_CHUNK_PAYLOAD,
        INVALID_CHUNK_KEY,
        INVALID_CHUNK_PAYLOAD,
        TARGET_CHUNK_MAPPING_FAILED
    }

    public record Capture(Set<Failure> failures, Optional<SubLevelReconstructionStagedPayload> payload) {
        public Capture {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(payload, "payload");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
            if (failures.isEmpty() != payload.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted staged payload capture requires payload and rejected capture requires failures"
                );
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.payload.isPresent();
        }
    }

    public record ChunkSnapshot(
            long localChunkKey,
            long targetGlobalChunkKey,
            CompoundTag chunkTag
    ) {
        public ChunkSnapshot {
            Objects.requireNonNull(chunkTag, "chunkTag");
            chunkTag = chunkTag.copy();
        }

        /** Returns a defensive deep copy. */
        @Override
        public CompoundTag chunkTag() {
            return this.chunkTag.copy();
        }
    }

    /**
     * Captures the validated payload against current target plot-grid geometry without mutation.
     */
    public static Capture capture(final ServerLevel level, final SubLevelReconstructionPlan plan) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(plan, "plan");
        if (!level.getServer().isSameThread()) {
            return rejected(Failure.NOT_SERVER_THREAD);
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return rejected(Failure.CONTAINER_UNAVAILABLE);
        }
        return captureFrom(
                plan,
                container.getOrigin().x,
                container.getOrigin().y,
                container.getLogPlotSize()
        );
    }

    /** Package-private pure seam for canonicalization and ownership tests. */
    static Capture captureFrom(
            final SubLevelReconstructionPlan plan,
            final int originX,
            final int originZ,
            final int logPlotSize
    ) {
        Objects.requireNonNull(plan, "plan");
        final CompoundTag fullTag = plan.fullTag();
        if (!fullTag.contains("plot", Tag.TAG_COMPOUND)) {
            return rejected(Failure.MISSING_PLOT_PAYLOAD);
        }

        final CompoundTag plotTag = fullTag.getCompound("plot");
        if (!plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            return rejected(Failure.MISSING_CHUNK_PAYLOAD);
        }

        final CompoundTag chunkTags = plotTag.getCompound("chunks");
        final List<ChunkSnapshot> stagedChunks = new ArrayList<>(chunkTags.getAllKeys().size());
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        final SubLevelReconstructionPreflight.TargetSlot target = plan.targetSlot();

        for (final String serializedKey : chunkTags.getAllKeys()) {
            final long localKey;
            try {
                localKey = Long.parseLong(serializedKey);
            } catch (final NumberFormatException invalidKey) {
                failures.add(Failure.INVALID_CHUNK_KEY);
                continue;
            }
            if (!chunkTags.contains(serializedKey, Tag.TAG_COMPOUND)) {
                failures.add(Failure.INVALID_CHUNK_PAYLOAD);
                continue;
            }

            final long targetGlobalKey;
            try {
                targetGlobalKey = SubLevelReconstructionPublicationPreflight.targetGlobalChunkKey(
                        target.plotX(),
                        target.plotZ(),
                        originX,
                        originZ,
                        logPlotSize,
                        localKey
                );
            } catch (final IllegalArgumentException mappingFailure) {
                failures.add(Failure.TARGET_CHUNK_MAPPING_FAILED);
                continue;
            }

            stagedChunks.add(new ChunkSnapshot(
                    localKey,
                    targetGlobalKey,
                    chunkTags.getCompound(serializedKey)
            ));
        }

        if (!failures.isEmpty()) {
            return new Capture(failures, Optional.empty());
        }

        stagedChunks.sort(Comparator
                .comparingLong(ChunkSnapshot::targetGlobalChunkKey)
                .thenComparingLong(ChunkSnapshot::localChunkKey));

        final CompoundTag plotMetadata = plotTag.copy();
        plotMetadata.remove("chunks");
        return new Capture(
                Set.of(),
                Optional.of(new SubLevelReconstructionStagedPayload(plotMetadata, stagedChunks))
        );
    }

    /** Returns a defensive deep copy of plot-level metadata excluding `chunks`. */
    public CompoundTag plotMetadata() {
        return this.plotMetadata.copy();
    }

    /**
     * Returns immutable snapshot descriptors. Each descriptor returns its chunk NBT defensively.
     */
    public List<ChunkSnapshot> chunks() {
        return this.chunks;
    }

    private static Capture rejected(final Failure failure) {
        return new Capture(EnumSet.of(failure), Optional.empty());
    }
}
