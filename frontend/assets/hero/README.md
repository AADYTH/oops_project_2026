# Hero media

The homepage hero is footage of a bust whose head **follows the cursor**. It is
not played by time; the pointer scrubs it. `hero-media.js` does the work,
`index.html` holds the markup, and `styles.css` (`.hero-*`) the layout.

## Files in this folder (generated — do not edit by hand)

| File | What it is |
| --- | --- |
| `hero-frames.bin` | All frames as WebP, concatenated, stored from the neutral pose outward so the centre of the range loads first. |
| `hero-frames.json` | Manifest: frame size, count, which frame is neutral, and each frame's byte offset/length. |
| `hero-poster.webp` | The neutral frame. Shown instantly on load and kept as the fallback. |

## Regenerating from a video

```bash
python3 tools/build_hero_frames.py path/to/hero-video.mp4
```

The script's constants (`LEFT_END`, `RIGHT_END`, `STEP`, `QUALITY`) decide which
part of the clip is used. It expects a clip that **loops** (first frame ≈ last
frame = the neutral pose) with a turn to one side near the start and to the
other side near the end. Those two runs are joined at the neutral pose into a
single continuous left ↔ right axis.

## Why frames, not `<video>`

Browsers cannot seek a normal MP4 smoothly; scrubbing lands on keyframes and
stalls, which looks like cuts. Frames drawn to a canvas can be positioned at any
fractional index with no seek latency, and neighbouring frames are cross-blended
so motion is continuous at any speed.

## Behaviour notes

* Cursor left of the figure → head turns left; right → turns right; over the
  figure or outside the window → neutral. Touch: dragging steers it.
* Motion runs through a critically-damped spring (`SMOOTH_TIME` in
  `hero-media.js`): raise it for a softer, slower follow; lower it for snappier.
* The head never moves onto a frame that isn't decoded yet — on a slow
  connection it simply reaches the extremes a little later, never jumps.
* `prefers-reduced-motion`, missing canvas/`createImageBitmap`, or any load
  failure leave the still poster in place.
* The wordmark, ring and corner marks are baked into the footage. The hero
  buttons are positioned in the footage's own coordinates (`.hero-stage`) so they
  stay clear of it at every screen size.
