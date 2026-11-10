"""
benchmark_models.py — actually select ONE primary model, with real data.

WHY THIS SCRIPT EXISTS AND HASN'T BEEN RUN FOR YOU
----------------------------------------------------
CropGuard's architecture requires picking exactly ONE primary disease model
based on real benchmarking (accuracy, macro-F1, per-class performance,
confusion matrix, real-world image performance, inference speed, model
size, offline feasibility) rather than trusting any model card's own
claimed number — see README, "Primary disease model" and "Model accuracy &
how to reach 80-90%".

That benchmarking genuinely was NOT run as part of this change, for a
concrete, checkable reason: the sandbox this code was written in only has
network access to pypi.org, npmjs.org, github.com and a short allow-list of
similar package registries — it cannot reach huggingface.co to download any
candidate checkpoint, nor Kaggle/the PlantVillage or PlantDoc mirrors to
download a labelled test set. Running this script requires an environment
with real internet access to those hosts, which is a you-run-this, not a
was-already-run-for-you, situation. Do not take a "final model selected"
claim from anyone (including a past run of this project) at face value
unless a report like the one this script prints is attached with real
numbers in it.

What this script actually does
-------------------------------
1. Loads a labelled test set from a local directory in ImageFolder layout
   (one subfolder per canonical class, images inside):
       test_data/
         Tomato___Late_blight/
           img001.jpg
           img002.jpg
         Tomato___healthy/
           ...
   Point --plantvillage-dir and --plantdoc-dir at your own prepared copies
   of each dataset (this script does not download them for you — see
   "Getting the datasets" below).
2. Runs every candidate model in CANDIDATE_MODELS (the four panel models
   already in ensemble.py, plus any you add) against that test set via the
   same `transformers.pipeline("image-classification", ...)` call
   production code uses — no separate/parallel scoring logic that could
   silently drift from what's actually deployed.
3. Canonicalizes every prediction through disease_labels.canonicalize_label
   — the same function production code uses — so a candidate model's own
   label dialect doesn't get an unfair or unfairly harsh score just for
   spelling a disease name differently.
4. Computes and prints, per model: overall accuracy, macro-F1, a
   per-class precision/recall/F1 breakdown, a confusion matrix, average
   inference latency, and on-disk checkpoint size.
5. Prints a plain-language recommendation: the model with the best
   macro-F1 (not raw accuracy — macro-F1 penalizes a model that only does
   well on whichever class has the most test images, which matters a lot
   for a real, class-imbalanced field dataset). You still have to look at
   the per-class and confusion-matrix output yourself before trusting that
   recommendation for YOUR target crops.

Getting the datasets
---------------------
- PlantVillage: multiple mirrors exist on Kaggle and Hugging Face Datasets
  (search "PlantVillage dataset"); pick one, verify its class list matches
  what you expect, and split off a held-out test portion that the
  candidate models were NOT trained on (a model that includes the exact
  PlantVillage split you test on in its own training data will score
  unrealistically high — check each model's card for what it trained on).
- PlantDoc: github.com/pratikkayal/PlantDoc-Dataset (real-world field
  photos, not lab photos — this is the one that actually stress-tests the
  "field condition gap" README section 1 talks about). Only a subset of
  PlantDoc's classes overlap with PlantVillage's; don't force-merge
  incompatible classes (README, "Dataset") — this script skips any
  PlantDoc subfolder whose class name doesn't map to something in
  disease_labels.py's canonicalization table, and tells you which ones it
  skipped so you can decide whether to extend that table instead of
  silently losing test coverage.

Also investigating "LeafVision"
---------------------------------
The spec that produced this pass explicitly asked this project to
investigate a candidate called "LeafVision". A web search at the time of
writing found no single, canonical, verifiably-real "LeafVision" plant-
disease checkpoint or paper — only a scatter of unrelated, small,
similarly-named community projects (e.g. assorted Roboflow "leaf
disease detection" datasets with a few hundred images each), none of which
is authoritative enough to add to CANDIDATE_MODELS without risking silently
benchmarking against the wrong thing. If you have a specific repo/paper/
Hugging Face model ID in mind when you say "LeafVision", add it to
CANDIDATE_MODELS below (following the existing ModelSpec shape in
ensemble.py) and this script will benchmark it like any other candidate —
but nothing already in this codebase should be described as "LeafVision"
without that link.

Usage
-----
    pip install -r ../requirements.txt scikit-learn --break-system-packages
    python benchmark_models.py --plantvillage-dir /path/to/plantvillage_test \\
                                --plantdoc-dir /path/to/plantdoc_test
"""

