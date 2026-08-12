package dev.ryanhcode.sable.api.physics;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Operational contract for a physics pipeline that can keep a reconstruction target body
 * provisional until the surrounding reconstruction transaction commits.
 *
 * <p>The capability bit in {@link SubLevelReconstructionPhysicsSupport} is deliberately not
 * sufficient. Reconstruction needs an actual body reservation whose native/provider state can be
 * verified and removed exactly without publishing the target through the pipeline's normal live
 * body registry.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionBodySupport {

    /**
     * Creates the provider-native body for a detached target without publishing it as a normal live
     * SubLevel body.
     *
     * <p>The target must already contain the final runtime ID, pose, mass data and plot bounds needed
     * to initialize its body. Implementations must reject an existing provider-native body for the
     * same runtime ID. If this method throws, provider state must remain observationally unchanged.</p>
     *
     * @param target detached reconstruction target
     * @return transaction-owned provisional body reservation
     */
    ReconstructionBodyReservation acquireReconstructionBody(ServerSubLevel target);

    interface ReconstructionBodyReservation {
        /** @return runtime ID of the provisional target body */
        int ownerRuntimeId();

        /** @return whether this body is still transaction-owned and rollbackable */
        boolean open();

        /**
         * Read-only proof that the expected provisional provider-native body still exists and has
         * not been published through the normal live-body registry.
         */
        void verify();

        /**
         * Publishes the already-verified body through the provider's normal live-body registry
         * without recreating it. If this method throws, it must remain open and rollbackable.
         */
        void commit();

        /**
         * Removes the provisional provider-native body and proves the exact pre-acquisition body
         * state was restored. If exact restoration cannot be proven, this method must throw before
         * pretending the reservation is closed.
         */
        void rollback();
    }
}
