package io.github.jaronie.centroidServer.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaronie.centroidServer.service.VideoLibraryService;

/**
 * GET /api/videos
 *
 * <p>The Express equivalent of this whole class:
 * <pre>
 *   app.get('/api/videos', (req, res) =&gt; res.json(listVideoNames()));
 * </pre>
 *
 * <p>{@code @RestController} is {@code @Controller} + {@code @ResponseBody}.
 * The {@code @ResponseBody} half is the interesting one: it means the returned
 * object IS the response body. Spring hands the List to Jackson, which writes
 * it as a JSON array and sets Content-Type: application/json. There is no
 * res.json() call because there is no res, and no serialization code to write.
 */
@RestController
public class VideoController {

    private final VideoLibraryService library;

    public VideoController(VideoLibraryService library) {
        this.library = library;
    }

    @GetMapping("/api/videos")
    public List<String> listVideos() {
        return library.listVideoNames();
    }
}
