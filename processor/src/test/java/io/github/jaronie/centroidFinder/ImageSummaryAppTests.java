package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI-level checks for ImageSummaryApp diagnostics and unsupported inputs. */
public class ImageSummaryAppTests {

    @TempDir
    Path tempDir;

    @Test
    public void missingArgumentsPrintUsage() {
        Output output = runMain(() -> ImageSummaryApp.main(new String[0]));

        assertTrue(output.stdout().contains("Usage: java ImageSummaryApp"));
    }

    @Test
    public void malformedThresholdIsReported() throws Exception {
        Path image = validImage();

        Output output = runMain(() -> ImageSummaryApp.main(
                new String[] {image.toString(), "FFFFFF", "not-a-number"}));

        assertTrue(output.stderr().contains("Threshold must be an integer."));
    }

    @Test
    public void malformedColorIsReported() throws Exception {
        Path image = validImage();

        Output output = runMain(() -> ImageSummaryApp.main(
                new String[] {image.toString(), "not-hex", "100"}));

        assertTrue(output.stderr().contains("Invalid hex target color."));
    }

    @Test
    public void unsupportedImageIsReportedWithoutThrowing() throws Exception {
        Path image = tempDir.resolve("not-an-image.dat");
        Files.writeString(image, "not an encoded image");

        Output output = runMain(() -> ImageSummaryApp.main(
                new String[] {image.toString(), "FFFFFF", "100"}));

        assertTrue(output.stderr().contains("is not a supported image."));
        assertFalse(output.stderr().contains("Exception in thread"));
    }

    private Path validImage() throws Exception {
        Path image = tempDir.resolve("input.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        return image;
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
