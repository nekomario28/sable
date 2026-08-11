package dev.ryanhcode.sable.platform;

import org.jetbrains.annotations.ApiStatus;

/**
 * Explicit opt-in for platform-specific plot hooks used by transactional SubLevel reconstruction.
 *
 * <p>Implementations must not claim a capability until the corresponding callback can be used
 * without leaking externally visible state before reconstruction commit.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionPlotPlatformSupport {

    Capabilities reconstructionPlotCapabilities();

    record Capabilities(
            boolean detachedChunkDataReadSafe,
            boolean postLoadDeferrable
    ) {
        public boolean complete() {
            return this.detachedChunkDataReadSafe && this.postLoadDeferrable;
        }
    }
}
