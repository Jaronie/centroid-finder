package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for FrameExtractor's --scale support: decoding frames smaller than the
 * source so that every per-pixel stage downstream has less to do.
 *
 * <p>Like FrameExtractorTests, these encode a real video rather than depend on
 * a checked-in one, because FFmpeg is what is under test here.
 *
 * <p>The distinction these pin down is between the size frames are DELIVERED at
 * and the size of the SOURCE. Mixing those up is the bug to guard against:
 * FrameValidator checks frames against the delivered size, while centroids have
 * to be mapped back using the source size.
 */
public class FrameExtractorScalingTests {

    // 640x480 so the arithmetic is clean: half is 320x240, and 320 is a
    // multiple of the 64-pixel width alignment, so scale 0.5 is exact.
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int FRAME_COUNT = 6;
    private static final double FPS = 10.0;

    @TempDir
    static Path tempDir;

    private static Path videoPath;

    @BeforeAll
    static void recordTestVideo() throws Exception {
        videoPath = tempDir.resolve("scaling-source.mp4");

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(videoPath.toFile(), WIDTH, HEIGHT);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFormat("mp4");
            recorder.setFrameRate(FPS);
            recorder.start();

            for (int i = 0; i < FRAME_COUNT; i++) {
                recorder.record(converter.getFrame(syntheticFrame()));
            }
        }

