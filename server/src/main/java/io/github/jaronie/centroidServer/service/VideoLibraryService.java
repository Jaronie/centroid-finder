package io.github.jaronie.centroidServer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import io.github.jaronie.centroidServer.config.AppProperties;
import io.github.jaronie.centroidServer.exception.ApiException;

/**
 * Lists the videos directory.
 *
 * <p>{@code @Service} marks this as a bean, so Spring creates exactly one and
 * injects it wherever it is asked for. There is no module export / require
 * step: the class itself is the registration.
 */
@Service
public class VideoLibraryService {

    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of(".mp4", ".mov", ".avi", ".mkv", ".webm", ".m4v");

    private final AppProperties props;

    /**
     * Constructor injection. Spring sees this class needs an AppProperties and
     * hands it the one from the container. The field is final, so the dependency
     * cannot be forgotten or swapped later, and a unit test can supply a fake
     * with a plain `new VideoLibraryService(...)`.
     */
    public VideoLibraryService(AppProperties props) {
        this.props = props;
    }

    /**
     * Filenames of every video in the configured directory, sorted.
     *
     * <p>An empty directory is not an error: it returns an empty list, which
     * serializes to [] with a 200. Only a directory we cannot read is a 500.
     */
    public List<String> listVideoNames() {
        Path dir = props.videosDir();

        if (!Files.isDirectory(dir)) {
            throw ApiException.serverError("Error reading video directory");
        }

        // try-with-resources: Files.list holds an OS directory handle that must
        // be closed. The stream is lazy, so the handle stays open until the
        // terminal .toList() runs, which is why the whole chain sits inside.
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(VideoLibraryService::looksLikeVideo)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw ApiException.serverError("Error reading video directory", e);
        }
    }

    private static boolean looksLikeVideo(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    public boolean isKnownVideo(String filename) {
        if(filename == null || filename.isBlank()) return false;

        return listVideoNames();
    }
}
