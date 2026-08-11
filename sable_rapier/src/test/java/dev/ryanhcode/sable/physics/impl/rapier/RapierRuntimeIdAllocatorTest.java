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
