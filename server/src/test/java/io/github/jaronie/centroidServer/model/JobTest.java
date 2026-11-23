package io.github.jaronie.centroidServer.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Job is immutable on purpose: it is written by the async job thread and read
 * by request threads, and replacing the whole value inside a ConcurrentHashMap
 * is what makes that safe without any explicit synchronization. These tests pin
 * that immutability, because losing it would reintroduce a race that is very
 * hard to reproduce.
 */
class JobTest {

    @Test
    @DisplayName("a new job starts as processing with neither result nor error")
    void startsProcessing() {
        Job job = Job.processing("id-1");

        assertThat(job.id()).isEqualTo("id-1");
        assertThat(job.status()).isEqualTo(JobStatus.PROCESSING);
        assertThat(job.resultUrl()).isNull();
        assertThat(job.errorMessage()).isNull();
    }

    @Test
    @DisplayName("done() keeps the id, sets the result, and leaves errorMessage null")
    void doneSetsResultOnly() {
        Job done = Job.processing("id-1").done("/results/a.csv");

        assertThat(done.id()).isEqualTo("id-1");
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.resultUrl()).isEqualTo("/results/a.csv");
        assertThat(done.errorMessage()).isNull();
    }

    @Test
    @DisplayName("failed() keeps the id, sets the error, and leaves resultUrl null")
    void failedSetsErrorOnly() {
        Job failed = Job.processing("id-1").failed("boom");

        assertThat(failed.id()).isEqualTo("id-1");
        assertThat(failed.status()).isEqualTo(JobStatus.ERROR);
        assertThat(failed.errorMessage()).isEqualTo("boom");
        assertThat(failed.resultUrl()).isNull();
    }

    @Test
    @DisplayName("a transition returns a NEW object and does not mutate the original")
    void transitionsDoNotMutate() {
        Job original = Job.processing("id-1");
        Job done = original.done("/results/a.csv");

        assertThat(done).isNotSameAs(original);
        assertThat(original.status()).isEqualTo(JobStatus.PROCESSING);
        assertThat(original.resultUrl()).isNull();
    }

    @Test
    @DisplayName("only one of resultUrl and errorMessage is ever set, matching the JSON shapes")
    void neverBothResultAndError() {
        for (Job job : new Job[] {
                Job.processing("x"),
                Job.processing("x").done("/results/a.csv"),
                Job.processing("x").failed("boom") }) {
            boolean hasResult = job.resultUrl() != null;
            boolean hasError = job.errorMessage() != null;
            assertThat(hasResult && hasError).isFalse();
        }
    }

    @Test
    @DisplayName("JobStatus serializes to the lowercase strings the spec requires")
    void statusJsonValues() {
        // The spec allows exactly processing/done/error -- no PENDING.
        assertThat(JobStatus.values()).hasSize(3);
        assertThat(JobStatus.PROCESSING.json()).isEqualTo("processing");
        assertThat(JobStatus.DONE.json()).isEqualTo("done");
        assertThat(JobStatus.ERROR.json()).isEqualTo("error");
    }
}
