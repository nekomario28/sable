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
 * once chunk data can be decoded on detached/unpublished chunks and externally visible post-load
 * callbacks/events can be deferred until commit.</p>
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

    public static Result validate() {
        final SubLevelReconstructionPlotPlatformSupport.Capabilities plotCapabilities =
                SablePlotPlatform.INSTANCE instanceof final SubLevelReconstructionPlotPlatformSupport support
                        ? support.reconstructionPlotCapabilities()
                        : null;
        final Boolean chunkEventDeferrable =
                SableChunkEventPlatform.INSTANCE instanceof final SubLevelReconstructionChunkEventSupport support
                        ? support.canDeferPlotChunkLoadEvent()
                        : null;
        return validateCapabilities(plotCapabilities, chunkEventDeferrable);
    }

    /** Package-private pure seam for executable capability tests. */
    static Result validateCapabilities(
            @Nullable final SubLevelReconstructionPlotPlatformSupport.Capabilities plotCapabilities,
            @Nullable final Boolean chunkEventDeferrable
    ) {
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        if (plotCapabilities == null || !plotCapabilities.detachedChunkDataReadSafe()) {
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
