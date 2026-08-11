package dev.ryanhcode.sable.sublevel.plot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Assertion-based executable for exact identity comparison used by rollback baselines. */
public final class SubLevelReconstructionContainerBaselineSelfTest {
    private SubLevelReconstructionContainerBaselineSelfTest() {
    }

    public static void main(final String[] args) {
        identityOrderRejectsEqualReplacement();
        identityOrderRejectsReordering();
        identityMapRejectsEqualReplacement();
        identityMapRejectsKeyDrift();
        identityMapAcceptsDifferentIterationOrder();
        System.out.println("SUB_LEVEL_RECONSTRUCTION_CONTAINER_BASELINE_SELF_TEST: PASS");
    }

    private static void identityOrderRejectsEqualReplacement() {
        final String first = new String("same");
        final String equalButDifferent = new String("same");

        assert SubLevelReconstructionContainerBaseline.sameIdentityOrder(
                List.of(first),
                List.of(first)
        );
        assert !SubLevelReconstructionContainerBaseline.sameIdentityOrder(
                List.of(first),
                List.of(equalButDifferent)
        );
    }

    private static void identityOrderRejectsReordering() {
        final Object first = new Object();
        final Object second = new Object();

        assert !SubLevelReconstructionContainerBaseline.sameIdentityOrder(
                List.of(first, second),
                List.of(second, first)
        );
    }

    private static void identityMapRejectsEqualReplacement() {
        final UUID uuid = UUID.randomUUID();
        final String original = new String("same");
        final String equalButDifferent = new String("same");
        final Map<UUID, Object> expected = new LinkedHashMap<>();
        expected.put(uuid, original);
        final Map<UUID, Object> exact = new LinkedHashMap<>();
        exact.put(uuid, original);
        final Map<UUID, Object> replacement = new LinkedHashMap<>();
        replacement.put(uuid, equalButDifferent);

        assert SubLevelReconstructionContainerBaseline.sameIdentityMap(expected, exact);
        assert !SubLevelReconstructionContainerBaseline.sameIdentityMap(expected, replacement);
    }

    private static void identityMapRejectsKeyDrift() {
        final UUID uuid = UUID.randomUUID();
        final Object value = new Object();
        final Map<UUID, Object> expected = Map.of(uuid, value);
        final Map<UUID, Object> extra = new LinkedHashMap<>(expected);
        extra.put(UUID.randomUUID(), new Object());

        assert !SubLevelReconstructionContainerBaseline.sameIdentityMap(expected, extra);
    }

    private static void identityMapAcceptsDifferentIterationOrder() {
        final UUID firstUuid = UUID.randomUUID();
        final UUID secondUuid = UUID.randomUUID();
        final Object first = new Object();
        final Object second = new Object();
        final Map<UUID, Object> expected = new LinkedHashMap<>();
        expected.put(firstUuid, first);
        expected.put(secondUuid, second);
        final Map<UUID, Object> reversed = new LinkedHashMap<>();
        reversed.put(secondUuid, second);
        reversed.put(firstUuid, first);

        assert SubLevelReconstructionContainerBaseline.sameIdentityMap(expected, reversed);
    }
}
