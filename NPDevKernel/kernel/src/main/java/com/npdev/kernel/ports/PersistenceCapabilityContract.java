package com.npdev.kernel.ports;

public interface PersistenceCapabilityContract {
    /**
     * REG-264: key the kernel stamps into the entity of a flow {@code updateConcept} step's
     * {@code save} (value {@link #WRITE_MODE_UPDATE}). {@code save} alone cannot tell an update from a
     * create -- both are an upsert -- so without it an update of an id that does not exist silently
     * created the row where the REST PUT answers 404. An adapter that sees it must refuse a save whose
     * row is absent and must strip the key before persisting; adapters ignore the absence of it.
     */
    String WRITE_MODE_KEY = "__npdevWriteMode";
    String WRITE_MODE_UPDATE = "update";

    /**
     * REG-337: key the kernel stamps into the entity of a flow's {@code save}, naming the flow's
     * authenticated acting user (the runtime-owned {@code actorId} of the flow state -- never a value
     * from the step's own payload, which the kernel strips first). The adapter's create-time defaulting
     * pass needs it to resolve a {@code $user.id} {@code defaultExpression}, so a record a flow creates
     * is owned by the person who started the flow exactly as a REST create is. An adapter that sees it
     * must strip it before persisting; adapters ignore the absence of it (no actor = anonymous).
     */
    String ACTOR_KEY = "__npdevActorId";

    Object save(Object entity);

    Object findById(Object concept, Object id);

    Object query(Object concept, Object criteria);

    Object delete(Object concept, Object id);

    Object exists(Object concept, Object field, Object value);

    Object unique(Object concept, Object field, Object value);
}

