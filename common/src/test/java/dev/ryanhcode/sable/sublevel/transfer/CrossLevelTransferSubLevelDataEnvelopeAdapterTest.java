package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CrossLevelTransferSubLevelDataEnvelopeAdapterTest {
    @Test
    void matchingDependencyFreeDataCreatesVerifiedEnvelope() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CompoundTag tag = serializedTag(expected.subLevelId());
        final SubLevelData data = data(expected.subLevelId(), List.of(), tag);

        final CrossLevelTransferSnapshotEnvelope envelope =
                CrossLevelTransferSubLevelDataEnvelopeAdapter.create(data, expected).orElseThrow();
        final CompoundTag decoded = CrossLevelTransferNbtPayloadCodec.decode(
                envelope.payload(),
                envelope.payload().length
        ).orElseThrow();

        assertTrue(envelope.verifyIntegrity());
        assertTrue(envelope.matches(expected));
        assertEquals(tag, decoded);
    }

    @Test
    void envelopePayloadIsIndependentFromLaterDataTagMutation() {
        final CrossLevelTransferTransactionState expected = preparing();
        final CompoundTag tag = serializedTag(expected.subLevelId());
        final CrossLevelTransferSnapshotEnvelope envelope =
                CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                        data(expected.subLevelId(), List.of(), tag),
                        expected
                ).orElseThrow();

        tag.getCompound("plot").putInt("plot_x", 99);

        final CompoundTag decoded = CrossLevelTransferNbtPayloadCodec.decode(
                envelope.payload(),
                envelope.payload().length
        ).orElseThrow();
        assertEquals(1, decoded.getCompound("plot").getInt("plot_x"));
    }

    @Test
    void transactionAndSerializedUuidMustBothMatch() {
        final CrossLevelTransferTransactionState expected = preparing();

        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data(UUID.randomUUID(), List.of(), serializedTag(UUID.randomUUID())),
                expected
        ).isEmpty());

        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data(expected.subLevelId(), List.of(), serializedTag(UUID.randomUUID())),
                expected
        ).isEmpty());
    }

    @Test
    void anyDependencyRepresentationFailsClosed() {
        final CrossLevelTransferTransactionState expected = preparing();
        final UUID dependency = UUID.randomUUID();

        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data(expected.subLevelId(), List.of(dependency), serializedTag(expected.subLevelId())),
                expected
        ).isEmpty());

        final CompoundTag taggedDependency = serializedTag(expected.subLevelId());
        final ListTag dependencies = new ListTag();
        dependencies.add(NbtUtils.createUUID(dependency));
        taggedDependency.put("loading_dependencies", dependencies);
        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data(expected.subLevelId(), List.of(), taggedDependency),
                expected
        ).isEmpty());
    }

    @Test
    void missingOrWrongTypedRequiredRootFieldsFailClosed() {
        final CrossLevelTransferTransactionState expected = preparing();
        for (final String key : new String[]{"uuid", "plot", "pose", "world_bounds"}) {
            final CompoundTag missing = serializedTag(expected.subLevelId());
            missing.remove(key);
            assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                    data(expected.subLevelId(), List.of(), missing),
                    expected
            ).isEmpty(), key);
        }

        final CompoundTag wrongDependenciesType = serializedTag(expected.subLevelId());
        wrongDependenciesType.putString("loading_dependencies", "invalid");
        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data(expected.subLevelId(), List.of(), wrongDependenciesType),
                expected
        ).isEmpty());
    }

    @Test
    void captureRequiresPreparingCurrentFormatTransaction() {
        final CrossLevelTransferTransactionState expected = preparing();
        final SubLevelData data = data(
                expected.subLevelId(),
                List.of(),
                serializedTag(expected.subLevelId())
        );

        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data,
                state(expected, CrossLevelTransferPhase.SNAPSHOT_WRITTEN, expected.snapshotFormatVersion())
        ).isEmpty());
        assertTrue(CrossLevelTransferSubLevelDataEnvelopeAdapter.create(
                data,
                state(expected, CrossLevelTransferPhase.PREPARING, expected.snapshotFormatVersion() + 1)
        ).isEmpty());
    }

    @Test
    void nullContractsAreRejected() {
        final CrossLevelTransferTransactionState expected = preparing();
        final SubLevelData data = data(
                expected.subLevelId(),
                List.of(),
                serializedTag(expected.subLevelId())
        );

        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferSubLevelDataEnvelopeAdapter.create(null, expected)
        );
        assertThrows(
                NullPointerException.class,
                () -> CrossLevelTransferSubLevelDataEnvelopeAdapter.create(data, null)
        );
    }

    private static CrossLevelTransferTransactionState preparing() {
        return new CrossLevelTransferTransactionState(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "minecraft:overworld",
                "starlance:space",
                1,
                2,
                CrossLevelTransferPhase.PREPARING,
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION
        );
    }

    private static CrossLevelTransferTransactionState state(
            final CrossLevelTransferTransactionState source,
            final CrossLevelTransferPhase phase,
            final int snapshotFormatVersion
    ) {
        return new CrossLevelTransferTransactionState(
                source.transactionId(),
                source.subLevelId(),
                source.sourceDimension(),
                source.targetDimension(),
                source.localPlotX(),
                source.localPlotZ(),
                phase,
                snapshotFormatVersion
        );
    }

    private static SubLevelData data(
            final UUID uuid,
            final List<UUID> dependencies,
            final CompoundTag fullTag
    ) {
        return new SubLevelData(
                uuid,
                new BoundingBox3d(0, 0, 0, 1, 1, 1),
                new Pose3d(),
                dependencies,
                fullTag
        );
    }

    private static CompoundTag serializedTag(final UUID uuid) {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", uuid);

        final CompoundTag plot = new CompoundTag();
        plot.putInt("plot_x", 1);
        plot.putInt("plot_z", 2);
        tag.put("plot", plot);
        tag.put("pose", new CompoundTag());
        tag.put("world_bounds", new CompoundTag());
        return tag;
    }
}
