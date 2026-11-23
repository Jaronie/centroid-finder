package io.github.jaronie.centroidServer.web.dto;

/** The 202 body from POST /process/{filename}: {"jobId": "..."}. */
public record JobIdResponse(String jobId) {
}
