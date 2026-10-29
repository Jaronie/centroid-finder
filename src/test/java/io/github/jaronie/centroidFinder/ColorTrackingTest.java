package io.github.jaronie.centroidFinder;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tracks a moving color through a video using the same approach as the image pipeline:
 * parse a hex target into a 24-bit int, binarize each frame by Euclidean color distance
 * (white if distance < threshold), group white pixels, and compute each group's size
 * and centroid (integer division). The largest group per frame is the tracked object.
 *
 * Test video (generated at runtime, 320x240, 30 fps, 60 frames):
 *  - gray background
 *  - a 30x30 GREEN square (the target) moving left to right with a sine wave in y
 *  - a stationary 30x30 RED square (a distractor that must NOT be tracked)
 *
 * Requires Java 17+ (uses records).
 */
class ColorTrackingTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;
    private static final int FPS = 30;
    private static final int TOTAL_FRAMES = 60;
    private static final int SQUARE = 30;

    private static final String TARGET_HEX = "00FF00";
    private static final int THRESHOLD = 80;

    private static final int DISTRACTOR_X = 150;
    private static final int DISTRACTOR_Y = 190;

    /** Groups smaller than this are treated as compression noise. */
    private static final int MIN_GROUP_SIZE = 100;

    @TempDir
    static Path sharedDir;

    private static File videoFile;

    // ------------------------------------------------------------------
    // Test video generation + ground truth
    // ------------------------------------------------------------------

    /** Top-left x of the moving square in frame i. */
    private static int squareX(int i) {
        return 10 + i * 4;
    }

    /** Top-left y of the moving square in frame i. */
    private static int squareY(int i) {
        return 100 + (int) Math.round(60 * Math.sin(i * 0.1));
    }

    @BeforeAll
    static void createTestVideo() throws Exception {
        videoFile = sharedDir.resolve("moving-color.mp4").toFile();

        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(videoFile, WIDTH, HEIGHT);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {

            recorder.setFormat("mp4");
            recorder.setVideoCodec(avcodec.AV_CODEC_ID_MPEG4);
            recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
            recorder.setFrameRate(FPS);
            recorder.setVideoBitrate(4_000_000);
            recorder.start();

            for (int i = 0; i < TOTAL_FRAMES; i++) {
                BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = img.createGraphics();
                g.setColor(new Color(128, 128, 128));
                g.fillRect(0, 0, WIDTH, HEIGHT);

                g.setColor(Color.RED); // distractor
                g.fillRect(DISTRACTOR_X, DISTRACTOR_Y, SQUARE, SQUARE);

                g.setColor(Color.GREEN); // target
                g.fillRect(squareX(i), squareY(i), SQUARE, SQUARE);
                g.dispose();

                recorder.record(converter.convert(img));
            }
            recorder.stop();
        }
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    void shouldParseHexColorTo24BitInt() {
        assertEquals(0x00FF00, parseHex("00FF00"));
        assertEquals(0x00FF00, parseHex("#00ff00"));
        assertEquals(0x000000, parseHex("000000"));
        assertEquals(0xFFFFFF, parseHex("FFFFFF"));
        assertThrows(IllegalArgumentException.class, () -> parseHex("FFF"));
    }

    @Test
    void shouldTrackMovingColorAcrossFrames() throws Exception {
        List<List<Group>> perFrame = analyzeVideo(videoFile, TARGET_HEX, THRESHOLD);
        assertEquals(TOTAL_FRAMES, perFrame.size(), 2);

        // Centroid of an even-sized square using integer division lands on x0 + (S-1)/2
        int offset = (SQUARE - 1) / 2;

        for (int i = 0; i < Math.min(perFrame.size(), TOTAL_FRAMES); i++) {
            Group tracked = largest(perFrame.get(i));
            assertNotNull(tracked, "No target found in frame " + i);

            assertEquals(squareX(i) + offset, tracked.x(), 3, "x mismatch in frame " + i);
            assertEquals(squareY(i) + offset, tracked.y(), 3, "y mismatch in frame " + i);

            // Lossy compression erodes the edges a little, so allow a wide size range
            assertTrue(tracked.size() > 500 && tracked.size() <= SQUARE * SQUARE + 100,
                    "Unexpected group size " + tracked.size() + " in frame " + i);
        }
    }

    @Test
    void shouldFollowMotionDirection() throws Exception {
        List<List<Group>> perFrame = analyzeVideo(videoFile, TARGET_HEX, THRESHOLD);

        Group first = largest(perFrame.get(0));
        Group last = largest(perFrame.get(perFrame.size() - 1));
        assertNotNull(first);
        assertNotNull(last);

        // The target moves right across the whole video
        assertTrue(last.x() - first.x() > 150,
                "Expected large rightward motion, was " + (last.x() - first.x()));

        // x never moves backward from one frame to the next (allow 1px jitter)
        int previousX = Integer.MIN_VALUE;
        for (List<Group> groups : perFrame) {
            Group g = largest(groups);
            assertNotNull(g);
            assertTrue(g.x() >= previousX - 1, "Target moved backward");
            previousX = g.x();
        }
    }

    @Test
    void shouldIgnoreDistractorOfDifferentColor() throws Exception {
        List<List<Group>> perFrame = analyzeVideo(videoFile, TARGET_HEX, THRESHOLD);

        int distractorCx = DISTRACTOR_X + (SQUARE - 1) / 2;
        int distractorCy = DISTRACTOR_Y + (SQUARE - 1) / 2;

        for (int i = 0; i < perFrame.size(); i++) {
            for (Group g : perFrame.get(i)) {
                if (g.size() < MIN_GROUP_SIZE) continue; // ignore noise
                boolean nearDistractor = Math.abs(g.x() - distractorCx) < 20
                        && Math.abs(g.y() - distractorCy) < 20;
                assertFalse(nearDistractor, "Red distractor was matched in frame " + i);
            }
        }
    }

    @Test
    void shouldMatchMorePixelsAsThresholdGrows() throws Exception {
        BufferedImage frame = grabFirstFrame();
        int target = parseHex(TARGET_HEX);

        int none = countWhite(binarize(frame, target, 0));    // strict "<": nothing passes
        int tight = countWhite(binarize(frame, target, 1));
        int normal = countWhite(binarize(frame, target, THRESHOLD));
        int loose = countWhite(binarize(frame, target, 300));

        assertEquals(0, none);
        assertTrue(tight <= normal, "tight=" + tight + " normal=" + normal);
        assertTrue(normal < loose, "normal=" + normal + " loose=" + loose);
    }

    @Test
    void shouldWriteBinarizedImageAndGroupsCsvForFrame() throws Exception {
        BufferedImage frame = grabFirstFrame();
        boolean[][] mask = binarize(frame, parseHex(TARGET_HEX), THRESHOLD);
        List<Group> groups = findGroups(mask);

        File png = sharedDir.resolve("binarized.png").toFile();
        File csv = sharedDir.resolve("groups.csv").toFile();

        assertTrue(ImageIO.write(toBinaryImage(mask), "png", png));
        List<String> lines = new ArrayList<>();
        for (Group g : groups) {
            lines.add(g.size() + "," + g.x() + "," + g.y());
        }
        Files.write(csv.toPath(), lines);

        // Image: same size, only pure black or pure white pixels
        BufferedImage written = ImageIO.read(png);
        assertEquals(WIDTH, written.getWidth());
        assertEquals(HEIGHT, written.getHeight());
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int rgb = written.getRGB(x, y) & 0xFFFFFF;
                assertTrue(rgb == 0x000000 || rgb == 0xFFFFFF, "Non-binary pixel at " + x + "," + y);
            }
        }

        // CSV: one "size,x,y" row per group, and the largest group matches the target square
        List<String> readLines = Files.readAllLines(csv.toPath());
        assertEquals(groups.size(), readLines.size());
        readLines.forEach(l -> assertTrue(l.matches("\\d+,\\d+,\\d+"), "Bad CSV row: " + l));

        Group tracked = largest(groups);
        assertNotNull(tracked);
        assertEquals(squareX(0) + (SQUARE - 1) / 2, tracked.x(), 3);
        assertEquals(squareY(0) + (SQUARE - 1) / 2, tracked.y(), 3);
    }

    // ------------------------------------------------------------------
    // Video analysis (JavaCV)
    // ------------------------------------------------------------------

    /** Returns the list of color groups found in every frame of the video. */
    private static List<List<Group>> analyzeVideo(File video, String targetHex, int threshold) throws Exception {
        int target = parseHex(targetHex);
        List<List<Group>> result = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                // Each frame is fully processed before the next grab, so the converter's
                // image reuse is not a problem here.
                BufferedImage image = converter.getBufferedImage(frame);
                result.add(findGroups(binarize(image, target, threshold)));
            }
            grabber.stop();
        }
        return result;
    }

    private static BufferedImage grabFirstFrame() throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            Frame frame = grabber.grabImage();
            assertNotNull(frame);

            // Copy, since the converter may reuse its buffer after close
            BufferedImage src = converter.getBufferedImage(frame);
            BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = copy.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            grabber.stop();
            return copy;
        }
    }

    // ------------------------------------------------------------------
    // Color tracking logic (same idea as your image pipeline)
    // ------------------------------------------------------------------

    /** A connected group of matching pixels: size and integer-division centroid. */
    record Group(int size, int x, int y) {}

    static int parseHex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) {
            throw new IllegalArgumentException("Expected 6 hex digits, got: " + hex);
        }
        return Integer.parseInt(h, 16);
    }

    /** True (white) where Euclidean RGB distance to target is strictly less than threshold. */
    static boolean[][] binarize(BufferedImage img, int target, int threshold) {
        int tr = (target >> 16) & 0xFF;
        int tg = (target >> 8) & 0xFF;
        int tb = target & 0xFF;
        long limit = (long) threshold * threshold; // compare squared distances, no sqrt needed

        int w = img.getWidth();
        int h = img.getHeight();
        boolean[][] mask = new boolean[h][w];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int dr = ((rgb >> 16) & 0xFF) - tr;
                int dg = ((rgb >> 8) & 0xFF) - tg;
                int db = (rgb & 0xFF) - tb;
                mask[y][x] = (long) dr * dr + dg * dg + db * db < limit;
            }
        }
        return mask;
    }

    /** 4-connected flood fill. Origin (0,0) is top-left; y increases downward. */
    static List<Group> findGroups(boolean[][] mask) {
        int h = mask.length;
        int w = h == 0 ? 0 : mask[0].length;
        boolean[][] visited = new boolean[h][w];
        int[] queue = new int[w * h];
        List<Group> groups = new ArrayList<>();

        for (int sy = 0; sy < h; sy++) {
            for (int sx = 0; sx < w; sx++) {
                if (!mask[sy][sx] || visited[sy][sx]) continue;

                int head = 0, tail = 0;
                queue[tail++] = sy * w + sx;
                visited[sy][sx] = true;
                long sumX = 0, sumY = 0;
                int size = 0;

                while (head < tail) {
                    int p = queue[head++];
                    int px = p % w, py = p / w;
                    sumX += px;
                    sumY += py;
                    size++;

                    if (px > 0 && mask[py][px - 1] && !visited[py][px - 1]) {
                        visited[py][px - 1] = true; queue[tail++] = py * w + px - 1;
                    }
                    if (px < w - 1 && mask[py][px + 1] && !visited[py][px + 1]) {
                        visited[py][px + 1] = true; queue[tail++] = py * w + px + 1;
                    }
                    if (py > 0 && mask[py - 1][px] && !visited[py - 1][px]) {
                        visited[py - 1][px] = true; queue[tail++] = (py - 1) * w + px;
                    }
                    if (py < h - 1 && mask[py + 1][px] && !visited[py + 1][px]) {
                        visited[py + 1][px] = true; queue[tail++] = (py + 1) * w + px;
                    }
                }
                groups.add(new Group(size, (int) (sumX / size), (int) (sumY / size)));
            }
        }
        return groups;
    }

    static BufferedImage toBinaryImage(boolean[][] mask) {
        int h = mask.length;
        int w = mask[0].length;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, mask[y][x] ? 0xFFFFFF : 0x000000);
            }
        }
        return out;
    }

    static int countWhite(boolean[][] mask) {
        int count = 0;
        for (boolean[] row : mask) {
            for (boolean v : row) {
                if (v) count++;
            }
        }
        return count;
    }

    /** The tracked object: the biggest group, or null if nothing matched. */
    static Group largest(List<Group> groups) {
        return groups.stream().max(Comparator.comparingInt(Group::size)).orElse(null);
    }
}