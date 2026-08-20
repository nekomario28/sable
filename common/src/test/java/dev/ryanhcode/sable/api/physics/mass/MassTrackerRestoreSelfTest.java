package dev.ryanhcode.sable.api.physics.mass;

import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class MassTrackerRestoreSelfTest {
    private MassTrackerRestoreSelfTest() {
    }

    public static void main(final String[] args) {
        restoresExactAuthoritativeValues();
        copiesCallerOwnedObjects();
        rejectsInverseMassMismatch();
        rejectsInverseInertiaMismatch();
        rejectsNonFiniteState();
        System.out.println("MASS_TRACKER_RESTORE_SELF_TEST: PASS");
    }

    private static void restoresExactAuthoritativeValues() {
        final Matrix3d inertia = diagonal(2.0, 4.0, 8.0);
        final Matrix3d inverse = new Matrix3d(inertia).invert();
        final Vector3d center = new Vector3d(1.25, -2.5, 3.75);
        final MassTracker tracker = MassTracker.restore(massData(5.0, 0.2, center, inertia, inverse));

        assert tracker.getMass() == 5.0;
        assert tracker.getInverseMass() == 0.2;
        assert tracker.getCenterOfMass().equals(center, 0.0);
        assert tracker.getInertiaTensor().equals(inertia, 0.0);
        assert tracker.getInverseInertiaTensor().equals(inverse, 0.0);
    }

    private static void copiesCallerOwnedObjects() {
        final Matrix3d inertia = diagonal(3.0, 5.0, 7.0);
        final Matrix3d inverse = new Matrix3d(inertia).invert();
        final Vector3d center = new Vector3d(0.5, 1.5, 2.5);
        final MassTracker tracker = MassTracker.restore(massData(4.0, 0.25, center, inertia, inverse));

        center.set(99.0);
        inertia.zero();
        inverse.zero();

        assert !tracker.getCenterOfMass().equals(center, 0.0);
        assert tracker.getInertiaTensor().m00() == 3.0;
        assert Math.abs(tracker.getInverseInertiaTensor().m00() - 1.0 / 3.0) < 1.0E-15;
    }

    private static void rejectsInverseMassMismatch() {
        final Matrix3d inertia = diagonal(2.0, 3.0, 4.0);
        assertThrows(() -> MassTracker.restore(massData(
                4.0,
                0.5,
                new Vector3d(),
                inertia,
                new Matrix3d(inertia).invert()
        )));
    }

    private static void rejectsInverseInertiaMismatch() {
        final Matrix3d inertia = diagonal(2.0, 3.0, 4.0);
        assertThrows(() -> MassTracker.restore(massData(
                4.0,
                0.25,
                new Vector3d(),
                inertia,
                new Matrix3d().identity()
        )));
    }

    private static void rejectsNonFiniteState() {
        final Matrix3d inertia = diagonal(2.0, 3.0, 4.0);
        assertThrows(() -> MassTracker.restore(massData(
                4.0,
                0.25,
                new Vector3d(Double.NaN, 0.0, 0.0),
                inertia,
                new Matrix3d(inertia).invert()
        )));
    }

    private static Matrix3d diagonal(final double x, final double y, final double z) {
        return new Matrix3d(
                x, 0.0, 0.0,
                0.0, y, 0.0,
                0.0, 0.0, z
        );
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

    private static void assertThrows(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (final IllegalArgumentException expected) {
            // expected
        }
    }
}
