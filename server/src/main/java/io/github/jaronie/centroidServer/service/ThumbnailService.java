package io.github.jaronie.centroidServer.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import io.github.jaronie.centroidServer.exception.ApiException;
import io.github.jaronie.centroidServer.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Service
public class ThumbnailService {

    private final AppProperties props;
    private final VideoLibraryService videoLibrary;
    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static final String ERROR = "Error generating thumbnail";
    private static final long FFMPEG_TIMEOUT_SECONDS = 30;


    public ThumbnailService(AppProperties props, VideoLibraryService videoLibrary) {
        this.props = props;
        this.videoLibrary = videoLibrary;
    }

    public byte[] thumbnailFor(String filename) {

        if (!videoLibrary.isKnownVideo(filename)) {
            log.warn("Thumbnail requested for unknown video: {}", filename);
            throw ApiException.serverError(ERROR);
        }

        Path cached = props.thumbnailFile(filename);

        // Storing cache
        try {
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
                return Files.readAllBytes(cached);
            }

            // Cache miss
            Files.createDirectories(cached.getParent());
            Path temp = Files.createTempFile(cached.getParent(), "thumb-", ".tmp");
            try {
                runFfmpeg(props.videosDir().resolve(filename), temp);

                if (Files.size(temp) == 0) {
                    log.warn("ffmpeg produced an empty thumbnail for {}", filename);
                    throw ApiException.serverError(ERROR);
                }
                Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            return Files.readAllBytes(cached);
        } catch (IOException e) {
            log.error("Thumbnail generation failed for {}", filename, e);
            throw ApiException.serverError(ERROR, e);
        }
    }

    private void runFfmpeg(Path video, Path output) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                props.ffmpegPath(),
                "-nostdin", "-loglevel", "error", "-y",
                "-i", video.toString(),
                "-frames:v", "1",
                "-q:v", "2",
                "-f", "image2",
                output.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        Process process = pb.start(); // IOException if ffmpeg isn't on PATH
        try {
            if (!process.waitFor(FFMPEG_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffmpeg timed out for {}", video);
                throw ApiException.serverError(ERROR);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ApiException.serverError(ERROR, e);
        }

        if (process.exitValue() != 0) {
            log.warn("ffmpeg exited with {} for {}", process.exitValue(), video);
            throw ApiException.serverError(ERROR);
        }
    }

}
