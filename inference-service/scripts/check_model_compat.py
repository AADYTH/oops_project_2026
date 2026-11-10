"""
check_model_compat.py — sanity-check a candidate MODEL_ID before switching.

Why this exists
----------------
This project's default MODEL_ID (linkanjarad/mobilenet_v2_1.0_224-plant-
disease-identification) is a real, working checkpoint, but its OWN published
eval accuracy is ~78.6% on lab-condition PlantVillage leaf photos — and nothing
else, no stems/fruit/flowers/roots/field backgrounds. If you go looking for a
"better" model on the Hub, you'll find plenty of README claims of 97-99.5%+
accuracy. Two things to check before trusting any of them enough to point
MODEL_ID at it:

1. Is it actually loadable by `transformers.pipeline("image-classification",
   model=...)`? Not every repo on the Hub is a `transformers`-native
   checkpoint — some are raw PyTorch `.pth` state dicts (torchvision-style)
   that need custom loading code, not just a MODEL_ID string swap. This
   script's whole point is to fail loudly and immediately if that's the case,
   instead of you finding out at 2am in a Docker log.
2. What was that accuracy measured on? A 99% number on a held-out split of
   the SAME clean lab dataset the model was trained on tells you nothing
   about accuracy on a farmer's field photo — see README.md section 1.
   A model card that doesn't say what test set the number came from, or says
   "unknown dataset", is a yellow flag, not a green one.

This script does NOT evaluate real-world accuracy for you — that requires
your own labelled field-photo test set (README.md step 3). It only confirms
the checkpoint loads, tells you its label set (so you can update
LABEL_CANONICALIZATION in main.py), and runs it against any sample images you
point it at so you can eyeball a few predictions before committing.

Usage
-----
    pip install -r ../requirements.txt --break-system-packages
    python check_model_compat.py <model_id_or_local_path> [image1.jpg image2.jpg ...]

Example
-------
    python check_model_compat.py linkanjarad/mobilenet_v2_1.0_224-plant-disease-identification sample_leaf.jpg
"""

import sys


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    model_id = sys.argv[1]
    image_paths = sys.argv[2:]

    print(f"Attempting to load '{model_id}' via transformers.pipeline(\"image-classification\", ...)")
    print("(This downloads the checkpoint on first run — needs network access.)\n")

    try:
        from transformers import pipeline
    except ImportError:
        print("ERROR: `transformers` isn't installed in this environment. "
              "Run: pip install -r ../requirements.txt --break-system-packages")
        sys.exit(1)

    try:
        classifier = pipeline("image-classification", model=model_id, top_k=5)
    except Exception as e:
        print(f"FAILED TO LOAD as a transformers image-classification pipeline: {e}\n")
        print("This usually means the repo isn't a `transformers`-native checkpoint "
              "(e.g. it's a raw .pth/.pt state dict meant for plain PyTorch/torchvision "
              "loading code, not AutoModelForImageClassification). Check the model's "
              "\"Files and versions\" tab on Hugging Face for config.json + "
              "preprocessor_config.json + pytorch_model.bin/model.safetensors — if those "
              "aren't all present, this MODEL_ID string will NOT work as a drop-in swap "
              "in inference-service/main.py without extra loading code.")
        sys.exit(1)

    print("Loaded successfully.\n")

    id2label = getattr(classifier.model.config, "id2label", None)
    if id2label:
        labels = list(id2label.values())
        print(f"Label set ({len(labels)} classes):")
        for label in labels:
            print(f"  - {label}")
        print()
        non_leaf_hint = [l for l in labels if any(
            kw in l.lower() for kw in ("stem", "fruit", "root", "flower", "whole_plant", "non_leaf", "unknown")
        )]
        if non_leaf_hint:
            print("Note: this label set includes non-leaf/'unknown' categories — check whether "
                  "that means it was actually trained on non-leaf photos, or just has a catch-all "
                  "rejection class (which is genuinely useful — see main.py's part-gating logic):")
            for label in non_leaf_hint:
                print(f"  - {label}")
        else:
            print("Note: no non-leaf/'unknown' labels detected — this looks like a leaf-only "
                  "classifier, same limitation as the current default. Non-leaf photos will "
                  "still need the part_supported gating in main.py, not this model alone.")
        print("\nUpdate LABEL_CANONICALIZATION in main.py to map this exact label set to "
              "(crop, disease) pairs your remedy knowledge base recognizes.")
    else:
        print("Could not read a label set from the model config — inspect it manually before trusting it.")

    if not image_paths:
        print("\nNo sample images given — pass one or more image paths to see real predictions, e.g.:")
        print(f"  python {sys.argv[0]} {model_id} my_leaf_photo.jpg")
        return

    from PIL import Image
    print()
    for path in image_paths:
        try:
            image = Image.open(path).convert("RGB")
        except Exception as e:
            print(f"{path}: could not open image ({e})")
            continue
        predictions = classifier(image)
        print(f"{path}:")
        for p in predictions:
            print(f"  {p['label']:<40s} {p['score'] * 100:5.1f}%")


if __name__ == "__main__":
    main()
