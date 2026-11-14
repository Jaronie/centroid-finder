package io.github.jaronie.centroidServer.web;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jaronie.centroidServer.exception.ApiException;
import io.github.jaronie.centroidServer.service.ThumbnailService;

@Import(WebSliceTestConfig.class)
@WebMvcTest(ThumbnailController.class)
class ThumbnailControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ThumbnailService thumbnails;

    @Test
    @DisplayName("200 with image/jpeg and the raw bytes")
    void returnsJpegBytes() throws Exception {
        byte[] jpeg = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02 };
        given(thumbnails.thumbnailFor("clip.mp4")).willReturn(jpeg);

        mvc.perform(get("/thumbnail/clip.mp4"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(jpeg));
    }

    @Test
    @DisplayName("the full filename including its extension reaches the service")
    void pathVariableKeepsTheExtension() throws Exception {
        // Spring used to truncate a path variable at the last dot, so this
        // would have arrived as "clip". That was removed in Spring 6; this test
        // pins the behaviour so an upgrade cannot regress it silently.
        given(thumbnails.thumbnailFor("clip.mp4")).willReturn(new byte[] { 1 });

        mvc.perform(get("/thumbnail/clip.mp4")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a service failure becomes 500 with the spec's message")
    void failureBecomes500() throws Exception {
        given(thumbnails.thumbnailFor("ghost.mp4"))
                .willThrow(ApiException.serverError("Error generating thumbnail"));

        mvc.perform(get("/thumbnail/ghost.mp4"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error generating thumbnail"));
    }
}
