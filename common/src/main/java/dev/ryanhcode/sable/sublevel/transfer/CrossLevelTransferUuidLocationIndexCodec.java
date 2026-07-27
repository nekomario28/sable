package dev.ryanhcode.sable.sublevel.transfer;

import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Versioned, deterministic NBT codec for one complete UUID location index.
 */
public final class CrossLevelTransferUuidLocationIndexCodec {
    private static final int CODEC_VERSION = 1;

    private static final String CODEC_VERSION_KEY = "codec_version";
    private static final String ENTRIES_KEY = "entries";
    private static final String UUID_KEY = "uuid";
    private static final String DIMENSION_KEY = "dimension";
    private static final String KIND_KEY = "kind";
    private static final String LOADED_KIND = "loaded";
    private static final String STORED_KIND = "stored";
    private static final String LOCAL_PLOT_X_KEY = "local_plot_x";
    private static final String LOCAL_PLOT_Z_KEY = "local_plot_z";
    private static final String CHUNK_X_KEY = "chunk_x";
    private static final String CHUNK_Z_KEY = "chunk_z";
    private static final String STORAGE_INDEX_KEY = "storage_index";
    private static final String SUB_LEVEL_INDEX_KEY = "sub_level_index";

    private CrossLevelTransferUuidLocationIndexCodec() {
    }

