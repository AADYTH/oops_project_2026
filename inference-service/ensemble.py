"""
ensemble.py — a panel of models that vote before the app commits to an answer.

READ THIS BEFORE TRUSTING THE WORD "BEST" ANYWHERE
----------------------------------------------------
There is no single, independently-verified "most accurate plant-disease
model on the internet." Every public leaderboard number you'll find for
this task (95%, 97%, 99.5%...) is almost always measured on a held-out
split of the SAME clean, single-leaf, plain-background dataset the model
was trained on (usually PlantVillage or a close relative) — not on real
farmer field photos. See README.md and the original single-model comments
in main.py for the long version of why that matters. Nothing below changes
that; four models built on largely the same underlying data source are not
four independent experts, they're four students who studied from
overlapping textbooks. What an ensemble DOES genuinely buy you:

  - Architecture diversity (MobileNet vs. Swin vs. ViT) means the models
    fail differently, not identically — so unanimous agreement is a
    somewhat stronger signal than any one model's confidence score, and a
    split vote is a real, honest signal that this particular photo is
    ambiguous, which a single model has no way to tell you about itself.
  - It catches a specific failure mode: one model quirking out on a
    label-mapping edge case, or over-fitting to a background artifact,
    stops silently being reported as ground truth.

It does NOT buy you real field accuracy on a crop/condition none of these
four were trained on. Swap or extend MODEL_SPECS with your own fine-tuned
checkpoint(s) — trained on YOUR field photos — the moment you have one;
that will always beat adding a fifth model trained on the same lab photos
as the other four.

How the panel decides
----------------------
1. Every configured model classifies the photo independently and casts one
   vote: its own top-1 (crop, disease) prediction (after canonicalization —
   see disease_labels.py — so differently-worded labels for the same
   disease are recognized as agreeing).
2. Votes are tallied by canonical (crop, disease). The group with the most
   votes wins; ties are broken by summed confidence.
3. The verdict's confidence_level is gated by how much the panel agreed,
   not just the winning group's raw average confidence:
     - UNANIMOUS (all models agree): normal HIGH/MEDIUM/LOW thresholds apply.
     - MAJORITY (more than half agree): capped at MEDIUM, even if the
       winning models were individually very confident — real disagreement
       happened, and the UI should say so.
     - SPLIT (no group has more than half): forced to LOW. This is the
       "the panel could not agree" case; it is reported to the user as
       exactly that, not smoothed over.

Any model that fails to load (network hiccup, incompatible checkpoint
format, etc.) is logged and excluded from the panel for that process
lifetime — a partial panel (e.g. 3 of 4) still runs and is reported
honestly via `total_models`, rather than the whole scan failing because one
model out of four had a bad day.
"""

import logging
import os
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Tuple

from disease_labels import canonicalize_label

logger = logging.getLogger("inference-service.ensemble")


@dataclass(frozen=True)
class ModelSpec:
    model_id: str
    display_name: str
    architecture: str
    notes: str


# ---------------------------------------------------------------------------
# The panel. Each entry is a real, publicly hosted Hugging Face checkpoint
# that exposes a standard `transformers` image-classification head (i.e.
# loadable via `pipeline("image-classification", model=<model_id>)` with no
# custom loading code) — the same bar the project's own
# scripts/check_model_compat.py already checks for the single-model default.
#
# Override any slot via env var without editing code, e.g. to point
# ENSEMBLE_MODEL_2 at your own fine-tuned checkpoint. Run
# `python scripts/check_model_compat.py <model_id>` against any replacement
# before deploying it — this file cannot verify that for you at import time
# without a network call and a multi-hundred-MB download.
# ---------------------------------------------------------------------------
DEFAULT_MODEL_SPECS: List[ModelSpec] = [
    ModelSpec(
        model_id=os.environ.get(
            "ENSEMBLE_MODEL_1", "linkanjarad/mobilenet_v2_1.0_224-plant-disease-identification"
        ),
        display_name="MobileNetV2 (PlantVillage)",
        architecture="MobileNetV2",
        notes="This project's original single-model default. Fine-tuned on the Kaggle "
              "'New Plant Diseases' mirror of PlantVillage, 38 classes, leaf-only photos.",
    ),
    ModelSpec(
        model_id=os.environ.get(
            "ENSEMBLE_MODEL_2", "ozair23/swin-tiny-patch4-window7-224-finetuned-plantdisease"
        ),
        display_name="Swin-Tiny (PlantVillage)",
        architecture="Swin Transformer",
        notes="Different architecture family (windowed transformer vs. MobileNet's "
              "convolutions) fine-tuned for the same task, which is the point of including "
              "it — it should fail on different photos than the MobileNet default, not the "
              "same ones.",
    ),
    ModelSpec(
        model_id=os.environ.get("ENSEMBLE_MODEL_3", "NouRed/recognize-plant-diseases-vit"),
        display_name="ViT (community plant-disease dataset)",
        architecture="Vision Transformer (ViT)",
        notes="Fine-tuned on a separate, community-curated plant-disease dataset rather "
              "than the exact PlantVillage split the other panel members share — the "
              "closest thing on this panel to genuine data diversity, not just "
              "architecture diversity.",
    ),
    ModelSpec(
        model_id=os.environ.get("ENSEMBLE_MODEL_4", "wambugu1738/crop_leaf_diseases_vit"),
        display_name="ViT (corn/potato/rice/wheat, field conditions)",
        architecture="Vision Transformer (ViT)",
        notes="Documented as trained on corn, potato, rice and wheat images captured "
              "under varied lighting/backgrounds rather than only clean lab photos — "
              "useful signal specifically for those four crops.",
    ),
]


