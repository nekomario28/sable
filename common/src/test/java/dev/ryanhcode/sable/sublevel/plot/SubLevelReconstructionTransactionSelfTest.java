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
        commitDiscardsRollbackActionsAndIsTerminal();
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
        assert transaction.state() == SubLevelReconstructionTransaction.State.ROLLBACK_FAILED;
    }

    private static void successfulRollbackIsTerminal() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger cleanups = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("one", cleanups::incrementAndGet);
        transaction.registerRollback("two", cleanups::incrementAndGet);

        final SubLevelReconstructionTransaction.RollbackReport report =
                transaction.rollback(new RuntimeException("injected failure"));

        assert report.successful();
        assert report.cleanupFailures().isEmpty();
        assert cleanups.get() == 2;
        assert transaction.state() == SubLevelReconstructionTransaction.State.ROLLED_BACK;
        assertThrows(() -> transaction.rollback(new RuntimeException("second rollback")));
        assertThrows(transaction::commit);
    }

    private static void commitDiscardsRollbackActionsAndIsTerminal() {
        final SubLevelReconstructionTransaction transaction = transaction();
        final AtomicInteger cleanups = new AtomicInteger();
        transaction.beginMaterialization();
        transaction.registerRollback("should-not-run", cleanups::incrementAndGet);
        assert transaction.pendingRollbackCount() == 1;

        transaction.commit();

        assert cleanups.get() == 0;
        assert transaction.pendingRollbackCount() == 0;
        assert transaction.state() == SubLevelReconstructionTransaction.State.COMMITTED;
        assertThrows(transaction::commit);
        assertThrows(() -> transaction.rollback(new RuntimeException("after commit")));
    }

    private static void invalidStateTransitionsAreRejected() {
        final SubLevelReconstructionTransaction transaction = transaction();
        assertThrows(() -> transaction.registerRollback("too-early", () -> {
        }));
        assertThrows(transaction::commit);
        assertThrows(() -> transaction.rollback(new RuntimeException("too-early")));

        transaction.beginMaterialization();
        assertThrows(transaction::beginMaterialization);
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
