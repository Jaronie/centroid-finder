package io.github.jaronie.centroidServer.model;

/**
 * One processing job. Deliberately immutable: a state change produces a new Job
 * rather than mutating this one. Exactly one of resultUrl and errorMessage is
 * ever set, which matches the three JSON status shapes.
 */
public record Job(
        String id,
        JobStatus status,
        String resultUrl,
        String errorMessage) {

    public static Job processing(String id) {
        return new Job(id, JobStatus.PROCESSING, null, null);
    }

    public Job done(String resultUrl) {
        return new Job(id, JobStatus.DONE, resultUrl, null);
    }

    public Job failed(String errorMessage) {
        return new Job(id, JobStatus.ERROR, null, errorMessage);
    }
}
