package io.github.jaronie.centroidServer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AppProperties is a record, so there is nothing to test about the binding
 * itself -- that is Spring's job, and the context-load test covers it. What is
 * worth testing is the path arithmetic in the helper methods, because every
 * other class depends on it being right.
 */
class AppPropertiesTest {

    private static AppProperties props() {
        return new AppProperties(
                Path.of("data/videos"),
                "../processor/target/centroidFinder.jar",
                Path.of("data/results"),
                Path.of("data/thumbnails"),
                Path.of("data/logs"),
                "ffmpeg");
    }

    @Test
    @DisplayName("every path helper returns an absolute, normalized path")
    void helpersReturnAbsolutePaths() {
        AppProperties p = props();

        assertThat(p.videoFile("a.mp4").isAbsolute()).isTrue();
        assertThat(p.resultFile("a.mp4").isAbsolute()).isTrue();
        assertThat(p.thumbnailFile("a.mp4").isAbsolute()).isTrue();
        assertThat(p.jobLogFile("job-1").isAbsolute()).isTrue();
        assertThat(p.jarFile().isAbsolute()).isTrue();
    }

    @Test
    @DisplayName("jarFile resolves the ../ that Spring's Path converter refuses")
    void jarFileNormalizesParentSegments() {
        // This is why jarPath is bound as a String rather than a Path: Spring's
        // converter rejects a value starting with "../". Path.of has no such
        // opinion, and normalize() collapses the segment away.
        assertThat(p().jarFile().toString())
                .endsWith("processor/target/centroidFinder.jar")
                .doesNotContain("..");
    }

    @Test
    @DisplayName("resultFile appends .csv, matching what the processor is told to write")
    void resultFileAppendsCsv() {
        assertThat(p().resultFile("intro.mp4").getFileName().toString())
                .isEqualTo("intro.mp4.csv");
    }

    @Test
    @DisplayName("thumbnail cache keys keep the original extension, so a.mov and a.mp4 cannot collide")
    void thumbnailNamesDoNotCollideAcrossExtensions() {
        AppProperties p = p();
        assertThat(p.thumbnailFile("a.mov").getFileName().toString()).isEqualTo("a.mov.jpg");
        assertThat(p.thumbnailFile("a.mp4").getFileName().toString()).isEqualTo("a.mp4.jpg");
        assertThat(p.thumbnailFile("a.mov")).isNotEqualTo(p.thumbnailFile("a.mp4"));
    }

    @Test
    @DisplayName("resultUrl is a URL path, not a filesystem path")
    void resultUrlIsAUrl() {
        // The client must never learn our directory layout. resultFile is for
        // the subprocess; resultUrl is for the response body.
        assertThat(p().resultUrl("intro.mp4")).isEqualTo("/results/intro.mp4.csv");
    }

    @Test
    @DisplayName("jobLogFile is named by job id, so concurrent jobs cannot overwrite each other")
    void jobLogFileIsPerJob() {
        assertThat(p().jobLogFile("abc").getFileName().toString()).isEqualTo("abc.log");
        assertThat(p().jobLogFile("abc")).isNotEqualTo(p().jobLogFile("def"));
    }

    private static AppProperties p() {
        return props();
    }
}
