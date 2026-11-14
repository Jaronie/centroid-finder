package io.github.jaronie.centroidServer.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jaronie.centroidServer.exception.ApiException;
import io.github.jaronie.centroidServer.service.VideoLibraryService;

/**
 * @WebMvcTest starts only the web layer -- the controller, the message
 * converters and ApiExceptionHandler -- with no embedded Tomcat and no services.
 * Fast, and it isolates "did the HTTP contract hold" from "is the logic right".
 */
@Import(WebSliceTestConfig.class)
@WebMvcTest(VideoController.class)
class VideoControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VideoLibraryService library;

    @Test
    @DisplayName("200 with a JSON array of filenames")
    void listsVideos() throws Exception {
        given(library.listVideoNames()).willReturn(List.of("a.mp4", "b.mov"));

        mvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").value("a.mp4"))
                .andExpect(jsonPath("$[1]").value("b.mov"));
    }

    @Test
    @DisplayName("an empty library is 200 with [], not an error")
    void emptyLibraryIsOk() throws Exception {
        given(library.listVideoNames()).willReturn(List.of());

        mvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("a service failure becomes 500 with the spec's error body")
    void serviceFailureBecomes500() throws Exception {
        // Proves the controller needs no try/catch: ApiExceptionHandler shapes
        // this, and the body must match the spec exactly.
        given(library.listVideoNames())
                .willThrow(ApiException.serverError("Error reading video directory"));

        mvc.perform(get("/api/videos"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error reading video directory"))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist());
    }
}
