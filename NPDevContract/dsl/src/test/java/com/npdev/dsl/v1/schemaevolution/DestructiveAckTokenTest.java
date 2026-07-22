package com.npdev.dsl.v1.schemaevolution;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 4 (task 4.5). Unit coverage for {@link DestructiveAckToken}: stability, sensitivity
 * to changes in either input, and order-independence (symmetry) -- the two properties the plan
 * explicitly calls out as needing proof since both the generator (Phase 6) and RuntimeHost (this
 * phase) must produce byte-identical hash input from independently-built item lists.
 */
class DestructiveAckTokenTest {

    @Test
    void isDeterministicForTheSameInputs() {
        String token1 = DestructiveAckToken.compute("sha256:abc", List.of("DROP_COLUMN:users:legacy_flag:BOOLEAN"));
        String token2 = DestructiveAckToken.compute("sha256:abc", List.of("DROP_COLUMN:users:legacy_flag:BOOLEAN"));
        assertEquals(token1, token2, "the same fingerprint + item set must always hash to the same token");
    }

    @Test
    void isA64CharacterLowercaseHexString() {
        String token = DestructiveAckToken.compute("sha256:abc", List.of("DROP_TABLE:widgets:0"));
        assertEquals(64, token.length());
        assertTrue(token.matches("[0-9a-f]{64}"), "expected lower-case hex, got: " + token);
    }

    @Test
    void isOrderIndependentAcrossDifferentListConstructionOrders() {
        List<String> itemsInOrderA = List.of(
                "DROP_COLUMN:users:legacy_flag:BOOLEAN",
                "DROP_TABLE:widgets:12",
                "NARROW_TYPE:orders:total:NUMERIC(19,4):NUMERIC(19,2)");

        List<String> itemsInOrderB = new ArrayList<>();
        itemsInOrderB.add("NARROW_TYPE:orders:total:NUMERIC(19,4):NUMERIC(19,2)");
        itemsInOrderB.add("DROP_TABLE:widgets:12");
        itemsInOrderB.add("DROP_COLUMN:users:legacy_flag:BOOLEAN");

        String tokenA = DestructiveAckToken.compute("sha256:new", itemsInOrderA);
        String tokenB = DestructiveAckToken.compute("sha256:new", itemsInOrderB);
        assertEquals(tokenA, tokenB, "two callers building the same logical item set in different "
                + "collection orders must hash identically -- this is the token's whole safety argument");
    }

    @Test
    void symmetryHoldsWhenTheTwoSidesBuildTheItemListViaCompletelyDifferentMechanisms() {
        // Simulates the two independent derivations the plan (§2.3) says must agree: one side
        // builds its list by appending as it iterates a Map, the other by iterating a Set built in
        // reverse insertion order -- both logically describe the identical destructive change.
        List<String> mapIterationStyle = List.of(
                "DROP_COLUMN:accounts:old_email:VARCHAR(255)",
                "DROP_COLUMN:accounts:old_phone:VARCHAR(20)");
        List<String> reverseSetStyle = List.of(
                "DROP_COLUMN:accounts:old_phone:VARCHAR(20)",
                "DROP_COLUMN:accounts:old_email:VARCHAR(255)");

        assertEquals(
                DestructiveAckToken.compute("sha256:target", mapIterationStyle),
                DestructiveAckToken.compute("sha256:target", reverseSetStyle));
    }

    @Test
    void changingTheFingerprintChangesTheToken() {
        List<String> items = List.of("DROP_TABLE:widgets:0");
        String token1 = DestructiveAckToken.compute("sha256:v1", items);
        String token2 = DestructiveAckToken.compute("sha256:v2", items);
        assertNotEquals(token1, token2, "the token must be bound to the target fingerprint");
    }

    @Test
    void changingTheItemSetChangesTheToken() {
        String token1 = DestructiveAckToken.compute("sha256:abc", List.of("DROP_TABLE:widgets:0"));
        String token2 = DestructiveAckToken.compute("sha256:abc",
                List.of("DROP_TABLE:widgets:0", "DROP_COLUMN:users:legacy_flag:BOOLEAN"));
        assertNotEquals(token1, token2, "a token must not silently \"cover\" an item set larger than what it "
                + "was computed for -- adding another destructive item must change the token");
    }

    @Test
    void aSingleCharacterDifferenceInOneItemChangesTheToken() {
        String token1 = DestructiveAckToken.compute("sha256:abc", List.of("DROP_COLUMN:users:legacy_flag:BOOLEAN"));
        String token2 = DestructiveAckToken.compute("sha256:abc", List.of("DROP_COLUMN:users:legacy_flags:BOOLEAN"));
        assertNotEquals(token1, token2);
    }

    @Test
    void emptyItemListStillProducesAStableToken() {
        String token1 = DestructiveAckToken.compute("sha256:abc", List.of());
        String token2 = DestructiveAckToken.compute("sha256:abc", null);
        assertEquals(token1, token2, "a null item list must be treated identically to an empty one");
    }
}
