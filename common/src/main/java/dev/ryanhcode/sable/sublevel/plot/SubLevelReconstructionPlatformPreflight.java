package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.platform.SableChunkEventPlatform;
import dev.ryanhcode.sable.platform.SablePlotPlatform;
import dev.ryanhcode.sable.platform.SubLevelReconstructionChunkEventSupport;
import dev.ryanhcode.sable.platform.SubLevelReconstructionPlotPlatformSupport;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Mutation-free platform callback capability gate for transactional SubLevel reconstruction.
 *
 * <p>Normal platform implementations are unsupported by default. Reconstruction may only proceed
 * once externally visible post-load callbacks/events can be deferred until commit. Detached
 * platform-data reads are required only when the decoded snapshot actually contains platform
 * extension payload; platform-data-free snapshots never invoke that unsafe generic read path.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionPlatformPreflight {
    private SubLevelReconstructionPlatformPreflight() {
    }

    public enum Failure {
        DETACHED_CHUNK_DATA_READ_UNAVAILABLE,
        POST_LOAD_DEFER_UNAVAILABLE,
        CHUNK_LOAD_EVENT_DEFER_UNAVAILABLE
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
     * Conservative capability check for callers that do not have decoded snapshot evidence.
     */
    public static Result validate() {
        return validateCurrentPlatform(true);
    }

    /**
     * Validates the current platform against the exact decoded reconstruction payload.
     *
     * <p>An empty platform payload means reconstruction has no platform light/attachment data to
     * restore before commit, so generic detached platform-data reads are not required. Any non-empty
     * platform payload keeps the existing fail-closed detached-read requirement.</p>
     */
    public static Result validate(final SubLevelReconstructionDecodedPayload decodedPayload) {
        Objects.requireNonNull(decodedPayload, "decodedPayload");
        final boolean detachedChunkDataRequired = decodedPayload.chunks().stream()
                .anyMatch(chunk -> !chunk.platformPayload().isEmpty());
        return validateCurrentPlatform(detachedChunkDataRequired);
    }

    private static Result validateCurrentPlatform(final boolean detachedChunkDataRequired) {
        final SubLevelReconstructionPlotPlatformSupport.Capabilities plotCapabilities =
                SablePlotPlatform.INSTANCE instanceof final SubLevelReconstructionPlotPlatformSupport support
                        ? support.reconstructionPlotCapabilities()
                        : null;
        final Boolean chunkEventDeferrable =
                SableChunkEventPlatform.INSTANCE instanceof final SubLevelReconstructionChunkEventSupport support
                        ? support.canDeferPlotChunkLoadEvent()
                        : null;
        return validateCapabilities(plotCapabilities, chunkEventDeferrable, detachedChunkDataRequired);
    }

    /** Package-private pure seam retaining the historical conservative capability check. */
    static Result validateCapabilities(
            @Nullable final SubLevelReconstructionPlotPlatformSupport.Capabilities plotCapabilities,
            @Nullable final Boolean chunkEventDeferrable
    ) {
        return validateCapabilities(plotCapabilities, chunkEventDeferrable, true);
    }

    /** Package-private pure seam for exact safe-subset capability tests. */
    static Result validateCapabilities(
            @Nullable final SubLevelReconstructionPlotPlatformSupport.Capabilities plotCapabilities,
            @Nullable final Boolean chunkEventDeferrable,
            final boolean detachedChunkDataRequired
    ) {
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        if (detachedChunkDataRequired
                && (plotCapabilities == null || !plotCapabilities.detachedChunkDataReadSafe())) {
            failures.add(Failure.DETACHED_CHUNK_DATA_READ_UNAVAILABLE);
        }
        if (plotCapabilities == null || !plotCapabilities.postLoadDeferrable()) {
            failures.add(Failure.POST_LOAD_DEFER_UNAVAILABLE);
        }
        if (!Boolean.TRUE.equals(chunkEventDeferrable)) {
            failures.add(Failure.CHUNK_LOAD_EVENT_DEFER_UNAVAILABLE);
        }
        return new Result(failures);
    }
}
