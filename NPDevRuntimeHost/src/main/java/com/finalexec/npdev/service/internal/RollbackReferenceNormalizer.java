package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RollbackReferenceNormalizer {

    public List<String> normalizeSourceAnchorCandidates(String anchorReference, String mutationReference) {
        Set<String> candidates = new LinkedHashSet<>();
        addTrimmed(candidates, anchorReference);
        addWithoutSuffix(candidates, anchorReference, "-anchor");
        addWithoutPrefix(candidates, anchorReference, "real-publication-");

        addTrimmed(candidates, mutationReference);
        addWithoutPrefix(candidates, mutationReference, "real-publication-");

        String mutationBase = stripPrefix(trim(anchorReference), "real-publication-");
        addWithSuffix(candidates, mutationBase, "-anchor");
        mutationBase = stripPrefix(trim(mutationReference), "real-publication-");
        addWithSuffix(candidates, mutationBase, "-anchor");
        return new ArrayList<>(candidates);
    }

    public List<String> normalizeSourceMutationCandidates(String mutationReference, String anchorReference) {
        Set<String> candidates = new LinkedHashSet<>();
        addTrimmed(candidates, mutationReference);
        addWithoutPrefix(candidates, mutationReference, "real-publication-");
        addWithPrefix(candidates, mutationReference, "real-publication-");

        addTrimmed(candidates, anchorReference);
        addWithoutSuffix(candidates, anchorReference, "-anchor");
        addWithoutPrefix(candidates, anchorReference, "real-publication-");
        addWithPrefix(candidates, stripSuffix(trim(anchorReference), "-anchor"), "real-publication-");
        return new ArrayList<>(candidates);
    }

    public List<String> normalizePublicationTransactionCandidates(String transactionReference, String executionReference) {
        Set<String> candidates = new LinkedHashSet<>();
        addTrimmed(candidates, transactionReference);
        addWithoutPrefix(candidates, transactionReference, "real-publication-");
        addTrimmed(candidates, executionReference);
        addWithoutPrefix(candidates, executionReference, "real-publication-");
        return new ArrayList<>(candidates);
    }

    public List<String> normalizePublicationExecutionCandidates(String executionReference, String transactionReference) {
        Set<String> candidates = new LinkedHashSet<>();
        addTrimmed(candidates, executionReference);
        addWithoutPrefix(candidates, executionReference, "real-publication-");
        addTrimmed(candidates, transactionReference);
        addWithoutPrefix(candidates, transactionReference, "real-publication-");
        addWithPrefix(candidates, transactionReference, "real-publication-");
        return new ArrayList<>(candidates);
    }

    public List<String> normalizeDraftCandidates(String draftReference) {
        Set<String> candidates = new LinkedHashSet<>();
        addTrimmed(candidates, draftReference);
        addWithoutPrefix(candidates, draftReference, "draft-");
        addWithPrefix(candidates, draftReference, "draft-");
        return new ArrayList<>(candidates);
    }

    private void addTrimmed(Set<String> candidates, String value) {
        String normalized = trim(value);
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }
    }

    private void addWithoutPrefix(Set<String> candidates, String value, String prefix) {
        String normalized = stripPrefix(trim(value), prefix);
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }
    }

    private void addWithoutSuffix(Set<String> candidates, String value, String suffix) {
        String normalized = stripSuffix(trim(value), suffix);
        if (!normalized.isBlank()) {
            candidates.add(normalized);
        }
    }

    private void addWithPrefix(Set<String> candidates, String value, String prefix) {
        String normalized = trim(value);
        if (normalized.isBlank()) {
            return;
        }
        if (!normalized.startsWith(prefix)) {
            candidates.add(prefix + normalized);
        }
    }

    private void addWithSuffix(Set<String> candidates, String value, String suffix) {
        String normalized = trim(value);
        if (normalized.isBlank()) {
            return;
        }
        if (!normalized.endsWith(suffix)) {
            candidates.add(normalized + suffix);
        }
    }

    private String stripPrefix(String value, String prefix) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private String stripSuffix(String value, String suffix) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