    /**
     * Encodes only a complete index. Building or conflicted indexes cannot become
     * durable authoritative evidence.
     */
    public static CompoundTag encode(final CrossLevelTransferUuidLocationIndex index) {
        Objects.requireNonNull(index, "index");
        if (index.status() != CrossLevelTransferUuidLocationIndex.Status.COMPLETE) {
            throw new IllegalStateException("Only a complete UUID location index can be encoded");
        }

        final List<Map.Entry<UUID, CrossLevelTransferSubLevelLocation>> entries =
                new ArrayList<>(index.snapshot().entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        final ListTag entryTags = new ListTag();
        for (final Map.Entry<UUID, CrossLevelTransferSubLevelLocation> entry : entries) {
            entryTags.add(encodeEntry(entry.getKey(), entry.getValue()));
        }

        final CompoundTag tag = new CompoundTag();
        tag.putInt(CODEC_VERSION_KEY, CODEC_VERSION);
        tag.put(ENTRIES_KEY, entryTags);
        return tag;
    }

    /**
     * Decodes the complete index fail-closed. One malformed or conflicting entry
     * rejects the entire index.
     */
    public static Optional<CrossLevelTransferUuidLocationIndex> decode(final CompoundTag tag) {
        Objects.requireNonNull(tag, "tag");
        if (!tag.contains(CODEC_VERSION_KEY, Tag.TAG_INT) ||
                !tag.contains(ENTRIES_KEY, Tag.TAG_LIST) ||
                tag.getInt(CODEC_VERSION_KEY) != CODEC_VERSION) {
            return Optional.empty();
        }

        final CrossLevelTransferUuidLocationIndex index = new CrossLevelTransferUuidLocationIndex();
        final ListTag entries = tag.getList(ENTRIES_KEY, Tag.TAG_COMPOUND);
        for (final Tag entryTag : entries) {
            if (!(entryTag instanceof final CompoundTag compoundEntry)) {
                return Optional.empty();
            }

            final Optional<DecodedEntry> decodedEntry = decodeEntry(compoundEntry);
            if (decodedEntry.isEmpty()) {
                return Optional.empty();
            }

            final DecodedEntry entry = decodedEntry.orElseThrow();
            final CrossLevelTransferUuidLocationIndex.RegistrationResult result =
                    index.register(entry.subLevelId(), entry.location());
            if (result == CrossLevelTransferUuidLocationIndex.RegistrationResult.DUPLICATE_UUID_CONFLICT ||
                    result == CrossLevelTransferUuidLocationIndex.RegistrationResult.INDEX_CONFLICTED) {
                return Optional.empty();
            }
        }

        try {
            index.complete();
            return Optional.of(index);
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static CompoundTag encodeEntry(
            final UUID subLevelId,
            final CrossLevelTransferSubLevelLocation location
    ) {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID(UUID_KEY, subLevelId);
        tag.putString(DIMENSION_KEY, location.dimension());

        if (location instanceof final CrossLevelTransferSubLevelLocation.Loaded loaded) {
            tag.putString(KIND_KEY, LOADED_KIND);
            tag.putInt(LOCAL_PLOT_X_KEY, loaded.localPlotX());
            tag.putInt(LOCAL_PLOT_Z_KEY, loaded.localPlotZ());
        } else if (location instanceof final CrossLevelTransferSubLevelLocation.Stored stored) {
            final GlobalSavedSubLevelPointer pointer = stored.pointer();
            tag.putString(KIND_KEY, STORED_KIND);
            tag.putInt(CHUNK_X_KEY, pointer.chunkPos().x);
            tag.putInt(CHUNK_Z_KEY, pointer.chunkPos().z);
            tag.putShort(STORAGE_INDEX_KEY, pointer.storageIndex());
            tag.putShort(SUB_LEVEL_INDEX_KEY, pointer.subLevelIndex());
        } else {
            throw new IllegalStateException("Unsupported sub-level location type: " + location.getClass());
        }

        return tag;
    }

    private static Optional<DecodedEntry> decodeEntry(final CompoundTag tag) {
        if (!tag.contains(UUID_KEY, Tag.TAG_INT_ARRAY) ||
                !tag.contains(DIMENSION_KEY, Tag.TAG_STRING) ||
                !tag.contains(KIND_KEY, Tag.TAG_STRING)) {
            return Optional.empty();
        }

        try {
            final UUID subLevelId = tag.getUUID(UUID_KEY);
            final String dimension = tag.getString(DIMENSION_KEY);
            final CrossLevelTransferSubLevelLocation location = switch (tag.getString(KIND_KEY)) {
                case LOADED_KIND -> decodeLoaded(tag, dimension);
                case STORED_KIND -> decodeStored(tag, dimension);
                default -> null;
            };
            return location == null ? Optional.empty() : Optional.of(new DecodedEntry(subLevelId, location));
        } catch (final RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static CrossLevelTransferSubLevelLocation decodeLoaded(
            final CompoundTag tag,
            final String dimension
    ) {
        if (!tag.contains(LOCAL_PLOT_X_KEY, Tag.TAG_INT) ||
                !tag.contains(LOCAL_PLOT_Z_KEY, Tag.TAG_INT)) {
            return null;
        }
        return new CrossLevelTransferSubLevelLocation.Loaded(
                dimension,
                tag.getInt(LOCAL_PLOT_X_KEY),
                tag.getInt(LOCAL_PLOT_Z_KEY)
        );
    }

    private static CrossLevelTransferSubLevelLocation decodeStored(
            final CompoundTag tag,
            final String dimension
    ) {
        if (!tag.contains(CHUNK_X_KEY, Tag.TAG_INT) ||
                !tag.contains(CHUNK_Z_KEY, Tag.TAG_INT) ||
                !tag.contains(STORAGE_INDEX_KEY, Tag.TAG_SHORT) ||
                !tag.contains(SUB_LEVEL_INDEX_KEY, Tag.TAG_SHORT)) {
            return null;
        }
        return new CrossLevelTransferSubLevelLocation.Stored(
                dimension,
                new GlobalSavedSubLevelPointer(
                        new ChunkPos(tag.getInt(CHUNK_X_KEY), tag.getInt(CHUNK_Z_KEY)),
                        tag.getShort(STORAGE_INDEX_KEY),
                        tag.getShort(SUB_LEVEL_INDEX_KEY)
                )
        );
    }

    private record DecodedEntry(
            UUID subLevelId,
            CrossLevelTransferSubLevelLocation location
    ) {
        private DecodedEntry {
            Objects.requireNonNull(subLevelId, "subLevelId");
            Objects.requireNonNull(location, "location");
        }
    }
}