import argparse
import os
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))  # import sibling modules

from disease_labels import canonicalize_label  # noqa: E402
from ensemble import DEFAULT_MODEL_SPECS  # noqa: E402

IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def load_image_folder(root: Path):
    """Yields (image_path, folder_class_name) for an ImageFolder-style directory."""
    if not root.is_dir():
        return
    for class_dir in sorted(root.iterdir()):
        if not class_dir.is_dir():
            continue
        for image_path in sorted(class_dir.iterdir()):
            if image_path.suffix.lower() in IMAGE_EXTENSIONS:
                yield image_path, class_dir.name


def canonical_class_from_folder_name(folder_name: str):
    """
    Re-uses the SAME canonicalization table production code uses, so a test
    set built with PlantVillage-style folder names ("Tomato___Late_blight")
    maps to the identical (crop, disease) space predictions are compared
    against. Returns None (meaning "skip this class") if the folder name
    doesn't match anything in disease_labels.py — see module docstring on
    why this script does not guess a mapping for you.
    """
    crop, disease, _ = canonicalize_label(folder_name)
    if disease == "Unknown / Not Recognized":
        return None
    return (crop, disease)


def benchmark_one_model(spec, test_items, image_cache):
    from transformers import pipeline
    from PIL import Image

    print(f"\n{'=' * 70}\nBenchmarking: {spec.display_name}  ({spec.model_id})\n{'=' * 70}")

    try:
        clf = pipeline("image-classification", model=spec.model_id, top_k=1)
    except Exception as e:
        print(f"  FAILED TO LOAD: {e}")
        return None

    model_size_mb = None
    try:
        import huggingface_hub
        model_size_mb = sum(
            f.stat().st_size for f in Path(huggingface_hub.snapshot_download(spec.model_id)).rglob("*")
            if f.is_file()
        ) / (1024 * 1024)
    except Exception:
        pass  # size reporting is best-effort; never block benchmarking on it

    y_true, y_pred, latencies = [], [], []
    skipped = 0

    for image_path, folder_class in test_items:
        canonical_true = canonical_class_from_folder_name(folder_class)
        if canonical_true is None:
            skipped += 1
            continue
        try:
            image = image_cache.setdefault(image_path, Image.open(image_path).convert("RGB"))
        except Exception as e:
            print(f"  Could not open {image_path}: {e}")
            continue

        start = time.perf_counter()
        try:
            prediction = clf(image)[0]
        except Exception as e:
            print(f"  Inference failed on {image_path}: {e}")
            continue
        latencies.append(time.perf_counter() - start)

        crop, disease, _ = canonicalize_label(prediction["label"])
        y_true.append(canonical_true)
        y_pred.append((crop, disease))

    if not y_true:
        print("  No usable test images matched this model's evaluation (check class-name mapping).")
        return None

    if skipped:
        print(f"  Skipped {skipped} test images whose folder class name has no disease_labels.py mapping.")

    from sklearn.metrics import classification_report, confusion_matrix

    labels = sorted(set(y_true) | set(y_pred))
    y_true_str = [str(t) for t in y_true]
    y_pred_str = [str(p) for p in y_pred]
    labels_str = [str(l) for l in labels]

    report = classification_report(y_true_str, y_pred_str, labels=labels_str, zero_division=0, output_dict=True)
    accuracy = report["accuracy"]
    macro_f1 = report["macro avg"]["f1-score"]

    print(f"  Test images evaluated: {len(y_true)}")
    print(f"  Accuracy:              {accuracy * 100:.1f}%")
    print(f"  Macro F1:              {macro_f1:.3f}")
    print(f"  Avg inference latency: {sum(latencies) / len(latencies) * 1000:.0f} ms/image")
    if model_size_mb:
        print(f"  Checkpoint size:       {model_size_mb:.0f} MB")
    print("\n  Per-class report:")
    for label in labels_str:
        if label in report and isinstance(report[label], dict):
            r = report[label]
            print(f"    {label:<55s} P={r['precision']:.2f} R={r['recall']:.2f} "
                  f"F1={r['f1-score']:.2f} support={int(r['support'])}")

    cm = confusion_matrix(y_true_str, y_pred_str, labels=labels_str)
    print("\n  Confusion matrix (rows=true, cols=predicted; label order matches per-class report above):")
    for row in cm:
        print("    " + " ".join(f"{v:4d}" for v in row))

    return {
        "model_id": spec.model_id,
        "display_name": spec.display_name,
        "accuracy": accuracy,
        "macro_f1": macro_f1,
        "avg_latency_ms": sum(latencies) / len(latencies) * 1000,
        "model_size_mb": model_size_mb,
        "n_test_images": len(y_true),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plantvillage-dir", type=Path, default=None,
                         help="ImageFolder-style directory of held-out PlantVillage test images")
    parser.add_argument("--plantdoc-dir", type=Path, default=None,
                         help="ImageFolder-style directory of PlantDoc real-world test images")
    args = parser.parse_args()

    if not args.plantvillage_dir and not args.plantdoc_dir:
        print("Provide at least one of --plantvillage-dir / --plantdoc-dir. See this script's module "
              "docstring for the expected directory layout and where to get each dataset.")
        sys.exit(1)

    test_items = []
    if args.plantvillage_dir:
        pv_items = list(load_image_folder(args.plantvillage_dir))
        print(f"Loaded {len(pv_items)} PlantVillage test images from {args.plantvillage_dir}")
        test_items += pv_items
    if args.plantdoc_dir:
        pd_items = list(load_image_folder(args.plantdoc_dir))
        print(f"Loaded {len(pd_items)} PlantDoc test images from {args.plantdoc_dir}")
        test_items += pd_items

    if not test_items:
        print("No test images found — check the directory paths and layout.")
        sys.exit(1)

    image_cache = {}
    results = []
    for spec in DEFAULT_MODEL_SPECS:
        result = benchmark_one_model(spec, test_items, image_cache)
        if result:
            results.append(result)

    if not results:
        print("\nNo model produced usable results — nothing to recommend.")
        sys.exit(1)

    print(f"\n{'=' * 70}\nSUMMARY (sorted by macro F1, the recommended selection criterion)\n{'=' * 70}")
    results.sort(key=lambda r: r["macro_f1"], reverse=True)
    for r in results:
        size_str = f"{r['model_size_mb']:.0f} MB" if r["model_size_mb"] else "size unknown"
        print(f"  {r['display_name']:<45s} acc={r['accuracy']*100:5.1f}%  macroF1={r['macro_f1']:.3f}  "
              f"latency={r['avg_latency_ms']:5.0f}ms  {size_str}  (n={r['n_test_images']})")

    best = results[0]
    print(f"\nBy macro F1 alone, the best-performing candidate on THIS test set is: {best['display_name']} "
          f"({best['model_id']}).")
    print("Before setting MODEL_ID to this and disabling the ensemble, also check: does this hold up across "
          "BOTH PlantVillage and PlantDoc separately (a model can win on lab photos and lose on field photos, "
          "or vice versa — README section 1), and is the per-class breakdown above acceptable for your "
          "specific target crops, not just the aggregate number.")
    print("\nSet MODEL_ID=<winner> and ENSEMBLE_ENABLED=false once you've made that call.")


if __name__ == "__main__":
    main()
