package com.npdev.dsl.v1.query;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessReadPredicateTest {

    private static AccessReadPredicate.Translation translate(String rule, String prefix, boolean curator) {
        return AccessReadPredicate.translate(rule, prefix, "ana", "t1", role -> curator && role.equalsIgnoreCase("Curator"))
                .orElseThrow();
    }

    @Test
    void userIdBecomesAQuotedLiteralOnEitherSide() {
        AccessReadPredicate.Translation translation = translate("ownerUsername == $user.id || status != 'DRAFT'", "", false);
        assertEquals(AccessReadPredicate.Kind.WHERE, translation.kind());
        assertEquals("ownerUsername == 'ana' || status != 'DRAFT'", translation.where());
        assertEquals("ownerUsername == 'ana'", translate("$user.id == ownerUsername", "", false).where());
    }

    @Test
    void joinPrefixIsAppliedToEveryClause() {
        assertEquals("mosaicId.ownerUsername == 'ana' || mosaicId.status != 'DRAFT'",
                translate("ownerUsername == $user.id || status != 'DRAFT'", "mosaicId.", false).where());
    }

    @Test
    void roleChecksAreCallerConstants() {
        String rule = "$user.roles.contains('Curator') || username == $user.id";
        assertEquals(AccessReadPredicate.Kind.ALL, translate(rule, "", true).kind());
        assertEquals("username == 'ana'", translate(rule, "", false).where());
        assertEquals(AccessReadPredicate.Kind.NONE, translate("$user.roles.contains('Curator')", "", false).kind());
        assertEquals(AccessReadPredicate.Kind.ALL, translate("!$user.roles.contains('Curator')", "", false).kind());
    }

    @Test
    void outsideTheSubsetIsNotTranslatable() {
        assertFalse(AccessReadPredicate.isTranslatable("region == $user.region", ""));
        assertFalse(AccessReadPredicate.isTranslatable("(a == 1 || b == 2) && c == 3", ""));
        assertFalse(AccessReadPredicate.isTranslatable("total > amount * 2", ""));
        assertTrue(AccessReadPredicate.isTranslatable("ownerUsername == $user.id || forTrade == true", ""));
        // A caller id carrying a quote is refused rather than spliced.
        assertEquals(Optional.empty(), AccessReadPredicate.translate("owner == $user.id", "", "o'brien", "t", r -> false));
    }
}
