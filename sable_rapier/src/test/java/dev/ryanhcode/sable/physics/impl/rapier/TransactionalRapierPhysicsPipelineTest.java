package dev.ryanhcode.sable.physics.impl.rapier;

import dev.ryanhcode.sable.api.physics.SubLevelReconstructionPhysicsSupport;
import dev.ryanhcode.sable.api.physics.SubLevelReconstructionRuntimeIdSupport;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TransactionalRapierPhysicsPipelineTest {
    @Test
    void optsInOnlyToRuntimeIdReservation() {
        assertTrue(SubLevelReconstructionRuntimeIdSupport.class
                .isAssignableFrom(TransactionalRapierPhysicsPipeline.class));
        assertFalse(SubLevelReconstructionPhysicsSupport.class
                .isAssignableFrom(TransactionalRapierPhysicsPipeline.class));
    }
}
