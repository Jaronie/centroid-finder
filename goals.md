Thumb Wars Tracking - Determine who is winning in a game of thumb-wars. Could be based on tracking tip of each thumb -- if both centroids go below a certain height, it can count down and determine who wins.

Easy - Implement ability to track 2 centroids separately.

Goal - Using multi-centroid tracking + ability to select a portion of the video to find the centroid and track, determine who is actively winning in a game of "thumb wars".

Stretch - 
* Ability to livestream / use camera to track thumb war and determine winning party.
* Ability for file uploads through the UI instead of uploading files via the directory
* Output binarized videos

Impossible - Determine who wins in a game of rock-paper-scissors through shape-tracking.

Step-by-step implementation Guide (Generated with Claude):

## Step 0: Decide what the camera sees (do this before any code)

The current pipeline finds pixels near **one** target color and reports the **largest** group (`VideoPipeline` takes `groups.get(0)`). Two thumbs are the same skin color, so the first decision is how to tell them apart.

| Option | How it works | Pros | Cons |
|---|---|---|---|
| **A. Colored markers (recommended)** | Each player wears a different colored sticker, tape or marker cap on their thumbnail (e.g. bright green and bright pink). | Reuses the existing color binarizer as is. Identity comes free: green is always Player 1. Easiest to test. | Needs props. Lighting can shift colors, so the thresholds need tuning. |
| B. Same color, two largest groups | Binarize on skin tone once and take the top 2 groups. Work out which thumb is which from the previous frame. | No props. | Thumbs touch all the time in a thumb war, and touching thumbs merge into ONE group. Needs identity tracking and occlusion handling. Skin tone also matches the hands and arms, not just the thumbs. |
| C. Split the frame into two regions | Left half is Player 1, right half is Player 2, using `CroppingImageGroupFinder` twice. | Trivial to build. | Breaks the moment a thumb crosses the midline, which happens constantly. Only good as a first demo. |

**Camera angle:** film from the **side**, so "height" is the vertical axis of the image. Remember that y grows **downward** (see `Group.java`), so "lower thumb" means **larger y**.

**Define "winning" now**, because it drives everything later:
- **Actively winning (momentary):** the thumb that's higher, i.e. has the smaller y, beyond a small margin.
- **Pin:** both tips are below a "pin line" and close together. The thumb on top (smaller y) is pinning.
- **Win:** a pin held for N seconds (the classic "1, 2, 3, 4, I declare a thumb war" count, e.g. 3 s).

## Step 1 (Easy): Track 2 centroids separately

**What changes:** `VideoPipeline` and `CentroidCsvWriter` are hard-wired to one centroid per frame. Leave them alone, since the existing app and tests depend on them, and build a parallel multi-target path.

