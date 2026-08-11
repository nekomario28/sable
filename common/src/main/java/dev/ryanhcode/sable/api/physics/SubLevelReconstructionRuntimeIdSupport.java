package dev.ryanhcode.sable.api.physics;

import org.jetbrains.annotations.ApiStatus;

import java.util.function.Supplier;

/**
 * Operational contract for physics implementations that can reserve a SubLevel runtime ID for a
 * transactional reconstruction attempt.
 *
 * <p>A capability boolean is not sufficient: reconstruction needs an actual reservation whose
 * failed attempt can restore allocator state exactly. An open reservation must keep exclusive
 * ownership of its allocator position until commit or rollback: unrelated normal runtime-ID
 * allocations and additional reconstruction reservations must fail rather than making rollback
 * impossible. Implementations must keep the reservation unpublished until commit.</p>
 */
@ApiStatus.Experimental
public interface SubLevelReconstructionRuntimeIdSupport {

    /**
     * Reserves one runtime ID without committing a target body.
     *
     * <p>Only one reconstruction reservation may own the allocator position at a time. While it is
     * open, unrelated normal runtime-ID allocation must fail closed.</p>
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

        /** @return whether this reservation still owns its exclusive allocator position */
        boolean open();

        /**
         * Makes this runtime ID allocation permanent and releases exclusive allocator ownership.
         * If this method throws, the reservation must remain open and rollbackable.
         */
        void commit();

        /**
         * Restores exact allocator state and releases exclusive allocator ownership.
         * If exact restoration is impossible this method must throw before closing the reservation,
         * leaving it open rather than pretending rollback succeeded.
         */
        void rollback();
    }
}
