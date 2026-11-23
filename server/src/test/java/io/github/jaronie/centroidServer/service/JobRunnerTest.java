package io.github.jaronie.centroidServer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaronie.centroidServer.config.AppProperties;
import io.github.jaronie.centroidServer.model.Job;
import io.github.jaronie.centroidServer.model.JobStatus;

/**
 * Calls run() directly. Without a Spring context there is no @Async proxy, so
 * the method runs synchronously on the test thread -- which is exactly what we
 * want: the subprocess behaviour is under test here, not the threading.
 *
 * <p>The success test launches the real centroidFinder.jar, so it is the slowest
 * test in the suite (a few seconds). It is worth it: it is the only test that
 * proves the server and the processor actually agree on the command line.
 */
class JobRunnerTest {

    private static final Path REAL_JAR = Path.of("../processor/target/centroidFinder.jar");
    private static final Path REAL_CLIP = Path.of("data/videos/testclip.mp4");

    @TempDir
    Path tmp;

    private Path videosDir;
    private Path resultsDir;
    private Path logsDir;
    private JobService jobs;

    @BeforeEach
    void setUp() throws IOException {
        videosDir = Files.createDirectories(tmp.resolve("videos"));
        resultsDir = tmp.resolve("results");
        logsDir = tmp.resolve("logs");
        jobs = new JobService();
    }

    private JobRunner runner(String jarPath) {
        return new JobRunner(
                new AppProperties(videosDir, jarPath, resultsDir, tmp.resolve("thumbs"), logsDir, "ffmpeg"),
                jobs);
    }

    @Test
    @DisplayName("a successful run marks the job done and writes a real CSV")
    void successfulRun() throws IOException {
        Files.copy(REAL_CLIP, videosDir.resolve("clip.mp4"));
        Job job = jobs.create();

        runner(REAL_JAR.toString()).run(job.id(), "clip.mp4", "FF0000", 100);

        Job finished = jobs.find(job.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.DONE);
        assertThat(finished.resultUrl()).isEqualTo("/results/clip.mp4.csv");
        assertThat(finished.errorMessage()).isNull();

        Path csv = resultsDir.resolve("clip.mp4.csv");
        assertThat(csv).exists();
        // The clip is 3s at 10fps with a red square dead centre, so every row
        // should report roughly the middle of a 320x240 frame.
        assertThat(Files.readAllLines(csv))
                .hasSize(30)
                .allSatisfy(line -> assertThat(line).matches("\\d+\\.\\d+,149,119"));
    }

    @Test
    @DisplayName("the job's resultUrl is a URL, never a filesystem path")
    void resultUrlIsNotAFilesystemPath() throws IOException {
        Files.copy(REAL_CLIP, videosDir.resolve("clip.mp4"));
        Job job = jobs.create();

        runner(REAL_JAR.toString()).run(job.id(), "clip.mp4", "FF0000", 100);

        String url = jobs.find(job.id()).orElseThrow().resultUrl();
        assertThat(url).startsWith("/results/").doesNotContain(tmp.toString());
    }

    @Test
    @DisplayName("a successful run writes a per-job log file")
    void writesJobLog() throws IOException {
        Files.copy(REAL_CLIP, videosDir.resolve("clip.mp4"));
        Job job = jobs.create();

        runner(REAL_JAR.toString()).run(job.id(), "clip.mp4", "FF0000", 100);

        assertThat(logsDir.resolve(job.id() + ".log")).exists();
    }

    @Test
    @DisplayName("a corrupt video fails the job even though the jar exits 0")
    void corruptVideoFailsTheJob() throws IOException {
        // This is the single most important test in this class. Every failure
        // path in VideoSummaryApp prints to stderr and then returns from main,
        // which is a NORMAL exit. If JobRunner trusted the exit code alone,
        // this job would be reported as done with a result URL pointing at a
        // file that does not exist.
        Files.writeString(videosDir.resolve("broken.mp4"), "this is not a video");
        Job job = jobs.create();

        runner(REAL_JAR.toString()).run(job.id(), "broken.mp4", "FF0000", 100);

        Job finished = jobs.find(job.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.ERROR);
        assertThat(finished.errorMessage()).startsWith("Error processing video:");
        assertThat(finished.resultUrl()).isNull();
        assertThat(resultsDir.resolve("broken.mp4.csv")).doesNotExist();
    }

    @Test
    @DisplayName("a missing jar fails the job instead of throwing out of the async method")
    void missingJarFailsTheJob() throws IOException {
        Files.copy(REAL_CLIP, videosDir.resolve("clip.mp4"));
        Job job = jobs.create();

        runner(tmp.resolve("no-such.jar").toString()).run(job.id(), "clip.mp4", "FF0000", 100);

        // An exception escaping an @Async void method has no caller to receive
        // it, so the job would sit in "processing" forever. It must be caught
        // and recorded instead.
        Job finished = jobs.find(job.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(JobStatus.ERROR);
        assertThat(finished.errorMessage()).startsWith("Error processing video:");
    }

    @Test
    @DisplayName("the job never stays in processing, whatever goes wrong")
    void neverStrandsAJob() throws IOException {
        Files.writeString(videosDir.resolve("broken.mp4"), "not a video");

        for (String[] scenario : new String[][] {
                { REAL_JAR.toString(), "broken.mp4" },
                { tmp.resolve("missing.jar").toString(), "broken.mp4" } }) {
            Job job = jobs.create();
            runner(scenario[0]).run(job.id(), scenario[1], "FF0000", 100);
            assertThat(jobs.find(job.id()).orElseThrow().status())
                    .as("scenario %s", scenario[0])
                    .isNotEqualTo(JobStatus.PROCESSING);
        }
    }
}
