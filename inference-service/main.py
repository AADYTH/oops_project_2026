"""
Crop Disease Scanner — ML Inference Microservice
=================================================

This service performs the two things that genuinely cannot be "coded" by
hand and must come from real computer vision / machine learning:

1. Disease classification — via a Hugging Face image-classification
   pipeline loaded from a real pretrained checkpoint (see MODEL_ID below).
2. Severity scoring — via real pixel-level lesion/necrosis segmentation
   using OpenCV colour thresholding in HSV space, not a random number.

READ THIS BEFORE DEPLOYING TO PRODUCTION
-----------------------------------------
No single public pretrained model reliably classifies disease across every
crop and every plant part (leaf, stem, fruit, root) at 80-90% accuracy out
of the box. To responsibly hit that bar for YOUR crops:

  1. Pick the crops/diseases you actually need to support.
  2. Fine-tune an image classifier (e.g. a ViT or EfficientNet backbone)
     on a labelled dataset that matches your real conditions — PlantVillage
     and PlantDoc are good starting points, but they are mostly clean,
     single-leaf, plain-background images. Field photos (blurry, cluttered
     backgrounds, mixed lighting, non-leaf parts) need a dataset that looks
     like what your users will actually upload, or accuracy will be much
     lower in production than on a clean benchmark.
  3. Hold out a real field-condition test set and measure accuracy
     yourself — don't trust a benchmark number from someone else's paper.
  4. Re-run step 3 periodically as you collect real user-submitted images
     (with consent) to catch model drift.

This file wires in a placeholder MODEL_ID pointing at a real, public
Hugging Face plant-disease classification checkpoint so the service is
100% functional end-to-end out of the box — swap MODEL_ID for your own
fine-tuned model once you have one, by setting the MODEL_ID environment
variable.
"""

import base64
import io
import logging
import os
from typing import List, Optional

import cv2
import numpy as np
from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from PIL import Image
from pydantic import BaseModel
from transformers import pipeline

from disease_labels import canonicalize_label
from ensemble import PanelClassifier, ModelVote, ConsensusResult, compute_consensus, gated_confidence_level
from leaf_validator import validate_leaf_content, NO_LEAF_MESSAGES

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("inference-service")

app = FastAPI(title="Crop Disease Inference Service", version="1.0.0")

# ---------------------------------------------------------------------------
# Global error visibility. Without these, a malformed/corrupted request (for
# example, a request body mangled in transit by a network layer between the
# Java backend and this service — see the HTTP/1.1 fix in
# RemoteMlInferenceService for one real cause of that) surfaces here as
# FastAPI's generic 422, with none of the detail our own code normally
# includes. That previously got misread as "photo quality is too low" on the
# Java side purely because the shapes looked similar. These handlers make
# the ACTUAL cause visible in this terminal instead of guessing.
# ---------------------------------------------------------------------------
@app.exception_handler(RequestValidationError)
async def log_validation_errors(request: Request, exc: RequestValidationError):
    logger.warning("Request to %s failed schema validation: %s", request.url.path, exc.errors())
    return JSONResponse(
        status_code=422,
        content={
            "errorCode": "REQUEST_VALIDATION_ERROR",
            "message": "The request body didn't match what this service expects. This is a backend/network "
                       "issue, not a photo-quality problem. See the inference-service terminal for the exact "
                       "field(s) that failed validation.",
            "details": exc.errors(),
        },
    )


@app.exception_handler(Exception)
async def log_unhandled_errors(request: Request, exc: Exception):
    logger.exception("Unhandled error while processing %s", request.url.path)
    return JSONResponse(
        status_code=500,
        content={"errorCode": "INTERNAL_ERROR", "message": f"Unhandled error: {exc}"},
    )

# ---------------------------------------------------------------------------
# Model loading
# ---------------------------------------------------------------------------
# Swap this for your own fine-tuned checkpoint once trained. Any Hugging Face
# `image-classification` compatible model id or local path works here.
MODEL_ID = os.environ.get("MODEL_ID", "linkanjarad/mobilenet_v2_1.0_224-plant-disease-identification")

_classifier = None


def get_classifier():
    """Lazily load the model once per process, not per-request."""
    global _classifier
    if _classifier is None:
        logger.info("Loading image-classification model '%s' (first request may be slow)...", MODEL_ID)
        _classifier = pipeline("image-classification", model=MODEL_ID, top_k=3)
        logger.info("Model loaded.")
    return _classifier


