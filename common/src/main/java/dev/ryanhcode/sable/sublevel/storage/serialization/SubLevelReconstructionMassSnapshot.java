package dev.ryanhcode.sable.sublevel.storage.serialization;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.ApiStatus;
import org.joml.Matrix3d;
import org.joml.Matrix3dc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable authoritative self-mass state captured with a serialized SubLevel.
 *
 * <p>Transactional reconstruction must not rebuild this state from the live target world: custom
 * block center-of-mass implementations may observe context, and reconstruction needs the exact
 * source-side mass state that was used when the snapshot was created.</p>
 */
@ApiStatus.Experimental
public final class SubLevelReconstructionMassSnapshot implements MassData {
    public static final int FORMAT_VERSION = 1;
    private static final double CONSISTENCY_EPSILON = 1.0e-9;
    private static final String[] VECTOR_COMPONENTS = {"x", "y", "z"};
    private static final String[] MATRIX_COMPONENTS = {
            "m00", "m01", "m02",
            "m10", "m11", "m12",
            "m20", "m21", "m22"
    };

    private final double mass;
    private final double inverseMass;
    private final Vector3d centerOfMass;
    private final Matrix3d inertiaTensor;
    private final Matrix3d inverseInertiaTensor;

    private SubLevelReconstructionMassSnapshot(
            final double mass,
            final double inverseMass,
            final Vector3dc centerOfMass,
            final Matrix3dc inertiaTensor,
            final Matrix3dc inverseInertiaTensor
    ) {
        this.mass = mass;
        this.inverseMass = inverseMass;
        this.centerOfMass = new Vector3d(Objects.requireNonNull(centerOfMass, "centerOfMass"));
        this.inertiaTensor = new Matrix3d(Objects.requireNonNull(inertiaTensor, "inertiaTensor"));
        this.inverseInertiaTensor = new Matrix3d(Objects.requireNonNull(inverseInertiaTensor, "inverseInertiaTensor"));
    }

    public enum Failure {
        MISSING_TAG,
        UNSUPPORTED_VERSION,
        INVALID_MASS,
        INVALID_INVERSE_MASS,
        MISSING_CENTER_OF_MASS,
        INVALID_CENTER_OF_MASS_ENCODING,
        NON_FINITE_CENTER_OF_MASS,
        MISSING_INERTIA_TENSOR,
        INVALID_INERTIA_TENSOR_ENCODING,
        NON_FINITE_INERTIA_TENSOR,
        MISSING_INVERSE_INERTIA_TENSOR,
        INVALID_INVERSE_INERTIA_TENSOR_ENCODING,
        NON_FINITE_INVERSE_INERTIA_TENSOR,
        INVERSE_MASS_MISMATCH,
        INVERSE_INERTIA_MISMATCH
    }

    public record Decode(Set<Failure> failures, Optional<SubLevelReconstructionMassSnapshot> snapshot) {
        public Decode {
            Objects.requireNonNull(failures, "failures");
            Objects.requireNonNull(snapshot, "snapshot");
            final EnumSet<Failure> copy = EnumSet.noneOf(Failure.class);
            copy.addAll(failures);
            failures = Collections.unmodifiableSet(copy);
            if (failures.isEmpty() != snapshot.isPresent()) {
                throw new IllegalArgumentException("Accepted mass snapshot decode requires a snapshot and rejected decode requires failures");
            }
        }

        public boolean accepted() {
            return this.failures.isEmpty() && this.snapshot.isPresent();
        }
    }

