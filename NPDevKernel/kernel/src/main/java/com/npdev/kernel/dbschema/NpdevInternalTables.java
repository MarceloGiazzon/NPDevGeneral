package com.npdev.kernel.dbschema;

import java.util.List;

public final class NpdevInternalTables {
    private NpdevInternalTables() {
    }

    public static List<InternalTableDefinition> all() {
        return List.of(
                NpdevFlowInstanceTable.definition(),
                NpdevCorrelationOwnerTable.definition(),
                NpdevEventStoreTable.definition(),
                NpdevAuditLogTable.definition(),
                NpdevTraceTable.definition(),
                NpdevIdempotencyTable.definition(),
                NpdevCircuitBreakerTable.definition(),
                NpdevScheduledEventTable.definition(),
                NpdevPublicationExecutionTable.definition(),
                NpdevPublicationAuditTable.definition(),
                NpdevSchemaMetadataTable.definition(),
                NpdevPromotionStateTable.definition(),
                NpdevTenantTable.definition(),
                NpdevApiCredentialTable.definition()
        );
    }
}
