# Workflow video

This folder is the one place the **How it works** page's workflow video lives.
Nothing is in it yet, on purpose. Until a file is supplied the page shows a
finished placeholder frame ("Workflow video — Coming soon"). No video was
generated and no stock footage is used.

## What the video should show

A short, calm walkthrough of the FOLIX flow, in this order:

1. **Capture** — taking or uploading the crop image
2. **AI Analysis** — FOLIX examining the plant and symptoms
3. **Diagnosis** — the most likely disease or condition, or an honest "unclear"
4. **Severity** — the severity estimate
5. **Recommended Action** — the next steps, and saving or forwarding the case

Please avoid on-screen claims of guaranteed or laboratory-level accuracy, and
show the uncertain case at least once. That matches the page's wording.

## To replace the placeholder (two steps)

1. Put the files in this folder:
   - `workflow.mp4` — the video
   - `workflow-poster.jpg` — a still frame shown before the video plays
     (and to visitors with "reduce motion" or Data Saver turned on)
2. In `how-it-works.html`, find the `<figure data-workflow-video ...>` and set:

   ```html
   data-src="assets/workflow/workflow.mp4"
   data-poster="assets/workflow/workflow-poster.jpg"
   ```

   No other edit is needed. The placeholder fades out by itself once the video
   is ready.

Because the `data-src` is empty today, the browser requests nothing and the
console stays clean. Once you set it, a wrong file name simply falls back to
the placeholder (it never leaves an empty box).

## Recommended file specs

- **Aspect ratio 16:9** (e.g. 1920×1080 or 1280×720). The frame is 16:9 and
  crops with `object-fit: cover` if the ratio differs.
- **MP4, H.264**, with the metadata at the front so it starts quickly:

  ```sh
  ffmpeg -i source.mov -c:v libx264 -crf 24 -preset slow -pix_fmt yuv420p \
         -an -movflags +faststart -vf "scale=1280:-2" workflow.mp4
  ```

  `-an` drops audio; the video always plays muted.
- **Keep it small.** FOLIX targets low-connectivity farms, so aim for a few
  MB, roughly 10 MB at the most.
- **Poster:** a JPG of the same 16:9 shape, e.g.
  `ffmpeg -i workflow.mp4 -frames:v 1 -q:v 3 workflow-poster.jpg`.
- Design it to **loop** cleanly; playback is looped.

## How playback behaves

- Muted, looped, inline (no fullscreen takeover on phones).
- Starts automatically only while at least half the frame is on screen, and
  pauses when scrolled away. A visitor who pauses it stays paused.
- No browser video controls. A single small play/pause button appears on hover
  or keyboard focus (always visible on touch screens).
- With "reduce motion" or Data Saver on, it does **not** autoplay: the poster
  shows and the visitor presses play.

## Caching note

`sw.js` caches the app shell (HTML/CSS/JS), not media, so the video is always
fetched from the network. **Whenever you edit `how-it-works.html`** (including
the two `data-` attributes above), bump `CACHE_NAME` in `sw.js` (for example
`folix-shell-v5` to `folix-shell-v6`). Otherwise browsers that already visited
the site keep showing their cached copy of the old page.
