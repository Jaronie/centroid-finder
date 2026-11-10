package io.github.jaronie.centroidServer.service;
import java.nio.file.Files;

import io.github.jaronie.centroidServer.exception.ApiException;

public class ThumbnailService {

    private final AppProperties props;
    private final VideoLibraryService service;
    
    @Service
    public ThumbnailService(AppProperties props, VideoLibraryService service){
        this.props = props;
        this.service = service;
    }

    public byte[] thumbnailFor(String filename){
        if(!service.isKnownVideo(filename)) throw ApiException.serverError("Error generating thumbnail");

        if (props.thumbnailFile(filename)) return Files.readAllBytes();
    }
    
}