# ---------------------------------------------------------------------------
# Leaf-only scanner (CropGuard architecture requirement).
#
# CropGuard is deliberately scoped to leaf photos only — see README,
# "Leaf-only scanner". MODEL_ID above (and every panel model in ensemble.py)
# is fine-tuned on PlantVillage-style datasets, which are exclusively
# single-leaf, plain-background photos; none of them have ever seen a
# stem, fruit, flower, root, or whole-plant training image. Running those
# photos through the classifier anyway used to produce a confident-looking
# crop/disease guess with zero ground truth behind it, just labelled LOW
# confidence after the fact — a UX safety net, not real gating.
#
# As of this pass, gating is based on actual pixel evidence from
# leaf_validator.py (colour/shape analysis of the submitted photo), not on
# the client-supplied plant_part_hint. If the photo itself doesn't contain
# a plausible leaf-shaped, plant-coloured region, the request is rejected
# with NO_LEAF_DETECTED before the classifier ever runs — no guess is
# fabricated for fruit, flower, root, stem, whole-plant, or non-plant
# photos, matching the "do not invent a disease" requirement. The hint is
# still accepted and logged (useful for future analytics / a future
# part-specific model), but it no longer changes whether — or how — a
# photo gets diagnosed.
# ---------------------------------------------------------------------------
NON_LEAF_PARTS = {"STEM", "FRUIT", "ROOT", "FLOWER", "WHOLE_PLANT"}

class ClassifyRequest(BaseModel):
    image_base64: str
    plant_part_hint: Optional[str] = "UNKNOWN"
    # If true, skip the blur/darkness pre-check. Used by the offline-sync
    # path where a low-quality photo may already have been accepted locally
    # and the user has chosen to submit it anyway.
    skip_quality_check: Optional[bool] = False


class AlternativePrediction(BaseModel):
    crop_name: str
    disease_name: str
    confidence_percent: float


class ModelVoteOut(BaseModel):
    """One panel member's independent verdict, for full transparency in the UI."""
    model_id: str
    display_name: str
    architecture: str
    crop_name: str
    disease_name: str
    confidence_percent: float
    is_epidemic_risk: bool


class LeafEvidenceOut(BaseModel):
    """Raw signals behind the leaf-detection gate, for debugging/transparency."""
    plant_material_ratio: float
    largest_region_ratio: float
    largest_region_fill_ratio: float


class ConsensusOut(BaseModel):
    """How the panel reconciled its votes into the final answer."""
    level: str  # UNANIMOUS | MAJORITY | SPLIT | SINGLE_MODEL
    agreeing_models: int
    total_models: int
    agreeing_model_names: List[str] = []


class ClassifyResponse(BaseModel):
    crop_name: str
    disease_name: str
    plant_part: str
    confidence_percent: float
    severity_percent: float
    is_epidemic_risk: bool
    # --- Confidence gating (Tier 1 upgrade) ---------------------------------
    # "HIGH" (>=75%), "MEDIUM" (45-75%), "LOW" (<45%). The frontend uses this
    # to decide whether to show one confident-looking diagnosis or an
    # "uncertain — here are a few possibilities" view. Thresholds are
    # heuristic defaults, not derived from a calibration study on your
    # crops; recalibrate them once you have real accuracy-vs-confidence data
    # (see /v1/feedback loop on the backend).
    confidence_level: str
    alternatives: List[AlternativePrediction] = []
    # --- Leaf-only gating (real pixel-evidence based, see leaf_validator.py) ---
    # part_supported is now a legacy-named alias for "a leaf was actually
    # detected in this photo" — kept under its original name for backward
    # wire-compatibility with older backend builds. When the photo doesn't
    # pass leaf validation, /v1/classify returns 422 NO_LEAF_DETECTED
    # instead (see below) — this field/response combination is not reached
    # in that case; it's retained here only for the (now rare) case where a
    # caller wants the raw leaf-evidence numbers alongside a successful
    # classification.
    part_supported: bool = True
    part_warning: Optional[str] = None
    leaf_evidence: Optional["LeafEvidenceOut"] = None
    # --- Ensemble / "panel of models" (Tier upgrade) -----------------------
    # model_panel always has at least one entry. When ENSEMBLE_ENABLED=false
    # (see below), it has exactly one entry and consensus.level is
    # "SINGLE_MODEL" — the shape stays the same either way so the frontend
    # doesn't need two different code paths.
    model_panel: List[ModelVoteOut] = []
    consensus: Optional[ConsensusOut] = None


