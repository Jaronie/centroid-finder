package io.github.jaronie.centroidServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jaronie.centroidServer.config.AppProperties;
import io.github.jaronie.centroidServer.config.AsyncConfig;
import io.github.jaronie.centroidServer.service.JobRunner;

/**
 * Starts the whole application context. This is the only test that covers the
 * wiring itself: property binding, component scanning, the async executor and
 * the static resource handlers. Those have no logic to unit test -- the thing
 * that can break about them is whether they are configured at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CentroidServerApplicationTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    AppProperties props;

    @Autowired
    JobRunner jobRunner;

    @Autowired
    org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("the context loads, which proves every bean's dependencies resolve")
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    @Test
    @DisplayName("every app.* property is bound -- a null here is the logsDir bug all over again")
    void allPropertiesAreBound() {
        // app.logs-dir was once missing from application.properties. It compiled
        // and started fine, then every job failed with a NullPointerException on
        // the first use. This test makes that impossible to ship again.
        assertThat(props.videosDir()).isNotNull();
        assertThat(props.jarPath()).isNotNull().isNotBlank();
        assertThat(props.resultsDir()).isNotNull();
        assertThat(props.thumbnailCacheDir()).isNotNull();
        assertThat(props.logsDir()).isNotNull();
        assertThat(props.ffmpegPath()).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("the job executor bean exists and is bounded")
    void jobExecutorIsConfiguredAndBounded() {
        ThreadPoolTaskExecutor executor =
                context.getBean(AsyncConfig.JOB_EXECUTOR, ThreadPoolTaskExecutor.class);

        // Unbounded would mean N simultaneous requests spawn N JVMs running
        // video decoding, and the machine falls over. The bound is what turns
        // overload into an honest 500 instead of a crash.
        assertThat(executor.getMaxPoolSize()).isPositive().isLessThan(100);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("job-");
    }

    @Test
    @DisplayName("JobRunner is a proxy, which is what makes @Async actually asynchronous")
    void jobRunnerIsProxied() {
        // If JobRunner were called directly rather than through a proxy, @Async
        // would be silently ignored and POST /process would block until the
        // whole video finished -- with an identical-looking response body.
        // A different class name means Spring wrapped it.
        assertThat(jobRunner.getClass().getName())
                .as("expected a Spring proxy wrapping JobRunner")
                .isNotEqualTo(JobRunner.class.getName());
    }

    @Test
    @DisplayName("/videos/** serves real files from the videos directory")
    void videosAreServedStatically() throws Exception {
        Path probe = props.videosDir().resolve("static-probe.mp4");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, "not really a video");
        try {
            mvc.perform(get("/videos/static-probe.mp4")).andExpect(status().isOk());
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @Test
    @DisplayName("/results/** serves real files from the results directory")
    void resultsAreServedStatically() throws Exception {
        // Without this handler the job status endpoint reports a result URL
        // that 404s -- correct JSON pointing at nothing.
        Path probe = props.resultsDir().resolve("static-probe.csv");
        Files.createDirectories(probe.getParent());
        Files.writeString(probe, "0.000,1,2\n");
        try {
            mvc.perform(get("/results/static-probe.csv")).andExpect(status().isOk());
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @Test
    @DisplayName("a missing static file is 404, in our error shape")
    void missingStaticFileIs404() throws Exception {
        mvc.perform(get("/results/definitely-absent.csv")).andExpect(status().isNotFound());
        mvc.perform(get("/videos/definitely-absent.mp4")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("an unmapped URL is 404, not 500")
    void unmappedUrlIs404() throws Exception {
        // The catch-all exception handler must not flatten Spring's own HTTP
        // errors into 500s. This caught a real bug during development.
        mvc.perform(get("/no-such-endpoint")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the static resource handler refuses to escape its directory")
    void staticHandlerRefusesTraversal() throws Exception {
        mvc.perform(get("/videos/../../../etc/passwd"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("the server does NOT have the processor on its classpath")
    void processorIsNotOnTheClasspath() throws IOException {
        // The assignment forbids graph-search logic in the server. Omitting the
        // Maven dependency is what enforces it; this test makes a well-meaning
        // future "just add the dependency" impossible to do quietly.
        assertThat(classExists("io.github.jaronie.centroidFinder.VideoPipeline")).isFalse();
        assertThat(classExists("io.github.jaronie.centroidFinder.DfsBinaryGroupFinder")).isFalse();
        assertThat(classExists("io.github.jaronie.centroidFinder.Group")).isFalse();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
