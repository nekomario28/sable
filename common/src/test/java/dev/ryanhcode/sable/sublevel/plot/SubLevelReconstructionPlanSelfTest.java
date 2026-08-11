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

/** Assertion-based executable for immutable reconstruction planning. */
public final class SubLevelReconstructionPlanSelfTest {
    private SubLevelReconstructionPlanSelfTest() {
    }

    public static void main(final String[] args) {
        sourceMutationDoesNotChangePlan();
        exportedCopiesDoNotChangePlan();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_PLAN_SELF_TEST: PASS");
    }

    private static void sourceMutationDoesNotChangePlan() {
        final UUID uuid = UUID.randomUUID();
        final UUID dependency = UUID.randomUUID();
        final Pose3d sourcePose = pose(1.0, 2.0, 3.0);
        final BoundingBox3d sourceBounds = new BoundingBox3d(1.0, 2.0, 3.0, 4.0, 5.0, 6.0);
        final List<UUID> sourceDependencies = new ArrayList<>(List.of(dependency));
        final CompoundTag sourceTag = new CompoundTag();
        sourceTag.putUUID("uuid", uuid);
        sourceTag.putString("marker", "original");
        final CompoundTag nested = new CompoundTag();
        nested.putInt("value", 7);
        sourceTag.put("nested", nested);

        final SubLevelData source = new SubLevelData(
                uuid,
                sourceBounds,
                sourcePose,
                sourceDependencies,
                sourceTag
        );
        final SubLevelReconstructionPlan plan = SubLevelReconstructionPlan.freezeAccepted(
                source,
                new SubLevelReconstructionPreflight.TargetSlot(4, 5)
        );

        sourcePose.position().set(100.0, 200.0, 300.0);
        sourceDependencies.add(UUID.randomUUID());
        sourceTag.putString("marker", "mutated");
        sourceTag.getCompound("nested").putInt("value", 99);

        assert plan.uuid().equals(uuid);
        assert plan.targetSlot().equals(new SubLevelReconstructionPreflight.TargetSlot(4, 5));
        assert plan.pose().position().equals(1.0, 2.0, 3.0);
        assert plan.dependencies().equals(List.of(dependency));
        assert plan.fullTag().getString("marker").equals("original");
        assert plan.fullTag().getCompound("nested").getInt("value") == 7;
    }

    private static void exportedCopiesDoNotChangePlan() {
        final UUID uuid = UUID.randomUUID();
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);
        tag.putString("marker", "frozen");
        final SubLevelData source = new SubLevelData(
                uuid,
                new BoundingBox3d(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                pose(8.0, 9.0, 10.0),
                List.of(),
                tag
        );
        final SubLevelReconstructionPlan plan = SubLevelReconstructionPlan.freezeAccepted(
                source,
                new SubLevelReconstructionPreflight.TargetSlot(1, 2)
        );

        final Pose3d exportedPose = plan.pose();
        exportedPose.position().zero();
        final CompoundTag exportedTag = plan.fullTag();
        exportedTag.putString("marker", "changed");
        final SubLevelData exportedData = plan.toData();
        exportedData.pose().position().set(-1.0, -1.0, -1.0);
        exportedData.fullTag().putString("marker", "changed-again");

        assert plan.pose().position().equals(8.0, 9.0, 10.0);
        assert plan.fullTag().getString("marker").equals("frozen");
        assert plan.toData().pose().position().equals(8.0, 9.0, 10.0);
        assert plan.toData().fullTag().getString("marker").equals("frozen");
    }

    private static Pose3d pose(final double x, final double y, final double z) {
        return new Pose3d(
                new Vector3d(x, y, z),
                new Quaterniond(),
                new Vector3d(0.5, 0.5, 0.5),
                new Vector3d(1.0)
        );
    }
}
