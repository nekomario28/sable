package dev.ryanhcode.sable.sublevel.storage.serialization;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import net.minecraft.nbt.CompoundTag;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class SubLevelReconstructionMassSnapshotSelfTest {
    private SubLevelReconstructionMassSnapshotSelfTest() {
    }

    public static void main(final String[] args) {
        roundTripPreservesAuthoritativeMassState();
        accessorsAreDefensive();
        incompleteVectorEncodingIsRejected();
        incompleteMatrixEncodingIsRejected();
        inverseMassMismatchIsRejected();
        inverseInertiaMismatchIsRejected();
        nonFiniteStateIsRejected();
        unsupportedVersionIsRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_MASS_SNAPSHOT_SELF_TEST: PASS");
    }

    private static void roundTripPreservesAuthoritativeMassState() {
        final Matrix3d inertia = new Matrix3d(
                2.0, 0.0, 0.0,
                0.0, 4.0, 0.0,
                0.0, 0.0, 8.0
        );
        final Matrix3d inverse = new Matrix3d(inertia).invert();
        final MassData source = massData(5.0, 0.2, new Vector3d(1.25, -3.5, 7.75), inertia, inverse);
        final SubLevelReconstructionMassSnapshot captured = SubLevelReconstructionMassSnapshot.capture(source);
        final CompoundTag parent = new CompoundTag();
        parent.put("self_mass", captured.toTag());

        final SubLevelReconstructionMassSnapshot.Decode decoded =
                SubLevelReconstructionMassSnapshot.decode(parent, "self_mass");
        assert decoded.accepted() : decoded.failures();
        final SubLevelReconstructionMassSnapshot restored = decoded.snapshot().orElseThrow();
        assert restored.getMass() == source.getMass();
        assert restored.getInverseMass() == source.getInverseMass();
        assert restored.getCenterOfMass().equals(source.getCenterOfMass(), 0.0);
        assert restored.getInertiaTensor().equals(source.getInertiaTensor(), 0.0);
        assert restored.getInverseInertiaTensor().equals(source.getInverseInertiaTensor(), 0.0);
    }

    private static void accessorsAreDefensive() {
        final SubLevelReconstructionMassSnapshot snapshot = validSnapshot();
        final Vector3d center = (Vector3d) snapshot.getCenterOfMass();
        final Matrix3d inertia = (Matrix3d) snapshot.getInertiaTensor();
        final Matrix3d inverse = (Matrix3d) snapshot.getInverseInertiaTensor();
        center.set(99.0);
        inertia.zero();
        inverse.zero();
        assert !snapshot.getCenterOfMass().equals(center, 0.0);
        assert snapshot.getInertiaTensor().m00() != 0.0;
        assert snapshot.getInverseInertiaTensor().m00() != 0.0;
    }

    private static void incompleteVectorEncodingIsRejected() {
        final CompoundTag tag = validSnapshot().toTag();
        tag.getCompound("center_of_mass").remove("z");
        final SubLevelReconstructionMassSnapshot.Decode decoded = SubLevelReconstructionMassSnapshot.decodeTag(tag);
        assert !decoded.accepted();
        assert decoded.failures().contains(
                SubLevelReconstructionMassSnapshot.Failure.INVALID_CENTER_OF_MASS_ENCODING
        );
    }

    private static void incompleteMatrixEncodingIsRejected() {
        final CompoundTag tag = validSnapshot().toTag();
        tag.getCompound("inertia").remove("m12");
        final SubLevelReconstructionMassSnapshot.Decode decoded = SubLevelReconstructionMassSnapshot.decodeTag(tag);
        assert !decoded.accepted();
        assert decoded.failures().contains(
                SubLevelReconstructionMassSnapshot.Failure.INVALID_INERTIA_TENSOR_ENCODING
        );
    }

    private static void inverseMassMismatchIsRejected() {
        final CompoundTag tag = validSnapshot().toTag();
        tag.putDouble("inverse_mass", 0.25);
        final SubLevelReconstructionMassSnapshot.Decode decoded = SubLevelReconstructionMassSnapshot.decodeTag(tag);
        assert !decoded.accepted();
        assert decoded.failures().contains(SubLevelReconstructionMassSnapshot.Failure.INVERSE_MASS_MISMATCH);
    }

    private static void inverseInertiaMismatchIsRejected() {
        final CompoundTag tag = validSnapshot().toTag();
        tag.getCompound("inverse_inertia").putDouble("m00", 100.0);
        final SubLevelReconstructionMassSnapshot.Decode decoded = SubLevelReconstructionMassSnapshot.decodeTag(tag);
        assert !decoded.accepted();
        assert decoded.failures().contains(SubLevelReconstructionMassSnapshot.Failure.INVERSE_INERTIA_MISMATCH);
    }

    private static void nonFiniteStateIsRejected() {
        final CompoundTag tag = validSnapshot().toTag();
        tag.getCompound("center_of_mass").putDouble("x", Double.NaN);
        final SubLevelReconstructionMassSnapshot.Decode decoded = SubLevelReconstructionMassSnapshot.decodeTag(tag);
        assert !decoded.accepted();
        assert decoded.failures().contains(SubLevelReconstructionMassSnapshot.Failure.NON_FINITE_CENTER_OF_MASS);
    }

    private static void unsupportedVersionIsRejected() {
        final CompoundTag tag = validSnapshot().toTag();
        tag.putInt("version", SubLevelReconstructionMassSnapshot.FORMAT_VERSION + 1);
        final SubLevelReconstructionMassSnapshot.Decode decoded = SubLevelReconstructionMassSnapshot.decodeTag(tag);
        assert !decoded.accepted();
        assert decoded.failures().contains(SubLevelReconstructionMassSnapshot.Failure.UNSUPPORTED_VERSION);
    }

    private static SubLevelReconstructionMassSnapshot validSnapshot() {
        final Matrix3d inertia = new Matrix3d(
                2.0, 0.0, 0.0,
                0.0, 3.0, 0.0,
                0.0, 0.0, 4.0
        );
        return SubLevelReconstructionMassSnapshot.capture(massData(
                4.0,
                0.25,
                new Vector3d(0.5, 1.5, -2.0),
                inertia,
                new Matrix3d(inertia).invert()
        ));
    }

    private static MassData massData(
            final double mass,
            final double inverseMass,
            final Vector3dc center,
            final Matrix3dc inertia,
            final Matrix3dc inverseInertia
    ) {
        return new MassData() {
            @Override
            public double getMass() {
                return mass;
            }

            @Override
            public double getInverseMass() {
                return inverseMass;
            }

            @Override
            public Matrix3dc getInertiaTensor() {
                return inertia;
            }

            @Override
            public Matrix3dc getInverseInertiaTensor() {
                return inverseInertia;
            }

            @Override
            public Vector3dc getCenterOfMass() {
                return center;
            }
        };
    }
}
