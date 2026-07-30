package dev.ryanhcode.sable.sublevel.plot;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/** Assertion-based executable for deterministic reconstruction preflight. */
public final class SubLevelReconstructionPreflightSelfTest {
    private static final int LOG_SIDE_LENGTH = 3;
    private static final int LOG_PLOT_SIZE = 2;
    private static final int SECTION_COUNT = 24;

    private SubLevelReconstructionPreflightSelfTest() {
    }

    public static void main(final String[] args) {
        validSnapshotIsAccepted();
        nonFinitePoseAndVelocityAreRejected();
        invalidTargetAndPlotMetadataAreRejected();
        invalidChunkAndSectionCoordinatesAreRejected();
        invalidUuidDependencyAndBoundsAreRejected();
        emptyPlotIsRejected();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_PREFLIGHT_SELF_TEST: PASS");
    }

    private static void validSnapshotIsAccepted() {
        final SubLevelReconstructionPreflight.Result result = validate(data(validTag(UUID.randomUUID())));
        assert result.accepted();
        assert result.targetSlot().orElseThrow().equals(
                new SubLevelReconstructionPreflight.TargetSlot(1, 2)
        );
    }

    private static void nonFinitePoseAndVelocityAreRejected() {
        final CompoundTag tag = validTag(UUID.randomUUID());
        tag.getCompound("pose").getCompound("position").putDouble("x", Double.NaN);
        final CompoundTag velocity = new CompoundTag();
        velocity.putDouble("x", Double.POSITIVE_INFINITY);
        velocity.putDouble("y", 0.0);
        velocity.putDouble("z", 0.0);
        tag.put("linear_velocity", velocity);
        final SubLevelReconstructionPreflight.Result result = validate(data(tag));
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.INVALID_POSE);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.INVALID_VELOCITY);
    }

    private static void invalidTargetAndPlotMetadataAreRejected() {
        final CompoundTag tag = validTag(UUID.randomUUID());
        final CompoundTag plot = tag.getCompound("plot");
        plot.putInt("plot_x", 1 << LOG_SIDE_LENGTH);
        plot.putInt("log_size", LOG_PLOT_SIZE + 1);
        plot.putInt("data_version", Integer.MAX_VALUE);
        final SubLevelReconstructionPreflight.Result result = validate(data(tag));
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.INVALID_TARGET_SLOT);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.PLOT_SIZE_MISMATCH);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.UNSUPPORTED_PLOT_VERSION);
        assert result.targetSlot().isEmpty();
    }

    private static void invalidChunkAndSectionCoordinatesAreRejected() {
        final CompoundTag tag = validTag(UUID.randomUUID());
        final CompoundTag chunks = tag.getCompound("plot").getCompound("chunks");
        final CompoundTag chunk = chunks.getCompound(String.valueOf(ChunkPos.asLong(0, 0)));
        chunks.remove(String.valueOf(ChunkPos.asLong(0, 0)));
        chunk.getCompound("sections").put(String.valueOf(SECTION_COUNT), new CompoundTag());
        chunks.put(String.valueOf(ChunkPos.asLong(1 << LOG_PLOT_SIZE, 0)), chunk);
        final SubLevelReconstructionPreflight.Result result = validate(data(tag));
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.CHUNK_OUTSIDE_PLOT);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.INVALID_SECTION_INDEX);
    }

    private static void invalidUuidDependencyAndBoundsAreRejected() {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag tag = validTag(uuid);
        final SubLevelData parsed = data(tag);
        tag.putUUID("uuid", UUID.randomUUID());
        tag.getCompound("world_bounds").putDouble("maxX", Double.NaN);
        final SubLevelData invalid = new SubLevelData(
                parsed.uuid(),
                parsed.bounds(),
                parsed.pose(),
                List.of(parsed.uuid()),
                tag
        );
        final SubLevelReconstructionPreflight.Result result = validate(invalid);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.UUID_MISMATCH);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.INVALID_DEPENDENCIES);
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.INVALID_BOUNDS);
    }

    private static void emptyPlotIsRejected() {
        final CompoundTag tag = validTag(UUID.randomUUID());
        tag.getCompound("plot").put("chunks", new CompoundTag());
        final SubLevelReconstructionPreflight.Result result = validate(data(tag));
        assert result.failures().contains(SubLevelReconstructionPreflight.Failure.EMPTY_PLOT);
    }

    private static SubLevelReconstructionPreflight.Result validate(final SubLevelData data) {
        return SubLevelReconstructionPreflight.validateSerialized(
                data,
                LOG_SIDE_LENGTH,
                LOG_PLOT_SIZE,
                SECTION_COUNT
        );
    }

    private static SubLevelData data(final CompoundTag tag) {
        final SubLevelData data = SubLevelSerializer.fromData(tag);
        assert data != null;
        return data;
    }

    private static CompoundTag validTag(final UUID uuid) {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        tag.put("pose", SableNBTUtils.writePose3d(new Pose3d(
                new Vector3d(10.0, 20.0, 30.0),
                new Quaterniond(),
                new Vector3d(0.5, 0.5, 0.5),
                new Vector3d(1.0)
        )));

        final CompoundTag bounds = new CompoundTag();
        bounds.putDouble("minX", 10.0);
        bounds.putDouble("minY", 20.0);
        bounds.putDouble("minZ", 30.0);
        bounds.putDouble("maxX", 11.0);
        bounds.putDouble("maxY", 21.0);
        bounds.putDouble("maxZ", 31.0);
        tag.put("world_bounds", bounds);

        final CompoundTag plot = new CompoundTag();
        plot.putInt("plot_x", 1);
        plot.putInt("plot_z", 2);
        plot.putInt("log_size", LOG_PLOT_SIZE);
        plot.putInt("data_version", 1);
        final CompoundTag chunk = new CompoundTag();
        chunk.put("sections", new CompoundTag());
        final CompoundTag chunks = new CompoundTag();
        chunks.put(String.valueOf(ChunkPos.asLong(0, 0)), chunk);
        plot.put("chunks", chunks);
        tag.put("plot", plot);
        return tag;
    }
}
