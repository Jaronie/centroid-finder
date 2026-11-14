package io.github.jaronie.centroidServer.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.jaronie.centroidServer.model.Job;
import io.github.jaronie.centroidServer.model.JobStatus;

/**
 * JobService takes no dependencies, so it needs no Spring context and no mocks.
 * That is a deliberate design property, not a happy accident: the class is pure
 * state, which is what makes it this cheap to test.
 */
class JobServiceTest {

    private final JobService jobs = new JobService();

    @Test
    @DisplayName("create() returns a processing job with a UUID id and stores it")
    void createStoresProcessingJob() {
        Job job = jobs.create();

        assertThat(job.status()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.id()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(jobs.find(job.id())).contains(job);
    }

    @Test
    @DisplayName("every create() mints a distinct id")
    void idsAreUnique() {
        Set<String> ids = IntStream.range(0, 500)
                .mapToObj(i -> jobs.create().id())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(ids).hasSize(500);
    }

    @Test
    @DisplayName("find() on an id that was never issued is empty, which the controller turns into a 404")
    void findUnknownIsEmpty() {
        assertThat(jobs.find("never-issued")).isEmpty();
    }

    @Test
    @DisplayName("markDone moves the job to done and records the result URL")
    void markDone() {
        Job job = jobs.create();

        jobs.markDone(job.id(), "/results/a.csv");

        Job updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.DONE);
        assertThat(updated.resultUrl()).isEqualTo("/results/a.csv");
        assertThat(updated.errorMessage()).isNull();
    }

    @Test
    @DisplayName("markFailed moves the job to error and records the message")
    void markFailed() {
        Job job = jobs.create();

        jobs.markFailed(job.id(), "boom");

        Job updated = jobs.find(job.id()).orElseThrow();
        assertThat(updated.status()).isEqualTo(JobStatus.ERROR);
        assertThat(updated.errorMessage()).isEqualTo("boom");
        assertThat(updated.resultUrl()).isNull();
    }

    @Test
    @DisplayName("marking an unknown id is a no-op, not an exception")
    void markingUnknownIdIsSilent() {
        // computeIfPresent does nothing when the key is absent. This matters
        // because the async thread may finish after a restart wiped the map,
        // and it must not blow up on a thread nobody is listening to.
        jobs.markDone("ghost", "/results/a.csv");
        jobs.markFailed("ghost", "boom");

        assertThat(jobs.find("ghost")).isEmpty();
    }

    @Test
    @DisplayName("jobs are independent: updating one does not disturb another")
    void jobsAreIndependent() {
        Job a = jobs.create();
        Job b = jobs.create();

        jobs.markDone(a.id(), "/results/a.csv");

        assertThat(jobs.find(a.id()).orElseThrow().status()).isEqualTo(JobStatus.DONE);
        assertThat(jobs.find(b.id()).orElseThrow().status()).isEqualTo(JobStatus.PROCESSING);
    }

    @Test
    @DisplayName("concurrent writers and readers neither lose an update nor corrupt the map")
    void survivesConcurrentAccess() throws Exception {
        // This is the scenario the ConcurrentHashMap exists for: the job thread
        // writing while request threads read. With a plain HashMap this test can
        // lose updates or, during a resize, hang a reader outright.
        int jobCount = 200;
        var created = IntStream.range(0, jobCount).mapToObj(i -> jobs.create()).toList();

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> readBack = ConcurrentHashMap.newKeySet();

        for (Job job : created) {
            pool.submit(() -> {
                start.await();
                jobs.markDone(job.id(), "/results/" + job.id() + ".csv");
                return null;
            });
            pool.submit(() -> {
                start.await();
                jobs.find(job.id()).ifPresent(j -> readBack.add(j.id()));
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("no thread should hang; a hang here means the map is not thread-safe")
                .isTrue();

        // Every write must be visible afterwards.
        for (Job job : created) {
            Job finished = jobs.find(job.id()).orElseThrow();
            assertThat(finished.status()).isEqualTo(JobStatus.DONE);
            assertThat(finished.resultUrl()).isEqualTo("/results/" + job.id() + ".csv");
        }
        assertThat(readBack).isNotEmpty();
    }
}
