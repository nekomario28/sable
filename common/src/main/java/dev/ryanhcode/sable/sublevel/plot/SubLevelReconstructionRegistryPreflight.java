package dev.ryanhcode.sable.sublevel.plot;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only target-registry validation for data that can be structurally valid but impossible to
 * reconstruct in the current server runtime.
 *
 * <p>This gate checks registry ownership only. It does not instantiate block entities, invoke mod
 * attachment callbacks, or activate chunks.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionRegistryPreflight {
    private SubLevelReconstructionRegistryPreflight() {
    }

    public enum Failure {
        NOT_SERVER_THREAD,
        MISSING_PLOT_PAYLOAD,
        UNKNOWN_TARGET_BIOME,
        UNKNOWN_BLOCK_ENTITY_TYPE
    }

    public record Result(Set<Failure> failures) {
        public Result {
            Objects.requireNonNull(failures, "failures");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
        }

        public boolean accepted() {
            return this.failures.isEmpty();
        }
    }

    /**
     * Validates registry references against the exact target level/runtime without mutation.
     */
    public static Result validate(final ServerLevel targetLevel, final SubLevelReconstructionPlan plan) {
        Objects.requireNonNull(targetLevel, "targetLevel");
        Objects.requireNonNull(plan, "plan");
        if (!targetLevel.getServer().isSameThread()) {
            return new Result(EnumSet.of(Failure.NOT_SERVER_THREAD));
        }

        final CompoundTag fullTag = plan.fullTag();
        if (!fullTag.contains("plot", Tag.TAG_COMPOUND)) {
            return new Result(EnumSet.of(Failure.MISSING_PLOT_PAYLOAD));
        }

        final Registry<Biome> biomeRegistry =
                targetLevel.registryAccess().registryOrThrow(Registries.BIOME);
        return validatePlotTag(fullTag.getCompound("plot"), biomeRegistry);
    }

    /** Package-private seam for registry-aware executable tests. */
    static Result validatePlotTag(final CompoundTag plotTag, final Registry<Biome> biomeRegistry) {
        Objects.requireNonNull(plotTag, "plotTag");
        Objects.requireNonNull(biomeRegistry, "biomeRegistry");
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);

        if (plotTag.contains("biome", Tag.TAG_STRING)) {
            final ResourceLocation biomeId = ResourceLocation.tryParse(plotTag.getString("biome"));
            if (biomeId == null || biomeRegistry.getHolder(
                    ResourceKey.create(Registries.BIOME, biomeId)
            ).isEmpty()) {
                failures.add(Failure.UNKNOWN_TARGET_BIOME);
            }
        }

        if (!plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            failures.add(Failure.MISSING_PLOT_PAYLOAD);
            return new Result(failures);
        }

        final CompoundTag chunks = plotTag.getCompound("chunks");
        for (final String chunkKey : chunks.getAllKeys()) {
            if (!chunks.contains(chunkKey, Tag.TAG_COMPOUND)) {
                continue;
            }
            final CompoundTag chunkTag = chunks.getCompound(chunkKey);
            if (!chunkTag.contains("block_entities", Tag.TAG_LIST)) {
                continue;
            }
            final ListTag blockEntities = chunkTag.getList("block_entities", Tag.TAG_COMPOUND);
            for (int index = 0; index < blockEntities.size(); index++) {
                final CompoundTag blockEntity = blockEntities.getCompound(index);
                if (!blockEntity.contains("id", Tag.TAG_STRING)) {
                    continue;
                }
                final ResourceLocation blockEntityId = ResourceLocation.tryParse(blockEntity.getString("id"));
                if (blockEntityId == null ||
                        BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(blockEntityId).isEmpty()) {
                    failures.add(Failure.UNKNOWN_BLOCK_ENTITY_TYPE);
                }
            }
        }

        return new Result(failures);
    }
}
