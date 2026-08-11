package dev.ryanhcode.sable.physics.impl.rapier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RapierRuntimeIdAllocatorTest {
    @Test
    void normalAllocationRemainsMonotonic() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();

        assertEquals(0, allocator.next());
        assertEquals(1, allocator.next());
        assertEquals(2, allocator.next());
    }

    @Test
    void latestReservationCanRollbackExactly() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        assertEquals(0, allocator.next());

        final RapierRuntimeIdAllocator.Reservation reservation = allocator.reserve();
        assertEquals(1, reservation.id());
        assertTrue(reservation.open());

        reservation.rollback();

        assertFalse(reservation.open());
        assertEquals(1, allocator.next());
    }

    @Test
    void reservedIdCanBeAdoptedByExactlyOneNormalAllocation() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation reservation = allocator.reserve();

        final int adopted = allocator.withReservation(reservation, allocator::next);

        assertEquals(reservation.id(), adopted);
        assertTrue(reservation.open());
        reservation.rollback();
        assertEquals(adopted, allocator.next());
    }

    @Test
    void allocationFailureAfterClaimLeavesReservationRollbackable() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation reservation = allocator.reserve();

        assertThrows(IllegalStateException.class, () -> allocator.withReservation(reservation, () -> {
            assertEquals(reservation.id(), allocator.next());
            throw new IllegalStateException("construction failed");
        }));

        assertTrue(reservation.open());
        reservation.rollback();
        assertEquals(0, allocator.next());
    }

    @Test
    void zeroAndMultipleConsumptionFailClosed() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();

        final RapierRuntimeIdAllocator.Reservation zero = allocator.reserve();
        assertThrows(IllegalStateException.class, () -> allocator.withReservation(zero, () -> "no id"));
        assertTrue(zero.open());
        zero.rollback();

        final RapierRuntimeIdAllocator.Reservation multiple = allocator.reserve();
        assertThrows(IllegalStateException.class, () -> allocator.withReservation(multiple, () -> {
            allocator.next();
            allocator.next();
            return null;
        }));
        assertTrue(multiple.open());
        multiple.rollback();
    }

    @Test
    void foreignAndNestedScopesFailClosed() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator other = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation foreign = other.reserve();

        assertThrows(IllegalArgumentException.class, () -> allocator.withReservation(foreign, allocator::next));
        foreign.rollback();

        final RapierRuntimeIdAllocator.Reservation outer = allocator.reserve();
        assertThrows(IllegalStateException.class, () -> allocator.withReservation(outer, () -> {
            allocator.next();
            final RapierRuntimeIdAllocator.Reservation impossible = allocator.reserve();
            return allocator.withReservation(impossible, allocator::next);
        }));
        assertTrue(outer.open());
        outer.rollback();
    }

    @Test
    void reservationCannotCloseInsideAdoptionScope() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation reservation = allocator.reserve();

        assertThrows(IllegalStateException.class, () -> allocator.withReservation(reservation, () -> {
            allocator.next();
            reservation.commit();
            return null;
        }));

        assertTrue(reservation.open());
        reservation.rollback();
    }

    @Test
    void nestedReservationsRollbackInStrictReverseOrder() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation first = allocator.reserve();
        final RapierRuntimeIdAllocator.Reservation second = allocator.reserve();
        assertEquals(0, first.id());
        assertEquals(1, second.id());

        second.rollback();
        first.rollback();

        assertEquals(0, allocator.next());
    }

    @Test
    void committedReservationIsNotReused() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation reservation = allocator.reserve();
        assertEquals(0, reservation.id());

        reservation.commit();

        assertFalse(reservation.open());
        assertEquals(1, allocator.next());
    }

    @Test
    void unrelatedLaterAllocationMakesRollbackFailClosed() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation reservation = allocator.reserve();
        assertEquals(0, reservation.id());
        assertEquals(1, allocator.next());

        assertThrows(IllegalStateException.class, reservation::rollback);
        assertTrue(reservation.open());
        assertEquals(2, allocator.next());
    }

    @Test
    void reservationCannotCloseTwice() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final RapierRuntimeIdAllocator.Reservation committed = allocator.reserve();
        committed.commit();
        assertThrows(IllegalStateException.class, committed::commit);
        assertThrows(IllegalStateException.class, committed::rollback);

        final RapierRuntimeIdAllocator.Reservation rolledBack = allocator.reserve();
        rolledBack.rollback();
        assertThrows(IllegalStateException.class, rolledBack::commit);
        assertThrows(IllegalStateException.class, rolledBack::rollback);
    }
}
