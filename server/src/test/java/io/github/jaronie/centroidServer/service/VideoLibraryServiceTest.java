package io.github.jaronie.centroidServer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import io.github.jaronie.centroidServer.config.AppProperties;
import io.github.jaronie.centroidServer.exception.ApiException;

/**
 * Uses a real temporary directory rather than a mocked filesystem, because the
 * behaviour under test IS filesystem behaviour: what Files.list returns, how a
 * missing directory differs from an unreadable one, and whether a traversal
 * string can escape.
 */
class VideoLibraryServiceTest {

    @TempDir
    Path videosDir;

    private VideoLibraryService service() {
        return new VideoLibraryService(new AppProperties(
                videosDir, "jar.jar",
                videosDir.resolve("results"),
                videosDir.resolve("thumbs"),
                videosDir.resolve("logs"),
                "ffmpeg"));
    }

    private void touch(String name) throws IOException {
        Files.writeString(videosDir.resolve(name), "x");
    }

    @Test
    @DisplayName("an empty directory is a success with an empty list, NOT an error")
    void emptyDirectoryIsNotAnError() {
        // The spec is explicit: an empty folder is [] with a 200. Collapsing
        // "no videos" into "cannot read directory" is the easy mistake here.
        assertThat(service().listVideoNames()).isEmpty();
    }

    @Test
    @DisplayName("lists video files and sorts them")
    void listsAndSorts() throws IOException {
        touch("zebra.mp4");
        touch("alpha.mov");
        touch("middle.webm");

        assertThat(service().listVideoNames())
                .containsExactly("alpha.mov", "middle.webm", "zebra.mp4");
    }

    @Test
    @DisplayName("non-video files are filtered out")
    void filtersNonVideos() throws IOException {
        touch("keep.mp4");
        touch("notes.txt");
        touch("thumb.jpg");
        touch("data.csv");
        touch("noextension");

        assertThat(service().listVideoNames()).containsExactly("keep.mp4");
    }

    @Test
    @DisplayName("extension matching ignores case")
    void extensionMatchingIsCaseInsensitive() throws IOException {
        touch("SHOUTY.MP4");
        touch("Mixed.MoV");

        assertThat(service().listVideoNames()).containsExactly("Mixed.MoV", "SHOUTY.MP4");
    }

    @Test
    @DisplayName("subdirectories are not listed as videos")
    void ignoresDirectories() throws IOException {
        touch("real.mp4");
        Files.createDirectory(videosDir.resolve("nested.mp4"));

        assertThat(service().listVideoNames()).containsExactly("real.mp4");
    }

    @Test
    @DisplayName("a missing directory is a 500 with the spec's exact message")
    void missingDirectoryThrows() {
        VideoLibraryService service = new VideoLibraryService(new AppProperties(
                Path.of("/nonexistent/path/nope"), "jar.jar",
                Path.of("/tmp"), Path.of("/tmp"), Path.of("/tmp"), "ffmpeg"));

        assertThatThrownBy(service::listVideoNames)
                .isInstanceOf(ApiException.class)
                .hasMessage("Error reading video directory")
                .extracting(e -> ((ApiException) e).getStatus())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("a path that is a file, not a directory, is also a 500")
    void fileInsteadOfDirectoryThrows() throws IOException {
        Path file = videosDir.resolve("iamafile.txt");
        Files.writeString(file, "x");

        VideoLibraryService service = new VideoLibraryService(new AppProperties(
                file, "jar.jar", Path.of("/tmp"), Path.of("/tmp"), Path.of("/tmp"), "ffmpeg"));

        assertThatThrownBy(service::listVideoNames)
                .isInstanceOf(ApiException.class)
                .hasMessage("Error reading video directory");
    }

    @Test
    @DisplayName("isKnownVideo accepts a real listed file")
    void knownVideoAccepted() throws IOException {
        touch("real.mp4");

        assertThat(service().isKnownVideo("real.mp4")).isTrue();
    }

    @Test
    @DisplayName("isKnownVideo rejects anything not in the listing, including null and blank")
    void unknownVideoRejected() throws IOException {
        touch("real.mp4");
        VideoLibraryService service = service();

        assertThat(service.isKnownVideo("absent.mp4")).isFalse();
        assertThat(service.isKnownVideo(null)).isFalse();
        assertThat(service.isKnownVideo("")).isFalse();
        assertThat(service.isKnownVideo("   ")).isFalse();
    }

    @Test
    @DisplayName("isKnownVideo defeats path traversal, absolute paths and leading-dash names")
    void allowlistDefeatsTraversal() throws IOException {
        touch("real.mp4");
        // A file that genuinely exists one level up, to prove the escape would
        // otherwise find something real.
        Files.writeString(videosDir.getParent().resolve("outside.mp4"), "x");
        VideoLibraryService service = service();

        assertThat(service.isKnownVideo("../outside.mp4")).isFalse();
        assertThat(service.isKnownVideo("../../etc/passwd")).isFalse();
        assertThat(service.isKnownVideo("/etc/passwd")).isFalse();
        assertThat(service.isKnownVideo("subdir/real.mp4")).isFalse();
        assertThat(service.isKnownVideo("-rf")).isFalse();
        assertThat(service.isKnownVideo("real.mp4 ")).isFalse();

        // The point: the check is exact-match against a real listing, so no
        // encoding trick helps. Only a name we enumerated ourselves passes.
        assertThat(service.isKnownVideo("real.mp4")).isTrue();
    }

    @Test
    @DisplayName("the listing is re-read each call, so a new file is picked up without a restart")
    void listingIsNotCached() throws IOException {
        VideoLibraryService service = service();
        assertThat(service.isKnownVideo("later.mp4")).isFalse();

        touch("later.mp4");

        assertThat(service.isKnownVideo("later.mp4")).isTrue();
    }
}
