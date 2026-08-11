package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * Read-only entity-residue preflight for target plot chunks.
 *
 * <p>A target ChunkMap coordinate can be clear while the persistent entity section manager still
 * contains entities for that chunk. Reconstruction refuses to reuse such coordinates so commit
 * cannot merge a new target plot with stale entity lifecycle state.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionEntityPreflight {
    private SubLevelReconstructionEntityPreflight() {
    }

    public enum Failure {
        NOT_SERVER_THREAD,
        CONTAINER_UNAVAILABLE,
        MISSING_CHUNK_PAYLOAD,
        INVALID_CHUNK_KEY,
        TARGET_ENTITY_RESIDUE
    }

    public record Result(Set<Failure> failures, Set<Long> blockedChunkKeys) {
        public Result {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(blockedChunkKeys, "blockedChunkKeys");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
            blockedChunkKeys = Set.copyOf(blockedChunkKeys);

            if (failures.isEmpty() && !blockedChunkKeys.isEmpty()) {
                throw new IllegalArgumentException("Accepted entity preflight cannot contain blocked chunks");
            }
            if (failures.contains(Failure.TARGET_ENTITY_RESIDUE) && blockedChunkKeys.isEmpty()) {
                throw new IllegalArgumentException("Entity residue failure requires blocked chunk evidence");
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty();
        }
    }

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

        return validateTargets(
                targetChunkKeys(container, plan),
                chunkKey -> level.entityManager.sectionStorage
                        .getExistingSectionsInChunk(chunkKey)
                        .anyMatch(section -> section.getEntities().findAny().isPresent())
        );
    }

    static Result validateTargets(final TargetChunks targetChunks, final LongPredicate hasEntityResidue) {
        Objects.requireNonNull(targetChunks, "targetChunks");
        Objects.requireNonNull(hasEntityResidue, "hasEntityResidue");
        if (!targetChunks.failures().isEmpty()) {
            return new Result(targetChunks.failures(), Set.of());
        }

        final HashSet<Long> blocked = new HashSet<>();
        for (final long chunkKey : targetChunks.chunkKeys()) {
            if (hasEntityResidue.test(chunkKey)) {
                blocked.add(chunkKey);
            }
        }
        if (blocked.isEmpty()) {
            return new Result(Set.of(), Set.of());
        }
        return new Result(EnumSet.of(Failure.TARGET_ENTITY_RESIDUE), blocked);
    }

    static TargetChunks targetChunkKeys(
            final ServerSubLevelContainer container,
            final SubLevelReconstructionPlan plan
    ) {
        final CompoundTag plotTag = plan.fullTag().getCompound("plot");
        if (!plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            return new TargetChunks(Set.of(), EnumSet.of(Failure.MISSING_CHUNK_PAYLOAD));
        }

        final CompoundTag chunks = plotTag.getCompound("chunks");
        final SubLevelReconstructionPreflight.TargetSlot target = plan.targetSlot();
        final HashSet<Long> keys = new HashSet<>();
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        for (final String serializedKey : chunks.getAllKeys()) {
            final long localKey;
            try {
                localKey = Long.parseLong(serializedKey);
            } catch (final NumberFormatException invalidKey) {
                failures.add(Failure.INVALID_CHUNK_KEY);
                continue;
            }
            keys.add(SubLevelReconstructionPublicationPreflight.targetGlobalChunkKey(
                    target.plotX(),
                    target.plotZ(),
                    container.getOrigin().x,
                    container.getOrigin().y,
                    container.getLogPlotSize(),
                    localKey
            ));
        }
        if (!failures.isEmpty()) {
            return new TargetChunks(Set.of(), failures);
        }
        return new TargetChunks(keys, Set.of());
    }

    record TargetChunks(Set<Long> chunkKeys, Set<Failure> failures) {
        TargetChunks {
            chunkKeys = Set.copyOf(Objects.requireNonNull(chunkKeys, "chunkKeys"));
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(Objects.requireNonNull(failures, "failures"));
            failures = Collections.unmodifiableSet(copy);
            if (!failures.isEmpty() && !chunkKeys.isEmpty()) {
                throw new IllegalArgumentException("Invalid target chunk evidence must not be partially usable");
            }
        }
    }

    private static Result rejected(final Failure failure) {
        return new Result(EnumSet.of(failure), Set.of());
    }
}
