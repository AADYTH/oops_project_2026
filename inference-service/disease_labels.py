"""
disease_labels.py — shared crop/disease label normalization.

Why this exists as its own module
----------------------------------
Once more than one model is in play (see ensemble.py), each one was
fine-tuned on its own dataset and therefore speaks its own label dialect:
one says "Tomato___Late_blight", another says "Tomato Late Blight", a third
might just say "late_blight" with the crop implied by a different head
entirely. If every model's raw label were compared verbatim, four models
that all correctly recognise the same disease would look like a 4-way
disagreement purely because of string formatting — which would make the
"panel of models" feature actively misleading rather than useful.

This module is the single place that turns any of those raw strings into a
canonical (crop, disease) pair, so votes from different architectures can
actually be tallied against each other. It is NOT a claim that this mapping
is exhaustive — see CANONICAL_DISEASE_RULES below — it only needs to cover
the disease vocabulary the configured models actually produce, and is meant
to be extended as you add or swap models (see ensemble.py's MODEL SPECS).
"""

import os
import re

# ---------------------------------------------------------------------------
# Diseases considered fast-spreading / quarantine-notifiable regardless of
# how localized the damage looks on a single plant. Review against your
# region's plant-quarantine authority list before relying on this.
# ---------------------------------------------------------------------------
EPIDEMIC_RISK_KEYWORDS = {
    "late blight",
    "bacterial leaf blight",
    "rice blast",
    "stripe rust",
    "yellow rust",
    "citrus greening",
    "huanglongbing",
    "panama wilt",
    "fusarium wilt tr4",
    "wheat rust",
}

# Exact-match table for the PlantVillage-style "Crop___Disease" labels used
# by the project's original default model. Kept for backward compatibility
# and because exact lookups are cheaper/safer than keyword matching when
# they're available.
EXACT_LABEL_CANONICALIZATION = {
    "tomato___late_blight": ("Tomato", "Late Blight (Phytophthora infestans)"),
    "potato___late_blight": ("Potato", "Late Blight (Phytophthora infestans)"),
    "tomato___early_blight": ("Tomato", "Early Blight (Alternaria solani)"),
    "corn_(maize)___northern_leaf_blight": ("Maize", "Northern Corn Leaf Blight"),
    "apple___apple_scab": ("Apple", "Apple Scab"),
    "grape___powdery_mildew": ("Grape", "Powdery Mildew"),
    "squash___powdery_mildew": ("Squash", "Powdery Mildew"),
    "cherry_(including_sour)___powdery_mildew": ("Cherry", "Powdery Mildew"),
    "healthy": (None, "Healthy - No Disease Detected"),
}

# Fallback keyword rules used when a label doesn't hit the exact table above
# (i.e. almost always, for any model that ISN'T the original default). Order
# matters: first matching rule wins, so more specific phrases (e.g. "late
# blight") are listed before generic ones (e.g. "blight" alone isn't listed
# at all, to avoid mis-merging early/late blight into one bucket).
#
# This list only covers the disease vocabulary the four default ensemble
# models are documented as producing (see ensemble.py). If you swap in a
# different model, check its label list (transformers pipeline exposes it at
# `classifier.model.config.id2label`) and extend this table — an
# uncategorized label still gets a best-effort Title Case pass-through
# rather than being silently dropped, so nothing disappears, but it also
# won't get merged with equivalent labels from other models until you add
# the rule.
CANONICAL_DISEASE_RULES = [
    (("healthy",), "Healthy - No Disease Detected"),
    (("late", "blight"), "Late Blight (Phytophthora infestans)"),
    (("early", "blight"), "Early Blight (Alternaria solani)"),
    (("bacterial", "spot"), "Bacterial Spot"),
    (("bacterial", "blight"), "Bacterial Leaf Blight"),
    (("leaf", "mold"), "Leaf Mold"),
    (("septoria",), "Septoria Leaf Spot"),
    (("spider", "mite"), "Spider Mites (Two-Spotted)"),
    (("target", "spot"), "Target Spot"),
    (("yellow", "leaf", "curl"), "Yellow Leaf Curl Virus"),
    (("mosaic",), "Mosaic Virus"),
    (("northern", "leaf", "blight"), "Northern Corn Leaf Blight"),
    (("gray", "leaf", "spot"), "Gray Leaf Spot"),
    (("cercospora",), "Gray Leaf Spot"),
    (("common", "rust"), "Common Rust"),
    (("black", "rot"), "Black Rot"),
    (("cedar", "apple", "rust"), "Cedar Apple Rust"),
    (("scab",), "Apple Scab"),
    (("powdery", "mildew"), "Powdery Mildew"),
    (("esca",), "Esca (Black Measles)"),
    (("huanglongbing",), "Citrus Greening (Huanglongbing)"),
    (("greening",), "Citrus Greening (Huanglongbing)"),
    (("leaf", "scorch"), "Leaf Scorch"),
    (("stripe", "rust"), "Stripe Rust"),
    (("yellow", "rust"), "Yellow Rust"),
    (("wheat", "rust"), "Wheat Rust"),
    (("blast",), "Rice Blast"),
    (("blight",), "Blight (unspecified)"),
]

