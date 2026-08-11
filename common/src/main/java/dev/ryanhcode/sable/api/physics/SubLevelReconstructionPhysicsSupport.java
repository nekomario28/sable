package dev.ryanhcode.sable.api.physics;

import org.jetbrains.annotations.ApiStatus;

/**
 * Explicit opt-in contract for physics pipelines that can participate in transactional SubLevel
 * reconstruction.
 *
 * <p>Normal {@link PhysicsPipeline} implementations are intentionally unsupported by default.
 * Implementations must opt in only after they can prove the individual capabilities below. This
 * keeps reconstruction fail-closed when a pipeline can load sections normally but cannot restore
 * exact pre-call state after a partial failure.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionPhysicsSupport {

    /**
     * @return the reconstruction guarantees currently provided by this pipeline
     */
    Capabilities reconstructionCapabilities();

    /**
     * Capabilities required before target materialization may begin.
     *
     * @param exactSectionRollback whether transaction-owned section insertion can be rolled back
     *                             without deleting or corrupting pre-existing/shared physics state
     * @param provisionalBodyLifecycle whether a target physics body can remain unpublished until
     *                                 commit and can be removed exactly on rollback
     */
    record Capabilities(
            boolean exactSectionRollback,
            boolean provisionalBodyLifecycle
    ) {
        public boolean complete() {
            return this.exactSectionRollback && this.provisionalBodyLifecycle;
        }
    }
}