def decode_image(image_base64: str) -> Image.Image:
    try:
        raw = base64.b64decode(image_base64)
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        return image
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")


def estimate_severity_percent(image: Image.Image) -> float:
    """
    Real (not simulated) lesion-area estimation via HSV colour thresholding.

    Approach:
      1. Convert to HSV.
      2. Classify each pixel within the plant-material mask as either
         "healthy green tissue" or "necrotic/chlorotic tissue" (brown,
         yellow, black, or grey-brown lesion colouring).
      3. Severity = necrotic pixel count / total plant-material pixel count.

    This is a lightweight, explainable heuristic used widely in plant
    pathology image-analysis tooling (e.g. leaf-disease-severity index
    tools). It is NOT a deep-learning segmentation model, so it can be
    fooled by naturally red/purple/variegated foliage or very dark
    backgrounds — see README limitations. For higher accuracy, replace this
    with a trained semantic-segmentation model (e.g. a U-Net fine-tuned on
    pixel-labelled lesion masks).
    """
    cv_image = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)
    hsv = cv2.cvtColor(cv_image, cv2.COLOR_BGR2HSV)

    # Plant-material mask: broad green + brown/yellow range to exclude
    # background (sky, soil far from the lesion, hands, containers, etc.)
    lower_plant = np.array([15, 25, 25])
    upper_plant = np.array([95, 255, 255])
    plant_mask = cv2.inRange(hsv, lower_plant, upper_plant)

    total_plant_pixels = int(np.count_nonzero(plant_mask))
    if total_plant_pixels == 0:
        # No detectable plant material at all — cannot estimate severity
        # meaningfully; return 0 rather than fabricating a number.
        return 0.0

    # Healthy tissue: green hues with reasonable saturation/value.
    lower_healthy = np.array([35, 40, 40])
    upper_healthy = np.array([85, 255, 255])
    healthy_mask = cv2.inRange(hsv, lower_healthy, upper_healthy)
    healthy_mask = cv2.bitwise_and(healthy_mask, plant_mask)

    necrotic_pixels = total_plant_pixels - int(np.count_nonzero(healthy_mask))
    severity = (necrotic_pixels / total_plant_pixels) * 100.0
    return round(max(0.0, min(100.0, severity)), 1)


# ---------------------------------------------------------------------------
# Ensemble ("panel of models") toggle.
#
# ON by default: every scan runs through the full panel defined in
# ensemble.py (the current single-model default plus 3 additional public
# checkpoints), which vote and reconcile before an answer is returned — see
# ensemble.py's module docstring for exactly how, and for an honest
# assessment of what this does and doesn't buy you accuracy-wise.
#
# DEFAULT IS NOW OFF. CropGuard's own architecture principle (see README,
# "Primary disease model") is: select exactly ONE primary model after real
# benchmarking, and only run more than one if that benchmarking shows a
# meaningful, repeatable accuracy improvement. Nobody has actually run
# scripts/benchmark_models.py against a held-out PlantVillage/PlantDoc split
# in this environment (it requires downloading datasets and Hugging Face
# checkpoints, which this build/sandbox has no network access to do) — so
# there is no measured evidence an ensemble beats the single MODEL_ID model,
# only the architectural-diversity argument in this module's docstring.
# Absent that evidence, the honest default is ONE model. Set
# ENSEMBLE_ENABLED=true to opt back into the 4-model panel once you've
# actually run the benchmark and it shows a real, repeatable improvement for
# your target crops.
# ---------------------------------------------------------------------------
ENSEMBLE_ENABLED = os.environ.get("ENSEMBLE_ENABLED", "false").strip().lower() not in ("false", "0", "no")

_panel: Optional[PanelClassifier] = None


def get_panel() -> PanelClassifier:
    global _panel
    if _panel is None:
        _panel = PanelClassifier()
    return _panel


