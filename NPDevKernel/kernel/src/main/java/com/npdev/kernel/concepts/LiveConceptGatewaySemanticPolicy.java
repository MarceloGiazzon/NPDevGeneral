package com.npdev.kernel.concepts;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.SequenceAllocator;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Rebuild-on-reload wrapper around the expensive {@link ConfiguredConceptGatewaySemanticPolicy}
 * full-model precomputation: an identity-compare cache against the live {@link CompiledModel}
 * (mirroring the {@code ModelHolder} {@code AtomicReference} idiom) means a request between hot
 * reloads pays one reference comparison, not a full model walk, while a reload is picked up on the
 * next call with no reconstruction of this wrapper itself.
 */
public final class LiveConceptGatewaySemanticPolicy implements ConceptGatewaySemanticPolicy {
    private final Supplier<CompiledModel> modelSupplier;
    private final SequenceAllocator sequenceAllocator;
    private final AtomicReference<CompiledModel> lastSeenModel = new AtomicReference<>();
    private volatile ConceptGatewaySemanticPolicy delegate;
    private final Object rebuildLock = new Object();

    public LiveConceptGatewaySemanticPolicy(Supplier<CompiledModel> modelSupplier, SequenceAllocator sequenceAllocator) {
        this.modelSupplier = Objects.requireNonNull(modelSupplier, "modelSupplier");
        this.sequenceAllocator = Objects.requireNonNull(sequenceAllocator, "sequenceAllocator");
    }

    /**
     * Returns the policy built from the currently-live model, rebuilding it only when the model
     * supplier's result has changed (by identity) since the last call. The fast path below is
     * lock-free; a rebuild is serialized under {@link #rebuildLock} and re-reads the supplier
     * INSIDE the lock so that two reloads racing in quick succession can never publish an older
     * model's policy after a newer one's -- whichever thread rebuilds second always re-fetches the
     * (by then) current model, so the last publish is always for the true latest model.
     */
    private ConceptGatewaySemanticPolicy current() {
        CompiledModel model = modelSupplier.get();
        if (model == lastSeenModel.get()) {
            ConceptGatewaySemanticPolicy cached = delegate;
            if (cached != null) {
                return cached;
            }
        }
        synchronized (rebuildLock) {
            CompiledModel latestModel = modelSupplier.get();
            if (delegate == null || latestModel != lastSeenModel.get()) {
                ConceptGatewaySemanticPolicy rebuilt =
                        ConfiguredConceptGatewaySemanticPolicy.fromCompiledModel(latestModel, sequenceAllocator);
                delegate = rebuilt;
                lastSeenModel.set(latestModel);
                return rebuilt;
            }
            return delegate;
        }
    }

    @Override
    public ConceptSemanticDecision normalizeAndValidate(ConceptGatewayRequestContext request) {
        return current().normalizeAndValidate(request);
    }

    @Override
    public ConceptSemanticDecision applyDefaultsAndDerivedValues(ConceptGatewayRequestContext request) {
        return current().applyDefaultsAndDerivedValues(request);
    }

    @Override
    public ConceptSemanticDecision validateLifecycleTransition(ConceptGatewayRequestContext request) {
        return current().validateLifecycleTransition(request);
    }

    @Override
    public ConceptSemanticDecision evaluateRuleProfiles(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles
    ) {
        return current().evaluateRuleProfiles(request, ruleProfiles);
    }

    @Override
    public ConceptRecord filterVisibleFields(ConceptRecord record, ConceptGatewayRequestContext request) {
        return current().filterVisibleFields(record, request);
    }

    @Override
    public boolean isRowReadable(ConceptRecord record, ConceptGatewayRequestContext request) {
        return current().isRowReadable(record, request);
    }

    @Override
    public boolean isRowWritable(ConceptGatewayRequestContext request) {
        return current().isRowWritable(request);
    }

    @Override
    public boolean hasRowReadScope(String conceptName) {
        return current().hasRowReadScope(conceptName);
    }

    @Override
    public Optional<String> resolveReferenceTarget(String conceptName, String fieldName) {
        return current().resolveReferenceTarget(conceptName, fieldName);
    }

    @Override
    public List<String> deniedWriteFields(ConceptGatewayRequestContext request) {
        return current().deniedWriteFields(request);
    }
}
