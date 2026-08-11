package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.nbt.CompoundTag;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Assertion-based executable for single-use reconstruction transaction semantics. */
public final class SubLevelReconstructionTransactionSelfTest {
    private SubLevelReconstructionTransactionSelfTest() {
    }

    public static void main(final String[] args) {
        rollbackRunsEveryActionInReverseOrder();
        successfulRollbackIsTerminal();
        commitRunsVerificationAndDiscardsRollbackActions();
        commitSealRunsAfterAllVerification();
        commitVerificationFailureLeavesRollbackAvailable();
        verificationFailureSkipsCommitSeal();
        commitSealFailureLeavesRollbackAvailable();
        rollbackDiscardsCommitSealWithoutRunningIt();
        allCommitVerificationsRunAndReportFailures();
        invalidStateTransitionsAreRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_TRANSACTION_SELF_TEST: PASS");
    }

    private static void rollbackRunsEveryActionInReverseOrder() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final List<String> order = new ArrayList<>();
        transaction.beginMaterialization();
        transaction.registerRollback("first", () -> order.add("first"));
        transaction.registerRollback("middle", () -> {
            order.add("middle");
            throw new IllegalStateException("injected cleanup failure");
        });
        transaction.registerRollback("last", () -> order.add("last"));

        final RuntimeException trigger = new RuntimeException("materialization failed");
        final SubLevelReconstructionTransaction.RollbackReport report = transaction.rollback(trigger);