# ---------------------------------------------------------------------------
# Image quality pre-check (Tier 3 upgrade #11).
#
# Rejecting an unusable photo before spending a model inference call on it
# is both cheaper and kinder to the user: a blurry/black photo produces a
# meaningless "diagnosis" that looks just as confident-looking as a good
# one. This mirrors the equivalent client-side check in frontend/app.js —
# the client check saves the user a wasted upload on their own data plan,
# this one is the server-side backstop that can't be bypassed by a
# modified client.
# ---------------------------------------------------------------------------
BLUR_VARIANCE_MIN = float(os.environ.get("BLUR_VARIANCE_MIN", "25.0"))
BRIGHTNESS_MIN = float(os.environ.get("BRIGHTNESS_MIN", "20.0"))
BRIGHTNESS_MAX = float(os.environ.get("BRIGHTNESS_MAX", "250.0"))
# A genuinely "blown out" or "black frame" photo isn't just bright/dark on
# average — it also has almost no local contrast (a flat wall of white or
# black has near-zero edges). Real photos of pale symptoms (e.g. powdery
# mildew's white coating, chlorosis, leaf-spot halos) can legitimately have
# a high average brightness while still being full of fine texture/edges,
# so brightness alone is not a safe rejection signal for THIS app's subject
# matter — unlike a generic photo-quality checker, rejecting "too white"
# photos here would systematically reject the very symptom the app exists
# to catch. Overexposure/darkness are only flagged when the photo is BOTH
# brightness-extreme AND texture-flat.
FLAT_IMAGE_BLUR_CEILING = float(os.environ.get("FLAT_IMAGE_BLUR_CEILING", "60.0"))


class ImageQualityIssue(BaseModel):
    passed: bool
    reason: Optional[str] = None
    blur_score: float
    brightness: float


def check_image_quality(image: Image.Image) -> ImageQualityIssue:
    gray = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2GRAY)
    # Variance of the Laplacian is a standard, cheap blur estimator: a sharp
    # image has strong edges (high variance of the second derivative); a
    # blurry one is smooth (low variance).
    blur_score = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    brightness = float(gray.mean())

    if blur_score < BLUR_VARIANCE_MIN:
        result = ImageQualityIssue(passed=False, reason="IMAGE_TOO_BLURRY", blur_score=blur_score, brightness=brightness)
    elif brightness < BRIGHTNESS_MIN and blur_score < FLAT_IMAGE_BLUR_CEILING:
        result = ImageQualityIssue(passed=False, reason="IMAGE_TOO_DARK", blur_score=blur_score, brightness=brightness)
    elif brightness > BRIGHTNESS_MAX and blur_score < FLAT_IMAGE_BLUR_CEILING:
        result = ImageQualityIssue(passed=False, reason="IMAGE_OVEREXPOSED", blur_score=blur_score, brightness=brightness)
    else:
        result = ImageQualityIssue(passed=True, blur_score=blur_score, brightness=brightness)

    if not result.passed:
        logger.warning(
            "Image quality check REJECTED photo: reason=%s blur_score=%.1f (min %.1f) brightness=%.1f (range %.1f-%.1f)",
            result.reason, blur_score, BLUR_VARIANCE_MIN, brightness, BRIGHTNESS_MIN, BRIGHTNESS_MAX,
        )
    else:
        logger.info("Image quality check passed: blur_score=%.1f brightness=%.1f", blur_score, brightness)

    return result


@app.get("/health")
def health():
    return {
        "status": "OK",
        "model": MODEL_ID,
        "ensemble_enabled": ENSEMBLE_ENABLED,
    }


@app.get("/v1/models")
def models_status():
    """Which panel members are configured and whether each has loaded
    successfully so far in this process (a model shows load_failed=false,
    loaded=false before its first use — loading is lazy)."""
    if not ENSEMBLE_ENABLED:
        return {
            "ensemble_enabled": False,
            "panel": [{
                "model_id": MODEL_ID,
                "display_name": "Single model (ensemble disabled)",
                "architecture": "unknown",
                "loaded": _classifier is not None,
                "load_failed": False,
            }],
        }
    return {"ensemble_enabled": True, "panel": get_panel().status()}


@app.post("/v1/check-quality", response_model=ImageQualityIssue)
def check_quality(request: ClassifyRequest):
    """Standalone quality check the backend (or frontend, via the backend
    proxy) can call before committing to a full classification call."""
    image = decode_image(request.image_base64)
    return check_image_quality(image)


