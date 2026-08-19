package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionSectionSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable global-coordinate block-state view backed only by a detached decoded reconstruction
 * payload. Missing chunks/sections are intentionally air, matching the serialized SubLevel's
 * absence of block content rather than falling through to the live target world.
 */
@ApiStatus.Internal
public final class SubLevelReconstructionDecodedBlockStateView
        implements SubLevelReconstructionSectionSupport.ReconstructionBlockStateView {

    private record SectionKey(int x, int y, int z) {
    }

    private final Map<SectionKey, SubLevelReconstructionDecodedPayload.DecodedSection> sections;

    private SubLevelReconstructionDecodedBlockStateView(
            final Map<SectionKey, SubLevelReconstructionDecodedPayload.DecodedSection> sections
    ) {
        this.sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
    }

    /** Creates a detached view using the target level's exact section-index origin. */
    public static SubLevelReconstructionDecodedBlockStateView create(
            final ServerLevel targetLevel,
            final SubLevelReconstructionDecodedPayload payload
    ) {
        Objects.requireNonNull(targetLevel, "targetLevel");
        Objects.requireNonNull(payload, "payload");
        return createFrom(payload, targetLevel.getMinSection());
    }

    /** Package-private pure seam used by executable tests after Minecraft bootstrap. */
    static SubLevelReconstructionDecodedBlockStateView createFrom(
            final SubLevelReconstructionDecodedPayload payload,
            final int minSection
    ) {
        Objects.requireNonNull(payload, "payload");
        final Map<SectionKey, SubLevelReconstructionDecodedPayload.DecodedSection> sections =
                new LinkedHashMap<>();

        for (final SubLevelReconstructionDecodedPayload.DecodedChunk chunk : payload.chunks()) {
            final int chunkX = ChunkPos.getX(chunk.targetGlobalChunkKey());
            final int chunkZ = ChunkPos.getZ(chunk.targetGlobalChunkKey());
            for (final SubLevelReconstructionDecodedPayload.DecodedSection section : chunk.sections()) {
                final int sectionY = Math.addExact(minSection, section.sectionIndex());
                final SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
                if (sections.put(key, section) != null) {
                    throw new IllegalArgumentException("Duplicate decoded target section: " + key);
                }
            }
        }

        return new SubLevelReconstructionDecodedBlockStateView(sections);
    }

    @Override
    public @NotNull BlockState stateAt(
            final int globalBlockX,
            final int globalBlockY,
            final int globalBlockZ
    ) {
        final int sectionX = Math.floorDiv(globalBlockX, 16);
        final int sectionY = Math.floorDiv(globalBlockY, 16);
        final int sectionZ = Math.floorDiv(globalBlockZ, 16);
        final SubLevelReconstructionDecodedPayload.DecodedSection section =
                this.sections.get(new SectionKey(sectionX, sectionY, sectionZ));
        if (section == null) {
            return Blocks.AIR.defaultBlockState();
        }

        return section.stateAt(
                Math.floorMod(globalBlockX, 16),
                Math.floorMod(globalBlockY, 16),
                Math.floorMod(globalBlockZ, 16)
        );
    }

    @ApiStatus.Internal
    int sectionCount() {
        return this.sections.size();
    }
}
