package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the real video pipeline using a generated three-frame clip:
 * FFmpeg extraction, color binarization, connected-group detection, cropping,
 * and CSV writing. A large white stripe outside the crop acts as a distractor;
 * the CSV should report the moving square in full-frame coordinates. The clip
 * is temporary so the test needs no checked-in video.
 */
public class VideoEndToEndTests {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 120;
    private static final int FRAME_COUNT = 3;
    private static final int SQUARE_SIZE = 20;

    @TempDir
    Path tempDir;

    @Test
    public void extractsBinarizesCropsDetectsAndWritesCentroids() throws Exception {
        Path videoPath = tempDir.resolve("moving-target.mp4");
        createVideo(videoPath);

        Path csvPath = tempDir.resolve("centroids.csv");
        VideoPipeline.Summary summary;
        try (FrameExtractor extractor = new FrameExtractor(videoPath.toString());
             CentroidCsvWriter csv = new CentroidCsvWriter(csvPath, false)) {
            ImageGroupFinder finder = new CroppingImageGroupFinder(
                    new BinarizingImageGroupFinder(
                            new DistanceImageBinarizer(new EuclideanColorDistance(), 0xFFFFFF, 120),
                            new DfsBinaryGroupFinder()),
                    10, 10, 140, 100);
            summary = new VideoPipeline(
                    finder,
                    new FrameValidator(extractor.width(), extractor.height()),
                    csv).run(extractor);
        }

        List<String> rows = Files.readAllLines(csvPath);
        assertEquals(new VideoPipeline.Summary(FRAME_COUNT, 0, 0), summary);
        assertEquals(FRAME_COUNT, rows.size());

        // The square advances 20 pixels per frame; centroids use integer division.
        int[] expectedX = {49, 69, 89};
        for (int i = 0; i < rows.size(); i++) {
            String[] columns = rows.get(i).split(",");
            assertEquals(3, columns.length);
            assertEquals(String.format(Locale.ROOT, "%.3f", i / 10.0), columns[0]);
            // MPEG-4 is lossy, so allow a small centroid shift from compression.
            assertTrue(Math.abs(Integer.parseInt(columns[1]) - expectedX[i]) <= 2,
                    "unexpected centroid x in row " + i + ": " + rows.get(i));
            assertTrue(Math.abs(Integer.parseInt(columns[2]) - 54) <= 2,
                    "unexpected centroid y in row " + i + ": " + rows.get(i));
        }
    }

    /** Encodes the moving target and out-of-crop stripe into a temporary MP4. */
    private static void createVideo(Path path) throws Exception {
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(path.toFile(), WIDTH, HEIGHT);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setFormat("mp4");
            recorder.setFrameRate(10);
            recorder.start();

            for (int i = 0; i < FRAME_COUNT; i++) {
                BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D graphics = frame.createGraphics();
                try {
                    graphics.setColor(Color.BLACK);
                    graphics.fillRect(0, 0, WIDTH, HEIGHT);
                    graphics.setColor(Color.WHITE);
                    // This larger stripe is outside the configured crop; the target square should win.
                    graphics.fillRect(0, 0, WIDTH, 9);
                    graphics.fillRect(40 + i * 20, 45, SQUARE_SIZE, SQUARE_SIZE);
                } finally {
                    graphics.dispose();
                }
                recorder.record(converter.getFrame(frame));
            }
        }
    }
}
