package com.npdev.kernel.ports;

public interface PersistenceCapabilityContract {
    Object save(Object entity);

    Object findById(Object concept, Object id);

    Object query(Object concept, Object criteria);

    Object delete(Object concept, Object id);

    Object exists(Object concept, Object field, Object value);

    Object unique(Object concept, Object field, Object value);
}

