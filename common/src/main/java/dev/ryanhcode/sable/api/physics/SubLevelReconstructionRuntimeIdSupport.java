package dev.ryanhcode.sable.api.physics;

import org.jetbrains.annotations.ApiStatus;

/**
 * Operational contract for physics implementations that can reserve a SubLevel runtime ID for a
 * transactional reconstruction attempt.
 *
 * <p>A capability boolean is not sufficient: reconstruction needs an actual reservation whose
 * failed attempt can restore allocator state exactly. Implementations must keep a reservation
 * unpublished until commit and must fail closed if exact rollback is no longer possible.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionRuntimeIdSupport {

    /**
     * Reserves one runtime ID without committing a target body.
     *
     * @return a transaction-owned reservation
     */
    RuntimeIdReservation reserveReconstructionRuntimeId();

    interface RuntimeIdReservation {
        /** @return the reserved runtime ID */
        int runtimeId();

        /** @return whether this reservation can still be committed or rolled back */
        boolean open();

        /** Makes this runtime ID allocation permanent. */
        void commit();

        /** Restores exact allocator state or fails without pretending rollback succeeded. */
        void rollback();
    }
}