@dataclass
class ModelVote:
    model_id: str
    display_name: str
    architecture: str
    crop_name: str
    disease_name: str
    confidence_percent: float
    is_epidemic_risk: bool
    raw_label: str


@dataclass
class ConsensusResult:
    crop_name: str
    disease_name: str
    confidence_percent: float
    is_epidemic_risk: bool
    level: str  # UNANIMOUS | MAJORITY | SPLIT | SINGLE_MODEL
    agreeing_models: int
    total_models: int
    agreeing_model_names: List[str] = field(default_factory=list)


class _ModelHandle:
    """Lazily-loaded, failure-isolated wrapper around one pipeline."""

    def __init__(self, spec: ModelSpec):
        self.spec = spec
        self._pipeline = None
        self._load_failed = False

    def get(self):
        if self._load_failed:
            return None
        if self._pipeline is None:
            try:
                from transformers import pipeline
                logger.info("Loading ensemble model '%s' (%s)...", self.spec.model_id, self.spec.display_name)
                self._pipeline = pipeline("image-classification", model=self.spec.model_id, top_k=3)
                logger.info("Loaded '%s'.", self.spec.model_id)
            except Exception as e:
                logger.error(
                    "Ensemble model '%s' (%s) failed to load and will be EXCLUDED from the "
                    "panel for the rest of this process: %s. If this keeps happening, run "
                    "`python scripts/check_model_compat.py %s` to see why.",
                    self.spec.model_id, self.spec.display_name, e, self.spec.model_id,
                )
                self._load_failed = True
                return None
        return self._pipeline


class PanelClassifier:
    """Holds the panel's model handles for the life of the process."""

    def __init__(self, specs: Optional[List[ModelSpec]] = None):
        self.specs = specs if specs is not None else DEFAULT_MODEL_SPECS
        self._handles: Dict[str, _ModelHandle] = {spec.model_id: _ModelHandle(spec) for spec in self.specs}

    def status(self) -> List[Dict]:
        """For a /v1/models health endpoint: which panel members are usable right now."""
        out = []
        for spec in self.specs:
            handle = self._handles[spec.model_id]
            out.append({
                "model_id": spec.model_id,
                "display_name": spec.display_name,
                "architecture": spec.architecture,
                "loaded": handle._pipeline is not None,
                "load_failed": handle._load_failed,
            })
        return out

    def vote(self, image) -> List[ModelVote]:
        votes: List[ModelVote] = []
        for spec in self.specs:
            clf = self._handles[spec.model_id].get()
            if clf is None:
                continue
            try:
                predictions = clf(image)
            except Exception as e:
                logger.error("Ensemble model '%s' raised during inference (not excluded, will retry "
                             "next request): %s", spec.model_id, e)
                continue
            if not predictions:
                continue
            top = predictions[0]
            crop, disease, is_epidemic = canonicalize_label(top["label"])
            votes.append(ModelVote(
                model_id=spec.model_id,
                display_name=spec.display_name,
                architecture=spec.architecture,
                crop_name=crop,
                disease_name=disease,
                confidence_percent=round(float(top["score"]) * 100.0, 1),
                is_epidemic_risk=is_epidemic,
                raw_label=top["label"],
            ))
        return votes

    def classify(self, image) -> Tuple[List[ModelVote], ConsensusResult]:
        votes = self.vote(image)
        if not votes:
            raise RuntimeError(
                "Every model in the panel failed to load or predict — see the ERROR log lines "
                "above for which model(s) and why. Nothing was diagnosed."
            )
        return votes, compute_consensus(votes)


def compute_consensus(votes: List[ModelVote]) -> ConsensusResult:
    tally: Dict[Tuple[str, str], Dict] = {}
    for v in votes:
        key = (v.crop_name, v.disease_name)
        entry = tally.setdefault(key, {"count": 0, "confidence_sum": 0.0, "names": [], "epidemic_votes": 0})
        entry["count"] += 1
        entry["confidence_sum"] += v.confidence_percent
        entry["names"].append(v.display_name)
        if v.is_epidemic_risk:
            entry["epidemic_votes"] += 1

    winner_key, winner = max(tally.items(), key=lambda kv: (kv[1]["count"], kv[1]["confidence_sum"]))
    total = len(votes)
    agree = winner["count"]
    avg_confidence = winner["confidence_sum"] / agree

    if total == 1:
        level = "SINGLE_MODEL"
    elif agree == total:
        level = "UNANIMOUS"
    elif agree > total / 2:
        level = "MAJORITY"
    else:
        level = "SPLIT"

    return ConsensusResult(
        crop_name=winner_key[0],
        disease_name=winner_key[1],
        confidence_percent=round(avg_confidence, 1),
        is_epidemic_risk=winner["epidemic_votes"] > 0,
        level=level,
        agreeing_models=agree,
        total_models=total,
        agreeing_model_names=winner["names"],
    )


def gated_confidence_level(consensus: ConsensusResult) -> str:
    """
    Confidence gating driven by PANEL AGREEMENT, not just the winning
    group's raw average score — see module docstring for the rationale.
    """
    from disease_labels import confidence_level_for

    if consensus.level in ("SINGLE_MODEL", "UNANIMOUS"):
        return confidence_level_for(consensus.confidence_percent)
    if consensus.level == "MAJORITY":
        raw = confidence_level_for(consensus.confidence_percent)
        return "MEDIUM" if raw == "HIGH" else raw
    # SPLIT
    return "LOW"
