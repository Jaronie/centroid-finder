package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for VideoPipeline.
 *
 * VideoPipeline is the coordinator: validate a frame -> find its groups ->
 * write one CSV row. No video decoding is involved, because run() takes any
 * Iterable<VideoFrame>, so these tests feed it hand-built frames and a stub
 * ImageGroupFinder with scripted answers. The validator and the CSV writer are
 * the real classes: the writer goes to a @TempDir file whose lines are read
 * back and compared.
 *
 * The invariant under test throughout is "exactly one row per frame", which is
 * what keeps the CSV's time axis continuous.
 */
public class VideoPipelineTests {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 4;

    @TempDir
    Path tempDir;

    // ---------- test doubles and helpers ----------

    /**
     * Returns a scripted list of groups for each successive call, so a test can
     * say "frame 0 has two groups, frame 1 has none" without binarizing
     * anything. Also records how many times it was asked.
     */
    private static class StubGroupFinder implements ImageGroupFinder {
        private final List<List<Group>> answers;
        private int calls = 0;

        StubGroupFinder(List<List<Group>> answers) {
            this.answers = answers;
        }

        @Override
        public List<Group> findConnectedGroups(BufferedImage image) {
            List<Group> answer = answers.get(calls);
            calls++;
            return answer;
        }
    }

