package io.github.jaronie.centroidServer.support;

import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The test suite's single source of truth for everything that lives outside the
 * repository: the sample video, the processor jar, and ffmpeg.
 *
 * <p>Why this class exists. The suite used to reach straight at
 * {@code data/videos/testclip.mp4} and
 * {@code ../processor/target/centroidFinder.jar}. Both are gitignored --
 * {@code server/data/} and every {@code target/} directory are -- so a fresh
 * clone had neither, and the tests that needed them failed with a
 * NoSuchFileException that looked like a bug in the server rather than a
 * missing prerequisite. That made the suite impossible to run automatically.
 *
 * <p>Two things fix that:
 *
 * <ul>
 *   <li>The clip is now a committed classpath resource, so it is always
 *       present and no test depends on the runtime data directory.
 *   <li>The jar and ffmpeg are genuinely optional. Asking for one that is
 *       absent <em>aborts</em> the calling test -- reported as skipped, with a
 *       reason -- instead of failing it. A build without the jar still runs
 *       every test that does not need it.
 * </ul>
 *
 * <p>Tests that need the jar or ffmpeg also carry {@code @Tag("integration")},
 * which is what lets CI select them deliberately rather than relying on skips.
 */
public final class TestEnv {

    /**
     * The committed sample clip: 3s, 320x240, 10fps, 30 frames, with a red
     * square dead centre. Small enough (2.6 KB) to live in git, which is the
     * whole reason the suite can be reproduced.
     */
    private static final String CLIP_RESOURCE = "/fixtures/testclip.mp4";

    /**
     * Overrides where the processor jar is looked for. CI sets this to an
     * absolute path, because the default is relative to the server module and
     * only correct when the whole repo is checked out side by side.
     */
    private static final String JAR_PROPERTY = "centroid.processor.jar";

    private static final Path DEFAULT_JAR = Path.of("..", "processor", "target", "centroidFinder.jar");

    private TestEnv() {
    }

    /**
     * Copies the sample clip to {@code destination} and returns it.
     *
     * <p>Read from the classpath rather than the filesystem so it resolves the
     * same way wherever the tests run from.
     */
    public static Path copyTestClip(Path destination) throws IOException {
        try (InputStream in = TestEnv.class.getResourceAsStream(CLIP_RESOURCE)) {
            if (in == null) {
                // Not an assumption: the resource is committed, so its absence
                // is a broken build, not a missing optional prerequisite.
                throw new IllegalStateException(
                        "Missing test resource " + CLIP_RESOURCE + " -- the test clip should be committed "
                                + "under server/src/test/resources/fixtures/");
            }
            Files.createDirectories(destination.getParent());
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    /** The processor jar, if it has been built. */
    public static Optional<Path> processorJar() {
        String override = System.getProperty(JAR_PROPERTY);
        Path jar = (override == null || override.isBlank())
                ? DEFAULT_JAR
                : Path.of(override);
        return Files.isRegularFile(jar) ? Optional.of(jar.toAbsolutePath().normalize()) : Optional.empty();
    }

    /**
     * The processor jar, or an aborted (skipped) test if it has not been built.
     *
     * <p>The jar is a ~100 MB fat jar produced by
     * {@code mvn package -Djavacpp.platform=linux-x86_64} in {@code processor/},
     * so it is reasonable for a developer not to have it. It is not reasonable
     * for that to look like a server defect.
     */
    public static Path requireProcessorJar() {
        return processorJar().orElseGet(() -> abort(
                "Processor jar not built. Run `mvn -DskipTests package -Djavacpp.platform=linux-x86_64` "
                        + "in processor/, or pass -D" + JAR_PROPERTY + "=/path/to/centroidFinder.jar"));
    }

    /** Whether {@code ffmpeg} on PATH can actually be executed. */
    public static boolean ffmpegAvailable() {
        return canRun("ffmpeg");
    }

    /** Aborts (skips) the calling test when ffmpeg is not installed. */
    public static void requireFfmpeg() {
        if (!ffmpegAvailable()) {
            abort("ffmpeg is not on PATH; install it to run the thumbnail tests");
        }
    }

    /**
     * Probes a binary by running it with {@code -version}.
     *
     * <p>Checked by execution rather than by looking for a file, because that
     * is how the server itself invokes ffmpeg: via PATH lookup in
     * ProcessBuilder. A file that exists but cannot be executed would pass a
     * filesystem check and still break the server.
     */
    private static boolean canRun(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            // The binary is not on PATH. That is an answer, not an error.
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("Interrupted probing " + command, e));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
