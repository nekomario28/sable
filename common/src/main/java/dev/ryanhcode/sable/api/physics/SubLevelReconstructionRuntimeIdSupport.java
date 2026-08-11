package dev.ryanhcode.sable.api.physics;

import org.jetbrains.annotations.ApiStatus;

import java.util.function.Supplier;

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

    /**
     * Runs one normal runtime-ID-consuming allocation while binding its single ID request to the
     * supplied reservation rather than allocating a second ID.
     *
     * <p>The allocation must consume exactly one runtime ID through this physics pipeline. The
     * reservation remains open after this method returns; the transaction still owns commit or
     * rollback. Implementations must reject foreign, closed, nested, zero-consumption, or
     * multi-consumption scopes.</p>
     *
     * @param reservation the open reservation owned by this physics pipeline
     * @param allocation  allocation that performs exactly one normal runtime-ID request
     * @return allocation result
     */
    <T> T withReservedRuntimeId(RuntimeIdReservation reservation, Supplier<T> allocation);

    interface RuntimeIdReservation {
        /** @return the reserved runtime ID */
        int runtimeId();

        /** @return whether this reservation can still be committed or rolled back */
        boolean open();

        /**
         * Makes this runtime ID allocation permanent.
         * If this method throws, the reservation must remain open and rollbackable.
         */
        void commit();

        /**
         * Restores exact allocator state.
         * If exact restoration is impossible this method must throw before closing the reservation,
         * leaving it open rather than pretending rollback succeeded.
         */
        void rollback();
    }
}
