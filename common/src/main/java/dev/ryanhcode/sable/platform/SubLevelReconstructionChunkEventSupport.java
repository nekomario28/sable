package dev.ryanhcode.sable.platform;

import org.jetbrains.annotations.ApiStatus;

/**
 * Explicit opt-in for platform chunk-load event handling during transactional SubLevel
 * reconstruction.
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionChunkEventSupport {

    /**
     * @return true only when plot-chunk load events can be deferred until reconstruction commit
     */
    boolean canDeferPlotChunkLoadEvent();
}