@app.post("/v1/check-leaf", response_model=LeafEvidenceOut)
def check_leaf(request: ClassifyRequest):
    """Standalone leaf-detection check, mirroring /v1/check-quality — lets a
    caller (or the frontend, via the backend proxy) check leaf content
    before committing to a full classification call."""
    image = decode_image(request.image_base64)
    result = validate_leaf_content(image)
    return LeafEvidenceOut(
        plant_material_ratio=result.plant_material_ratio,
        largest_region_ratio=result.largest_region_ratio,
        largest_region_fill_ratio=result.largest_region_fill_ratio,
    )


@app.post("/v1/classify", response_model=ClassifyResponse)
def classify(request: ClassifyRequest):
    image = decode_image(request.image_base64)

    # -----------------------------------------------------------------
    # Leaf-only gate (README "Leaf-only scanner"). Runs BEFORE the
    # quality check and BEFORE any model inference: there is no point
    # telling someone their photo of a shoe is "too blurry" or spending a
    # model call on it. This check is intentionally about CONTENT (is
    # there a leaf here at all), not IMAGE QUALITY (blur/exposure) — those
    # stay as separate, separately-reported failure reasons so the user
    # gets the right corrective instruction for the right problem.
    # -----------------------------------------------------------------
    leaf_result = validate_leaf_content(image)
    if not leaf_result.leaf_detected:
        logger.info(
            "Leaf validation REJECTED photo: reason=%s plant_material_ratio=%.3f largest_region_ratio=%.3f "
            "fill_ratio=%.3f",
            leaf_result.reason, leaf_result.plant_material_ratio, leaf_result.largest_region_ratio,
            leaf_result.largest_region_fill_ratio,
        )
        raise HTTPException(
            status_code=422,
            detail={
                "errorCode": "NO_LEAF_DETECTED",
                "message": NO_LEAF_MESSAGES.get(
                    leaf_result.reason,
                    "NO LEAF DETECTED — this photo doesn't appear to contain a usable leaf.",
                ),
                "plantMaterialRatio": leaf_result.plant_material_ratio,
                "largestRegionRatio": leaf_result.largest_region_ratio,
            },
        )

    if not request.skip_quality_check:
        quality = check_image_quality(image)
        if not quality.passed:
            reason_messages = {
                "IMAGE_TOO_BLURRY": "This photo looks too blurry to diagnose reliably. Hold the camera steady, "
                                     "focus close to the leaf, and try again.",
                "IMAGE_TOO_DARK": "This photo is too dark to diagnose reliably. Retake it in better light.",
                "IMAGE_OVEREXPOSED": "This photo is heavily washed out/overexposed. Retake it out of direct glare "
                                      "or bright backlighting.",
            }
            raise HTTPException(
                status_code=422,
                detail={
                    "errorCode": quality.reason,
                    "message": reason_messages.get(
                        quality.reason,
                        "The photo quality is too low to diagnose reliably. Please retake it in good light, "
                        "holding the camera steady and close to the leaf.",
                    ),
                    "blurScore": quality.blur_score,
                    "brightness": quality.brightness,
                },
            )

    # -----------------------------------------------------------------
    # Run either the full panel or the single legacy model, producing the
    # same (votes, consensus) shape either way so everything downstream
    # (part-gating, severity override, response building) doesn't need to
    # know which mode is active.
    # -----------------------------------------------------------------
    if ENSEMBLE_ENABLED:
        try:
            votes, consensus = get_panel().classify(image)
        except RuntimeError as e:
            # Every panel model failed — this is a real infrastructure
            # problem (e.g. no network to Hugging Face on first boot), not a
            # "photo quality" issue, so it's a 503, not a fabricated result.
            raise HTTPException(status_code=503, detail=str(e))
        predictions = None  # not used in ensemble mode; alternatives come from non-winning votes instead
    else:
        classifier = get_classifier()
        raw_predictions = classifier(image)
        if not raw_predictions:
            raise HTTPException(status_code=500, detail="Model returned no predictions")
        top_prediction = raw_predictions[0]
        crop_name, disease_name, is_epidemic_risk = canonicalize_label(top_prediction["label"])
        single_vote = ModelVote(
            model_id=MODEL_ID,
            display_name="Single model (ensemble disabled)",
            architecture="unknown",
            crop_name=crop_name,
            disease_name=disease_name,
            confidence_percent=round(float(top_prediction["score"]) * 100.0, 1),
            is_epidemic_risk=is_epidemic_risk,
            raw_label=top_prediction["label"],
        )
        votes = [single_vote]
        consensus = compute_consensus(votes)
        predictions = raw_predictions

    crop_name = consensus.crop_name
    disease_name = consensus.disease_name
    confidence_percent = consensus.confidence_percent
    is_epidemic_risk = consensus.is_epidemic_risk

    severity_percent = estimate_severity_percent(image)

    # A "healthy" classification should never carry lesion-based severity.
    if "healthy" in disease_name.lower():
        severity_percent = 0.0
        is_epidemic_risk = False

    plant_part = (request.plant_part_hint or "UNKNOWN").upper()
    if plant_part not in {"LEAF", "STEM", "FRUIT", "ROOT", "FLOWER", "WHOLE_PLANT"}:
        plant_part = "UNKNOWN"
    if plant_part in NON_LEAF_PARTS:
        # A leaf WAS detected in the photo itself (leaf_result.leaf_detected
        # is True or we wouldn't have reached this point), so the user's
        # hint disagreeing with the pixel evidence is just a mislabeled
        # dropdown, not a reason to distrust a real leaf photo. Log it and
        # move on rather than silently overriding the user's own selection.
        logger.info(
            "plant_part_hint=%s but leaf_validator found a genuine leaf-shaped region — proceeding as LEAF.",
            plant_part,
        )
        plant_part = "LEAF"

    part_supported = True
    part_warning = None

    confidence_level = gated_confidence_level(consensus)

    # Alternatives: every non-winning canonical (crop, disease) group any
    # panel member voted for, most-confident first. In single-model mode
    # this instead falls back to that one model's own runner-up guesses
    # (top_k=3), same as before the ensemble existed.
    alternatives: List[AlternativePrediction] = []
    if ENSEMBLE_ENABLED:
        seen = {(crop_name, disease_name)}
        for v in sorted(votes, key=lambda v: v.confidence_percent, reverse=True):
            key = (v.crop_name, v.disease_name)
            if key in seen:
                continue
            seen.add(key)
            alternatives.append(AlternativePrediction(
                crop_name=v.crop_name, disease_name=v.disease_name, confidence_percent=v.confidence_percent,
            ))
    else:
        for pred in predictions[1:3]:
            alt_crop, alt_disease, _ = canonicalize_label(pred["label"])
            alternatives.append(AlternativePrediction(
                crop_name=alt_crop,
                disease_name=alt_disease,
                confidence_percent=round(float(pred["score"]) * 100.0, 1),
            ))

    model_panel = [
        ModelVoteOut(
            model_id=v.model_id,
            display_name=v.display_name,
            architecture=v.architecture,
            crop_name=v.crop_name,
            disease_name=v.disease_name,
            confidence_percent=v.confidence_percent,
            is_epidemic_risk=v.is_epidemic_risk,
        )
        for v in votes
    ]
    consensus_out = ConsensusOut(
        level=consensus.level,
        agreeing_models=consensus.agreeing_models,
        total_models=consensus.total_models,
        agreeing_model_names=consensus.agreeing_model_names,
    )

    logger.info(
        "Classified crop=%s disease=%s confidence=%.1f%% (%s) severity=%.1f%% epidemic=%s part=%s "
        "part_supported=%s consensus=%s (%d/%d models agreed)",
        crop_name, disease_name, confidence_percent, confidence_level, severity_percent, is_epidemic_risk,
        plant_part, part_supported, consensus.level, consensus.agreeing_models, consensus.total_models,
    )

    return ClassifyResponse(
        crop_name=crop_name,
        disease_name=disease_name,
        plant_part=plant_part,
        confidence_percent=confidence_percent,
        severity_percent=severity_percent,
        is_epidemic_risk=is_epidemic_risk,
        confidence_level=confidence_level,
        alternatives=alternatives,
        part_supported=part_supported,
        part_warning=part_warning,
        leaf_evidence=LeafEvidenceOut(
            plant_material_ratio=leaf_result.plant_material_ratio,
            largest_region_ratio=leaf_result.largest_region_ratio,
            largest_region_fill_ratio=leaf_result.largest_region_fill_ratio,
        ),
        model_panel=model_panel,
        consensus=consensus_out,
    )
