"""
export_onnx_model.py — export the selected primary model for genuine
on-device (browser) offline inference.

Run this AFTER you've picked a primary model via benchmark_models.py.
This is a real export using `optimum` (Hugging Face's official ONNX export
tooling) — not a stub. It was not run as part of this change for the same
reason benchmark_models.py wasn't: this environment has no network access to
huggingface.co to download the source checkpoint. Run it yourself, then copy
the two output files into frontend/model/ (see onnx-classifier.js).

Usage
-----
    pip install optimum[exporters] onnx onnxruntime --break-system-packages
    python export_onnx_model.py <model_id> --output-dir ../../frontend/model

Example
-------
    python export_onnx_model.py linkanjarad/mobilenet_v2_1.0_224-plant-disease-identification \\
        --output-dir ../../frontend/model

What this produces
-------------------
  <output-dir>/model.onnx    — the exported, browser-runnable model
  <output-dir>/labels.json   — ["Tomato___Late_blight", "Tomato___healthy", ...]
                                index-ordered to match the model's output logits,
                                read the same "Crop___Disease" convention as
                                onnx-classifier.js expects

Quantization (recommended for phone-class hardware)
-----------------------------------------------------
Add --quantize to also produce model.quant.onnx, a dynamically-quantized
(int8) version that's roughly 4x smaller and noticeably faster on a phone
CPU, at a small, usually-acceptable accuracy cost. If you use it, point
onnx-classifier.js's MODEL_PATH at model.quant.onnx instead and re-verify
accuracy didn't drop unacceptably for your target crops (compare against
benchmark_models.py's numbers for the un-quantized export).
"""

import argparse
import json
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("model_id", help="Hugging Face model ID or local path of the SELECTED primary model")
    parser.add_argument("--output-dir", type=Path, default=Path("model_export"))
    parser.add_argument("--quantize", action="store_true", help="Also produce a dynamically-quantized int8 export")
    args = parser.parse_args()

    try:
        from optimum.exporters.onnx import main_export
    except ImportError:
        print("ERROR: `optimum` isn't installed. Run:\n"
              "  pip install optimum[exporters] onnx onnxruntime --break-system-packages")
        sys.exit(1)

    from transformers import AutoConfig

    args.output_dir.mkdir(parents=True, exist_ok=True)

    print(f"Exporting '{args.model_id}' to ONNX (this downloads the checkpoint — needs network access)...")
    main_export(
        model_name_or_path=args.model_id,
        output=args.output_dir,
        task="image-classification",
    )

    # optimum names its output `model.onnx` inside output_dir by default —
    # verify that landed where onnx-classifier.js expects it.
    exported = args.output_dir / "model.onnx"
    if not exported.exists():
        candidates = list(args.output_dir.glob("*.onnx"))
        if candidates:
            candidates[0].rename(exported)
        else:
            print(f"ERROR: optimum did not produce an .onnx file in {args.output_dir} — check its output above.")
            sys.exit(1)
    print(f"Wrote {exported}")

    # Labels, index-ordered to match the model's output layer, in the same
    # "Crop___Disease" convention the rest of this project's label
    # canonicalization (disease_labels.py) and onnx-classifier.js expect.
    config = AutoConfig.from_pretrained(args.model_id)
    id2label = getattr(config, "id2label", None)
    if not id2label:
        print("WARNING: could not read id2label from the model config — labels.json will be empty. "
              "You'll need to populate it by hand from the model card.")
        labels = []
    else:
        labels = [id2label[i] for i in sorted(id2label.keys(), key=int)]

    labels_path = args.output_dir / "labels.json"
    labels_path.write_text(json.dumps(labels, indent=2))
    print(f"Wrote {labels_path} ({len(labels)} classes)")

    if args.quantize:
        try:
            from onnxruntime.quantization import quantize_dynamic, QuantType
        except ImportError:
            print("ERROR: --quantize needs `onnxruntime` with the quantization extra. Run:\n"
                  "  pip install onnxruntime --break-system-packages")
            sys.exit(1)
        quantized_path = args.output_dir / "model.quant.onnx"
        quantize_dynamic(str(exported), str(quantized_path), weight_type=QuantType.QInt8)
        print(f"Wrote {quantized_path} (quantized int8 — verify accuracy before relying on this over the "
              f"full-precision export)")

    print("\nNext steps:")
    print(f"  1. Copy {exported.name} and {labels_path.name} (or model.quant.onnx) into frontend/model/")
    print("  2. Uncomment the onnxruntime-web <script> tag in frontend/index.html")
    print("  3. Re-run any real-world accuracy check you have — an ONNX export can (rarely) behave slightly "
          "differently from the original PyTorch model on edge cases; don't assume bit-for-bit identical output.")


if __name__ == "__main__":
    main()
