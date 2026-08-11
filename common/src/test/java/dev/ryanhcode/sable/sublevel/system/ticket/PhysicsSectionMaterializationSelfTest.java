package dev.ryanhcode.sable.sublevel.system.ticket;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Assertion-based executable for physics section materialization ownership and cleanup. */
public final class PhysicsSectionMaterializationSelfTest {
    private PhysicsSectionMaterializationSelfTest() {
    }

    public static void main(final String[] args) {
        ownedSectionUploadsAndRollsBack();
        borrowedSectionIsNeverUploadedOrRemoved();
        additionFailureCleansSectionAndTicket();
        cleanupContinuesWhenSectionRemovalFails();
        rollbackContinuesWhenSectionRemovalFails();
        commitPreservesSectionTicket();
        System.out.println("PHYSICS_SECTION_MATERIALIZATION_SELF_TEST: PASS");
    }

    private static void ownedSectionUploadsAndRollsBack() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(1, 2, 3);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager, probe.pipeline(), section(), pos, 10L, true
                )
        );

        assert materialization.uploadedByTransaction();
        assert materialization.ticketOwnership() == PhysicsSectionTicketReservation.Ownership.OWNED;
        assert probe.events.equals(List.of("add:true"));

        final PhysicsSectionMaterialization.RollbackReport rollback = materialization.rollback();
        assert rollback.successful();
        assert probe.events.equals(List.of("add:true", "remove"));

        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 11L);
        assert after.owned();
        after.rollback();
    }

    private static void borrowedSectionIsNeverUploadedOrRemoved() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final SectionPos pos = SectionPos.of(4, 5, 6);
        final PhysicsSectionTicketReservation preExisting = manager.reserveTicketForSection(pos, 12L);
        final PipelineProbe probe = new PipelineProbe();

        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager, probe.pipeline(), section(), pos, 13L, true
                )
        );

        assert !materialization.uploadedByTransaction();
        assert materialization.ticketOwnership() == PhysicsSectionTicketReservation.Ownership.BORROWED;
        assert probe.events.isEmpty();
        assert materialization.rollback().successful();
        assert probe.events.isEmpty();

        final PhysicsSectionTicketReservation stillExisting = manager.reserveTicketForSection(pos, 14L);
        assert !stillExisting.owned();
        stillExisting.rollback();
        preExisting.rollback();
    }

    private static void additionFailureCleansSectionAndTicket() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        probe.failAddition = true;
        final SectionPos pos = SectionPos.of(7, 8, 9);

        final PhysicsSectionMaterialization.Failed failed = failed(
                PhysicsSectionMaterialization.acquire(
                        manager, probe.pipeline(), section(), pos, 15L, false
                )
        );

        assert failed.rollbackSuccessful();
        assert failed.cleanupFailures().isEmpty();
        assert probe.events.equals(List.of("add:false", "remove"));

        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 16L);
        assert after.owned();
        after.rollback();
    }

    private static void cleanupContinuesWhenSectionRemovalFails() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        probe.failAddition = true;
        probe.failRemoval = true;
        final SectionPos pos = SectionPos.of(10, 11, 12);

        final PhysicsSectionMaterialization.Failed failed = failed(
                PhysicsSectionMaterialization.acquire(
                        manager, probe.pipeline(), section(), pos, 17L, true
                )
        );

        assert !failed.rollbackSuccessful();
        assert failed.cleanupFailures().size() == 1;
        assert failed.cleanupFailures().getFirst().resource().equals("physics_section");

        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 18L);
        assert after.owned();
        after.rollback();
    }

    private static void rollbackContinuesWhenSectionRemovalFails() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(13, 14, 15);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager, probe.pipeline(), section(), pos, 19L, true
                )
        );
        probe.failRemoval = true;

        final PhysicsSectionMaterialization.RollbackReport rollback = materialization.rollback();

        assert !rollback.successful();
        assert rollback.state() == PhysicsSectionMaterialization.State.ROLLBACK_FAILED;
        assert rollback.failures().size() == 1;
        assert rollback.failures().getFirst().resource().equals("physics_section");

        final PhysicsSectionTicketReservation after = manager.reserveTicketForSection(pos, 20L);
        assert after.owned();
        after.rollback();
    }

    private static void commitPreservesSectionTicket() {
        final PhysicsChunkTicketManager manager = new PhysicsChunkTicketManager();
        final PipelineProbe probe = new PipelineProbe();
        final SectionPos pos = SectionPos.of(16, 17, 18);
        final PhysicsSectionMaterialization materialization = acquired(
                PhysicsSectionMaterialization.acquire(
                        manager, probe.pipeline(), section(), pos, 21L, true
                )
        );

        materialization.commit();

        assert materialization.state() == PhysicsSectionMaterialization.State.COMMITTED;
        assertIllegalState(materialization::commit);
        assertIllegalState(materialization::rollback);
        assert probe.events.equals(List.of("add:true"));

        final PhysicsSectionTicketReservation borrowed = manager.reserveTicketForSection(pos, 22L);
        assert !borrowed.owned();
        borrowed.rollback();
    }

    private static LevelChunkSection section() {
        return new LevelChunkSection(BuiltInRegistries.BIOME);
    }

    private static PhysicsSectionMaterialization acquired(
            final PhysicsSectionMaterialization.Acquisition acquisition
    ) {
        assert acquisition instanceof PhysicsSectionMaterialization.Acquired;
        return ((PhysicsSectionMaterialization.Acquired) acquisition).materialization();
    }

    private static PhysicsSectionMaterialization.Failed failed(
            final PhysicsSectionMaterialization.Acquisition acquisition
    ) {
        assert acquisition instanceof PhysicsSectionMaterialization.Failed;
        return (PhysicsSectionMaterialization.Failed) acquisition;
    }

    private static void assertIllegalState(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        assert threw;
    }

    private static final class PipelineProbe {
        private final List<String> events = new ArrayList<>();
        private boolean failAddition;
        private boolean failRemoval;

        private PhysicsPipeline pipeline() {
            return (PhysicsPipeline) Proxy.newProxyInstance(
                    PhysicsPipeline.class.getClassLoader(),
                    new Class<?>[]{PhysicsPipeline.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("handleChunkSectionAddition")) {
                            this.events.add("add:" + args[4]);
                            if (this.failAddition) {
                                throw new IllegalStateException("injected addition failure");
                            }
                            return null;
                        }
                        if (method.getName().equals("handleChunkSectionRemoval")) {
                            this.events.add("remove");
                            if (this.failRemoval) {
                                throw new IllegalStateException("injected removal failure");
                            }
                            return null;
                        }
                        if (method.getReturnType() == boolean.class) {
                            return false;
                        }
                        if (method.getReturnType() == int.class) {
                            return 0;
                        }
                        if (method.getReturnType() == double.class) {
                            return 0.0;
                        }
                        return null;
                    }
            );
        }
    }
}
