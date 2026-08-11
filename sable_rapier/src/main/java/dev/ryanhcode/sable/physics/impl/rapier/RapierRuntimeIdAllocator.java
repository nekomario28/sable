package dev.ryanhcode.sable.physics.impl.rapier;

import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Monotonic runtime-ID allocator with an exclusive transaction reservation.
 *
 * <p>Normal allocation remains exactly equivalent to the historical {@code counter++} behavior
 * when no reconstruction reservation is open. While a reservation is open, unrelated allocations
 * and additional reservations are rejected so rollback remains exact for the entire transaction.
 * The reserved ID may be consumed only by its explicit adoption scope.</p>
 */
@ApiStatus.Internal
final class RapierRuntimeIdAllocator {
    private int nextId;
    private Reservation openReservation;
    private ClaimScope activeClaimScope;

    synchronized int next() {
        if (this.activeClaimScope != null) {
            return this.activeClaimScope.claim();
        }
        if (this.openReservation != null) {
            throw new IllegalStateException(
                    "Cannot allocate another runtime ID while a reconstruction reservation is open"
            );
        }
        return this.nextId++;
    }

    synchronized Reservation reserve() {
        if (this.openReservation != null || this.activeClaimScope != null) {
            throw new IllegalStateException("A reconstruction runtime ID reservation is already active");
        }
        final Reservation reservation = new Reservation(this, this.nextId++);
        this.openReservation = reservation;
        return reservation;
    }

    synchronized <T> T withReservation(final Reservation reservation, final Supplier<T> allocation) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(allocation, "allocation");
        if (reservation.allocator != this) {
            throw new IllegalArgumentException("Runtime ID reservation belongs to another allocator");
        }
        if (this.openReservation != reservation || !reservation.open()) {
            throw new IllegalStateException("Runtime ID reservation is not the active open reservation");
        }
        if (this.activeClaimScope != null) {
            throw new IllegalStateException("Nested runtime ID adoption scopes are not allowed");
        }

        final ClaimScope scope = new ClaimScope(reservation.id());
        this.activeClaimScope = scope;
        try {
            final T result = allocation.get();
            if (!scope.claimed()) {
                throw new IllegalStateException("Reserved runtime ID adoption consumed no runtime ID");
            }
            if (this.openReservation != reservation || !reservation.open()) {
                throw new IllegalStateException("Runtime ID reservation was closed inside its adoption scope");
            }
            return result;
        } finally {
            this.activeClaimScope = null;
        }
    }

    private synchronized void commit(final Reservation reservation) {
        this.requireClosable(reservation);
        reservation.state = Reservation.State.COMMITTED;
        this.openReservation = null;
    }

    private synchronized void rollback(final Reservation reservation) {
        this.requireClosable(reservation);
        if (this.nextId != reservation.id + 1) {
            throw new IllegalStateException(
                    "Exclusive runtime ID reservation lost exact allocator ownership for %d"
                            .formatted(reservation.id)
            );
        }
        this.nextId = reservation.id;
        reservation.state = Reservation.State.ROLLED_BACK;
        this.openReservation = null;
    }

    private void requireClosable(final Reservation reservation) {
        if (reservation.allocator != this) {
            throw new IllegalArgumentException("Runtime ID reservation belongs to another allocator");
        }
        if (this.openReservation != reservation || !reservation.open()) {
            throw new IllegalStateException("Runtime ID reservation is not the active open reservation");
        }
        if (this.activeClaimScope != null && this.activeClaimScope.runtimeId == reservation.id) {
            throw new IllegalStateException("Cannot close a runtime ID while its adoption scope is active");
        }
    }

    private static final class ClaimScope {
        private final int runtimeId;
        private boolean claimed;

        private ClaimScope(final int runtimeId) {
            this.runtimeId = runtimeId;
        }

        private int claim() {
            if (this.claimed) {
                throw new IllegalStateException("Reserved runtime ID adoption consumed more than one runtime ID");
            }
            this.claimed = true;
            return this.runtimeId;
        }

        private boolean claimed() {
            return this.claimed;
        }
    }

    @ApiStatus.Internal
    static final class Reservation {
        private enum State {
            OPEN,
            COMMITTED,
            ROLLED_BACK
        }

        private final RapierRuntimeIdAllocator allocator;
        private final int id;
        private volatile State state = State.OPEN;

        private Reservation(final RapierRuntimeIdAllocator allocator, final int id) {
            this.allocator = Objects.requireNonNull(allocator, "allocator");
            this.id = id;
        }

        int id() {
            return this.id;
        }

        boolean open() {
            return this.state == State.OPEN;
        }

        void commit() {
            this.allocator.commit(this);
        }

        void rollback() {
            this.allocator.rollback(this);
        }
    }
}
