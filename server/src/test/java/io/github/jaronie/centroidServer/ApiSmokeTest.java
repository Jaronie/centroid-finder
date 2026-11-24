package io.github.jaronie.centroidServer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;

import io.github.jaronie.centroidServer.support.TestEnv;

/**
 * The baseline end-to-end test: a real Tomcat on a real port, a real HTTP
 * client, the real async executor, and the real processor jar launched as a
 * real subprocess.
 *
 * <p>Every other test in this suite stubs something. The web slice tests mock
 * the services, and the service tests construct their collaborators by hand.
 * That is the right way round for pinning behaviour, but it leaves one thing
 * untested: whether the pieces, wired together by Spring and talking over
 * HTTP, actually complete a job. The parts can all pass while the whole is
 * broken -- a controller that returns a result URL no handler serves, an
 * {@code @Async} annotation that silently does nothing, a jar invocation whose
 * argument order is wrong.
 *
 * <p>This test is deliberately the slowest one here. It is also the only one
 * that would have caught any of those.
 *
 * <p><strong>Why the JDK's HttpClient</strong> rather than Spring's
 * TestRestTemplate: TestRestTemplate drags in {@code spring-boot-restclient}
 * for RestTemplateBuilder, and this server has no RestTemplate on its
 * classpath by design -- it makes no outbound HTTP calls. Adding a client
 * library purely to test it would widen the dependency surface the pom is
 * careful about. {@code java.net.http} is in the JDK and is not tied to a
 * Spring Boot version.
 *
 * <p><strong>Hermetic by construction.</strong> Every {@code app.*} directory
 * is redirected into a per-run temp directory, so this test neither reads nor
 * writes the repository's {@code server/data/}. It therefore passes on a fresh
 * clone, where that directory does not exist at all.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTest {

    private static final String CLIP = "testclip.mp4";

    /** How long the processor gets to handle a 3-second clip before we call it broken. */
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(3);

    /**
     * Created before the context starts, because {@code @DynamicPropertySource}
     * runs at context-creation time -- earlier than {@code @BeforeAll}, and far
     * earlier than JUnit's {@code @TempDir} injection.
     */
    private static final Path SANDBOX = createSandbox();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    int port;

    private static Path createSandbox() {
        try {
            Path root = Files.createTempDirectory("centroid-smoke-");
            // The clip has to exist before the context starts: the videos
            // directory is an allowlist, and an empty one makes every request
            // a 400 "Unknown video".
            TestEnv.copyTestClip(root.resolve("videos").resolve(CLIP));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void sandboxProperties(DynamicPropertyRegistry registry) {
        registry.add("app.videos-dir", () -> SANDBOX.resolve("videos"));
        registry.add("app.results-dir", () -> SANDBOX.resolve("results"));
        registry.add("app.thumbnail-cache-dir", () -> SANDBOX.resolve("thumbnails"));
        registry.add("app.logs-dir", () -> SANDBOX.resolve("logs"));
        // An absent jar still yields a valid context -- the path is only read
        // when a job runs -- so the assumption below is what skips this class.
        registry.add("app.jar-path", () -> TestEnv.processorJar()
                .map(Path::toString)
                .orElse(SANDBOX.resolve("no-processor-jar-was-built.jar").toString()));
    }

    @BeforeAll
    static void requireProcessorJar() {
        TestEnv.requireProcessorJar();
    }

    // -----------------------------------------------------------------------
    // The happy path, start to finish.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("POST /process -> poll status -> GET the CSV: the whole flow over real HTTP")
    void fullProcessingFlow() throws Exception {
        // 1. Start the job. 202, not 200: taken, not finished.
        HttpResponse<String> accepted =
                post("/process/" + CLIP + "?targetColor=FF0000&threshold=100");

        assertThat(accepted.statusCode()).isEqualTo(202);
        String jobId = (String) parse(accepted).get("jobId");
        assertThat(jobId)
                .as("the 202 body must carry a jobId, or a client has nothing to poll")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

        // 2. Poll until the job leaves "processing".
        Map<String, Object> finished = awaitTerminalStatus(jobId);

        assertThat(finished.get("status"))
                .as("job did not finish successfully: %s", finished)
                .isEqualTo("done");
        assertThat(finished.get("error")).isNull();

        String resultUrl = (String) finished.get("result");
        assertThat(resultUrl).isEqualTo("/results/" + CLIP + ".csv");

        // 3. The reported URL must actually serve the CSV. A correct-looking
        // status pointing at a 404 is the exact failure this catches.
        HttpResponse<String> csv = get(resultUrl);
        assertThat(csv.statusCode()).isEqualTo(200);

        // 4. The content is a real centroid track: one row per frame, and the
        // red square sits dead centre of a 320x240 frame.
        List<String> rows = csv.body().lines().filter(line -> !line.isBlank()).toList();
        assertThat(rows)
                .hasSize(30)
                .allSatisfy(row -> assertThat(row).matches("\\d+\\.\\d+,149,119"));

        // 5. A per-job log exists either way, which is where a failure would
        // have to be diagnosed from.
        assertThat(SANDBOX.resolve("logs").resolve(jobId + ".log")).exists();
    }

    @Test
    @DisplayName("GET /api/videos lists the clip that POST /process accepts")
    void videosListMatchesWhatCanBeProcessed() throws Exception {
        HttpResponse<String> videos = get("/api/videos");

        assertThat(videos.statusCode()).isEqualTo(200);
        // These two endpoints read the same allowlist. If they ever disagree,
        // the UI offers a video the server then rejects as unknown.
        assertThat(JSON.readValue(videos.body(), String[].class)).contains(CLIP);
    }

    @Test
    @DisplayName("GET /thumbnail returns a real JPEG for a listed video")
    void thumbnailIsAJpeg() throws Exception {
        TestEnv.requireFfmpeg();

        HttpResponse<byte[]> thumb = HTTP.send(
                HttpRequest.newBuilder(uri("/thumbnail/" + CLIP)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(thumb.statusCode()).isEqualTo(200);
        assertThat(thumb.headers().firstValue("Content-Type")).contains("image/jpeg");
        // JPEG magic number: FF D8 FF.
        assertThat(thumb.body()).isNotEmpty();
        assertThat(thumb.body()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(thumb.body()[1] & 0xFF).isEqualTo(0xD8);
        assertThat(thumb.body()[2] & 0xFF).isEqualTo(0xFF);
    }

    // -----------------------------------------------------------------------
    // The error contract, over real HTTP rather than MockMvc.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("a rejected request is a 400 and starts nothing")
    void userErrorsAre400() throws Exception {
        assertThat(post("/process/" + CLIP + "?targetColor=FF0000").statusCode())
                .as("missing threshold")
                .isEqualTo(400);
        assertThat(post("/process/" + CLIP + "?targetColor=nothex&threshold=100").statusCode())
                .as("malformed targetColor")
                .isEqualTo(400);
        assertThat(post("/process/" + CLIP + "?targetColor=FF0000&threshold=abc").statusCode())
                .as("non-numeric threshold")
                .isEqualTo(400);
        assertThat(post("/process/ghost.mp4?targetColor=FF0000&threshold=100").statusCode())
                .as("unknown video")
                .isEqualTo(400);

        // Nothing above was a valid job, so nothing may have been written.
        Path results = SANDBOX.resolve("results");
        if (Files.isDirectory(results)) {
            assertThat(results.resolve("ghost.mp4.csv")).doesNotExist();
        }
    }

    @Test
    @DisplayName("an unissued job id is a 404")
    void unknownJobIdIs404() throws Exception {
        assertThat(get("/process/11111111-2222-3333-4444-555555555555/status").statusCode())
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------------

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(uri(path)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(HttpResponse<String> response) throws IOException {
        return JSON.readValue(response.body(), Map.class);
    }

    /**
     * Polls the status endpoint until the job is no longer processing.
     *
     * <p>Polling rather than sleeping a fixed interval: the subprocess takes
     * as long as it takes, and a fixed wait is either flaky or slow. The
     * timeout is the failure mode that matters -- a job stuck in "processing"
     * forever is precisely the bug JobRunner's try/catch exists to prevent.
     */
    private Map<String, Object> awaitTerminalStatus(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(JOB_TIMEOUT);
        Map<String, Object> body = null;

        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> response = get("/process/" + jobId + "/status");

            assertThat(response.statusCode())
                    .as("a job we just started must be findable")
                    .isEqualTo(200);

            body = parse(response);
            if (!"processing".equals(body.get("status"))) {
                return body;
            }

            Thread.sleep(250);
        }

        throw new AssertionError(
                "Job " + jobId + " was still processing after " + JOB_TIMEOUT + ". Last status: " + body);
    }
}
