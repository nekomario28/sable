package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Computes the exact non-air plot bounds from an already-decoded reconstruction payload without
 * allocating a live target chunk.
 *
 * <p>The scan intentionally mirrors {@link PlotChunkHolder}'s bounding-box semantics: X/Z are
 * translated through the payload's frozen target-global chunk key, Y is translated through the
 * target level's section index mapping, and only non-air block states expand the inclusive bounds.</p>
 */
@ApiStatus.Internal
public final class SubLevelReconstructionDecodedBounds {
    private SubLevelReconstructionDecodedBounds() {
    }

    public enum Failure {
        EMPTY_NON_AIR_CONTENT,
        COORDINATE_OVERFLOW
    }

    public record Capture(Set<Failure> failures, Optional<BoundingBox3i> bounds) {
        public Capture {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(bounds, "bounds");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
            if (failures.isEmpty() != bounds.isPresent()) {
                throw new IllegalArgumentException(
                        "Accepted decoded bounds require a box and rejected bounds require evidence"
                );
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.bounds.isPresent();
        }
    }

    /** Computes bounds using the target level's exact section-index origin. */
    public static Capture compute(
            final ServerLevel targetLevel,
            final SubLevelReconstructionDecodedPayload payload
    ) {
        Objects.requireNonNull(targetLevel, "targetLevel");
        Objects.requireNonNull(payload, "payload");
        return computeFrom(payload, targetLevel.getMinSection());
    }

    /** Package-private pure seam used by executable tests after Minecraft bootstrap. */
    static Capture computeFrom(
            final SubLevelReconstructionDecodedPayload payload,
            final int minSection
    ) {
        Objects.requireNonNull(payload, "payload");

        boolean found = false;
        int minX = 0;
        int minY = 0;
        int minZ = 0;
        int maxX = 0;
        int maxY = 0;
        int maxZ = 0;

        try {
            for (final SubLevelReconstructionDecodedPayload.DecodedChunk chunk : payload.chunks()) {
                final int chunkX = ChunkPos.getX(chunk.targetGlobalChunkKey());
                final int chunkZ = ChunkPos.getZ(chunk.targetGlobalChunkKey());
                final int chunkMinX = Math.multiplyExact(chunkX, 16);
                final int chunkMinZ = Math.multiplyExact(chunkZ, 16);

                for (final SubLevelReconstructionDecodedPayload.DecodedSection section : chunk.sections()) {
                    final int sectionY = Math.addExact(minSection, section.sectionIndex());
                    final int sectionMinY = Math.multiplyExact(sectionY, 16);

                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                if (section.stateAt(x, y, z).isAir()) {
                                    continue;
                                }

                                final int globalX = Math.addExact(chunkMinX, x);
                                final int globalY = Math.addExact(sectionMinY, y);
                                final int globalZ = Math.addExact(chunkMinZ, z);
                                if (!found) {
                                    minX = maxX = globalX;
                                    minY = maxY = globalY;
                                    minZ = maxZ = globalZ;
                                    found = true;
                                } else {
                                    minX = Math.min(minX, globalX);
                                    minY = Math.min(minY, globalY);
                                    minZ = Math.min(minZ, globalZ);
                                    maxX = Math.max(maxX, globalX);
                                    maxY = Math.max(maxY, globalY);
                                    maxZ = Math.max(maxZ, globalZ);
                                }
                            }
                        }
                    }
                }
            }
        } catch (final ArithmeticException overflow) {
            return rejected(Failure.COORDINATE_OVERFLOW);
        }

        if (!found) {
            return rejected(Failure.EMPTY_NON_AIR_CONTENT);
        }
        return new Capture(Set.of(), Optional.of(new BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ)));
    }

    private static Capture rejected(final Failure failure) {
        return new Capture(Set.of(failure), Optional.empty());
    }
}
