package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;
import java.util.Optional;

/**
 * Converts already serialized single-sub-level data into an immutable envelope.
 *
 * <p>This adapter does not read a live world and does not call the serializer.</p>
 */
public final class CrossLevelTransferSubLevelDataEnvelopeAdapter {
    private static final String UUID_KEY = "uuid";
    private static final String PLOT_KEY = "plot";
    private static final String POSE_KEY = "pose";
    private static final String WORLD_BOUNDS_KEY = "world_bounds";
    private static final String DEPENDENCIES_KEY = "loading_dependencies";

    private CrossLevelTransferSubLevelDataEnvelopeAdapter() {
    }

    /**
     * Creates an envelope only when the serialized data matches the immutable
     * transaction identity and represents one dependency-free sub-level.
     *
     * @param data already serialized sub-level data
     * @param expected preparing transaction identity
     * @return verified envelope, or empty when the data is inconsistent
     */
    public static Optional<CrossLevelTransferSnapshotEnvelope> create(
            final SubLevelData data,
            final CrossLevelTransferTransactionState expected
    ) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(expected, "expected");

        if (expected.phase() != CrossLevelTransferPhase.PREPARING ||
                expected.snapshotFormatVersion() !=
                        CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION ||
                !data.uuid().equals(expected.subLevelId()) ||
                !data.dependencies().isEmpty()) {
            return Optional.empty();
        }

        final CompoundTag fullTag = data.fullTag();
        if (!hasRequiredRootFields(fullTag)) {
            return Optional.empty();
        }

        try {
            if (!fullTag.getUUID(UUID_KEY).equals(expected.subLevelId())) {
                return Optional.empty();
            }
            if (fullTag.contains(DEPENDENCIES_KEY, Tag.TAG_LIST) &&
                    !fullTag.getList(DEPENDENCIES_KEY, Tag.TAG_INT_ARRAY).isEmpty()) {
                return Optional.empty();
            }

            final byte[] payload = CrossLevelTransferNbtPayloadCodec.encode(fullTag.copy());
            return Optional.of(CrossLevelTransferSnapshotEnvelope.create(
                    expected.transactionId(),
                    expected.subLevelId(),
                    expected.snapshotFormatVersion(),
                    payload
            ));
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean hasRequiredRootFields(final CompoundTag tag) {
        return tag != null &&
                tag.contains(UUID_KEY, Tag.TAG_INT_ARRAY) &&
                tag.contains(PLOT_KEY, Tag.TAG_COMPOUND) &&
                tag.contains(POSE_KEY, Tag.TAG_COMPOUND) &&
                tag.contains(WORLD_BOUNDS_KEY, Tag.TAG_COMPOUND) &&
                (!tag.contains(DEPENDENCIES_KEY) || tag.contains(DEPENDENCIES_KEY, Tag.TAG_LIST));
    }
}
