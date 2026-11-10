package io.github.jaronie.centroidServer.web;


import io.github.jaronie.centroidServer.service.ThumbnailService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ThumbnailController {

    private final ThumbnailService thumbnailService;

    public ThumbnailController(ThumbnailService thumbnailService) {
        this.thumbnailService = thumbnailService;
    }
    @GetMapping(value = "/thumbnail/{filename}", produces = MediaType.IMAGE_JPEG_VALUE)
    public byte[] thumbnail(@PathVariable String filename) {
        return thumbnailService.thumbnailFor(filename);
    }
}