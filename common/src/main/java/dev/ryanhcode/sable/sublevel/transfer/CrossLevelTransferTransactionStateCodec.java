package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Objects;
import java.util.Optional;

/**
 * NBT codec for durable cross-level transfer transaction state.
 */
public final class CrossLevelTransferTransactionStateCodec {
    private static final int CODEC_VERSION = 1;

    private static final String CODEC_VERSION_KEY = "codec_version";
    private static final String TRANSACTION_ID_KEY = "transaction_id";
    private static final String SUB_LEVEL_ID_KEY = "sub_level_id";
    private static final String SOURCE_DIMENSION_KEY = "source_dimension";
    private static final String TARGET_DIMENSION_KEY = "target_dimension";
    private static final String LOCAL_PLOT_X_KEY = "local_plot_x";
    private static final String LOCAL_PLOT_Z_KEY = "local_plot_z";
    private static final String PHASE_KEY = "phase";
    private static final String SNAPSHOT_FORMAT_VERSION_KEY = "snapshot_format_version";

    private CrossLevelTransferTransactionStateCodec() {
    }

    /**
     * Encodes an immutable transfer transaction state.
     *
     * @param state the state to encode
     * @return a new NBT tag containing the complete state
     */
    public static CompoundTag encode(final CrossLevelTransferTransactionState state) {
        Objects.requireNonNull(state, "state");

        final CompoundTag tag = new CompoundTag();
        tag.putInt(CODEC_VERSION_KEY, CODEC_VERSION);
        tag.putUUID(TRANSACTION_ID_KEY, state.transactionId());
        tag.putUUID(SUB_LEVEL_ID_KEY, state.subLevelId());
        tag.putString(SOURCE_DIMENSION_KEY, state.sourceDimension());
        tag.putString(TARGET_DIMENSION_KEY, state.targetDimension());
        tag.putInt(LOCAL_PLOT_X_KEY, state.localPlotX());
        tag.putInt(LOCAL_PLOT_Z_KEY, state.localPlotZ());
        tag.putString(PHASE_KEY, state.phase().name());
        tag.putInt(SNAPSHOT_FORMAT_VERSION_KEY, state.snapshotFormatVersion());
        return tag;
    }

    /**
     * Decodes transaction state without throwing for malformed or unsupported data.
     *
     * @param tag the tag to decode
     * @return the decoded state, or empty when required data is missing, malformed, or unsupported
     */
    public static Optional<CrossLevelTransferTransactionState> decode(final CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");

        if (!hasRequiredFields(tag)) {
            return Optional.empty();
        }
        if (tag.getInt(CODEC_VERSION_KEY) != CODEC_VERSION) {
            return Optional.empty();
        }
        if (tag.getInt(SNAPSHOT_FORMAT_VERSION_KEY) !=
                CrossLevelTransferTransactionState.CURRENT_SNAPSHOT_FORMAT_VERSION) {
            return Optional.empty();
        }

        final CrossLevelTransferPhase phase;
        try {
            phase = CrossLevelTransferPhase.valueOf(tag.getString(PHASE_KEY));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }

        try {
            return Optional.of(new CrossLevelTransferTransactionState(
                    tag.getUUID(TRANSACTION_ID_KEY),
                    tag.getUUID(SUB_LEVEL_ID_KEY),
                    tag.getString(SOURCE_DIMENSION_KEY),
                    tag.getString(TARGET_DIMENSION_KEY),
                    tag.getInt(LOCAL_PLOT_X_KEY),
                    tag.getInt(LOCAL_PLOT_Z_KEY),
                    phase,
                    tag.getInt(SNAPSHOT_FORMAT_VERSION_KEY)
            ));
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static boolean hasRequiredFields(final CompoundTag tag) {
        return tag.contains(CODEC_VERSION_KEY, Tag.TAG_INT) &&
                tag.contains(TRANSACTION_ID_KEY, Tag.TAG_INT_ARRAY) &&
                tag.contains(SUB_LEVEL_ID_KEY, Tag.TAG_INT_ARRAY) &&
                tag.contains(SOURCE_DIMENSION_KEY, Tag.TAG_STRING) &&
                tag.contains(TARGET_DIMENSION_KEY, Tag.TAG_STRING) &&
                tag.contains(LOCAL_PLOT_X_KEY, Tag.TAG_INT) &&
                tag.contains(LOCAL_PLOT_Z_KEY, Tag.TAG_INT) &&
                tag.contains(PHASE_KEY, Tag.TAG_STRING) &&
                tag.contains(SNAPSHOT_FORMAT_VERSION_KEY, Tag.TAG_INT);
    }
}
