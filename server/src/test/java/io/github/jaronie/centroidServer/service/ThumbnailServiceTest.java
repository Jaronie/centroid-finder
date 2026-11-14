package io.github.jaronie.centroidServer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import io.github.jaronie.centroidServer.config.AppProperties;
import io.github.jaronie.centroidServer.exception.ApiException;

/**
 * Runs the real ffmpeg against the real test clip. Mocking the subprocess would
 * leave the interesting parts untested -- whether our argument list is right,
 * whether the exit code is read correctly, and whether a failure leaves a
 * truncated file in the cache.
 *
 * <p>VideoLibraryService IS mocked, so these tests do not depend on the videos
 * directory's real contents.
 */
class ThumbnailServiceTest {

    /** The clip generated for manual testing: 3s, 320x240, red square centred. */
    private static final Path REAL_CLIP = Path.of("data/videos/testclip.mp4");

    @TempDir
    Path tmp;

    private Path videosDir;
    private Path cacheDir;
    private VideoLibraryService library;

    private ThumbnailService service(String ffmpeg) throws IOException {
        videosDir = Files.createDirectories(tmp.resolve("videos"));
        cacheDir = tmp.resolve("thumbs");
        library = mock(VideoLibraryService.class);
        AppProperties props = new AppProperties(
                videosDir, "jar.jar", tmp.resolve("results"), cacheDir, tmp.resolve("logs"), ffmpeg);
        return new ThumbnailService(props, library);
    }

    private void copyRealClipAs(String name) throws IOException {
        Files.copy(REAL_CLIP, videosDir.resolve(name));
        when(library.isKnownVideo(name)).thenReturn(true);
    }

    @Test
    @DisplayName("extracts a real JPEG first frame from a real video")
    void extractsJpeg() throws IOException {
        ThumbnailService service = service("ffmpeg");
        copyRealClipAs("clip.mp4");

        byte[] jpeg = service.thumbnailFor("clip.mp4");

        assertThat(jpeg).isNotEmpty();
        // JPEG magic number: FF D8 FF. Proves we produced an image, not just bytes.
        assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
        assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
        assertThat(jpeg[2] & 0xFF).isEqualTo(0xFF);
    }

    @Test
    @DisplayName("the generated thumbnail is cached under the expected name")
    void writesCacheFile() throws IOException {
        ThumbnailService service = service("ffmpeg");
        copyRealClipAs("clip.mp4");

        service.thumbnailFor("clip.mp4");

        assertThat(cacheDir.resolve("clip.mp4.jpg")).exists();
        assertThat(Files.size(cacheDir.resolve("clip.mp4.jpg"))).isGreaterThan(0);
    }

    @Test
    @DisplayName("a second call serves from cache instead of re-running ffmpeg")
    void secondCallUsesCache() throws IOException {
        ThumbnailService service = service("ffmpeg");
        copyRealClipAs("clip.mp4");

        byte[] first = service.thumbnailFor("clip.mp4");

        // Point ffmpeg at a command that does not exist. If the second call
        // tried to regenerate, it would fail; serving from cache cannot.
        ThumbnailService brokenFfmpeg = new ThumbnailService(
                new AppProperties(videosDir, "jar.jar", tmp.resolve("results"),
                        cacheDir, tmp.resolve("logs"), "definitely-not-a-real-binary"),
                library);

        assertThat(brokenFfmpeg.thumbnailFor("clip.mp4")).isEqualTo(first);
    }

    @Test
    @DisplayName("an unknown filename is a 500 with the spec's message, and never runs ffmpeg")
    void unknownFilenameIs500() throws IOException {
        ThumbnailService service = service("ffmpeg");
        when(library.isKnownVideo("ghost.mp4")).thenReturn(false);

        assertThatThrownBy(() -> service.thumbnailFor("ghost.mp4"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Error generating thumbnail")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("a traversal attempt is rejected by the allowlist before touching the filesystem")
    void traversalRejected() throws IOException {
        ThumbnailService service = service("ffmpeg");
        when(library.isKnownVideo("../../etc/passwd")).thenReturn(false);

        assertThatThrownBy(() -> service.thumbnailFor("../../etc/passwd"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Error generating thumbnail");
    }

    @Test
    @DisplayName("a corrupt video is a 500 and leaves NOTHING in the cache")
    void corruptVideoLeavesNoCacheFile() throws IOException {
        ThumbnailService service = service("ffmpeg");
        Files.writeString(videosDir.resolve("broken.mp4"), "not a video at all");
        when(library.isKnownVideo("broken.mp4")).thenReturn(true);

        assertThatThrownBy(() -> service.thumbnailFor("broken.mp4"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Error generating thumbnail");

        // The whole point of writing to a temp file and moving it only on
        // success: a failed run must not cache a truncated JPEG that every
        // later request would then serve forever.
        assertThat(cacheDir.resolve("broken.mp4.jpg")).doesNotExist();
    }

    @Test
    @DisplayName("a failed run leaves no .part temp files behind")
    void failedRunCleansUpTempFiles() throws IOException {
        ThumbnailService service = service("ffmpeg");
        Files.writeString(videosDir.resolve("broken.mp4"), "not a video");
        when(library.isKnownVideo("broken.mp4")).thenReturn(true);

        assertThatThrownBy(() -> service.thumbnailFor("broken.mp4"))
                .isInstanceOf(ApiException.class);

        try (Stream<Path> leftovers = Files.list(cacheDir)) {
            assertThat(leftovers).isEmpty();
        }
    }

    @Test
    @DisplayName("a missing ffmpeg binary is a 500, not an escaped IOException")
    void missingFfmpegIs500() throws IOException {
        ThumbnailService service = service("definitely-not-a-real-binary");
        copyRealClipAs("clip.mp4");

        assertThatThrownBy(() -> service.thumbnailFor("clip.mp4"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Error generating thumbnail");
    }
}
