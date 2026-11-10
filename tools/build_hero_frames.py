#!/usr/bin/env python3
"""
Build the cursor-driven hero frame pack from a source video.

    python3 tools/build_hero_frames.py path/to/hero-video.mp4

Why frames instead of <video>?
    Browsers cannot seek an ordinary MP4 smoothly (seeks land on keyframes and
    stall), which shows up as "cuts" when you scrub with the cursor. A pre-cut
    frame sequence drawn to a <canvas> — with neighbouring frames blended —
    can be positioned at any fractional frame with zero seek latency.

What it produces (into frontend/assets/hero/):
    hero-frames.bin    all frames as WebP, concatenated, stored from the
                       NEUTRAL pose outward so the centre loads first
    hero-frames.json   manifest: geometry, neutral index, per-frame offset/length
    hero-poster.webp   the neutral frame (shown instantly, and as the fallback)

The source clip (a bust that slowly looks around) is a seamless loop: frame 0
and the last frame are visually the same neutral pose. From that pose the head
turns to the viewer's LEFT over the first ~80 frames and to the viewer's RIGHT
over the last ~110 frames. Those two runs are joined at the neutral pose into
one continuous left <-> right axis, which is what the cursor drives. The
in-between part of the clip (head tilting up) is intentionally not used: it
would put a large pitch change in the middle of the axis.

Tune LEFT_END / RIGHT_END / STEP below if you swap in a different video.
"""
import io
import json
import pathlib
import subprocess
import sys
import tempfile

from PIL import Image

LEFT_END = 80      # last frame (counting up from 0) of the leftward turn
RIGHT_END = 250    # last frame (counting down from the end) of the rightward turn
STEP = 2           # keep every Nth frame; the runtime blends between neighbours
QUALITY = 78       # WebP quality for the sequence
POSTER_QUALITY = 88

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "frontend" / "assets" / "hero"


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    video = pathlib.Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-i", str(video), f"{tmp}/f%04d.png"],
            check=True,
        )
        files = sorted(pathlib.Path(tmp).glob("f*.png"))
        total = len(files)
        last = total - 1  # frame `last` == frame 0 (loop point)

        left = list(range(LEFT_END - (LEFT_END % STEP), -1, -STEP))          # far-left .. neutral(0)
        right = list(range(last - STEP, RIGHT_END - 1, -STEP))                # just past neutral .. far-right
        seq = left + right
        neutral = left.index(0)

        blobs = []
        for src in seq:
            im = Image.open(files[src]).convert("RGB")
            buf = io.BytesIO()
            im.save(buf, "WEBP", quality=QUALITY, method=6)
            blobs.append(buf.getvalue())
        width, height = Image.open(files[0]).size

        # Store from the neutral pose outward (alternating left/right) so a
        # streaming reader has the centre of the axis first.
        order = [neutral]
        for d in range(1, len(seq)):
            if neutral - d >= 0:
                order.append(neutral - d)
            if neutral + d < len(seq):
                order.append(neutral + d)

        frames = [None] * len(seq)
        offset = 0
        with open(OUT / "hero-frames.bin", "wb") as fh:
            for k in order:
                fh.write(blobs[k])
                frames[k] = [offset, len(blobs[k])]
                offset += len(blobs[k])

        manifest = {
            "version": 1,
            "width": width,
            "height": height,
            "count": len(seq),
            "neutral": neutral,
            "bytes": offset,
            "mime": "image/webp",
            "order": order,
            "frames": frames,
            "sourceFrames": seq,
        }
        (OUT / "hero-frames.json").write_text(json.dumps(manifest, separators=(",", ":")))

        poster = Image.open(files[0]).convert("RGB")
        poster.save(OUT / "hero-poster.webp", "WEBP", quality=POSTER_QUALITY, method=6)

    print(f"{len(seq)} frames, neutral index {neutral}, {offset / 1e6:.2f} MB -> {OUT}")


if __name__ == "__main__":
    main()
