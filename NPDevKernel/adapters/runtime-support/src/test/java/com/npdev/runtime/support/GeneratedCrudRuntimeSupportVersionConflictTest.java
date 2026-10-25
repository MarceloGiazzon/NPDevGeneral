package com.npdev.runtime.support;

import com.npdev.kernel.concepts.ConceptGatewayOptimisticLockException;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.concepts.ConceptStoreOptimisticLockException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** REG-308: a lost compare-and-swap race answers the same version_conflict as the sequential check. */
class GeneratedCrudRuntimeSupportVersionConflictTest {

    private static final Map<String, Object> REQUEST = Map.of("version", 3);
    private static final ConceptRecord STORED =
            new ConceptRecord("Widget", "w1", "t1", Map.of("version", 4), 7L);

    @Test
    void aSuccessfulWriteIsReturnedUntouched() {
        assertEquals("ok", GeneratedCrudRuntimeSupport.guardVersionConflict("Widget", REQUEST, () -> "ok"));
    }

    @Test
    void aLostStoreRaceIsAVersionConflictNamingTheStoredVersion() {
        var thrown = assertThrows(GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> GeneratedCrudRuntimeSupport.guardVersionConflict("Widget", REQUEST, () -> {
                    throw new ConceptStoreOptimisticLockException("Widget", "w1", "t1", Optional.of(STORED));
                }));
        var detail = thrown.violations().get(0);
        assertEquals("version_conflict", detail.code());
        assertEquals("Record was modified by another request. expected=3 actual=4", detail.message());
    }

    @Test
    void aLostGatewayRaceIsAVersionConflictEvenWhenTheRowIsGone() {
        var thrown = assertThrows(GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> GeneratedCrudRuntimeSupport.guardVersionConflict("Widget", REQUEST, () -> {
                    throw new ConceptGatewayOptimisticLockException("Widget", "w1", "t1", Optional.empty());
                }));
        assertEquals("Record was modified by another request. expected=3 actual=null",
                thrown.violations().get(0).message());
    }

    @Test
    void aGatewayWriteReportsTheRowVersionItLeftBehindOnlyWhenOneWasAskedFor() {
        ConceptRecord written = new ConceptRecord("Widget", "w1", "t1", Map.of(), 8L);
        assertEquals(8L, GeneratedCrudRuntimeSupport.writeWithRowVersion("Widget", REQUEST, 7L, () -> written));
        assertNull(GeneratedCrudRuntimeSupport.writeWithRowVersion("Widget", Map.of(), null, () -> written));
    }

    @Test
    void anUpdateFlowClaimIsACompareAndSwapOnlyWhenTheRequestCarriedAVersion() {
        int[] claims = {0};
        GeneratedCrudRuntimeSupport.claimRowForUpdate("Widget", REQUEST, 7L, () -> {
            claims[0]++;
            return STORED;
        });
        GeneratedCrudRuntimeSupport.claimRowForUpdate("Widget", Map.of(), null, () -> {
            claims[0]++;
            return STORED;
        });
        assertEquals(1, claims[0]);
    }

    @Test
    void aLostUpdateFlowClaimIsAVersionConflict() {
        var thrown = assertThrows(GeneratedCrudRuntimeSupport.InvariantViolationException.class,
                () -> GeneratedCrudRuntimeSupport.claimRowForUpdate("Widget", REQUEST, 7L, () -> {
                    throw new ConceptStoreOptimisticLockException("Widget", "w1", "t1", Optional.of(STORED));
                }));
        assertEquals("version_conflict", thrown.violations().get(0).code());
    }

    @Test
    void anyOtherFailureIsNotSwallowed() {
        IllegalStateException other = new IllegalStateException("boom");
        assertSame(other, assertThrows(IllegalStateException.class,
                () -> GeneratedCrudRuntimeSupport.guardVersionConflict("Widget", REQUEST, () -> {
                    throw other;
                })));
    }
}