**1a. A "tracked target" concept.** Each target has a name ("P1"/"P2") and its own way of finding its centroid in a frame.
- For Option A, each target owns its own `ImageGroupFinder` chain (`DistanceImageBinarizer` with that target's color → `BinarizingImageGroupFinder` → optional cropping/scaling). It picks its group with `LargestComponentSelector`, whose minimum size filters out stray noise pixels.
- For Option B, one finder is shared, and an assignment step (1c) splits the top 2 groups between targets.

**1b. Choose how the pipeline is generalized:**

| Option | Description | Trade-off |
|---|---|---|
| **New `MultiTargetPipeline` (recommended)** | Iterates `Iterable<VideoFrame>` like `VideoPipeline` and asks each target for a centroid (or "missing"). Uses the same `FrameValidator`. | Duplicates a little loop logic, but nothing existing breaks. |
| Strategy interface inside `VideoPipeline` | Pull "pick a centroid from the groups" out into an interface; the default implementation keeps today's behavior. | Cleaner long term, but touches tested code. |

**1c. Identity (only for Option B):** with 2 previous positions and 2 new groups, there are only 2 possible pairings. Keep the one with the smaller total distance. If only 1 group shows up (the thumbs merged), mark both as "merged" at that centroid, or carry each thumb's last known position forward. Pick one and document it.

**1d. Output:** a new CSV writer with rows like `seconds,p1x,p1y,p2x,p2y`, using `-1,-1` for missing, the same convention as `CentroidCsvWriter`. One row per frame keeps the time axis continuous.

**1e. Entry point:** a new `ThumbWarApp` (or new flags on `VideoSummaryApp`), e.g. `--target=P1:00FF00:60 --target=P2:FF00FF:60`. Reuse the existing `--crop` and `--scale` parsing.

**Tests:** synthetic `BufferedImage`s with two colored squares. Check that each target finds its own square, that a missing color gives `-1,-1`, and that swapping the squares' positions doesn't swap identities.

**Checkpoint:** record a short clip of two marked thumbs and plot both y columns over time. The thumb movement should be clearly visible.

## Step 2 (Goal): Select a portion of the video + decide who's winning

**2a. "Select a portion": pick which meaning(s) to support:**

| Option | What exists | Work needed |
|---|---|---|
| **Spatial region (already built)** | `--crop=X,Y,W,H` and `--inset=N` through `CroppingImageGroupFinder`, which maps centroids back to full-frame coordinates. | Allow a crop per target, or one crop for the play area. |
| Time range | Nothing yet. | `--start=S --end=E` flags. Either seek in `FrameExtractor` or skip frames outside the range in the pipeline loop. Useful for cutting out setup and the bow. |
| Click-to-select (UI) | The server has `/thumbnail/{filename}` and `/process/{filename}`. | The frontend draws a rectangle (and/or picks each player's color by clicking a pixel) on the thumbnail. `ProcessController` accepts new crop/color query params and passes them as CLI flags to the JAR in `JobRunner`. |

Recommended order: spatial crop through the CLI first, then a time range, then UI selection if time allows.

**2b. Smooth the signal before judging it.** Raw centroids jitter frame to frame. Options:
- Moving average over the last k frames (simple, adds a little lag).
- Exponential smoothing (one parameter, no buffer).
- **Hysteresis (recommended on top of either):** the leader only changes when the other thumb is higher by more than a margin, so ties don't flicker.

**2c. A referee state machine, kept separate from the pipeline.** Make it a pure class (e.g. `ThumbWarReferee`) that takes one `(seconds, p1, p2)` sample at a time and reports a state. It never touches video, so it's easy to unit-test with made-up sequences.

```
NEUTRAL ──(one thumb higher by > margin)──> P1_LEADING / P2_LEADING
LEADING ──(both below pin line AND close together)──> P1_PINNING / P2_PINNING (start timer)
PINNING ──(held for holdSeconds)──> P1_WINS / P2_WINS  (terminal)
PINNING ──(pin broken: separated or rose above line)──> back to LEADING/NEUTRAL (reset timer)
any     ──(a thumb missing for > tolerance)──> keep last state; don't advance timers
```

Parameters to make configurable: `pinLineY` (absolute, or a fraction of the crop height, e.g. bottom 30%), `pinDistance`, `holdSeconds`, `leadMargin`, `missingTolerance`. Use frame **timestamps**, not frame counts, for the hold timer, so it works at any FPS or `--scale`.

**2d. Output the verdict:**
- Add a `state` column to the CSV so the leader can be graphed over time (this also covers the ideas.md "who is topping at set intervals" idea).
- Print a summary like `P2 wins at 12.40s (pinned 3.0s)`, or "no winner".
- Optional: write an annotated video with dots, the pin line and the state drawn on each frame (JavaCV's `FFmpegFrameRecorder`). This overlaps with the "binarized video" idea.

**Tests:** fabricated sample sequences covering a clean pin and win, a pin broken at 2.9 s, jittery ties around the margin, missing frames mid-pin, and both thumbs never going below the line.

**Checkpoint:** run on 3–5 recorded games and compare against the winner by eye. Tune the parameters and write down the values that worked.

## Step 3 (Stretch): Live camera

The pipeline already consumes `Iterable<VideoFrame>`, so most of the work is a new frame **source** and a live **display**.

**3a. Frame source options:**
- **`FFmpegFrameGrabber` on the webcam (recommended):** same library as `FrameExtractor`. On Windows, use format `dshow` with the device `video=<camera name>`. Extract a `FrameSource` interface (or just accept any `Iterable<VideoFrame>`) so files and cameras are interchangeable.
- `OpenCVFrameGrabber(0)`: simpler device selection, but pulls in more of OpenCV.

**3b. Make it real-time:** use `--scale` (0.5 or below) and a crop. Measure the per-frame time and keep it under about 33 ms for 30 FPS, or drop frames when behind (always process the newest one).

**3c. Display options:**
- **JavaCV `CanvasFrame` desktop window (recommended):** draws the annotated frame plus the referee state. Very little code.
- Browser: stream through the Spring server with WebSocket/MJPEG, or capture with `getUserMedia` and send frames up. Much more work. Only worth it if the web app is a deliverable.

**3d.** Feed live samples to the same `ThumbWarReferee` and show the countdown on screen during a pin.

## Step 4 ("Impossible"): Rock-paper-scissors by shape

Not needed, but here's where it would start: `Group` only stores `size` and `centroid`, which isn't enough to describe a shape. The group finder would also need to report a **bounding box** and/or the group's pixels. Then:
- **Simple heuristic:** fill ratio (size ÷ bounding-box area) plus aspect ratio. Rock is compact with a high fill. Paper is large and wide. Scissors is elongated with a low fill.
- **Better:** convex hull and convexity defects (OpenCV through JavaCV) to count extended fingers: 0 → rock, 2 → scissors, 5 → paper.
- Then a small "throw detector": wait until the shape is stable for about 0.3 s after motion, classify both hands, and apply the RPS rules.

## Suggested split and order

1. Record test footage: markers on, side view, a few full games. **(Both, first)**
2. Step 1 multi-target tracking and CSV. **(Partner A)** In parallel, the `ThumbWarReferee` state machine and its tests, against fake data. **(Partner B)**
3. Join them in `ThumbWarApp` and tune on the footage. **(Both)**
4. Time-range flag, then server/UI params for crop and color. **(Split)**
5. Live camera stretch.