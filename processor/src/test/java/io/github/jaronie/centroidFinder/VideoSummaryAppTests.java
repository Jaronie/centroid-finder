package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI-level checks for VideoSummaryApp diagnostics and invalid video input. */
public class VideoSummaryAppTests {

    @TempDir
    Path tempDir;

    @Test
    public void missingArgumentsPrintUsage() {
        Output output = runMain(() -> VideoSummaryApp.main(new String[0]));

        assertTrue(output.stdout().contains("Usage: java VideoSummaryApp"));
    }

    @Test
    public void malformedThresholdIsReported() {
        Output output = runMain(() -> VideoSummaryApp.main(
                new String[] {"unused.mp4", "FFFFFF", "not-a-number"}));

        assertTrue(output.stderr().contains("Threshold must be an integer."));
    }

    @Test
    public void malformedColorIsReported() {
        Output output = runMain(() -> VideoSummaryApp.main(
                new String[] {"unused.mp4", "not-hex", "100"}));

        assertTrue(output.stderr().contains("Invalid hex target color."));
    }

    @Test
    public void invalidVideoFileIsReported() throws Exception {
        Path video = tempDir.resolve("not-a-video.mp4");
        Files.writeString(video, "not an encoded video");

        Output output = runMain(() -> VideoSummaryApp.main(
                new String[] {video.toString(), "FFFFFF", "100"}));

        assertTrue(output.stderr().contains("Could not open video"));
    }

    private static Output runMain(Runnable main) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (PrintStream capturedOut = new PrintStream(stdout, true, StandardCharsets.UTF_8);
             PrintStream capturedErr = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(capturedOut);
            System.setErr(capturedErr);
            main.run();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return new Output(stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8));
    }

    private record Output(String stdout, String stderr) {}
}
