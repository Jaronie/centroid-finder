package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for FrameExtractor.
 *
 * FrameExtractor wraps FFmpeg, so it cannot be tested with a hand-built array
 * the way the pure classes can: it needs a real file on disk. Rather than
 * depend on a checked-in video, these tests encode a small one (160x120,
 * 10 fps, 10 frames, a white square sliding right on black) once for the whole
 * class and then assert against those known numbers.
 *
 * The frame size is deliberately not tiny: for very narrow frames FFmpeg pads
 * each decoded row, and Java2DFrameConverter then misreads the padding as extra
 * color channels and fails to build an image.
 */
public class FrameExtractorTests {

    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final int SQUARE = 20;
    private static final int STEP = 12;
    private static final int FRAME_COUNT = 10;
    private static final double FPS = 10.0;

    @TempDir
    static Path tempDir;

    private static Path videoPath;

    @BeforeAll
    static void recordTestVideo() throws Exception {
        videoPath = tempDir.resolve("moving-square.mp4");

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(videoPath.toFile(), WIDTH, HEIGHT);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFormat("mp4");
            recorder.setFrameRate(FPS);
            recorder.start();

            for (int i = 0; i < FRAME_COUNT; i++) {
                recorder.record(converter.getFrame(syntheticFrame(i)));
            }
        }

