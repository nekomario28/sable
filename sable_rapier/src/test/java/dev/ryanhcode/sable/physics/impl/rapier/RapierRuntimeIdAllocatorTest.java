package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
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

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reservation = allocator.reserve();
        assertEquals(1, reservation.runtimeId());
        assertTrue(reservation.open());

        reservation.rollback();

        assertFalse(reservation.open());
        assertEquals(1, allocator.next());
    }

    @Test
    void nestedReservationsRollbackInStrictReverseOrder() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation first = allocator.reserve();
        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation second = allocator.reserve();
        assertEquals(0, first.runtimeId());
        assertEquals(1, second.runtimeId());

        second.rollback();
        first.rollback();

        assertEquals(0, allocator.next());
    }

    @Test
    void committedReservationIsNotReused() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reservation = allocator.reserve();
        assertEquals(0, reservation.runtimeId());

        reservation.commit();

        assertFalse(reservation.open());
        assertEquals(1, allocator.next());
    }

    @Test
    void unrelatedLaterAllocationMakesRollbackFailClosed() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation reservation = allocator.reserve();
        assertEquals(0, reservation.runtimeId());
        assertEquals(1, allocator.next());

        assertThrows(IllegalStateException.class, reservation::rollback);
        assertTrue(reservation.open());
        assertEquals(2, allocator.next());
    }

    @Test
    void reservationCannotCloseTwice() {
        final RapierRuntimeIdAllocator allocator = new RapierRuntimeIdAllocator();
        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation committed = allocator.reserve();
        committed.commit();
        assertThrows(IllegalStateException.class, committed::commit);
        assertThrows(IllegalStateException.class, committed::rollback);

        final SubLevelReconstructionRuntimeIdSupport.RuntimeIdReservation rolledBack = allocator.reserve();
        rolledBack.rollback();
        assertThrows(IllegalStateException.class, rolledBack::commit);
        assertThrows(IllegalStateException.class, rolledBack::rollback);
    }
}