    public static SubLevelReconstructionMassSnapshot capture(final MassData massData) {
        Objects.requireNonNull(massData, "massData");
        final Vector3dc center = Objects.requireNonNull(massData.getCenterOfMass(), "massData.centerOfMass");
        final SubLevelReconstructionMassSnapshot snapshot = new SubLevelReconstructionMassSnapshot(
                massData.getMass(),
                massData.getInverseMass(),
                center,
                massData.getInertiaTensor(),
                massData.getInverseInertiaTensor()
        );
        final EnumSet<Failure> failures = snapshot.validate();
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException("Cannot capture invalid mass state: " + failures);
        }
        return snapshot;
    }

    public CompoundTag toTag() {
        final CompoundTag tag = new CompoundTag();
        tag.putInt("version", FORMAT_VERSION);
        tag.putDouble("mass", this.mass);
        tag.putDouble("inverse_mass", this.inverseMass);
        tag.put("center_of_mass", writeVector(this.centerOfMass));
        tag.put("inertia", writeMatrix(this.inertiaTensor));
        tag.put("inverse_inertia", writeMatrix(this.inverseInertiaTensor));
        return tag;
    }

    public static Decode decode(final CompoundTag parent, final String key) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(key, "key");
        if (!parent.contains(key, Tag.TAG_COMPOUND)) {
            return rejected(Failure.MISSING_TAG);
        }
        return decodeTag(parent.getCompound(key));
    }

    static Decode decodeTag(final CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        final EnumSet<Failure> failures = EnumSet.noneOf(Failure.class);
        if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != FORMAT_VERSION) {
            failures.add(Failure.UNSUPPORTED_VERSION);
        }

        final double mass = tag.getDouble("mass");
        final double inverseMass = tag.getDouble("inverse_mass");
        if (!tag.contains("mass", Tag.TAG_DOUBLE) || !Double.isFinite(mass) || mass <= 0.0) {
            failures.add(Failure.INVALID_MASS);
        }
        if (!tag.contains("inverse_mass", Tag.TAG_DOUBLE)
                || !Double.isFinite(inverseMass) || inverseMass <= 0.0) {
            failures.add(Failure.INVALID_INVERSE_MASS);
        }

        final Vector3d center = readVector(
                tag,
                "center_of_mass",
                Failure.MISSING_CENTER_OF_MASS,
                Failure.INVALID_CENTER_OF_MASS_ENCODING,
                failures
        );
        if (center != null && !finite(center)) {
            failures.add(Failure.NON_FINITE_CENTER_OF_MASS);
        }
        final Matrix3d inertia = readMatrix(
                tag,
                "inertia",
                Failure.MISSING_INERTIA_TENSOR,
                Failure.INVALID_INERTIA_TENSOR_ENCODING,
                failures
        );
        if (inertia != null && !finite(inertia)) {
            failures.add(Failure.NON_FINITE_INERTIA_TENSOR);
        }
        final Matrix3d inverseInertia = readMatrix(
                tag,
                "inverse_inertia",
                Failure.MISSING_INVERSE_INERTIA_TENSOR,
                Failure.INVALID_INVERSE_INERTIA_TENSOR_ENCODING,
                failures
        );
        if (inverseInertia != null && !finite(inverseInertia)) {
            failures.add(Failure.NON_FINITE_INVERSE_INERTIA_TENSOR);
        }

        if (tag.contains("mass", Tag.TAG_DOUBLE) && tag.contains("inverse_mass", Tag.TAG_DOUBLE)
                && Double.isFinite(mass) && mass > 0.0
                && Double.isFinite(inverseMass) && inverseMass > 0.0
                && !approximatelyOne(mass * inverseMass)) {
            failures.add(Failure.INVERSE_MASS_MISMATCH);
        }
        if (inertia != null && inverseInertia != null && finite(inertia) && finite(inverseInertia)
                && !approximatelyIdentity(new Matrix3d(inertia).mul(inverseInertia))) {
            failures.add(Failure.INVERSE_INERTIA_MISMATCH);
        }

        if (!failures.isEmpty()) {
            return new Decode(failures, Optional.empty());
        }
        return new Decode(
                Set.of(),
                Optional.of(new SubLevelReconstructionMassSnapshot(
                        mass,
                        inverseMass,
                        Objects.requireNonNull(center),
                        Objects.requireNonNull(inertia),
                        Objects.requireNonNull(inverseInertia)
                ))
        );
    }

    private EnumSet<Failure> validate() {
        final CompoundTag holder = new CompoundTag();
        holder.put("mass", this.toTag());
        final EnumSet<Failure> result = EnumSet.noneOf(Failure.class);
        result.addAll(decode(holder, "mass").failures());
        return result;
    }

    private static Decode rejected(final Failure failure) {
        return new Decode(EnumSet.of(failure), Optional.empty());
    }

    private static CompoundTag writeVector(final Vector3dc value) {
        final CompoundTag tag = new CompoundTag();
        tag.putDouble("x", value.x());
        tag.putDouble("y", value.y());
        tag.putDouble("z", value.z());
        return tag;
    }

    private static Vector3d readVector(
            final CompoundTag parent,
            final String key,
            final Failure missingFailure,
            final Failure invalidEncodingFailure,
            final EnumSet<Failure> failures
    ) {
        if (!parent.contains(key, Tag.TAG_COMPOUND)) {
            failures.add(missingFailure);
            return null;
        }
        final CompoundTag tag = parent.getCompound(key);
        if (!containsDoubles(tag, VECTOR_COMPONENTS)) {
            failures.add(invalidEncodingFailure);
            return null;
        }
        return new Vector3d(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
    }

    private static CompoundTag writeMatrix(final Matrix3dc value) {
        final CompoundTag tag = new CompoundTag();
        tag.putDouble("m00", value.m00());
        tag.putDouble("m01", value.m01());
        tag.putDouble("m02", value.m02());
        tag.putDouble("m10", value.m10());
        tag.putDouble("m11", value.m11());
        tag.putDouble("m12", value.m12());
        tag.putDouble("m20", value.m20());
        tag.putDouble("m21", value.m21());
        tag.putDouble("m22", value.m22());
        return tag;
    }

    private static Matrix3d readMatrix(
            final CompoundTag parent,
            final String key,
            final Failure missingFailure,
            final Failure invalidEncodingFailure,
            final EnumSet<Failure> failures
    ) {
        if (!parent.contains(key, Tag.TAG_COMPOUND)) {
            failures.add(missingFailure);
            return null;
        }
        final CompoundTag tag = parent.getCompound(key);
        if (!containsDoubles(tag, MATRIX_COMPONENTS)) {
            failures.add(invalidEncodingFailure);
            return null;
        }
        return new Matrix3d(
                tag.getDouble("m00"), tag.getDouble("m01"), tag.getDouble("m02"),
                tag.getDouble("m10"), tag.getDouble("m11"), tag.getDouble("m12"),
                tag.getDouble("m20"), tag.getDouble("m21"), tag.getDouble("m22")
        );
    }

    private static boolean containsDoubles(final CompoundTag tag, final String[] keys) {
        for (final String key : keys) {
            if (!tag.contains(key, Tag.TAG_DOUBLE)) {
                return false;
            }
        }
        return true;
    }

    private static boolean finite(final Vector3dc value) {
        return Double.isFinite(value.x()) && Double.isFinite(value.y()) && Double.isFinite(value.z());
    }

    private static boolean finite(final Matrix3dc value) {
        return Double.isFinite(value.m00()) && Double.isFinite(value.m01()) && Double.isFinite(value.m02())
                && Double.isFinite(value.m10()) && Double.isFinite(value.m11()) && Double.isFinite(value.m12())
                && Double.isFinite(value.m20()) && Double.isFinite(value.m21()) && Double.isFinite(value.m22());
    }

    private static boolean approximatelyOne(final double value) {
        return Math.abs(value - 1.0) <= CONSISTENCY_EPSILON;
    }

    private static boolean approximatelyIdentity(final Matrix3dc matrix) {
        return Math.abs(matrix.m00() - 1.0) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m11() - 1.0) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m22() - 1.0) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m01()) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m02()) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m10()) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m12()) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m20()) <= CONSISTENCY_EPSILON
                && Math.abs(matrix.m21()) <= CONSISTENCY_EPSILON;
    }

    @Override
    public double getMass() {
        return this.mass;
    }

    @Override
    public double getInverseMass() {
        return this.inverseMass;
    }

    @Override
    public Matrix3dc getInertiaTensor() {
        return new Matrix3d(this.inertiaTensor);
    }

    @Override
    public Matrix3dc getInverseInertiaTensor() {
        return new Matrix3d(this.inverseInertiaTensor);
    }

    @Override
    public Vector3dc getCenterOfMass() {
        return new Vector3d(this.centerOfMass);
    }
}
