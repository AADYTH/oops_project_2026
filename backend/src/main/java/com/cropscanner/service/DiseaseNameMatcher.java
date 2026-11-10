package com.cropscanner.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reconciles a disease name as reported by Gemini (free text, e.g. "Late blight") against the exact,
 * curated keys of a knowledge base (e.g. {@code remedies.json}'s {@code "Late Blight (Phytophthora
 * infestans)"}, or the maintained epidemic-risk lookup table). Gemini's structured-output schema does not
 * constrain {@code disease} to a fixed set of strings, so an exact-string lookup misses almost everything;
 * this class is the one place that decides when two spellings mean "the same disease".
 *
 * The rules, in order, are deliberately conservative — a miss (returning no match) is always safer than a
 * wrong match, since a wrong match would attach one disease's remedies/epidemic-risk flag to another:
 *
 * <ol>
 *   <li>Exact match, case-insensitive, on the full key.</li>
 *   <li>Exact match, case-insensitive, on the key's "primary name" — everything before a trailing
 *       {@code (...)} scientific-name/qualifier, e.g. "Late Blight" for "Late Blight (Phytophthora
 *       infestans)". This is the common case: Gemini names the disease but not the pathogen.</li>
 *   <li>Containment: the normalized query contains the key's primary name, or vice versa — but ONLY when
 *       exactly one key matches. An ambiguous containment match (more than one candidate) is treated as no
 *       match at all, never a guess.</li>
 * </ol>
 *
 * Pure and dependency-free so the matching rules can be unit-tested directly and reused unchanged by any
 * lookup table keyed by disease name.
 */
public final class DiseaseNameMatcher {

    private DiseaseNameMatcher() { }

    /**
     * @param query        the disease name as reported by Gemini (or any other caller)
     * @param entriesByKey the knowledge base, keyed by its exact, curated spelling
     * @return the value for the single best-matching key, or null if nothing matched confidently
     */
    public static <T> T match(String query, Map<String, T> entriesByKey) {
        String key = matchKey(query, entriesByKey.keySet());
        return key == null ? null : entriesByKey.get(key);
    }

    /** Same matching rules as {@link #match}, but returns the matched key itself rather than its value. */
    public static String matchKey(String query, Iterable<String> keys) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalizedQuery = normalize(query);
        if (normalizedQuery.isEmpty()) {
            return null;
        }

        List<String> allKeys = new ArrayList<>();
        for (String k : keys) {
            allKeys.add(k);
        }

        // 1. Exact, case-insensitive match on the full key.
        for (String k : allKeys) {
            if (normalize(k).equals(normalizedQuery)) {
                return k;
            }
        }

        // 2. Exact, case-insensitive match on the key's primary name (before a trailing "(...)").
        String primaryQuery = primaryName(normalizedQuery);
        for (String k : allKeys) {
            if (primaryName(normalize(k)).equals(primaryQuery)) {
                return k;
            }
        }

        // 3. Containment, only if it uniquely identifies one key.
        String uniqueMatch = null;
        for (String k : allKeys) {
            String primaryKey = primaryName(normalize(k));
            if (primaryKey.isEmpty()) {
                continue;
            }
            boolean contains = primaryKey.contains(primaryQuery) || primaryQuery.contains(primaryKey);
            if (contains) {
                if (uniqueMatch != null) {
                    return null; // ambiguous: more than one candidate — never guess
                }
                uniqueMatch = k;
            }
        }
        return uniqueMatch;
    }

    /** Lowercased, trimmed, whitespace-collapsed. */
    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Everything before a trailing " (...)" qualifier, e.g. "late blight" for "late blight (phytophthora infestans)". */
    private static String primaryName(String normalized) {
        int paren = normalized.indexOf('(');
        String primary = paren >= 0 ? normalized.substring(0, paren) : normalized;
        return primary.replaceAll("[^a-z0-9 ]", "").trim();
    }
}
