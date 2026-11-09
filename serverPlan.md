Essentially, creating multiple API endpoints so a user can interface with our
video processor. We will split responsibilities by wave, and implement the HTTP
handling with Springboot.

---

# Server Plan — Seadrah & Jaron

---

# Reference

## A. The API we have to build

https://github.com/auberonedu/salamander-api

| #   | Method & path                                                | Returns on success                                           | Error cases                                                                                                      |
| --- | ------------------------------------------------------------ | ------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| 1   | `GET /api/videos`                                            | `200` — JSON array of filenames: `["intro.mp4", "demo.mov"]` | `500` `{"error": "Error reading video directory"}`                                                               |
| 2   | `GET /thumbnail/{filename}`                                  | `200` — JPEG bytes, `Content-Type: image/jpeg`               | `500` `{"error": "Error generating thumbnail"}`                                                                  |
| 3   | `POST /process/{filename}?targetColor=<hex>&threshold=<int>` | `202` — `{"jobId": "123e4567-e89b-12d3-a456-426614174000"}`  | `400` `{"error": "Missing targetColor or threshold query parameter."}` / `500` `{"error": "Error starting job"}` |
| 4   | `GET /process/{jobId}/status`                                | `200` — one of three shapes (below)                          | `404` `{"error": "Job ID not found"}` / `500` `{"error": "Error fetching job status"}`                           |

The three status shapes for #4:

```json
{ "status": "processing" }
{ "status": "done",  "result": "/results/intro.mp4.csv" }
{ "status": "error", "error": "Error processing video: Unexpected ffmpeg error" }
```

Also stated: videos are "available publicly at /videos/VIDEO_NAME" — static file
hosting. Statuses are only `processing` / `done` / `error`.

## B. How our existing JAR is invoked

```
java -jar centroidFinder.jar <video> <hexColor> <threshold> [out.csv] [--inset=N | --crop=X,Y,W,H]
```

Writes `seconds,x,y` rows, one per frame, `-1,-1` when nothing matched. Exit is
normal on success; errors print to stderr. The API's `targetColor` and
`threshold` query params line up exactly with arguments 2 and 3.

## D. The constraint that bites harder in Spring Boot than in Express

Server must call the JAR, no graph search logic in code.

In Express this is automatic — JavaScript can't call our Java classes, so the
only way in is a subprocess. In Spring Boot it is not automatic: both sides are
Java, so we could add `centroidFinder` as a Maven dependency and call
`VideoPipeline` directly. That would violate the requirement.

We have to launch `centroidFinder.jar` as a separate OS process via
`ProcessBuilder`, exactly as Express would with `child_process`. The server
should know nothing about `DfsBinaryGroupFinder`, `Group`, or centroids: only
about files, arguments, exit codes, and job IDs.

---

# Our Plan

## 1. What We understand about this API

This is a baseline to build a front-end on, allowing a user to make HTTP
requests to our tool. It lists available videos, generates a thumbnail, starts
the job, and checks in on it. The request to start the job returns the job ID so
that it can later poll and see if the job is still running. When it has
finished, it returns the CSV.

**Decision 1 — Where does job state live?** We'll store it in-memory, as there's
no mention of restart-survival being crucial for this project. If it turns out
to be a big issue, we can later implement a database, which requires more set-up
but would resist corruption / partial file failures.

**Decision 2 — How do we know a job finished, and whether it succeeded?** Watch
for the exit code, possibly with some sort of testing implementation to verify
that the CSV is valid and not corrupted.

**Decision 3 — Where does the thumbnail come from?** We can grab the first frame
of the video through ffmpeg (ffmpeg -i videos/intro.mp4 -frames:v 1 -f image2
thumb.jpg). Writes to temp or cache file so no re-run needed.

**Decision 4 — Validation and safety.** 400 for all user errors, like
targetColor invalid or threshold NaN. 500 for server-side errors that should be
re-attempted. We will need a ControllerAdvice w ExceptionHandler methods to
prevent escaped exceptions --- anything reported as a 400 should be checked
before JAR launched and reply, since a 202 response means a 400 can no longer be
returned. Should defend against shell injection for passwd issue -- use an
Allowlist from our own directory listing.

## 3. Architecture diagram

```
plan.png
```

## 5. Endpoint-by-endpoint notes

**`GET /api/videos`** Uses class: `VideoController`+ `VideoLibraryService`,
lists the videos directory from `app.videos-dir`. Returns `200` alongside JSON
array of filenames. Returns `500` when directory is missing or unreadable. Empty
folder is not an error — it's `[]`.

**`GET /thumbnail/{filename}`** Uses class: `ThumbnailController` +
`ThumbnailService`. Checks `filename` against allowlist first, then runs ffmpeg
to pull first frame. Writes JPEG to a temp or cache file. We can cache by
filename so we don't re-run ffmpeg on every load. Unknown filename, ffmpeg
missing from PATH, a corrupt video, or a video with no decodable first frame
throw `500` (`"Error generating thumbnail"`).

**`POST /process/{filename}`** Uses class: `ProcessController` + `JobService`.
Validates everything first: filename on allowlist, `targetColor` hex,
`threshold` int, since once we answer `202` we can't go back and say `400`. It
mints a `UUID`, records job as `processing` in the in-memory map, hands the
`ProcessBuilder` call to an `@Async` method, and returns `202` with
`{"jobId": "..."}`. The async side launches the JAR as a real OS subprocess and
later flips entry to `done` or `error`. A missing param is `400`. A
misconfigured JAR path or an executor that won't take the task is `500`
(`"Error starting job"`). Our CLI exits `0` even on bad args, so we check the
CSV too, not just the exit code (Decision 2).

**`GET /process/{jobId}/status`** Uses class: `ProcessController`, reading the
same in-memory map. Returns `200` with one of the three:
`{"status":"processing"}`, `{"status":"done","result":"..."}`, or
`{"status":"error","error":"..."}`. Unknown ID is the `404`
(`"Job ID not found"`). A jobId that was never issued, or one lost to a restart
since state is in memory are both `404`. Map gets written by the async thread
and read by request threads, so needs to be a `ConcurrentHashMap`.

## 6. Testing approach

HTTP Requests with postman, implementing tests in BootStrap + Maven.
