# Server test baseline

The server's automated test baseline: what it covers, how to run it, and the
rules that keep it runnable.

## Running it

From `server/`:

```bash
mvn test                                    # everything the machine can run
mvn test -Dtest.excludedGroups=integration  # fast, hermetic subset (~15s)
mvn test -Dtest.groups=integration          # only the slow, external-dependency tests
```

Current counts: **85 tests** — 66 unit, 19 integration.

## The two groups

Tests split by what they need from the machine, enforced by a JUnit tag rather
than by naming convention.

| | Unit (66) | Integration (19) |
|---|---|---|
| Tag | untagged | `@Tag("integration")` |
| Needs | a JDK | ffmpeg, and the processor jar |
| Runtime | ~15s | ~1 min (plus building the jar) |
| Classes | everything else | `ApiSmokeTest`, `JobRunnerTest`, `ThumbnailServiceTest` |

The integration tests need things that are **not in the repository**:

- **`processor/target/centroidFinder.jar`** — a ~100 MB fat jar, gitignored
  like every `target/` directory. Build it from `processor/`:

  ```bash
  mvn -DskipTests package -Djavacpp.platform=linux-x86_64
  ```

  Or point the tests at one you already have:
  `-Dcentroid.processor.jar=/path/to/centroidFinder.jar`

- **ffmpeg** on `PATH`, for the thumbnail tests.

When either is missing, the tests that need it are **skipped with a reason**,
not failed. A plain `mvn test` on a fresh clone with no jar and no ffmpeg is
therefore green, and reports honestly what it did not run.

## What it covers

- `JobTest`, `JobServiceTest` — the job state machine and its concurrency
  properties.
- `AppPropertiesTest` — path arithmetic every other class depends on.
- `VideoControllerTest`, `ProcessControllerTest`, `ThumbnailControllerTest` —
  the HTTP contract per endpoint, with services mocked. Status codes and the
  exact error messages the spec requires.
- `VideoLibraryServiceTest` — real filesystem behaviour, including traversal
  rejection.
- `ThumbnailServiceTest` — real ffmpeg: argument list, exit-code handling, and
  that a failed run caches nothing.
- `JobRunnerTest` — the real subprocess. Most importantly that a job is never
  left in `processing`, and that a corrupt video fails the job even though the
  jar exits 0.
- `CentroidServerApplicationTests` — the wiring: property binding, the bounded
  async executor, static resource handlers, and that the processor is *not* on
  the server's classpath.
- `ApiSmokeTest` — the whole flow over real HTTP on a real port: `POST
  /process` → poll `GET /process/{id}/status` → `GET` the CSV the status points
  at, and assert its contents.

## Rules that keep this working

**Fixtures live in the repository, not in `data/`.** `server/data/` is
gitignored, so it is empty on a fresh clone. The sample clip is a committed
classpath resource at `src/test/resources/fixtures/testclip.mp4` (2.6 KB; 3s,
320×240, 10fps, 30 frames, red square centred). Load it through
`TestEnv.copyTestClip(...)` — never read `data/videos/` from a test.

**External prerequisites go through `TestEnv`.** `TestEnv.requireProcessorJar()`
and `TestEnv.requireFfmpeg()` abort (skip) the calling test with an explanation
instead of failing it. Tests that call them must carry `@Tag("integration")`.

**A test that needs an external binary is tagged.** Otherwise it lands in the
unit job, which runs on a bare runner and would skip it silently.

`ApiSmokeTest` redirects every `app.*` directory into a temp directory, so it
touches no repository state. `CentroidServerApplicationTests` deliberately does
*not*: it reads the real `application.properties` because proving those values
bind correctly is the point of that class — a missing `app.logs-dir` once
shipped and broke every job at runtime.

## CI

[`.github/workflows/server-tests.yml`](../.github/workflows/server-tests.yml):

- **`unit`** — JDK 21, `mvn clean test -Dtest.excludedGroups=integration`. Must
  pass on a bare runner: no ffmpeg, no jar, no `data/` directory.
- **`integration`** — runs after `unit`. Installs ffmpeg, builds the processor
  jar, then runs the tagged tests with `-Dcentroid.processor.jar` pointing at
  it.

Both jobs assert a test count afterwards, because surefire reports "zero tests
found" as success — a green tick that ran nothing is the failure mode a test
count catches and a passing build does not. The integration job additionally
fails if *any* test was skipped: a skip there means ffmpeg or the jar was
missing, which is a broken job, not a pass.

The processor module has its own workflow,
[`run-tests.yml`](../.github/workflows/run-tests.yml).
