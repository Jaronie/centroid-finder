package io.github.jaronie.centroidServer.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import io.github.jaronie.centroidServer.model.Job;

/**
 * The in-memory job table. Pure state: this class never launches a process and
 * knows nothing about centroidFinder.jar.
 *
 * <p>State lives only in memory, per Decision 1 of the plan. A restart loses
 * every job, and the status endpoint then reports 404 for ids it once issued.
 */
@Service
public class JobService {

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    /** Mints a job id and records it as processing before any work starts. */
    public Job create() {
        String id = UUID.randomUUID().toString();
        Job job = Job.processing(id);
        jobs.put(id, job);
        return job;
    }

    /**
     * Optional rather than a nullable Job, so "this id might not exist" is part
     * of the method's type and the caller cannot forget the 404 case.
     */
    public Optional<Job> find(String jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void markDone(String jobId, String resultUrl) {
        jobs.computeIfPresent(jobId, (id, job) -> job.done(resultUrl));
    }

    public void markFailed(String jobId, String errorMessage) {
        jobs.computeIfPresent(jobId, (id, job) -> job.failed(errorMessage));
    }
}
