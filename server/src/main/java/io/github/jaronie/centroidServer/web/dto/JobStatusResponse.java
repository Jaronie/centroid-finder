package io.github.jaronie.centroidServer.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.github.jaronie.centroidServer.model.Job;
import io.github.jaronie.centroidServer.model.JobStatus;

/**
 * All three status shapes from one record.
 *
 * <p>@JsonInclude(NON_NULL) makes Jackson omit null fields entirely, so a
 * processing job serializes to {"status":"processing"} with no empty "result"
 * or "error" keys hanging off it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobStatusResponse(
        JobStatus status,
        String result,
        String error) {

    public static JobStatusResponse from(Job job) {
        return new JobStatusResponse(job.status(), job.resultUrl(), job.errorMessage());
    }
}
