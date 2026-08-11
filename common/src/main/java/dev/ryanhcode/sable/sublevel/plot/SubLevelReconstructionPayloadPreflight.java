package dev.ryanhcode.sable.sublevel.plot;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Deep, mutation-free validation for deterministic failures inside a serialized reconstruction
 * payload.
 *
 * <p>This deliberately stops short of claiming arbitrary platform attachment callbacks, block
 * entity lifecycle callbacks, chunk activation, or physics publication are safe. It only moves
 * deterministic NBT/codec failures ahead of target allocation.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionPayloadPreflight {
    private static final int LIGHT_ARRAY_BYTES = 2048;
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState()
    );

    private SubLevelReconstructionPayloadPreflight() {
    }

    public enum Failure {
        MISSING_PLOT_PAYLOAD,
        INVALID_BIOME_ID,
        AMBIGUOUS_ROOT_LIGHT_STATE,
        INVALID_CHUNK_PAYLOAD,
        MISSING_CHUNK_LIGHT_STATE,
        INVALID_SECTION_PAYLOAD,
        INVALID_BLOCK_STATES,
        INVALID_LIGHT_DATA,
        INVALID_TICK_DATA,
        INVALID_HEIGHTMAP_DATA,
        INVALID_BLOCK_ENTITY_DATA
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
     * Validates the frozen reconstruction payload without touching target-world state.
     */
    public static Result validate(final SubLevelReconstructionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        final CompoundTag fullTag = plan.fullTag();
        if (!fullTag.contains("plot", Tag.TAG_COMPOUND)) {
            return new Result(EnumSet.of(Failure.MISSING_PLOT_PAYLOAD));
        }
        return validatePlotTag(fullTag.getCompound("plot"));
    }

    /** Package-private pure seam for executable payload tests. */
    static Result validatePlotTag(final CompoundTag plotTag) {
        Objects.requireNonNull(plotTag, "plotTag");
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);

        if (plotTag.contains("biome")) {
            if (!plotTag.contains("biome", Tag.TAG_STRING) ||
                    ResourceLocation.tryParse(plotTag.getString("biome")) == null) {
                failures.add(Failure.INVALID_BIOME_ID);
            }
        }

        if (plotTag.contains("isLightOn")) {
            failures.add(Failure.AMBIGUOUS_ROOT_LIGHT_STATE);
        }

        if (!plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            failures.add(Failure.MISSING_PLOT_PAYLOAD);
            return new Result(failures);
        }

        final CompoundTag chunks = plotTag.getCompound("chunks");
        for (final String chunkKey : chunks.getAllKeys()) {
            if (!chunks.contains(chunkKey, Tag.TAG_COMPOUND)) {
                failures.add(Failure.INVALID_CHUNK_PAYLOAD);
                continue;
            }
            validateChunk(chunks.getCompound(chunkKey), failures);
        }

        return new Result(failures);
    }

    private static void validateChunk(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        if (!chunkTag.contains("isLightOn", Tag.TAG_BYTE)) {
            failures.add(Failure.MISSING_CHUNK_LIGHT_STATE);
        }

        if (!chunkTag.contains("sections", Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_CHUNK_PAYLOAD);
        } else {
            final CompoundTag sections = chunkTag.getCompound("sections");
            for (final String sectionKey : sections.getAllKeys()) {
                if (!sections.contains(sectionKey, Tag.TAG_COMPOUND)) {
                    failures.add(Failure.INVALID_SECTION_PAYLOAD);
                    continue;
                }
                validateSection(sections.getCompound(sectionKey), failures);
            }
        }

        validateTickList(chunkTag, "block_ticks", true, failures);
        validateTickList(chunkTag, "fluid_ticks", false, failures);
        validateHeightmaps(chunkTag, failures);
        validateBlockEntities(chunkTag, failures);
    }

    private static void validateSection(
            final CompoundTag sectionTag,
            final EnumSet<Failure> failures
    ) {
        if (!sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_BLOCK_STATES);
        } else {
            try {
                BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states"))
                        .getOrThrow(IllegalArgumentException::new);
            } catch (final RuntimeException invalidBlockStates) {
                failures.add(Failure.INVALID_BLOCK_STATES);
            }
        }

        validateLightArray(sectionTag, "BlockLight", failures);
        validateLightArray(sectionTag, "SkyLight", failures);
    }

    private static void validateLightArray(
            final CompoundTag sectionTag,
            final String key,
            final EnumSet<Failure> failures
    ) {
        if (!sectionTag.contains(key)) {
            return;
        }
        if (!sectionTag.contains(key, Tag.TAG_BYTE_ARRAY) ||
                sectionTag.getByteArray(key).length != LIGHT_ARRAY_BYTES) {
            failures.add(Failure.INVALID_LIGHT_DATA);
        }
    }

    private static void validateTickList(
            final CompoundTag chunkTag,
            final String key,
            final boolean blockTicks,
            final EnumSet<Failure> failures
    ) {
        final Tag raw = chunkTag.get(key);
        if (!(raw instanceof final ListTag ticks) ||
                (!ticks.isEmpty() && ticks.getElementType() != Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_TICK_DATA);
            return;
        }

        for (int index = 0; index < ticks.size(); index++) {
            final CompoundTag tick = ticks.getCompound(index);
            if (!tick.contains("i", Tag.TAG_STRING) ||
                    !tick.contains("x", Tag.TAG_INT) ||
                    !tick.contains("y", Tag.TAG_INT) ||
                    !tick.contains("z", Tag.TAG_INT) ||
                    !tick.contains("t", Tag.TAG_INT) ||
                    !tick.contains("p", Tag.TAG_INT)) {
                failures.add(Failure.INVALID_TICK_DATA);
                continue;
            }

            final ResourceLocation id = ResourceLocation.tryParse(tick.getString("i"));
            if (id == null || (blockTicks
                    ? BuiltInRegistries.BLOCK.getOptional(id).isEmpty()
                    : BuiltInRegistries.FLUID.getOptional(id).isEmpty())) {
                failures.add(Failure.INVALID_TICK_DATA);
            }
        }
    }

    private static void validateHeightmaps(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        if (!chunkTag.contains("heightmaps", Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_HEIGHTMAP_DATA);
            return;
        }

        final CompoundTag heightmaps = chunkTag.getCompound("heightmaps");
        for (final String key : heightmaps.getAllKeys()) {
            if (!heightmaps.contains(key, Tag.TAG_LONG_ARRAY)) {
                failures.add(Failure.INVALID_HEIGHTMAP_DATA);
            }
        }
    }

    private static void validateBlockEntities(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        final Tag raw = chunkTag.get("block_entities");
        if (!(raw instanceof final ListTag blockEntities) ||
                (!blockEntities.isEmpty() && blockEntities.getElementType() != Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_BLOCK_ENTITY_DATA);
            return;
        }

        for (int index = 0; index < blockEntities.size(); index++) {
            final CompoundTag blockEntity = blockEntities.getCompound(index);
            if (!blockEntity.contains("id", Tag.TAG_STRING) ||
                    ResourceLocation.tryParse(blockEntity.getString("id")) == null ||
                    !blockEntity.contains("x", Tag.TAG_INT) ||
                    !blockEntity.contains("y", Tag.TAG_INT) ||
                    !blockEntity.contains("z", Tag.TAG_INT)) {
                failures.add(Failure.INVALID_BLOCK_ENTITY_DATA);
            }
        }
    }
}
