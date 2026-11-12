package io.github.jaronie.centroidServer.web;

import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.jaronie.centroidServer.exception.ApiException;
import io.github.jaronie.centroidServer.model.Job;
import io.github.jaronie.centroidServer.service.JobRunner;
import io.github.jaronie.centroidServer.service.JobService;
import io.github.jaronie.centroidServer.service.VideoLibraryService;
import io.github.jaronie.centroidServer.web.dto.JobIdResponse;
import io.github.jaronie.centroidServer.web.dto.JobStatusResponse;

/**
 * POST /process/{filename} and GET /process/{jobId}/status.
 */
@RestController
public class ProcessController {

    private static final Logger log = LoggerFactory.getLogger(ProcessController.class);

    /**
     * Bare RRGGBB, no leading "#". The processor calls
     * Integer.parseInt(hex, 16), which throws on a "#".
     */
    private static final Pattern HEX_COLOR = Pattern.compile("^[0-9a-fA-F]{6}$");

    private final VideoLibraryService library;
    private final JobService jobs;
    private final JobRunner runner;

    public ProcessController(VideoLibraryService library, JobService jobs, JobRunner runner) {
        this.library = library;
        this.jobs = jobs;
        this.runner = runner;
    }

    /**
     * Starts a job and returns 202 Accepted immediately.
     *
     * <p>Both query parameters are declared NOT required even though they are
     * mandatory. If Spring enforced required=true it would generate its own 400
     * with its own body, and the spec demands an exact message.
     *
     * <p>Validation order is the whole design of this method. Everything that
     * could be a 400 is checked BEFORE a job id is minted or the executor is
     * touched, because once 202 goes out we have promised to do the work and
     * 400 is no longer available to us.
     */
    @PostMapping("/process/{filename}")
    public ResponseEntity<JobIdResponse> startJob(
            @PathVariable String filename,
            @RequestParam(required = false) String targetColor,
            @RequestParam(required = false) String threshold) {

        if (isBlank(targetColor) || isBlank(threshold)) {
            throw ApiException.badRequest("Missing targetColor or threshold query parameter.");
        }
        if (!HEX_COLOR.matcher(targetColor).matches()) {
            throw ApiException.badRequest("targetColor must be a 6-digit hex value such as FF0000.");
        }

        int parsedThreshold;
        try {
            parsedThreshold = Integer.parseInt(threshold.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("threshold must be an integer.");
        }
        if (parsedThreshold < 0) {
            throw ApiException.badRequest("threshold must be zero or greater.");
        }

        if (!library.isKnownVideo(filename)) {
            throw ApiException.badRequest("Unknown video: " + filename);
        }

        // Only now, with every user error ruled out, does the job become real.
        Job job = jobs.create();

        try {
            runner.run(job.id(), filename, targetColor, parsedThreshold);
        } catch (RejectedExecutionException e) {
            // The pool is saturated. Spring's TaskRejectedException extends
            // RejectedExecutionException, so catching the parent covers both.
            log.warn("Executor refused job {}", job.id(), e);
            jobs.markFailed(job.id(), "Error starting job");
            throw ApiException.serverError("Error starting job", e);
        }

        // 202 Accepted, not 200 OK: taken, not finished.
        return ResponseEntity.accepted().body(new JobIdResponse(job.id()));
    }

    /**
     * Pure lookup. Writes nothing, never touches a subprocess. An id we never
     * issued is a 404, and so is an id lost to a restart.
     */
    @GetMapping("/process/{jobId}/status")
    public JobStatusResponse jobStatus(@PathVariable String jobId) {
        Job job = jobs.find(jobId)
                .orElseThrow(() -> ApiException.notFound("Job ID not found"));
        return JobStatusResponse.from(job);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
