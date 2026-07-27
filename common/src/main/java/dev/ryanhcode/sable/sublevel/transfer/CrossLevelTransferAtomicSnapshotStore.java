package dev.ryanhcode.sable.sublevel.transfer;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated immutable snapshot store using file fsync, atomic rename, directory
 * fsync, and read-after-write verification.
 *
 * <p>This store is world-independent and is not connected to transfer journal
 * phase advancement or world mutation.</p>
 */
public final class CrossLevelTransferAtomicSnapshotStore {
    private static final String FILE_SUFFIX = ".sable-transfer-snapshot.nbt";

    private final Path directory;
    private final int maximumPayloadBytes;
    private final int maximumFileBytes;

    public CrossLevelTransferAtomicSnapshotStore(
            final Path directory,
            final int maximumPayloadBytes,
            final int maximumFileBytes
    ) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (maximumPayloadBytes < 1) {
            throw new IllegalArgumentException("maximumPayloadBytes must be positive");
        }
        if (maximumFileBytes < maximumPayloadBytes) {
            throw new IllegalArgumentException("maximumFileBytes must cover maximumPayloadBytes");
        }
        this.maximumPayloadBytes = maximumPayloadBytes;
        this.maximumFileBytes = maximumFileBytes;
    }

    /**
     * Writes one immutable envelope. Repeating the exact envelope is idempotent;
     * the same transaction UUID with different contents is a conflict.
     */
    public synchronized CrossLevelTransferSnapshotStoreWriteStatus write(
            final CrossLevelTransferSnapshotEnvelope envelope
    ) {
        Objects.requireNonNull(envelope, "envelope");
        if (!envelope.verifyIntegrity() || envelope.payload().length > this.maximumPayloadBytes) {
            return CrossLevelTransferSnapshotStoreWriteStatus.VERIFICATION_FAILURE;
        }

        final Path target = this.pathFor(envelope.transactionId());
        Path temporary = null;
        try {
            Files.createDirectories(this.directory);

            if (Files.exists(target)) {
                return this.verifyExisting(target, envelope);
            }

            final CompoundTag envelopeTag = CrossLevelTransferSnapshotEnvelopeCodec.encode(envelope);
            final byte[] filePayload = CrossLevelTransferNbtPayloadCodec.encode(envelopeTag);
            if (filePayload.length > this.maximumFileBytes) {
                return CrossLevelTransferSnapshotStoreWriteStatus.VERIFICATION_FAILURE;
            }

            temporary = Files.createTempFile(
                    this.directory,
                    envelope.transactionId() + ".",
                    ".tmp"
            );
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                final ByteBuffer buffer = ByteBuffer.wrap(filePayload);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
            } catch (final AtomicMoveNotSupportedException exception) {
                return CrossLevelTransferSnapshotStoreWriteStatus.ATOMIC_MOVE_UNAVAILABLE;
            }

            this.forceDirectory();
            final Optional<CrossLevelTransferSnapshotEnvelope> verified = this.read(envelope.transactionId());
            return verified.filter(envelope::equals).isPresent() ?
                    CrossLevelTransferSnapshotStoreWriteStatus.WRITTEN :
                    CrossLevelTransferSnapshotStoreWriteStatus.VERIFICATION_FAILURE;
        } catch (final IOException exception) {
            return CrossLevelTransferSnapshotStoreWriteStatus.IO_FAILURE;
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (final IOException ignored) {
                }
            }
        }
    }

    /**
     * Reads and fully verifies the envelope stored for one transaction UUID.
     */
    public synchronized Optional<CrossLevelTransferSnapshotEnvelope> read(final UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        final Path path = this.pathFor(transactionId);

        try {
            if (!Files.isRegularFile(path)) {
                return Optional.empty();
            }
            final long fileSize = Files.size(path);
            if (fileSize < 1 || fileSize > this.maximumFileBytes) {
                return Optional.empty();
            }

            final byte[] filePayload = Files.readAllBytes(path);
            final Optional<CompoundTag> envelopeTag = CrossLevelTransferNbtPayloadCodec.decode(
                    filePayload,
                    this.maximumFileBytes
            );
            if (envelopeTag.isEmpty()) {
                return Optional.empty();
            }

            return CrossLevelTransferSnapshotEnvelopeCodec.decode(
                    envelopeTag.orElseThrow(),
                    this.maximumPayloadBytes
            ).filter(envelope -> envelope.transactionId().equals(transactionId));
        } catch (final IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    public Path pathFor(final UUID transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return this.directory.resolve(transactionId + FILE_SUFFIX);
    }

    private CrossLevelTransferSnapshotStoreWriteStatus verifyExisting(
            final Path target,
            final CrossLevelTransferSnapshotEnvelope expected
    ) throws IOException {
        final Optional<CrossLevelTransferSnapshotEnvelope> existing = this.read(expected.transactionId());
        if (existing.isEmpty()) {
            return CrossLevelTransferSnapshotStoreWriteStatus.VERIFICATION_FAILURE;
        }
        if (!existing.orElseThrow().equals(expected)) {
            return CrossLevelTransferSnapshotStoreWriteStatus.CONFLICTING_EXISTING_SNAPSHOT;
        }

        this.forceDirectory();
        return CrossLevelTransferSnapshotStoreWriteStatus.ALREADY_PRESENT;
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(this.directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
