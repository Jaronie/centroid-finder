package io.github;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for video metadata reading and frame extraction using JavaCV.
 *
 * The test video is generated at runtime (no binary fixtures needed):
 * 50 frames, 320x240, 25 fps (2 seconds). The first 25 frames are red,
 * the last 25 frames are blue, so extracted frames can be verified by color.
 */
class TestingJavaCV {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FPS = 25;
    private static final int TOTAL_FRAMES = 50;

    @TempDir
    static Path sharedDir;

    private static File videoFile;

    @BeforeAll
    static void createTestVideo() throws Exception {
        videoFile = sharedDir.resolve("test-video.mp4").toFile();

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(videoFile, WIDTH, HEIGHT);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            recorder.setFormat("mp4");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(FPS);
            recorder.setVideoBitrate(2_000_000);
            recorder.start();

            for (int i = 0; i < TOTAL_FRAMES; i++) {
                BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = image.createGraphics();
                g.setColor(i < TOTAL_FRAMES / 2 ? Color.RED : Color.BLUE);
                g.fillRect(0, 0, WIDTH, HEIGHT);
                g.dispose();
                recorder.record(converter.convert(image));
            }
            recorder.stop();
        }

        assertTrue(videoFile.exists() && videoFile.length() > 0, "Test video should have been created");
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    @Test
    void shouldReadVideoMetadata() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();

            assertEquals(WIDTH, grabber.getImageWidth());
            assertEquals(HEIGHT, grabber.getImageHeight());
            assertEquals(FPS, grabber.getFrameRate(), 0.5);
            assertEquals(TOTAL_FRAMES, grabber.getLengthInVideoFrames(), 2);

            // Duration is reported in microseconds
            long expectedMicros = (long) (TOTAL_FRAMES / (double) FPS * 1_000_000);
            assertEquals(expectedMicros, grabber.getLengthInTime(), 200_000);

            assertNotNull(grabber.getFormat());
            assertTrue(grabber.getFormat().contains("mp4"), "Format was: " + grabber.getFormat());
            assertEquals(avcodec.AV_CODEC_ID_MPEG4, grabber.getVideoCodec());
            assertTrue(grabber.getVideoBitrate() > 0);

            // File has no audio track
            assertEquals(0, grabber.getAudioChannels());

            grabber.stop();
        }
    }

    @Test
    void shouldReadFormatAndMetadataMaps() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();

            assertNotNull(grabber.getMetadata());
            assertNotNull(grabber.getVideoMetadata());

            grabber.stop();
        }
    }

    // ------------------------------------------------------------------
    // Frame extraction
    // ------------------------------------------------------------------

    @Test
    void shouldGrabFirstFrameAsImage() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            Frame frame = grabber.grabImage();
            assertNotNull(frame, "First frame should not be null");
            assertNotNull(frame.image);

            BufferedImage image = converter.getBufferedImage(frame);
            assertNotNull(image);
            assertEquals(WIDTH, image.getWidth());
            assertEquals(HEIGHT, image.getHeight());

            assertTrue(isMostlyRed(image), "First frame should be red");

            grabber.stop();
        }
    }

    @Test
    void shouldCountAllFrames() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile)) {
            grabber.start();

            int count = 0;
            while (grabber.grabImage() != null) {
                count++;
            }

            assertEquals(TOTAL_FRAMES, count, 2);
            assertNull(grabber.grabImage(), "Grabbing past the end should return null");

            grabber.stop();
        }
    }

    @Test
    void shouldExtractEveryNthFrameAndSaveAsPng() throws Exception {
        File outputDir = sharedDir.resolve("frames").toFile();
        assertTrue(outputDir.mkdirs());

        final int step = 10;
        int saved = 0;

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            Frame frame;
            int index = 0;
            while ((frame = grabber.grabImage()) != null) {
                if (index % step == 0) {
                    BufferedImage image = converter.getBufferedImage(frame);
                    File out = new File(outputDir, String.format("frame_%04d.png", index));
                    assertTrue(ImageIO.write(image, "png", out), "PNG writer should be available");
                    saved++;
                }
                index++;
            }
            grabber.stop();
        }

        File[] files = outputDir.listFiles((dir, name) -> name.endsWith(".png"));
        assertNotNull(files);
        assertEquals(saved, files.length);
        assertEquals(TOTAL_FRAMES / step, files.length, 1);

        for (File f : files) {
            BufferedImage img = ImageIO.read(f);
            assertNotNull(img);
            assertEquals(WIDTH, img.getWidth());
            assertEquals(HEIGHT, img.getHeight());
        }
    }

    @Test
    void shouldSeekToFrameNumber() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            // Frame 40 is in the blue half of the video
            grabber.setVideoFrameNumber(40);
            Frame frame = grabber.grabImage();
            assertNotNull(frame, "Frame after seeking should not be null");

            BufferedImage image = converter.getBufferedImage(frame);
            assertTrue(isMostlyBlue(image), "Frame 40 should be blue");

            grabber.stop();
        }
    }

    @Test
    void shouldSeekToTimestamp() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            // 0.2s -> frame 5, which is red; 1.6s -> frame 40, which is blue
            grabber.setTimestamp(200_000);
            assertTrue(isMostlyRed(converter.getBufferedImage(grabber.grabImage())));

            grabber.setTimestamp(1_600_000);
            Frame frame = grabber.grabImage();
            assertNotNull(frame);
            assertTrue(isMostlyBlue(converter.getBufferedImage(frame)));
            assertTrue(frame.timestamp >= 1_500_000, "Timestamp was: " + frame.timestamp);

            grabber.stop();
        }
    }

    // ------------------------------------------------------------------
    // Error handling
    // ------------------------------------------------------------------

    @Test
    void shouldFailForMissingFile() {
        File missing = sharedDir.resolve("does-not-exist.mp4").toFile();

        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(missing);
        assertThrows(FFmpegFrameGrabber.Exception.class, grabber::start);
        try {
            grabber.release();
        } catch (Exception ignored) {
            // nothing to clean up
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Samples the center pixel; tolerant of lossy codec color shifts. */
    private static boolean isMostlyRed(BufferedImage image) {
        Color c = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
        return c.getRed() > 150 && c.getBlue() < 100;
    }

    private static boolean isMostlyBlue(BufferedImage image) {
        Color c = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2));
        return c.getBlue() > 150 && c.getRed() < 100;
    }
}