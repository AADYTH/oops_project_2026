"""
leaf_validator.py — "is there actually a leaf in this photo?"

Why this exists
----------------
CropGuard is a *leaf-only* scanner (see README, "Leaf-only scanner"). Before
this module existed, the service trusted whatever `plant_part_hint` the
client sent and ran the classifier anyway — a photo of a tomato, a flower,
or someone's hand got a confident-looking crop/disease guess with zero
ground truth behind it, just gated to LOW confidence after the fact. That is
a UX safety net, not leaf detection.

This module makes an honest, evidence-based call — from the pixels
themselves, not the user-supplied hint — about whether the photo plausibly
contains a leaf worth diagnosing at all. If it doesn't, the caller should
return NO_LEAF_DETECTED and never invent a disease.

What this is, and isn't
------------------------
This is a classical computer-vision heuristic (colour-space segmentation +
shape/texture analysis of the plant-coloured region), not a trained leaf
detector. It cannot compete with an actual object-detection model trained
on a labelled leaf-vs-not-leaf dataset. What it CAN do reliably:

  - Reject images with essentially no plant-coloured material at all (a
    photo of the sky, a wall, a person, a piece of paper, a phone screen).
  - Reject images where the only plant-coloured material forms a shape with
    none of the broad, relatively flat, leaf-like characteristics (e.g. a
    sliver of background grass at the edge of an otherwise unrelated photo).
  - Stay lenient on exactly the conditions README section "Real-world image
    handling" calls out: blur, shadows, harsh sunlight, dust, water
    droplets, damaged/necrotic tissue, cheap-camera noise, and cluttered
    backgrounds. None of those change a leaf's basic colour/shape footprint
    enough to trip this check — deliberately, since over-rejecting real
    farmer photos is its own failure mode (see README).

This deliberately does NOT try to distinguish "leaf" from "fruit" or "whole
plant" — that finer distinction still needs a trained classifier. It only
answers the coarser, higher-confidence question: "is there a plant-like
leaf surface here at all, or is this a non-plant / no-plant-visible photo?"
A real leaf-vs-non-leaf-part classifier (trained on positive leaf crops and
negative fruit/stem/root/background crops) is the natural upgrade path once
that dataset exists — see the module docstring in scripts/benchmark_models.py.
"""

import cv2
import numpy as np
from dataclasses import dataclass
from typing import Optional
from PIL import Image


@dataclass
class LeafValidationResult:
    leaf_detected: bool
    plant_material_ratio: float  # fraction of the frame that is plant-coloured at all
    largest_region_ratio: float  # fraction of the frame occupied by the single largest plant-coloured blob
    largest_region_fill_ratio: float  # how "solid"/leaf-like (vs. wispy/grass-like) that blob's shape is
    reason: Optional[str] = None  # populated only when leaf_detected is False


# ---------------------------------------------------------------------------
# Thresholds. Deliberately permissive — see module docstring on why
# over-rejection is treated as the worse failure mode here. Tune these
# against real submitted photos over time (the same feedback loop used for
# confidence-threshold calibration, see backend ScanFeedbackEntity).
# ---------------------------------------------------------------------------
MIN_PLANT_MATERIAL_RATIO = 0.04   # at least ~4% of the frame must be plant-coloured
MIN_LARGEST_REGION_RATIO = 0.03   # the single biggest plant-coloured blob must cover ~3% of the frame
MIN_FILL_RATIO = 0.25             # blob must be reasonably solid, not a thin wisp/blade sliver


def _plant_color_mask(hsv: np.ndarray) -> np.ndarray:
    """
    Broad plant-material mask covering healthy green AND diseased tissue
    (yellow/brown/necrotic), since a leaf worth diagnosing is often
    partially discoloured by definition. Intentionally wider than a
    "healthy green only" mask — narrowing it would systematically reject
    the very symptomatic leaves this app exists to look at.
    """
    lower = np.array([10, 20, 20])
    upper = np.array([100, 255, 255])
    return cv2.inRange(hsv, lower, upper)


def validate_leaf_content(image: Image.Image) -> LeafValidationResult:
    cv_image = cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)
    hsv = cv2.cvtColor(cv_image, cv2.COLOR_BGR2HSV)
    frame_area = hsv.shape[0] * hsv.shape[1]

    mask = _plant_color_mask(hsv)
    # Light morphological close: bridges small gaps from veins/glare/water
    # droplets so a genuine leaf isn't fragmented into many tiny specks.
    kernel = np.ones((5, 5), np.uint8)
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)

    plant_pixels = int(np.count_nonzero(mask))
    plant_material_ratio = plant_pixels / frame_area if frame_area else 0.0

    if plant_material_ratio < MIN_PLANT_MATERIAL_RATIO:
        return LeafValidationResult(
            leaf_detected=False,
            plant_material_ratio=plant_material_ratio,
            largest_region_ratio=0.0,
            largest_region_fill_ratio=0.0,
            reason="NO_PLANT_MATERIAL_DETECTED",
        )

    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        return LeafValidationResult(
            leaf_detected=False,
            plant_material_ratio=plant_material_ratio,
            largest_region_ratio=0.0,
            largest_region_fill_ratio=0.0,
            reason="NO_PLANT_MATERIAL_DETECTED",
        )

    largest = max(contours, key=cv2.contourArea)
    largest_area = cv2.contourArea(largest)
    largest_region_ratio = largest_area / frame_area if frame_area else 0.0

    # Fill ratio: contour area vs. its own bounding box. A solid leaf-like
    # blob fills a decent fraction of its bounding box; thin grass blades or
    # scattered background-green noise fill very little of theirs.
    x, y, w, h = cv2.boundingRect(largest)
    bbox_area = max(w * h, 1)
    fill_ratio = largest_area / bbox_area

    if largest_region_ratio < MIN_LARGEST_REGION_RATIO or fill_ratio < MIN_FILL_RATIO:
        return LeafValidationResult(
            leaf_detected=False,
            plant_material_ratio=plant_material_ratio,
            largest_region_ratio=largest_region_ratio,
            largest_region_fill_ratio=fill_ratio,
            reason="NO_LEAF_SHAPED_REGION_DETECTED",
        )

    return LeafValidationResult(
        leaf_detected=True,
        plant_material_ratio=plant_material_ratio,
        largest_region_ratio=largest_region_ratio,
        largest_region_fill_ratio=fill_ratio,
    )


NO_LEAF_MESSAGES = {
    "NO_PLANT_MATERIAL_DETECTED": (
        "NO LEAF DETECTED — this photo doesn't appear to contain any plant material. "
        "CropGuard only diagnoses leaves; photograph a leaf directly, filling as much of the frame as possible."
    ),
    "NO_LEAF_SHAPED_REGION_DETECTED": (
        "NO LEAF DETECTED — this photo has some plant-coloured content, but nothing shaped like a leaf "
        "filling enough of the frame to diagnose. Get closer to a single leaf and make sure it's the main "
        "subject of the photo."
    ),
}