        assert order.equals(List.of("last", "middle", "first"));
        assert report.state() == SubLevelReconstructionTransaction.State.ROLLBACK_FAILED;
        assert !report.successful();
        assert report.trigger() == trigger;
        assert report.cleanupFailures().size() == 1;
        assert report.cleanupFailures().getFirst().label().equals("middle");
        assert transaction.pendingRollbackCount() == 0;
        assert transaction.pendingCommitVerificationCount() == 0;
        assert transaction.pendingCommitSealCount() == 0;
        assert transaction.state() == SubLevelReconstructionTransaction.State.ROLLBACK_FAILED;
    }

    private static void successfulRollbackIsTerminal() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger cleanups = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("one", cleanups::incrementAndGet);
        transaction.registerRollback("two", cleanups::incrementAndGet);
        transaction.registerCommitVerification("unused", () -> {
        });
        transaction.registerCommitSeal("unused-seal", () -> {
        });

        final SubLevelReconstructionTransaction.RollbackReport report =
                transaction.rollback(new RuntimeException("injected failure"));

        assert report.successful();
        assert report.cleanupFailures().isEmpty();
        assert cleanups.get() == 2;
        assert transaction.pendingCommitVerificationCount() == 0;
        assert transaction.pendingCommitSealCount() == 0;
        assert transaction.state() == SubLevelReconstructionTransaction.State.ROLLED_BACK;
        assertThrows(() -> transaction.rollback(new RuntimeException("second rollback")));
        assertThrows(transaction::commit);
    }

    private static void commitRunsVerificationAndDiscardsRollbackActions() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger cleanups = new AtomicInteger();
        final List<String> verificationOrder = new ArrayList<>();
        transaction.beginMaterialization();
        transaction.registerRollback("should-not-run", cleanups::incrementAndGet);
        transaction.registerCommitVerification("first", () -> verificationOrder.add("first"));
        transaction.registerCommitVerification("second", () -> verificationOrder.add("second"));
        assert transaction.pendingRollbackCount() == 1;
        assert transaction.pendingCommitVerificationCount() == 2;

        transaction.commit();

        assert verificationOrder.equals(List.of("first", "second"));
        assert cleanups.get() == 0;
        assert transaction.pendingRollbackCount() == 0;
        assert transaction.pendingCommitVerificationCount() == 0;
        assert transaction.pendingCommitSealCount() == 0;
        assert transaction.state() == SubLevelReconstructionTransaction.State.COMMITTED;
        assertThrows(transaction::commit);
        assertThrows(() -> transaction.rollback(new RuntimeException("after commit")));
    }

    private static void commitSealRunsAfterAllVerification() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final List<String> order = new ArrayList<>();
        transaction.beginMaterialization();
        transaction.registerCommitVerification("first", () -> order.add("verify-first"));
        transaction.registerCommitVerification("second", () -> order.add("verify-second"));
        transaction.registerCommitSeal("runtime-id", () -> order.add("seal"));

        transaction.commit();

        assert order.equals(List.of("verify-first", "verify-second", "seal"));
        assert transaction.state() == SubLevelReconstructionTransaction.State.COMMITTED;
        assert transaction.pendingCommitSealCount() == 0;
    }

    private static void commitVerificationFailureLeavesRollbackAvailable() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger cleanups = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("resource", cleanups::incrementAndGet);
        transaction.registerCommitVerification("resource", () -> {
            throw new IllegalStateException("ownership drift");
        });

        SubLevelReconstructionTransaction.CommitVerificationException thrown = null;
        try {
            transaction.commit();
        } catch (final SubLevelReconstructionTransaction.CommitVerificationException exception) {
            thrown = exception;
        }

        assert thrown != null;
        assert thrown.failures().size() == 1;
        assert thrown.failures().getFirst().label().equals("resource");
        assert transaction.state() == SubLevelReconstructionTransaction.State.MATERIALIZING;
        assert transaction.pendingRollbackCount() == 1;
        assert transaction.pendingCommitVerificationCount() == 1;

        final SubLevelReconstructionTransaction.RollbackReport rollback = transaction.rollback(thrown);
        assert rollback.successful();
        assert cleanups.get() == 1;
    }

    private static void verificationFailureSkipsCommitSeal() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger seals = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("resource", () -> {
        });
        transaction.registerCommitVerification("broken", () -> {
            throw new IllegalStateException("verification failed");
        });
        transaction.registerCommitSeal("must-not-run", seals::incrementAndGet);

        SubLevelReconstructionTransaction.CommitVerificationException thrown = null;
        try {
            transaction.commit();
        } catch (final SubLevelReconstructionTransaction.CommitVerificationException exception) {
            thrown = exception;
        }

        assert thrown != null;
        assert seals.get() == 0;
        assert transaction.pendingCommitSealCount() == 1;
        assert transaction.rollback(thrown).successful();
    }

    private static void commitSealFailureLeavesRollbackAvailable() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger cleanups = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("resource", cleanups::incrementAndGet);
        transaction.registerCommitVerification("resource", () -> {
        });
        transaction.registerCommitSeal("runtime-id", () -> {
            throw new IllegalStateException("atomic seal rejected");
        });

        SubLevelReconstructionTransaction.CommitSealException thrown = null;
        try {
            transaction.commit();
        } catch (final SubLevelReconstructionTransaction.CommitSealException exception) {
            thrown = exception;
        }

        assert thrown != null;
        assert thrown.label().equals("runtime-id");
        assert transaction.state() == SubLevelReconstructionTransaction.State.MATERIALIZING;
        assert transaction.pendingRollbackCount() == 1;
        assert transaction.pendingCommitVerificationCount() == 1;
        assert transaction.pendingCommitSealCount() == 1;

        final SubLevelReconstructionTransaction.RollbackReport rollback = transaction.rollback(thrown);
        assert rollback.successful();
        assert cleanups.get() == 1;
        assert transaction.pendingCommitSealCount() == 0;
    }

    private static void rollbackDiscardsCommitSealWithoutRunningIt() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger seals = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("resource", () -> {
        });
        transaction.registerCommitSeal("unused", seals::incrementAndGet);

        final SubLevelReconstructionTransaction.RollbackReport report =
                transaction.rollback(new IllegalStateException("materialization failed"));

        assert report.successful();
        assert seals.get() == 0;
        assert transaction.pendingCommitSealCount() == 0;
    }

    private static void allCommitVerificationsRunAndReportFailures() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final List<String> order = new ArrayList<>();
        transaction.beginMaterialization();
        transaction.registerCommitVerification("first", () -> {
            order.add("first");
            throw new IllegalStateException("first failure");
        });
        transaction.registerCommitVerification("middle", () -> order.add("middle"));
        transaction.registerCommitVerification("last", () -> {
            order.add("last");
            throw new IllegalArgumentException("last failure");
        });

        SubLevelReconstructionTransaction.CommitVerificationException thrown = null;
        try {
            transaction.commit();
        } catch (final SubLevelReconstructionTransaction.CommitVerificationException exception) {
            thrown = exception;
        }

        assert thrown != null;
        assert order.equals(List.of("first", "middle", "last"));
        assert thrown.failures().size() == 2;
        assert thrown.failures().get(0).label().equals("first");
        assert thrown.failures().get(1).label().equals("last");
        assert transaction.state() == SubLevelReconstructionTransaction.State.MATERIALIZING;
        assert transaction.rollback(thrown).successful();
    }

    private static void invalidStateTransitionsAreRejected() {
        final SubLevelReconstructionTransaction transaction = transaction();
        assertThrows(() -> transaction.registerRollback("too-early", () -> {
        }));
        assertThrows(() -> transaction.registerCommitVerification("too-early", () -> {
        }));
        assertThrows(() -> transaction.registerCommitSeal("too-early", () -> {
        }));
        assertThrows(transaction::commit);
        assertThrows(() -> transaction.rollback(new RuntimeException("too-early")));

        transaction.beginMaterialization();
        assertThrows(transaction::beginMaterialization);
        transaction.registerCommitSeal("only", () -> {
        });
        assertThrows(() -> transaction.registerCommitSeal("duplicate", () -> {
        }));
    }

    private static SubLevelReconstructionTransaction transaction() {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        final SubLevelData data = new SubLevelData(
                uuid,
                new BoundingBox3d(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                new Pose3d(
                        new Vector3d(1.0, 2.0, 3.0),
                        new Quaterniond(),
                        new Vector3d(0.5, 0.5, 0.5),
                        new Vector3d(1.0)
                ),
                List.of(),
                tag
        );
        final SubLevelReconstructionPlan plan = SubLevelReconstructionPlan.freezeAccepted(
                data,
                new SubLevelReconstructionPreflight.TargetSlot(0, 0)
        );
        return new SubLevelReconstructionTransaction(plan);
    }

    private static void assertThrows(final Runnable operation) {
        boolean threw = false;
        try {
            operation.run();
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        assert threw;
    }
}
