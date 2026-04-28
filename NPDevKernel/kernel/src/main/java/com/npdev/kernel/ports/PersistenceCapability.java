package com.npdev.kernel.ports;

import java.util.List;
import java.util.Optional;

/**
 * Persistence capability contract for runtime operations.
 * Implementations map this contract to concrete technologies (JPA, Mongo, etc).
 */
public interface PersistenceCapability<T, ID> {

    Optional<T> findById(ID id);

    List<T> findAll();

    T save(T entity);

    boolean existsById(ID id);

    void deleteById(ID id);

    /**
     * Generic uniqueness check by field name.
     * Implementations can ignore unsupported fields and return false.
     *
     * @param fieldName logical model field
     * @param value candidate value
     * @param excludeId optional current entity id for update checks (null for create)
     * @return true if uniqueness is violated
     */
    boolean existsUnique(String fieldName, Object value, ID excludeId);
}