# Crop-name keyword rules, same "first match wins" ordering rationale.
CANONICAL_CROP_RULES = [
    (("tomato",), "Tomato"),
    (("potato",), "Potato"),
    (("corn",), "Maize"),
    (("maize",), "Maize"),
    (("apple",), "Apple"),
    (("grape",), "Grape"),
    (("squash",), "Squash"),
    (("cherry",), "Cherry"),
    (("pepper", "bell"), "Pepper (Bell/Chili)"),
    (("pepper",), "Pepper (Bell/Chili)"),
    (("strawberry",), "Strawberry"),
    (("peach",), "Peach"),
    (("orange",), "Orange/Citrus"),
    (("citrus",), "Orange/Citrus"),
    (("soybean",), "Soybean"),
    (("wheat",), "Wheat"),
    (("rice",), "Rice"),
    (("blueberry",), "Blueberry"),
    (("raspberry",), "Raspberry"),
    (("sugarcane",), "Sugarcane"),
    (("cotton",), "Cotton"),
]


def _keywords_present(normalized_text: str, keywords: tuple) -> bool:
    return all(kw in normalized_text for kw in keywords)


def canonicalize_label(raw_label: str):
    """
    Best-effort mapping from ANY model's raw label to a comparable
    (crop, disease, is_epidemic_risk) tuple.

    Strategy, in order:
      1. Exact match against EXACT_LABEL_CANONICALIZATION (fast path for the
         original PlantVillage-family default model).
      2. Split "Crop___Disease"-style labels on the "___" delimiter used by
         the PlantVillage family, if present.
      3. Run keyword rules over whatever text remains to normalize both the
         crop and disease name, so differently-worded labels from different
         models land on the same canonical string when they mean the same
         thing.
      4. If no keyword rule matches, fall back to a Title Case pass-through
         of the raw text rather than dropping it — an unrecognized label is
         still shown to the user, it just won't merge with an equivalent
         label from a different model until a rule is added above.
    """
    normalized = raw_label.strip().lower().replace(" ", "_")

    if normalized in EXACT_LABEL_CANONICALIZATION:
        crop, disease = EXACT_LABEL_CANONICALIZATION[normalized]
        crop = crop or "Unknown crop"
        is_epidemic = any(kw in disease.lower() for kw in EPIDEMIC_RISK_KEYWORDS)
        return crop, disease, is_epidemic

    # IMPORTANT: split on "___" FIRST, then replace underscores within each
    # part. Doing it in the other order destroys the "___" delimiter itself.
    if "___" in raw_label:
        parts = raw_label.split("___")
        crop_part = parts[0].replace("_", " ").strip()
        disease_part = parts[-1].replace("_", " ").strip()
    else:
        crop_part = raw_label.replace("_", " ").strip()
        disease_part = crop_part

    search_text = re.sub(r"[^a-z0-9 ]", " ", (crop_part + " " + disease_part).lower())
    search_words = set(search_text.split())

    disease = None
    for keywords, canonical_name in CANONICAL_DISEASE_RULES:
        if all(any(kw in w or w in kw for w in search_words) for kw in keywords):
            disease = canonical_name
            break
    if disease is None:
        disease_raw = disease_part.title()
        disease = disease_raw if disease_raw else "UNKNOWN"

    crop = None
    for keywords, canonical_name in CANONICAL_CROP_RULES:
        if all(any(kw in w or w in kw for w in search_words) for kw in keywords):
            crop = canonical_name
            break
    if crop is None:
        crop = crop_part.title() if crop_part and crop_part != disease_part else "Unknown crop"

    is_epidemic = any(kw in disease.lower() for kw in EPIDEMIC_RISK_KEYWORDS)
    return crop, disease, is_epidemic


# ---------------------------------------------------------------------------
# Confidence gating thresholds (Tier 1 upgrade #1).
# ---------------------------------------------------------------------------
CONFIDENCE_HIGH_THRESHOLD = float(os.environ.get("CONFIDENCE_HIGH_THRESHOLD", "75.0"))
CONFIDENCE_MEDIUM_THRESHOLD = float(os.environ.get("CONFIDENCE_MEDIUM_THRESHOLD", "45.0"))


def confidence_level_for(confidence_percent: float) -> str:
    if confidence_percent >= CONFIDENCE_HIGH_THRESHOLD:
        return "HIGH"
    if confidence_percent >= CONFIDENCE_MEDIUM_THRESHOLD:
        return "MEDIUM"
    return "LOW"
