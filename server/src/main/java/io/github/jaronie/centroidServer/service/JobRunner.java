package io.github.jaronie.centroidServer.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import io.github.jaronie.centroidServer.config.AppProperties;
import io.github.jaronie.centroidServer.config.AsyncConfig;

/**
 * Launches centroidFinder.jar as a separate OS process and records the outcome.
 *
 * <p>This is the class that satisfies the assignment's central constraint: the
 * server reaches the processor only as a subprocess, never by importing it.
 *
 * <p>This MUST be its own bean rather than a method on JobService. See run().
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final long TIMEOUT_MINUTES = 30;

    private final AppProperties props;
    private final JobService jobs;

    public JobRunner(AppProperties props, JobService jobs) {
        this.props = props;
        this.jobs = jobs;
    }

    /**
     * Runs one job. Returns to the caller immediately.
     *
     * <p>@Async is implemented with a proxy: other beans receive a wrapper around
     * this object, and the wrapper is what diverts the call onto a pool thread.
     * A call that does not pass through that wrapper runs normally, on the
     * caller's thread. That is why this cannot live on JobService: JobService
     * calling its own @Async method would bypass the proxy and block the request
     * until the whole video finished, returning a late 202 that looks correct in
     * every other respect.
     *
     * <p>The whole body is wrapped in try/catch because an exception escaping an
     * @Async void method has no caller to propagate to. The job would sit in
     * "processing" forever.
     */
    @Async(AsyncConfig.JOB_EXECUTOR)
    public void run(String jobId, String filename, String targetColor, int threshold) {
        log.info("Job {} starting for {}", jobId, filename);
        try {
            Path output = props.resultFile(filename);
            Path logFile = props.jobLogFile(jobId);
            Files.createDirectories(output.getParent());
            Files.createDirectories(logFile.getParent());

            List<String> command = new ArrayList<>(List.of(
                    "java", "-jar", props.jarFile().toString(),
                    props.videoFile(filename).toString(),
                    targetColor,
                    String.valueOf(threshold),
                    output.toString()));

            log.debug("Job {} command: {}", jobId, command);

            // Both streams go to a per-job log file. Since the jar reports
            // failures on stderr while still exiting 0, that file is often the
            // only evidence of what went wrong.
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();

            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                fail(jobId, "Error processing video: timed out after " + TIMEOUT_MINUTES + " minutes");
                return;
            }

            int exitCode = process.exitValue();

            // The exit code alone is NOT trustworthy. Every failure path in
            // VideoSummaryApp prints to stderr and then returns from main, which
            // is a normal exit. So success means: exited cleanly AND left a
            // non-empty CSV behind.
            if (exitCode != 0) {
                fail(jobId, "Error processing video: processor exited with code " + exitCode);
                return;
            }
            if (!Files.exists(output) || Files.size(output) == 0) {
                fail(jobId, "Error processing video: no output was produced. See " + logFile.getFileName());
                return;
            }

            jobs.markDone(jobId, props.resultUrl(filename));
            log.info("Job {} done -> {}", jobId, props.resultUrl(filename));

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(jobId, "Error processing video: interrupted");
        } catch (Exception e) {
            log.error("Job {} failed unexpectedly", jobId, e);
            fail(jobId, "Error processing video: " + e.getMessage());
        }
    }

    private void fail(String jobId, String message) {
        log.warn("Job {} failed: {}", jobId, message);
        jobs.markFailed(jobId, message);
    }
}