    /** A valid frame: correct size, non-null image. */
    private static VideoFrame validFrame(int index, double seconds) {
        return new VideoFrame(index, seconds, new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB));
    }

    /** An invalid frame: the converter handed back no image. */
    private static VideoFrame nullImageFrame(int index, double seconds) {
        return new VideoFrame(index, seconds, null);
    }

    /** An invalid frame: right content, wrong dimensions. */
    private static VideoFrame wrongSizeFrame(int index, double seconds) {
        return new VideoFrame(index, seconds,
                new BufferedImage(WIDTH + 2, HEIGHT, BufferedImage.TYPE_INT_RGB));
    }

    private static Group group(int size, int x, int y) {
        return new Group(size, new Coordinate(x, y));
    }

    private static List<Group> noGroups() {
        return Collections.emptyList();
    }

    /**
     * Runs the pipeline over the given frames with the given scripted group
     * answers, and hands back the summary plus the CSV lines that were written.
     */
    private Result run(List<VideoFrame> frames, List<List<Group>> answers, boolean header) throws IOException {
        Path csvPath = tempDir.resolve("centroids-" + System.nanoTime() + ".csv");
        StubGroupFinder finder = new StubGroupFinder(answers);
        FrameValidator validator = new FrameValidator(WIDTH, HEIGHT);

        VideoPipeline.Summary summary;
        try (CentroidCsvWriter csv = new CentroidCsvWriter(csvPath, header)) {
            summary = new VideoPipeline(finder, validator, csv).run(frames);
        }

        return new Result(summary, Files.readAllLines(csvPath), finder.calls);
    }

    private record Result(VideoPipeline.Summary summary, List<String> lines, int groupFinderCalls) {}

    // ---------- the happy path ----------

    @Test
    public void testWritesOneRowPerFrameWithLargestCentroid() throws IOException {
        List<VideoFrame> frames = List.of(
            validFrame(0, 0.0),
            validFrame(1, 0.5),
            validFrame(2, 1.0)
        );
        List<List<Group>> answers = List.of(
            List.of(group(10, 1, 2)),
            List.of(group(20, 3, 4)),
            List.of(group(30, 5, 6))
        );

        Result result = run(frames, answers, false);

        assertAll(
            () -> assertEquals(List.of("0.000,1,2", "0.500,3,4", "1.000,5,6"), result.lines()),
            () -> assertEquals(new VideoPipeline.Summary(3, 0, 0), result.summary())
        );
    }

    @Test
    public void testUsesTheFirstGroupBecauseTheListIsSortedLargestFirst() throws IOException {
        // ImageGroupFinder promises descending order, so the pipeline takes
        // index 0 rather than searching for a maximum. This test pins that
        // contract: the first element wins even when a later one is bigger.
        List<List<Group>> answers = List.of(List.of(
            group(5, 7, 7),
            group(500, 1, 1)
        ));

        Result result = run(List.of(validFrame(0, 2.0)), answers, false);

        assertEquals(List.of("2.000,7,7"), result.lines());
    }

    @Test
    public void testWritesHeaderWhenTheWriterIsConfiguredWithOne() throws IOException {
        Result result = run(List.of(validFrame(0, 0.0)), List.of(List.of(group(1, 0, 0))), true);

        assertAll(
            () -> assertEquals("seconds,x,y", result.lines().get(0)),
            () -> assertEquals("0.000,0,0", result.lines().get(1)),
            () -> assertEquals(2, result.lines().size())
        );
    }

    @Test
    public void testTimestampsAreWrittenWithThreeDecimals() throws IOException {
        List<VideoFrame> frames = List.of(validFrame(0, 1.0 / 3.0), validFrame(1, 12.3456));
        List<List<Group>> answers = List.of(List.of(group(1, 0, 0)), List.of(group(1, 0, 0)));

        Result result = run(frames, answers, false);

        assertEquals(List.of("0.333,0,0", "12.346,0,0"), result.lines());
    }

    // ---------- frames with nothing to report ----------

    @Test
    public void testFrameWithNoGroupsWritesMissingRow() throws IOException {
        Result result = run(List.of(validFrame(0, 0.25)), List.of(noGroups()), false);

        assertAll(
            () -> assertEquals(List.of("0.250,-1,-1"), result.lines()),
            () -> assertEquals(new VideoPipeline.Summary(1, 0, 1), result.summary())
        );
    }

    @Test
    public void testInvalidFrameWritesMissingRowAndIsNotSentToTheGroupFinder() throws IOException {
        // A frame that fails validation must still produce a row, and must not
        // reach the group finder (its image may be null).
        Result result = run(List.of(nullImageFrame(0, 0.75)), new ArrayList<>(), false);

        assertAll(
            () -> assertEquals(List.of("0.750,-1,-1"), result.lines()),
            () -> assertEquals(new VideoPipeline.Summary(1, 1, 0), result.summary()),
            () -> assertEquals(0, result.groupFinderCalls())
        );
    }

    @Test
    public void testWrongSizedFrameCountsAsInvalid() throws IOException {
        Result result = run(List.of(wrongSizeFrame(3, 1.5)), new ArrayList<>(), false);

        assertAll(
            () -> assertEquals(List.of("1.500,-1,-1"), result.lines()),
            () -> assertEquals(new VideoPipeline.Summary(1, 1, 0), result.summary())
        );
    }

    @Test
    public void testMissingRowsUseTheWritersNoCentroidConstant() throws IOException {
        Result result = run(List.of(validFrame(0, 0.0)), List.of(noGroups()), false);

        String expected = "0.000," + CentroidCsvWriter.NO_CENTROID + "," + CentroidCsvWriter.NO_CENTROID;
        assertEquals(expected, result.lines().get(0));
    }

    // ---------- mixed streams ----------

    @Test
    public void testMixedStreamKeepsOneRowPerFrameAndCountsEachCategory() throws IOException {
        List<VideoFrame> frames = List.of(
            validFrame(0, 0.0),        // has a group
            nullImageFrame(1, 0.1),    // invalid
            validFrame(2, 0.2),        // no group
            validFrame(3, 0.3),        // has a group
            wrongSizeFrame(4, 0.4)     // invalid
        );
        // Only the three valid frames consult the group finder, in order.
        List<List<Group>> answers = List.of(
            List.of(group(9, 2, 2)),
            noGroups(),
            List.of(group(4, 6, 1))
        );

        Result result = run(frames, answers, false);

        assertAll(
            () -> assertEquals(List.of(
                    "0.000,2,2",
                    "0.100,-1,-1",
                    "0.200,-1,-1",
                    "0.300,6,1",
                    "0.400,-1,-1"
                ), result.lines()),
            () -> assertEquals(frames.size(), result.lines().size(), "one row per frame"),
            () -> assertEquals(new VideoPipeline.Summary(5, 2, 1), result.summary()),
            () -> assertEquals(3, result.groupFinderCalls())
        );
    }

    @Test
    public void testSummaryCountsAreIndependent() throws IOException {
        // invalidFrames and framesWithoutGroup must not double-count: an
        // invalid frame is never also counted as "without a group".
        List<VideoFrame> frames = List.of(nullImageFrame(0, 0.0), nullImageFrame(1, 0.1));

        Result result = run(frames, new ArrayList<>(), false);

        VideoPipeline.Summary summary = result.summary();
        assertAll(
            () -> assertEquals(2, summary.frames()),
            () -> assertEquals(2, summary.invalidFrames()),
            () -> assertEquals(0, summary.framesWithoutGroup())
        );
    }

    // ---------- edge cases ----------

    @Test
    public void testEmptyStreamWritesNothing() throws IOException {
        Result result = run(List.of(), new ArrayList<>(), false);

        assertAll(
            () -> assertEquals(List.of(), result.lines()),
            () -> assertEquals(new VideoPipeline.Summary(0, 0, 0), result.summary()),
            () -> assertEquals(0, result.groupFinderCalls())
        );
    }

    @Test
    public void testEmptyStreamStillWritesTheHeader() throws IOException {
        Result result = run(List.of(), new ArrayList<>(), true);

        assertEquals(List.of("seconds,x,y"), result.lines());
    }

    @Test
    public void testRowOrderFollowsTheStreamNotTheTimestamps() throws IOException {
        // The pipeline is a straight pass-through; it does not sort or dedupe.
        List<VideoFrame> frames = List.of(validFrame(0, 5.0), validFrame(1, 1.0));
        List<List<Group>> answers = List.of(List.of(group(1, 1, 1)), List.of(group(1, 2, 2)));

        Result result = run(frames, answers, false);

        assertEquals(List.of("5.000,1,1", "1.000,2,2"), result.lines());
    }

    @Test
    public void testHandlesALongerStream() throws IOException {
        // A stand-in for a real video: 300 frames at 30 fps, every tenth frame
        // empty. The row count is what matters here.
        int frameCount = 300;
        List<VideoFrame> frames = new ArrayList<>();
        List<List<Group>> answers = new ArrayList<>();
        int expectedEmpty = 0;
        for (int i = 0; i < frameCount; i++) {
            frames.add(validFrame(i, i / 30.0));
            if (i % 10 == 0) {
                answers.add(noGroups());
                expectedEmpty++;
            } else {
                answers.add(List.of(group(i + 1, i % WIDTH, i % HEIGHT)));
            }
        }

        Result result = run(frames, answers, false);
        int empty = expectedEmpty;

        assertAll(
            () -> assertEquals(frameCount, result.lines().size()),
            () -> assertEquals(new VideoPipeline.Summary(frameCount, 0, empty), result.summary()),
            () -> assertTrue(result.lines().stream().allMatch(line -> line.split(",").length == 3))
        );
    }

    // ---------- failure propagation ----------

    @Test
    public void testIoExceptionFromTheWriterPropagates() throws IOException {
        // If the disk fails mid-video the caller must hear about it rather than
        // get a summary describing rows that were never written.
        Path csvPath = tempDir.resolve("broken.csv");
        CentroidCsvWriter failing = new CentroidCsvWriter(csvPath, false) {
            @Override
            public void write(double seconds, int x, int y) throws IOException {
                throw new IOException("disk full");
            }
        };

        VideoPipeline pipeline = new VideoPipeline(
            new StubGroupFinder(List.of(List.of(group(1, 0, 0)))),
            new FrameValidator(WIDTH, HEIGHT),
            failing
        );

        IOException exception = assertThrows(
            IOException.class,
            () -> pipeline.run(List.of(validFrame(0, 0.0)))
        );
        assertEquals("disk full", exception.getMessage());

        failing.close();
    }

    @Test
    public void testExceptionFromTheGroupFinderPropagates() throws IOException {
        Path csvPath = tempDir.resolve("exploding.csv");
        ImageGroupFinder exploding = image -> {
            throw new IllegalStateException("binarizer blew up");
        };

        try (CentroidCsvWriter csv = new CentroidCsvWriter(csvPath, false)) {
            VideoPipeline pipeline = new VideoPipeline(exploding, new FrameValidator(WIDTH, HEIGHT), csv);

            assertThrows(IllegalStateException.class, () -> pipeline.run(List.of(validFrame(0, 0.0))));
        }
    }

    /**
     * Documents current behavior, not desired behavior: a null element in the
     * stream is reported invalid by the validator, but the pipeline then logs
     * frame.index() on the null reference and throws. A real FrameExtractor
     * never yields null, so this is reachable only from a hand-built stream --
     * but the "frame is null" branch of FrameValidator is unusable until
     * VideoPipeline stops dereferencing the frame it just rejected.
     */
    @Test
    public void testNullFrameInTheStreamCurrentlyThrows() throws IOException {
        Path csvPath = tempDir.resolve("null-frame.csv");

        try (CentroidCsvWriter csv = new CentroidCsvWriter(csvPath, false)) {
            VideoPipeline pipeline = new VideoPipeline(
                new StubGroupFinder(new ArrayList<>()),
                new FrameValidator(WIDTH, HEIGHT),
                csv
            );

            assertThrows(NullPointerException.class, () -> pipeline.run(Arrays.asList((VideoFrame) null)));
        }
    }

    // ---------- Summary record ----------

    @Test
    public void testSummaryCarriesItsFields() {
        VideoPipeline.Summary summary = new VideoPipeline.Summary(7, 2, 1);

        assertAll(
            () -> assertEquals(7, summary.frames()),
            () -> assertEquals(2, summary.invalidFrames()),
            () -> assertEquals(1, summary.framesWithoutGroup())
        );
    }
}
