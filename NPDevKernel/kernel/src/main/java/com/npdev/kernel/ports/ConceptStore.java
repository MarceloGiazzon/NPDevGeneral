package com.npdev.kernel.ports;

import com.npdev.kernel.concepts.ConceptRecord;

import java.util.List;
import java.util.Optional;

public interface ConceptStore {
    Optional<ConceptRecord> findById(String tenantId, String conceptName, String id);

    List<ConceptRecord> findAll(String tenantId, String conceptName);

    ConceptRecord save(ConceptRecord record);

    void deleteById(String tenantId, String conceptName, String id);
}