        assumeTrue(Files.exists(videoPath) && Files.size(videoPath) > 0,
                "FFmpeg could not encode a test video in this environment");
    }

    /** A black frame with a white square whose x position depends on the index. */
    private static BufferedImage syntheticFrame(int index) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(Color.WHITE);
        g.fillRect(index * STEP, (HEIGHT - SQUARE) / 2, SQUARE, SQUARE);
        g.dispose();
        return image;
    }

    /**
     * Helper: drain an extractor into a list so its frames' index/timestamp
     * metadata can be inspected. Pixel data must NOT be read from this list --
     * Java2DFrameConverter reuses one BufferedImage, so every element ends up
     * pointing at the last decoded frame (see
     * testAllFramesShareTheConvertersImageBuffer).
     */
    private static List<VideoFrame> readAll(FrameExtractor extractor) {
        List<VideoFrame> frames = new ArrayList<>();
        for (VideoFrame frame : extractor) {
            frames.add(frame);
        }
        return frames;
    }

    // ---------- opening a video ----------

    @Test
    public void testReportsVideoDimensions() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            assertAll(
                () -> assertEquals(WIDTH, extractor.width()),
                () -> assertEquals(HEIGHT, extractor.height())
            );
        }
    }

    @Test
    public void testMissingFileThrowsIllegalArgument() {
        Path missing = tempDir.resolve("does-not-exist.mp4");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new FrameExtractor(missing.toString())
        );

        assertTrue(exception.getMessage().contains("Could not open video"));
    }

    @Test
    public void testNonVideoFileThrowsIllegalArgument() throws Exception {
        Path garbage = tempDir.resolve("not-a-video.mp4");
        Files.writeString(garbage, "this is plain text, not an encoded video");

        assertThrows(IllegalArgumentException.class, () -> new FrameExtractor(garbage.toString()));
    }

    // ---------- decoding frames ----------

    @Test
    public void testDecodesEveryRecordedFrame() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            assertEquals(FRAME_COUNT, readAll(extractor).size());
        }
    }

    @Test
    public void testFramesCarryNonNullImagesOfVideoSize() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            for (VideoFrame frame : extractor) {
                assertNotNull(frame.image(), "frame " + frame.index() + " had a null image");
                assertEquals(WIDTH, frame.image().getWidth());
                assertEquals(HEIGHT, frame.image().getHeight());
            }
        }
    }

    @Test
    public void testIndicesStartAtZeroAndIncreaseByOne() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            List<VideoFrame> frames = readAll(extractor);

            for (int i = 0; i < frames.size(); i++) {
                assertEquals(i, frames.get(i).index());
            }
        }
    }

    @Test
    public void testTimestampsNeverGoBackwards() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            List<VideoFrame> frames = readAll(extractor);

            double previous = -1.0;
            for (VideoFrame frame : frames) {
                assertTrue(frame.seconds() >= previous,
                        "timestamp went backwards at frame " + frame.index());
                previous = frame.seconds();
            }
        }
    }

    @Test
    public void testTimestampsSpanTheExpectedDuration() throws Exception {
        // 10 frames at 10 fps is roughly one second of video, so the last
        // timestamp should land near 0.9s rather than at 0 or off in the weeds.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            List<VideoFrame> frames = readAll(extractor);
            double last = frames.get(frames.size() - 1).seconds();

            assertTrue(last > 0.5 && last < 1.5, "unexpected final timestamp: " + last);
        }
    }

    @Test
    public void testDecodedContentFollowsTheMovingSquare() throws Exception {
        // Lossy encoding changes exact pixel values, so this asserts on the
        // bright region's position instead: the square starts at the left edge
        // and ends further right.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            // Measured during iteration, not from a collected list: the
            // converter's image buffer is reused frame to frame.
            List<Double> centers = new ArrayList<>();
            for (VideoFrame frame : extractor) {
                centers.add(brightCenterX(frame.image()));
            }

            double firstX = centers.get(0);
            double lastX = centers.get(centers.size() - 1);

            assertTrue(lastX > firstX + STEP, "square did not move right: " + firstX + " -> " + lastX);

            for (int i = 1; i < centers.size(); i++) {
                assertTrue(centers.get(i) >= centers.get(i - 1),
                        "square moved left between frames " + (i - 1) + " and " + i);
            }
        }
    }

    @Test
    public void testAllFramesShareTheConvertersImageBuffer() throws Exception {
        // Documents a sharp edge rather than desired behavior:
        // Java2DFrameConverter returns the same BufferedImage object every
        // time, so holding on to VideoFrames and reading their pixels later
        // shows the LAST frame's content, not each frame's. VideoPipeline is
        // safe because it consumes each frame before advancing; anything that
        // buffers frames must copy the image first.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            Iterator<VideoFrame> iterator = extractor.iterator();
            BufferedImage first = iterator.next().image();
            BufferedImage second = iterator.next().image();

            assertSame(first, second);
        }
    }

    /** Average x of the bright pixels in the image's middle row. */
    private static double brightCenterX(BufferedImage image) {
        int y = image.getHeight() / 2;
        long sum = 0;
        int count = 0;
        for (int x = 0; x < image.getWidth(); x++) {
            int rgb = image.getRGB(x, y);
            int luma = ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
            if (luma > 383) { // over half brightness: inside the white square
                sum += x;
                count++;
            }
        }
        assertTrue(count > 0, "no bright pixels found in the decoded frame");
        return (double) sum / count;
    }

    // ---------- iterator contract ----------

    @Test
    public void testIteratingTwiceThrowsIllegalState() throws Exception {
        // The grabber is a one-way stream, so the extractor refuses a replay
        // instead of silently handing back an empty iterator.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            extractor.iterator();

            IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> extractor.iterator()
            );

            assertEquals("FrameExtractor can only be iterated once.", exception.getMessage());
        }
    }

    @Test
    public void testIteratorIsMarkedUsedEvenIfNoFramesAreRead() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            Iterator<VideoFrame> first = extractor.iterator();

            assertTrue(first.hasNext());
            assertThrows(IllegalStateException.class, extractor::iterator);
        }
    }

    @Test
    public void testHasNextIsFalseAndNextThrowsAtEndOfStream() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            Iterator<VideoFrame> iterator = extractor.iterator();
            while (iterator.hasNext()) {
                iterator.next();
            }

            assertFalse(iterator.hasNext());
            assertThrows(NoSuchElementException.class, iterator::next);
        }
    }

    @Test
    public void testHasNextDoesNotConsumeFrames() throws Exception {
        // hasNext() is called repeatedly here; the frame it reports must still
        // be the one next() returns.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            Iterator<VideoFrame> iterator = extractor.iterator();

            assertTrue(iterator.hasNext());
            assertTrue(iterator.hasNext());
            assertTrue(iterator.hasNext());

            assertEquals(0, iterator.next().index());
            assertEquals(1, iterator.next().index());
        }
    }

    // ---------- the checked-in sample video ----------

    @Test
    public void testReadsTheSampleVideoIfPresent() throws Exception {
        Path sample = Path.of("sampleInput", "ensantina.mp4");
        assumeTrue(Files.exists(sample), "sampleInput/ensantina.mp4 is not available");

        try (FrameExtractor extractor = new FrameExtractor(sample.toString())) {
            assertTrue(extractor.width() > 0);
            assertTrue(extractor.height() > 0);

            Iterator<VideoFrame> iterator = extractor.iterator();
            assertTrue(iterator.hasNext(), "sample video decoded no frames");

            VideoFrame first = iterator.next();
            assertNotNull(first.image());
            assertEquals(extractor.width(), first.image().getWidth());
            assertEquals(extractor.height(), first.image().getHeight());
        }
    }
}
