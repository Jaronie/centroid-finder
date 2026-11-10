package io.github.jaronie.centroidServer.service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import io.github.jaronie.centroidServer.exception.ApiException;

public class ThumbnailService {

    private final AppProperties props;
    private final VideoLibraryService service;
    private static final Logger log = LoggerFactory.getLogger(ThumbnailService.class);
    private static String ERROR = "Error generation thumbnail";
    private static final long FFMPEG_TIMEOUT_SECONDS = 30;
    
    @Service
    public ThumbnailService(AppProperties props, VideoLibraryService service){
        this.props = props;
        this.service = service;
    }

    public byte[] thumbnailFor(String filename){
        if(!service.isKnownVideo(filename)) {
            log.warn("Thumbnail requested for unknwon video: {}", filename);
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
        Path temp = Files.createTempFile(cahced.getParent(), "thumb-", ".tmp");
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
    } catch (IOException e){
        log.error("Thumbnail generation failed for {}", filename, e);
        throw ApiException.serverError(ERROR, e);
    }
    }

    
}
