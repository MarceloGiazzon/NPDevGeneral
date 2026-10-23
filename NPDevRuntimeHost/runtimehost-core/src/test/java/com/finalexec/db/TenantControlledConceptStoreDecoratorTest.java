package com.finalexec.db;

import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REG-293 (X0 rule): when a tenant's persistence mode cannot be resolved, the write REFUSES and the
 * delegate is never reached -- it used to answer "not audited", so an audited tenant's writes left no
 * audit trail during a database hiccup.
 */
class TenantControlledConceptStoreDecoratorTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<DataSource> provider(DataSource dataSource) {
        ObjectProvider<DataSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(dataSource);
        return provider;
    }

    @Test
    void aFailedPersistenceModeLookupRefusesTheWriteAndNeverCallsTheDelegate() throws Exception {
        ConceptStore delegate = mock(ConceptStore.class);
        DataSource down = mock(DataSource.class);
        when(down.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
        TenantControlledConceptStoreDecorator store =
                new TenantControlledConceptStoreDecorator(delegate, "Order", provider(down));
        ConceptRecord record = new ConceptRecord("Order", "o-1", "acme", java.util.Map.of("total", 10));

        assertThrows(IllegalStateException.class, () -> store.save(record));
        assertThrows(IllegalStateException.class, () -> store.deleteById("acme", "Order", "o-1"));
        verify(delegate, never()).save(any());
        verify(delegate, never()).deleteById(any(), any(), any());
    }

    @Test
    void aMissingDataSourceRefusesTheWriteToo() {
        ConceptStore delegate = mock(ConceptStore.class);
        TenantControlledConceptStoreDecorator store =
                new TenantControlledConceptStoreDecorator(delegate, "Order", provider(null));
        ConceptRecord record = new ConceptRecord("Order", "o-1", "acme", java.util.Map.of("total", 10));

        assertThrows(IllegalStateException.class, () -> store.save(record));
        verify(delegate, never()).save(any());
    }
}