        assumeTrue(Files.exists(videoPath) && Files.size(videoPath) > 0,
                "FFmpeg could not encode a test video in this environment");
    }

    /** Black with a white square centred, so a centroid is predictable. */
    private static BufferedImage syntheticFrame() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(Color.WHITE);
        g.fillRect(WIDTH / 2 - 40, HEIGHT / 2 - 40, 80, 80);
        g.dispose();
        return image;
    }

    // ---------- unscaled behavior is unchanged ----------

    @Test
    @DisplayName("the one-argument constructor still decodes at full size")
    public void defaultConstructorIsUnscaled() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString())) {
            assertAll(
                () -> assertEquals(WIDTH, extractor.width()),
                () -> assertEquals(HEIGHT, extractor.height()),
                () -> assertEquals(WIDTH, extractor.nativeWidth()),
                () -> assertEquals(HEIGHT, extractor.nativeHeight()),
                () -> assertEquals(1.0, extractor.scaleX()),
                () -> assertEquals(1.0, extractor.scaleY()),
                () -> assertFalse(extractor.isScaled())
            );
        }
    }

    @Test
    @DisplayName("an explicit scale of 1.0 is also unscaled")
    public void explicitFullScaleIsUnscaled() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 1.0)) {
            assertAll(
                () -> assertEquals(WIDTH, extractor.width()),
                () -> assertEquals(HEIGHT, extractor.height()),
                () -> assertFalse(extractor.isScaled())
            );
        }
    }

    // ---------- scaled decoding ----------

    @Test
    @DisplayName("half scale delivers half-size frames while reporting the source size")
    public void halfScaleDeliversHalfSizeFrames() throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.5)) {
            assertAll(
                () -> assertEquals(WIDTH / 2, extractor.width()),
                () -> assertEquals(HEIGHT / 2, extractor.height()),
                // The source size must survive, because that is what centroids
                // get mapped back into.
                () -> assertEquals(WIDTH, extractor.nativeWidth()),
                () -> assertEquals(HEIGHT, extractor.nativeHeight()),
                () -> assertEquals(0.5, extractor.scaleX()),
                () -> assertEquals(0.5, extractor.scaleY()),
                () -> assertTrue(extractor.isScaled())
            );
        }
    }

    @Test
    @DisplayName("decoded images really are the reduced size")
    public void decodedImagesAreScaled() throws Exception {
        // The reported size is one thing; what FFmpeg hands back is another.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.5)) {
            int seen = 0;
            for (VideoFrame frame : extractor) {
                assertAll(
                    () -> assertEquals(WIDTH / 2, frame.image().getWidth()),
                    () -> assertEquals(HEIGHT / 2, frame.image().getHeight())
                );
                seen++;
            }
            assertTrue(seen > 0, "expected at least one frame");
        }
    }

    @Test
    @DisplayName("a scaled frame still passes a validator built from the delivered size")
    public void scaledFramesValidateAgainstDeliveredSize() throws Exception {
        // This is the contract VideoSummaryApp relies on: it builds the
        // validator from width()/height(), so frames must match those, not the
        // source's dimensions.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.5)) {
            FrameValidator validator = new FrameValidator(extractor.width(), extractor.height());

            for (VideoFrame frame : extractor) {
                assertTrue(validator.validate(frame).valid(),
                        "scaled frame rejected: " + validator.validate(frame).reason());
            }
        }
    }

    @Test
    @DisplayName("timestamps are unaffected by scaling")
    public void timestampsSurviveScaling() throws Exception {
        // Scaling changes the pixels, not the time axis.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.5)) {
            double previous = -1;
            int count = 0;
            for (VideoFrame frame : extractor) {
                assertTrue(frame.seconds() >= previous,
                        "timestamps must not go backwards: " + frame.seconds() + " after " + previous);
                previous = frame.seconds();
                count++;
            }
            assertTrue(count > 0);
        }
    }

    @Test
    @DisplayName("a scale too small to align falls back to full size rather than corrupting frames")
    public void unusablySmallScaleFallsBackToNative() throws Exception {
        // 640 * 0.001 aligns down to 0, which FFmpeg would reject outright.
        // Below the 64-pixel alignment there is no safe width, so decoding
        // happens at native size: a slower correct answer beats a faster wrong
        // one, and scaleX()/scaleY() report what actually happened.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.001)) {
            assertAll(
                () -> assertEquals(WIDTH, extractor.width()),
                () -> assertEquals(HEIGHT, extractor.height()),
                () -> assertEquals(1.0, extractor.scaleX()),
                () -> assertEquals(1.0, extractor.scaleY()),
                () -> assertFalse(extractor.isScaled())
            );
        }
    }

    @Test
    @DisplayName("the delivered width is a multiple of 64, snapped down from the request")
    public void widthIsSnappedDownToAlignment() throws Exception {
        // 640 * 0.45 = 288, which is not a multiple of 64, so it snaps to 256.
        // Height is independent and only needs to be even: 480 * 0.45 = 216.
        //
        // This alignment is not cosmetic. An unaligned width makes FFmpeg pad
        // each row, and Java2DFrameConverter then reads 3-channel data as
        // 4-channel and produces garbage pixels with no error.
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.45)) {
            assertAll(
                () -> assertEquals(256, extractor.width()),
                () -> assertEquals(0, extractor.width() % 64),
                () -> assertEquals(216, extractor.height()),
                () -> assertEquals(0, extractor.height() % 2),
                // The axes end up with genuinely different scales, which is why
                // they are tracked separately rather than as one factor.
                () -> assertEquals(256.0 / WIDTH, extractor.scaleX()),
                () -> assertEquals(216.0 / HEIGHT, extractor.scaleY())
            );
        }
    }

    @Test
    @DisplayName("scaled frames are decoded as 3-band images, not misread as 4")
    public void scaledFramesAreThreeBandImages() throws Exception {
        // The direct guard on the corruption described above. A 4-band image
        // here means FFmpeg's row padding was misinterpreted and every pixel is
        // wrong -- which is silent without this assertion.
        for (double scale : new double[] { 0.5, 0.45, 0.25, 0.1 }) {
            try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), scale)) {
                VideoFrame frame = extractor.iterator().next();
                assertEquals(3, frame.image().getRaster().getNumBands(),
                        "scale " + scale + " produced a "
                                + frame.image().getRaster().getNumBands() + "-band image");
            }
        }
    }

    @Test
    @DisplayName("a white square stays white after scaling, rather than becoming noise")
    public void scaledFrameContentIsNotCorrupted() throws Exception {
        // The behavioral version of the band-count check: corrupted frames
        // showed the centred square as scattered specks. A correct half-size
        // frame has roughly a quarter of the white pixels.
        int fullCount = whitePixelCount(1.0);
        int halfCount = whitePixelCount(0.5);

        assertTrue(fullCount > 0, "expected white pixels at full size");
        double ratio = (double) halfCount / fullCount;
        assertTrue(ratio > 0.15 && ratio < 0.4,
                "half-scale white area should be about a quarter of full scale, but was "
                        + ratio + " (" + halfCount + " vs " + fullCount + ")");
    }

    private static int whitePixelCount(double scale) throws Exception {
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), scale)) {
            int[][] binary = new DistanceImageBinarizer(new EuclideanColorDistance(), 0xFFFFFF, 100)
                    .toBinaryArray(extractor.iterator().next().image());
            int count = 0;
            for (int[] row : binary) {
                for (int value : row) {
                    count += value;
                }
            }
            return count;
        }
    }

    // ---------- rejected input ----------

    @Test
    @DisplayName("a scale outside (0, 1] is rejected")
    public void invalidScaleIsRejected() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new FrameExtractor(videoPath.toString(), 0.0)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new FrameExtractor(videoPath.toString(), -0.5)),
            // Upscaling is not the point of this option and would only add
            // work, so it is refused rather than silently honored.
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new FrameExtractor(videoPath.toString(), 1.5)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new FrameExtractor(videoPath.toString(), Double.NaN))
        );
    }

    @Test
    @DisplayName("a missing file is still reported as such, even with a scale")
    public void missingFileStillThrows() {
        // The size probe opens the file first, so this must fail there with the
        // same error as before rather than something about scaling.
        assertThrows(IllegalArgumentException.class,
                () -> new FrameExtractor(tempDir.resolve("no-such.mp4").toString(), 0.5));
    }

    // ---------- end to end: a scaled centroid lands where it should ----------

    @Test
    @DisplayName("a centroid found at half scale maps back onto the source square")
    public void scaledCentroidMapsBackToSourceCoordinates() throws Exception {
        // The square is centred, so its centroid should come back near the
        // middle of the SOURCE frame despite being measured in a half-size one.
        ImageBinarizer binarizer =
                new DistanceImageBinarizer(new EuclideanColorDistance(), 0xFFFFFF, 100);
        ImageGroupFinder inner =
                new BinarizingImageGroupFinder(binarizer, new DfsBinaryGroupFinder());

        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString(), 0.5)) {
            ImageGroupFinder finder = new ScalingImageGroupFinder(
                    inner, extractor.scaleX(), extractor.scaleY());

            VideoFrame first = extractor.iterator().next();
            Coordinate centroid = finder.findConnectedGroups(first.image()).get(0).centroid();

            // Within a few pixels of centre: scaling costs about a pixel of
            // precision per halving, and the encoder is lossy.
            assertAll(
                () -> assertTrue(Math.abs(centroid.x() - WIDTH / 2) <= 6,
                        "x was " + centroid.x() + ", expected near " + WIDTH / 2),
                () -> assertTrue(Math.abs(centroid.y() - HEIGHT / 2) <= 6,
                        "y was " + centroid.y() + ", expected near " + HEIGHT / 2)
            );
        }
    }
}
