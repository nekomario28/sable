package dev.ryanhcode.sable.sublevel.plot;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Detached, decoded reconstruction input that can be built before any target chunk exists.
 *
 * <p>This stage consumes the canonical NBT snapshots from {@link SubLevelReconstructionStagedPayload}
 * and removes another class of failure from future materialization: section-key parsing, block-state
 * codec decoding, tick-id lookup, heightmap copying, light payload copying, and block-entity identity
 * parsing all happen here while the target world is still untouched.</p>
 *
 * <p>Decoded block states are frozen as canonical 4096-entry lists rather than retaining mutable
 * {@link PalettedContainer} instances. Platform extension data remains as a defensive NBT copy with
 * the core Sable chunk fields removed, so a future platform commit hook does not need access to the
 * original serialized section/tick/heightmap/block-entity payload.</p>
 */
@ApiStatus.Internal
public final class SubLevelReconstructionDecodedPayload {
    private static final int SECTION_VOLUME = 16 * 16 * 16;
    private static final int LIGHT_ARRAY_BYTES = 2048;
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATE_CODEC = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY,
            BlockState.CODEC,
            PalettedContainer.Strategy.SECTION_STATES,
            Blocks.AIR.defaultBlockState()
    );

    public enum Failure {
        NOT_SERVER_THREAD,
        INVALID_BIOME_ID,
        INVALID_SECTION_INDEX,
        BLOCK_STATE_DECODE_FAILED,
        INVALID_LIGHT_DATA,
        INVALID_TICK_DATA,
        INVALID_HEIGHTMAP_DATA,
        INVALID_BLOCK_ENTITY_DATA
    }

    public record Capture(
            Set<Failure> failures,
            Set<Long> failedChunkKeys,
            Optional<SubLevelReconstructionDecodedPayload> payload
    ) {
        public Capture {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(failedChunkKeys, "failedChunkKeys");
            Objects.requireNonNull(payload, "payload");
            final EnumSet<Failure> failureCopy = EnumSet.noneOf(Failure.class);
            failureCopy.addAll(failures);
            failures = Collections.unmodifiableSet(failureCopy);
            failedChunkKeys = Set.copyOf(failedChunkKeys);
            if (failures.isEmpty() != payload.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted decoded capture requires payload and rejected capture requires failures"
                );
            }
            if (failures.isEmpty() && !failedChunkKeys.isEmpty()) {
                throw new IllegalArgumentException("Accepted decoded capture cannot contain failed chunk evidence");
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.payload.isPresent();
        }
    }

    public record DecodedBlockTick(Block block, int x, int y, int z, int delay, int priority) {
        public DecodedBlockTick {
            Objects.requireNonNull(block, "block");
        }
    }

    public record DecodedFluidTick(Fluid fluid, int x, int y, int z, int delay, int priority) {
        public DecodedFluidTick {
            Objects.requireNonNull(fluid, "fluid");
        }
    }

    public static final class DecodedBlockEntity {
        private final ResourceLocation typeId;
        private final BlockPos pos;
        private final boolean keepPacked;
        private final CompoundTag payload;

        private DecodedBlockEntity(
                final ResourceLocation typeId,
                final BlockPos pos,
                final boolean keepPacked,
                final CompoundTag payload
        ) {
            this.typeId = Objects.requireNonNull(typeId, "typeId");
            this.pos = Objects.requireNonNull(pos, "pos").immutable();
            this.keepPacked = keepPacked;
            this.payload = Objects.requireNonNull(payload, "payload").copy();
        }

        public ResourceLocation typeId() {
            return this.typeId;
        }

        public BlockPos pos() {
            return this.pos;
        }

        public boolean keepPacked() {
            return this.keepPacked;
        }

        public CompoundTag payload() {
            return this.payload.copy();
        }
    }

    public static final class DecodedSection {
        private final int sectionIndex;
        private final List<BlockState> blockStates;
        private final byte[] blockLight;
        private final byte[] skyLight;

        private DecodedSection(
                final int sectionIndex,
                final List<BlockState> blockStates,
                final byte[] blockLight,
                final byte[] skyLight
        ) {
            this.sectionIndex = sectionIndex;
            this.blockStates = List.copyOf(Objects.requireNonNull(blockStates, "blockStates"));
            if (this.blockStates.size() != SECTION_VOLUME) {
                throw new IllegalArgumentException("Decoded section must contain exactly 4096 block states");
            }
            this.blockLight = blockLight == null ? null : blockLight.clone();
            this.skyLight = skyLight == null ? null : skyLight.clone();
        }

        public int sectionIndex() {
            return this.sectionIndex;
        }

        /** Canonical index is {@code (y << 8) | (z << 4) | x}. */
        public BlockState stateAt(final int x, final int y, final int z) {
            if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) {
                throw new IndexOutOfBoundsException("Section coordinates must be in [0, 16)");
            }
            return this.blockStates.get((y << 8) | (z << 4) | x);
        }

        public byte[] blockLight() {
            return this.blockLight == null ? null : this.blockLight.clone();
        }

        public byte[] skyLight() {
            return this.skyLight == null ? null : this.skyLight.clone();
        }
    }

    public static final class DecodedChunk {
        private final long localChunkKey;
        private final long targetGlobalChunkKey;
        private final List<DecodedSection> sections;
        private final List<DecodedBlockTick> blockTicks;
        private final List<DecodedFluidTick> fluidTicks;
        private final Map<String, long[]> heightmaps;
        private final List<DecodedBlockEntity> blockEntities;
        private final boolean lightCorrect;
        private final CompoundTag platformPayload;

        private DecodedChunk(
                final long localChunkKey,
                final long targetGlobalChunkKey,
                final List<DecodedSection> sections,
                final List<DecodedBlockTick> blockTicks,
                final List<DecodedFluidTick> fluidTicks,
                final Map<String, long[]> heightmaps,
                final List<DecodedBlockEntity> blockEntities,
                final boolean lightCorrect,
                final CompoundTag platformPayload
        ) {
            this.localChunkKey = localChunkKey;
            this.targetGlobalChunkKey = targetGlobalChunkKey;
            this.sections = List.copyOf(Objects.requireNonNull(sections, "sections"));
            this.blockTicks = List.copyOf(Objects.requireNonNull(blockTicks, "blockTicks"));
            this.fluidTicks = List.copyOf(Objects.requireNonNull(fluidTicks, "fluidTicks"));
            this.heightmaps = copyHeightmaps(heightmaps);
            this.blockEntities = List.copyOf(Objects.requireNonNull(blockEntities, "blockEntities"));
            this.lightCorrect = lightCorrect;
            this.platformPayload = Objects.requireNonNull(platformPayload, "platformPayload").copy();
        }

        public long localChunkKey() {
            return this.localChunkKey;
        }

        public long targetGlobalChunkKey() {
            return this.targetGlobalChunkKey;
        }

        public List<DecodedSection> sections() {
            return this.sections;
        }

        public List<DecodedBlockTick> blockTicks() {
            return this.blockTicks;
        }

        public List<DecodedFluidTick> fluidTicks() {
            return this.fluidTicks;
        }

        public Map<String, long[]> heightmaps() {
            return copyHeightmaps(this.heightmaps);
        }

        public List<DecodedBlockEntity> blockEntities() {
            return this.blockEntities;
        }

        public boolean lightCorrect() {
            return this.lightCorrect;
        }

        public CompoundTag platformPayload() {
            return this.platformPayload.copy();
        }
    }

    private final ResourceLocation biomeId;
    private final int dataVersion;
    private final List<DecodedChunk> chunks;

    private SubLevelReconstructionDecodedPayload(
            final ResourceLocation biomeId,
            final int dataVersion,
            final List<DecodedChunk> chunks
    ) {
        this.biomeId = Objects.requireNonNull(biomeId, "biomeId");
        this.dataVersion = dataVersion;
        this.chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    }

    public static Capture decode(
            final ServerLevel targetLevel,
            final SubLevelReconstructionStagedPayload stagedPayload
    ) {
        Objects.requireNonNull(targetLevel, "targetLevel");
        Objects.requireNonNull(stagedPayload, "stagedPayload");
        if (!targetLevel.getServer().isSameThread()) {
            return rejected(Failure.NOT_SERVER_THREAD, Set.of());
        }
        return decodeFrom(stagedPayload, targetLevel.getSectionsCount());
    }

    /** Package-private pure seam for executable tests after Minecraft bootstrap. */
    static Capture decodeFrom(
            final SubLevelReconstructionStagedPayload stagedPayload,
            final int sectionCount
    ) {
        Objects.requireNonNull(stagedPayload, "stagedPayload");
        if (sectionCount <= 0) {
            throw new IllegalArgumentException("sectionCount must be positive");
        }

        final CompoundTag plotMetadata = stagedPayload.plotMetadata();
        final ResourceLocation biomeId;
        if (plotMetadata.contains("biome")) {
            biomeId = ResourceLocation.tryParse(plotMetadata.getString("biome"));
            if (biomeId == null) {
                return rejected(Failure.INVALID_BIOME_ID, Set.of());
            }
        } else {
            biomeId = Biomes.PLAINS.location();
        }

        final int dataVersion = plotMetadata.contains("data_version")
                ? plotMetadata.getInt("data_version")
                : 0;
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        final java.util.HashSet<Long> failedChunkKeys = new java.util.HashSet<>();
        final List<DecodedChunk> decodedChunks = new ArrayList<>(stagedPayload.chunks().size());

        for (final SubLevelReconstructionStagedPayload.ChunkSnapshot snapshot : stagedPayload.chunks()) {
            final EnumSet<Failure> chunkFailures = EnumSet.noneOf(Failure.class);
            final DecodedChunk decoded = decodeChunk(snapshot, sectionCount, chunkFailures);
            if (!chunkFailures.isEmpty()) {
                failures.addAll(chunkFailures);
                failedChunkKeys.add(snapshot.targetGlobalChunkKey());
            } else {
                decodedChunks.add(Objects.requireNonNull(decoded, "successful decode must produce a chunk"));
            }
        }

        if (!failures.isEmpty()) {
            return new Capture(failures, failedChunkKeys, Optional.empty());
        }

        return new Capture(
                Set.of(),
                Set.of(),
                Optional.of(new SubLevelReconstructionDecodedPayload(biomeId, dataVersion, decodedChunks))
        );
    }

    public ResourceLocation biomeId() {
        return this.biomeId;
    }

    public int dataVersion() {
        return this.dataVersion;
    }

    public List<DecodedChunk> chunks() {
        return this.chunks;
    }

    private static DecodedChunk decodeChunk(
            final SubLevelReconstructionStagedPayload.ChunkSnapshot snapshot,
            final int sectionCount,
            final EnumSet<Failure> failures
    ) {
        final CompoundTag chunkTag = snapshot.chunkTag();
        final List<DecodedSection> sections = decodeSections(chunkTag, sectionCount, failures);
        final List<DecodedBlockTick> blockTicks = decodeBlockTicks(chunkTag, failures);
        final List<DecodedFluidTick> fluidTicks = decodeFluidTicks(chunkTag, failures);
        final Map<String, long[]> heightmaps = decodeHeightmaps(chunkTag, failures);
        final List<DecodedBlockEntity> blockEntities = decodeBlockEntities(chunkTag, failures);
        final boolean lightCorrect = chunkTag.getBoolean("isLightOn");

        if (!failures.isEmpty()) {
            return null;
        }

        final CompoundTag platformPayload = chunkTag.copy();
        platformPayload.remove("sections");
        platformPayload.remove("block_ticks");
        platformPayload.remove("fluid_ticks");
        platformPayload.remove("heightmaps");
        platformPayload.remove("block_entities");
        platformPayload.remove("isLightOn");

        return new DecodedChunk(
                snapshot.localChunkKey(),
                snapshot.targetGlobalChunkKey(),
                sections,
                blockTicks,
                fluidTicks,
                heightmaps,
                blockEntities,
                lightCorrect,
                platformPayload
        );
    }

    private static List<DecodedSection> decodeSections(
            final CompoundTag chunkTag,
            final int sectionCount,
            final EnumSet<Failure> failures
    ) {
        if (!chunkTag.contains("sections", Tag.TAG_COMPOUND)) {
            failures.add(Failure.BLOCK_STATE_DECODE_FAILED);
            return List.of();
        }

        final CompoundTag sectionTags = chunkTag.getCompound("sections");
        final List<DecodedSection> sections = new ArrayList<>(sectionTags.size());
        for (final String key : sectionTags.getAllKeys()) {
            final int sectionIndex;
            try {
                sectionIndex = Integer.parseInt(key);
            } catch (final NumberFormatException invalidSectionIndex) {
                failures.add(Failure.INVALID_SECTION_INDEX);
                continue;
            }
            if (sectionIndex < 0 || sectionIndex >= sectionCount ||
                    !sectionTags.contains(key, Tag.TAG_COMPOUND)) {
                failures.add(Failure.INVALID_SECTION_INDEX);
                continue;
            }

            final CompoundTag sectionTag = sectionTags.getCompound(key);
            final PalettedContainer<BlockState> decodedStates;
            try {
                decodedStates = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, sectionTag.getCompound("block_states"))
                        .getOrThrow(IllegalArgumentException::new);
            } catch (final RuntimeException decodeFailure) {
                failures.add(Failure.BLOCK_STATE_DECODE_FAILED);
                continue;
            }

            final List<BlockState> states = new ArrayList<>(SECTION_VOLUME);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        states.add(decodedStates.get(x, y, z));
                    }
                }
            }

            final byte[] blockLight = decodeLight(sectionTag, "BlockLight", failures);
            final byte[] skyLight = decodeLight(sectionTag, "SkyLight", failures);
            sections.add(new DecodedSection(sectionIndex, states, blockLight, skyLight));
        }
        sections.sort(Comparator.comparingInt(DecodedSection::sectionIndex));
        return sections;
    }

    private static byte[] decodeLight(
            final CompoundTag sectionTag,
            final String key,
            final EnumSet<Failure> failures
    ) {
        if (!sectionTag.contains(key)) {
            return null;
        }
        if (!sectionTag.contains(key, Tag.TAG_BYTE_ARRAY)) {
            failures.add(Failure.INVALID_LIGHT_DATA);
            return null;
        }
        final byte[] light = sectionTag.getByteArray(key);
        if (light.length != LIGHT_ARRAY_BYTES) {
            failures.add(Failure.INVALID_LIGHT_DATA);
            return null;
        }
        return light.clone();
    }

    private static List<DecodedBlockTick> decodeBlockTicks(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        final Tag raw = chunkTag.get("block_ticks");
        if (!(raw instanceof final ListTag ticks) ||
                (!ticks.isEmpty() && ticks.getElementType() != Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_TICK_DATA);
            return List.of();
        }

        final List<DecodedBlockTick> result = new ArrayList<>(ticks.size());
        for (int index = 0; index < ticks.size(); index++) {
            final CompoundTag tick = ticks.getCompound(index);
            final ResourceLocation id = ResourceLocation.tryParse(tick.getString("i"));
            final Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block == null || !hasTickShape(tick)) {
                failures.add(Failure.INVALID_TICK_DATA);
                continue;
            }
            result.add(new DecodedBlockTick(
                    block,
                    tick.getInt("x"),
                    tick.getInt("y"),
                    tick.getInt("z"),
                    tick.getInt("t"),
                    tick.getInt("p")
            ));
        }
        return result;
    }

    private static List<DecodedFluidTick> decodeFluidTicks(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        final Tag raw = chunkTag.get("fluid_ticks");
        if (!(raw instanceof final ListTag ticks) ||
                (!ticks.isEmpty() && ticks.getElementType() != Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_TICK_DATA);
            return List.of();
        }

        final List<DecodedFluidTick> result = new ArrayList<>(ticks.size());
        for (int index = 0; index < ticks.size(); index++) {
            final CompoundTag tick = ticks.getCompound(index);
            final ResourceLocation id = ResourceLocation.tryParse(tick.getString("i"));
            final Fluid fluid = id == null ? null : BuiltInRegistries.FLUID.getOptional(id).orElse(null);
            if (fluid == null || !hasTickShape(tick)) {
                failures.add(Failure.INVALID_TICK_DATA);
                continue;
            }
            result.add(new DecodedFluidTick(
                    fluid,
                    tick.getInt("x"),
                    tick.getInt("y"),
                    tick.getInt("z"),
                    tick.getInt("t"),
                    tick.getInt("p")
            ));
        }
        return result;
    }

    private static boolean hasTickShape(final CompoundTag tick) {
        return tick.contains("i", Tag.TAG_STRING) &&
                tick.contains("x", Tag.TAG_INT) &&
                tick.contains("y", Tag.TAG_INT) &&
                tick.contains("z", Tag.TAG_INT) &&
                tick.contains("t", Tag.TAG_INT) &&
                tick.contains("p", Tag.TAG_INT);
    }

    private static Map<String, long[]> decodeHeightmaps(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        if (!chunkTag.contains("heightmaps", Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_HEIGHTMAP_DATA);
            return Map.of();
        }
        final CompoundTag heightmapTag = chunkTag.getCompound("heightmaps");
        final Map<String, long[]> result = new LinkedHashMap<>();
        for (final String key : heightmapTag.getAllKeys().stream().sorted().toList()) {
            if (!heightmapTag.contains(key, Tag.TAG_LONG_ARRAY)) {
                failures.add(Failure.INVALID_HEIGHTMAP_DATA);
                continue;
            }
            result.put(key, heightmapTag.getLongArray(key).clone());
        }
        return result;
    }

    private static List<DecodedBlockEntity> decodeBlockEntities(
            final CompoundTag chunkTag,
            final EnumSet<Failure> failures
    ) {
        final Tag raw = chunkTag.get("block_entities");
        if (!(raw instanceof final ListTag blockEntities) ||
                (!blockEntities.isEmpty() && blockEntities.getElementType() != Tag.TAG_COMPOUND)) {
            failures.add(Failure.INVALID_BLOCK_ENTITY_DATA);
            return List.of();
        }

        final List<DecodedBlockEntity> result = new ArrayList<>(blockEntities.size());
        for (int index = 0; index < blockEntities.size(); index++) {
            final CompoundTag payload = blockEntities.getCompound(index);
            final ResourceLocation id = ResourceLocation.tryParse(payload.getString("id"));
            if (id == null ||
                    !payload.contains("x", Tag.TAG_INT) ||
                    !payload.contains("y", Tag.TAG_INT) ||
                    !payload.contains("z", Tag.TAG_INT)) {
                failures.add(Failure.INVALID_BLOCK_ENTITY_DATA);
                continue;
            }
            result.add(new DecodedBlockEntity(
                    id,
                    new BlockPos(payload.getInt("x"), payload.getInt("y"), payload.getInt("z")),
                    payload.getBoolean("keepPacked"),
                    payload
            ));
        }
        return result;
    }

    private static Capture rejected(final Failure failure, final Set<Long> failedChunkKeys) {
        return new Capture(EnumSet.of(failure), failedChunkKeys, Optional.empty());
    }

    private static Map<String, long[]> copyHeightmaps(final Map<String, long[]> source) {
        Objects.requireNonNull(source, "source");
        final Map<String, long[]> copy = new LinkedHashMap<>();
        for (final Map.Entry<String, long[]> entry : source.entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "heightmap key"),
                    Objects.requireNonNull(entry.getValue(), "heightmap data").clone()
            );
        }
        return Collections.unmodifiableMap(copy);
    }
}
