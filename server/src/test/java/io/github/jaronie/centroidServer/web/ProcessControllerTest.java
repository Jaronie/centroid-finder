package io.github.jaronie.centroidServer.web;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jaronie.centroidServer.model.Job;
import io.github.jaronie.centroidServer.service.JobRunner;
import io.github.jaronie.centroidServer.service.JobService;
import io.github.jaronie.centroidServer.service.VideoLibraryService;

/**
 * The most valuable test class in the suite. It pins the two things that are
 * easiest to break and hardest to notice: the exact 400 messages, and that
 * nothing is started until every user error has been ruled out.
 */
@Import(WebSliceTestConfig.class)
@WebMvcTest(ProcessController.class)
class ProcessControllerTest {

    private static final String ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    VideoLibraryService library;
    @MockitoBean
    JobService jobs;
    @MockitoBean
    JobRunner runner;

    @BeforeEach
    void setUp() {
        given(library.isKnownVideo("clip.mp4")).willReturn(true);
        given(jobs.create()).willReturn(Job.processing(ID));
    }

    // ---------- the happy path ----------

    @Test
    @DisplayName("202 Accepted with the job id, and the runner is handed the parsed values")
    void startsJob() throws Exception {
        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "FF0000")
                        .param("threshold", "100"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(ID));

        // 202 means "taken, not finished". The threshold must arrive as an int.
        verify(runner).run(ID, "clip.mp4", "FF0000", 100);
    }

    @Test
    @DisplayName("202, not 200 -- the status code is the whole point of the design")
    void statusIsAccepted() throws Exception {
        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "ff00aa")
                        .param("threshold", "0"))
                .andExpect(status().is(202));
    }

    // ---------- validation: every one of these must be a 400 ----------

    @Test
    @DisplayName("both params missing -> the spec's exact message")
    void missingBothParams() throws Exception {
        mvc.perform(post("/process/clip.mp4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Missing targetColor or threshold query parameter."));
    }

    @Test
    @DisplayName("only targetColor -> same missing-parameter message")
    void missingThreshold() throws Exception {
        mvc.perform(post("/process/clip.mp4").param("targetColor", "FF0000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Missing targetColor or threshold query parameter."));
    }

    @Test
    @DisplayName("only threshold -> same missing-parameter message")
    void missingTargetColor() throws Exception {
        mvc.perform(post("/process/clip.mp4").param("threshold", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Missing targetColor or threshold query parameter."));
    }

    @Test
    @DisplayName("blank params count as missing, not as a bad hex value")
    void blankParamsCountAsMissing() throws Exception {
        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "  ")
                        .param("threshold", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("Missing targetColor or threshold query parameter."));
    }

    @Test
    @DisplayName("a leading # is rejected, because the processor does parseInt(hex, 16)")
    void hashPrefixRejected() throws Exception {
        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "#FF0000")
                        .param("threshold", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error")
                        .value("targetColor must be a 6-digit hex value such as FF0000."));
    }

    @Test
    @DisplayName("hex of the wrong length or with non-hex digits is rejected")
    void malformedHexRejected() throws Exception {
        for (String bad : new String[] { "FFF", "FF00000", "GGGGGG", "ff 000" }) {
            mvc.perform(post("/process/clip.mp4")
                            .param("targetColor", bad)
                            .param("threshold", "100"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error")
                            .value("targetColor must be a 6-digit hex value such as FF0000."));
        }
    }

    @Test
    @DisplayName("lowercase and mixed-case hex are accepted")
    void hexCaseInsensitive() throws Exception {
        for (String ok : new String[] { "ff0000", "Ff00Aa", "ABCDEF" }) {
            mvc.perform(post("/process/clip.mp4")
                            .param("targetColor", ok)
                            .param("threshold", "100"))
                    .andExpect(status().isAccepted());
        }
    }

    @Test
    @DisplayName("a non-numeric threshold is rejected")
    void nonNumericThresholdRejected() throws Exception {
        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "FF0000")
                        .param("threshold", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("threshold must be an integer."));
    }

    @Test
    @DisplayName("a negative threshold is rejected")
    void negativeThresholdRejected() throws Exception {
        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "FF0000")
                        .param("threshold", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("threshold must be zero or greater."));
    }

    @Test
    @DisplayName("a filename not in the library is rejected")
    void unknownVideoRejected() throws Exception {
        given(library.isKnownVideo("ghost.mp4")).willReturn(false);

        mvc.perform(post("/process/ghost.mp4")
                        .param("targetColor", "FF0000")
                        .param("threshold", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Unknown video: ghost.mp4"));
    }

    // ---------- the ordering constraint ----------

    @Test
    @DisplayName("NO job is created and NO work is started when validation fails")
    void validationHappensBeforeAnythingIsStarted() throws Exception {
        // This is the constraint the 202 imposes. Once 202 goes out we have
        // promised to do the work, so 400 is no longer available -- which means
        // every user error must be caught before a job id is minted.
        mvc.perform(post("/process/clip.mp4").param("targetColor", "nothex").param("threshold", "100"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/process/clip.mp4").param("targetColor", "FF0000").param("threshold", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/process/clip.mp4"))
                .andExpect(status().isBadRequest());

        verify(jobs, never()).create();
        verify(runner, never()).run(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a saturated executor is a 500 'Error starting job', and the job is marked failed")
    void rejectedExecutionBecomes500() throws Exception {
        willThrow(new RejectedExecutionException("pool full"))
                .given(runner).run(anyString(), anyString(), anyString(), anyInt());

        mvc.perform(post("/process/clip.mp4")
                        .param("targetColor", "FF0000")
                        .param("threshold", "100"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error starting job"));

        verify(jobs).markFailed(ID, "Error starting job");
    }

    // ---------- the status endpoint: all three shapes ----------

    @Test
    @DisplayName("processing -> {\"status\":\"processing\"} with no other keys")
    void processingShape() throws Exception {
        given(jobs.find(ID)).willReturn(Optional.of(Job.processing(ID)));

        mvc.perform(get("/process/{id}/status", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("done -> status plus result, with no error key")
    void doneShape() throws Exception {
        given(jobs.find(ID)).willReturn(Optional.of(Job.processing(ID).done("/results/clip.mp4.csv")));

        mvc.perform(get("/process/{id}/status", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.result").value("/results/clip.mp4.csv"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("error -> status plus error, with no result key")
    void errorShape() throws Exception {
        given(jobs.find(ID)).willReturn(
                Optional.of(Job.processing(ID).failed("Error processing video: boom")));

        mvc.perform(get("/process/{id}/status", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.error").value("Error processing video: boom"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("an unknown job id is 404 'Job ID not found'")
    void unknownJobIdIs404() throws Exception {
        given(jobs.find("nope")).willReturn(Optional.empty());

        mvc.perform(get("/process/nope/status"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Job ID not found"));
    }

    @Test
    @DisplayName("reading a status starts nothing and changes nothing")
    void statusIsReadOnly() throws Exception {
        given(jobs.find(ID)).willReturn(Optional.of(Job.processing(ID)));

        mvc.perform(get("/process/{id}/status", ID)).andExpect(status().isOk());

        verify(jobs, never()).create();
        verify(runner, never()).run(anyString(), anyString(), anyString(), anyInt());
    }
}
