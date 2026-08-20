package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelReconstructionMassSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.List;
import java.util.UUID;

public final class SubLevelReconstructionMassPreflightSelfTest {
    private SubLevelReconstructionMassPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        validSnapshotIsFrozen();
        missingSnapshotIsRejected();
        invalidSnapshotIsRejectedWithTypedEvidence();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_MASS_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void validSnapshotIsFrozen() {
        final CompoundTag tag = new CompoundTag();
        tag.put("reconstruction_self_mass", validMassSnapshot().toTag());
        final SubLevelReconstructionMassPreflight.Result result =
                SubLevelReconstructionMassPreflight.validate(plan(tag));

        assert result.accepted() : result.failures();
        final SubLevelReconstructionMassSnapshot snapshot = result.snapshot().orElseThrow();
        assert snapshot.getMass() == 4.0;
        assert snapshot.getInverseMass() == 0.25;
    }

    private static void missingSnapshotIsRejected() {
        final SubLevelReconstructionMassPreflight.Result result =
                SubLevelReconstructionMassPreflight.validate(plan(new CompoundTag()));
        assert !result.accepted();
        assert result.failures().contains(SubLevelReconstructionMassSnapshot.Failure.MISSING_TAG);
        assert result.snapshot().isEmpty();
    }

    private static void invalidSnapshotIsRejectedWithTypedEvidence() {
        final CompoundTag massTag = validMassSnapshot().toTag();
        massTag.putDouble("inverse_mass", 0.5);
        final CompoundTag tag = new CompoundTag();
        tag.put("reconstruction_self_mass", massTag);
        final SubLevelReconstructionMassPreflight.Result result =
                SubLevelReconstructionMassPreflight.validate(plan(tag));

        assert !result.accepted();
        assert result.failures().contains(SubLevelReconstructionMassSnapshot.Failure.INVERSE_MASS_MISMATCH);
        assert result.snapshot().isEmpty();
    }

    private static SubLevelReconstructionPlan plan(final CompoundTag tag) {
        final UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        return SubLevelReconstructionPlan.freezeAccepted(
                new SubLevelData(
                        uuid,
                        new BoundingBox3d(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                        new Pose3d(),
                        List.of(),
                        tag
                ),
                new SubLevelReconstructionPreflight.TargetSlot(1, 2)
        );
    }

    private static SubLevelReconstructionMassSnapshot validMassSnapshot() {
        final Matrix3d inertia = new Matrix3d(
                2.0, 0.0, 0.0,
                0.0, 3.0, 0.0,
                0.0, 0.0, 4.0
        );
        final Matrix3d inverse = new Matrix3d(inertia).invert();
        return SubLevelReconstructionMassSnapshot.capture(new MassData() {
            @Override
            public double getMass() {
                return 4.0;
            }

            @Override
            public double getInverseMass() {
                return 0.25;
            }

            @Override
            public Matrix3dc getInertiaTensor() {
                return inertia;
            }

            @Override
            public Matrix3dc getInverseInertiaTensor() {
                return inverse;
            }

            @Override
            public Vector3dc getCenterOfMass() {
                return new Vector3d(0.5, 0.5, 0.5);
            }
        });
    }
}
