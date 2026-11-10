package com.cropscanner.folix;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side guard on the model's recommended actions.
 *
 * The prompt already forbids fabricated dosing and legal claims, but a prompt
 * is a request, not a control. This class is the control:
 *
 *  1. Actions that read like a chemical dose, concentration, mixing ratio,
 *     spray schedule, or a claim that a treatment is legally required/approved
 *     are DROPPED. They are never softened or rewritten — a confident-looking
 *     number is exactly the failure mode we are guarding against.
 *  2. Actions that name a treatment product/class (without a dose) are kept,
 *     but labelled as general AI guidance rather than a verified local
 *     recommendation, as the brief requires.
 *  3. The final list always contains a pointer to verified local agricultural
 *     guidance, appended if the model did not already include one.
 *
 * The rules are deliberately conservative: a false positive costs one dropped
 * sentence; a false negative could put an invented spray rate in front of a farmer.
 */
@Component
public class ActionSafetyPolicy {

    static final String AI_GUIDANCE_PREFIX = "General guidance (not a verified local recommendation): ";
    static final String LOCAL_GUIDANCE_ACTION =
            "Confirm the diagnosis and any treatment with a local agricultural officer, extension service "
                    + "or agronomist before acting.";

    static final int MAX_ACTIONS = 5;
    static final int MAX_ACTION_LENGTH = 240;

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    /** Named treatment products / product classes. */
    private static final Pattern CHEMICAL_TERMS = Pattern.compile(
            "\\b(?:fungicid\\w*|pesticid\\w*|insecticid\\w*|bactericid\\w*|herbicid\\w*|miticid\\w*|acaricid\\w*|"
                    + "nematicid\\w*|spray\\w*|chemical\\w*|copper|sulphur|sulfur|mancozeb|carbendazim|"
                    + "chlorothalonil|imidacloprid|streptomycin|antibiotic\\w*|bordeaux|neem|drench\\w*|"
                    + "bio-?pesticide\\w*|horticultural\\s+oil|insecticidal\\s+soap)\\b", FLAGS);

    /** Words that make a number look like a treatment instruction. */
    private static final Pattern APPLY_TERMS = Pattern.compile(
            "\\b(?:appl(?:y|ied|ication\\w*)|treat(?:ed|ment|ments)?|dilut\\w*|mix(?:ed|ing)?|drench\\w*)\\b", FLAGS);

    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*(?:ml|litres?|liters?|l|gm|grams?|g|kg|mg|ppm|%|percent|oz|lbs?|tsp|tbsp|"
                    + "teaspoons?|tablespoons?|cc|gal(?:lons?)?)(?![a-z])", FLAGS);

    private static final Pattern PER_AREA = Pattern.compile(
            "\\bper\\s+(?:\\d+\\s*)?(?:litres?|liters?|l|ha|hectares?|acres?|gallons?|plants?|trees?)\\b", FLAGS);

    private static final Pattern FREQUENCY = Pattern.compile(
            "\\bevery\\s+\\d+(?:\\s*(?:-|to)\\s*\\d+)?\\s*(?:days?|weeks?|hours?)\\b"
                    + "|\\b\\d+\\s*(?:times|x)\\s*(?:a|per|every)\\s*(?:day|week|month)\\b"
                    + "|\\b(?:once|twice|thrice)\\s+(?:a|per|every)\\s+(?:day|week|month)\\b", FLAGS);

    /** Dose-language is never acceptable, with or without a number. */
    private static final Pattern DOSE_WORDS = Pattern.compile(
            "\\b(?:dosage|doses?|application\\s+rate|concentration|ppm|mixing\\s+ratio|dilution|dilute[sd]?)\\b", FLAGS);

    private static final Pattern LEGAL_CLAIMS = Pattern.compile(
            "\\b(?:legally|by\\s+law|mandatory|compulsory|required\\s+by\\s+(?:law|regulation|the\\s+government)|"
                    + "law\\s+requires|illegal\\s+to)\\b", FLAGS);

    private static final String[] LOCAL_GUIDANCE_MARKERS = {
            "agronomist", "agricultural officer", "agricultural expert", "agricultural extension", "extension service",
            "extension officer", "local agricultur", "plant protection", "krishi", "expert review", "human review"
    };

    /** Returns the safe action list. Never returns null; never returns an empty list. */
    public List<String> apply(List<String> modelActions) {
        List<String> kept = new ArrayList<>();
        for (String raw : modelActions) {
            String action = collapseWhitespace(raw);
            if (action.isEmpty() || isUnsafe(action)) {
                continue;
            }
            if (CHEMICAL_TERMS.matcher(action).find() && !action.startsWith(AI_GUIDANCE_PREFIX)) {
                action = AI_GUIDANCE_PREFIX + action;
            }
            action = truncate(action, MAX_ACTION_LENGTH);
            if (!containsIgnoreCase(kept, action)) {
                kept.add(action);
            }
            if (kept.size() == MAX_ACTIONS) {
                break;
            }
        }
        if (!mentionsLocalGuidance(kept)) {
            kept.add(LOCAL_GUIDANCE_ACTION);
        }
        return kept;
    }

    static boolean isUnsafe(String action) {
        if (LEGAL_CLAIMS.matcher(action).find() || DOSE_WORDS.matcher(action).find()) {
            return true;
        }
        boolean treatmentContext = CHEMICAL_TERMS.matcher(action).find() || APPLY_TERMS.matcher(action).find();
        return treatmentContext && (NUMBER_UNIT.matcher(action).find()
                || PER_AREA.matcher(action).find()
                || FREQUENCY.matcher(action).find());
    }

    private static boolean mentionsLocalGuidance(List<String> actions) {
        for (String a : actions) {
            String lower = a.toLowerCase(Locale.ROOT);
            for (String marker : LOCAL_GUIDANCE_MARKERS) {
                if (lower.contains(marker)) return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) {
            if (s.equalsIgnoreCase(value)) return true;
        }
        return false;
    }

    static String collapseWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    static String truncate(String s, int max) {
        if (s.length() <= max) return s;
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max / 2) cut = max - 1;
        return s.substring(0, cut).trim() + "\u2026";
    }
}
