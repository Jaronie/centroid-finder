package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the optimized DistanceImageBinarizer against the straightforward
 * definition it replaced.
 *
 * <p>toBinaryArray used to be one loop calling getRGB() and
 * EuclideanColorDistance per pixel. It now reads the raster a row at a time and
 * compares squared distances, which measured about 6x faster on a decoded video
 * frame. That is only worth having if the output is unchanged, so every test
 * here compares against {@link #reference}, a transcription of the original
 * implementation.
 *
 * <p>The cases that matter are the ones where a "faster pixel read" quietly
 * stops being equivalent: sub-images, non-RGB rasters, and a caller-supplied
 * distance finder.
 */
public class DistanceImageBinarizerFastPathTest {

    private static final int TARGET = 0x1E1E1E;
    private static final int THRESHOLD = 60;

    /**
     * The original algorithm, exactly as it was: getRGB per pixel, a real
     * Math.sqrt, and a strict less-than against the threshold.
     */
    private static int[][] reference(BufferedImage image, ColorDistanceFinder finder,
                                     int targetColor, int threshold) {
        int[][] out = new int[image.getHeight()][image.getWidth()];
        for (int r = 0; r < image.getHeight(); r++) {
            for (int c = 0; c < image.getWidth(); c++) {
                int rgb = image.getRGB(c, r) & 0xFFFFFF;
                out[r][c] = finder.distance(rgb, targetColor) < threshold ? 1 : 0;
            }
        }
        return out;
    }

    private static void assertMatchesReference(BufferedImage image, int targetColor, int threshold) {
        int[][] expected = reference(image, new EuclideanColorDistance(), targetColor, threshold);
        int[][] actual = new DistanceImageBinarizer(new EuclideanColorDistance(), targetColor, threshold)
                .toBinaryArray(image);

        assertEquals(expected.length, actual.length, "row count");
        for (int r = 0; r < expected.length; r++) {
            assertArrayEquals(expected[r], actual[r], "row " + r);
        }
    }

    /** Pseudo-random colors, so the comparison is not accidentally all 0s or all 1s. */
    private static BufferedImage noise(int width, int height, int type, long seed) {
        BufferedImage image = new BufferedImage(width, height, type);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    /** Colors clustered near the target, so plenty of pixels land near the cutoff. */
    private static BufferedImage nearTarget(int width, int height, long seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = 0x1E + random.nextInt(81) - 40;
                int g = 0x1E + random.nextInt(81) - 40;
                int b = 0x1E + random.nextInt(81) - 40;
                image.setRGB(x, y, (clamp(r) << 16) | (clamp(g) << 8) | clamp(b));
            }
        }
        return image;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // ---------- every image type the pipeline could hand it ----------

    @ParameterizedTest(name = "image type {0}")
    @ValueSource(ints = {
        BufferedImage.TYPE_3BYTE_BGR,   // what Java2DFrameConverter produces
        BufferedImage.TYPE_INT_RGB,
        BufferedImage.TYPE_INT_ARGB,    // 4 bands: the alpha band must be ignored
        BufferedImage.TYPE_4BYTE_ABGR,
        BufferedImage.TYPE_INT_BGR,
        BufferedImage.TYPE_BYTE_GRAY,   // 1 band: must fall back to getRGB
        BufferedImage.TYPE_USHORT_GRAY,
        BufferedImage.TYPE_BYTE_INDEXED // 1 band of palette indices
    })
    @DisplayName("output is identical to the original implementation for every image type")
    public void matchesReferenceForEveryImageType(int type) {
        assertMatchesReference(noise(40, 24, type, 11), TARGET, THRESHOLD);
    }

    @Test
    @DisplayName("grayscale goes through getRGB, because a raster sample is luminance, not a color")
    public void grayscaleIsNotTreatedAsRgb() {
        // Reading a gray raster's single band as if it were red/green/blue
        // produces a different answer than getRGB's color conversion. This test
        // exists because that mistake still looks plausible and would only show
        // up on grayscale input.
        BufferedImage gray = noise(32, 32, BufferedImage.TYPE_BYTE_GRAY, 5);
        assertMatchesReference(gray, 0x808080, 100);
    }

    // ---------- sub-images: what the crop path passes in ----------

    @Test
    @DisplayName("a sub-image binarizes correctly, despite sharing its parent's array and stride")
    public void subImageMatchesReference() {
        // CroppingImageGroupFinder calls getSubimage, and a child raster shares
        // the parent's backing array with an origin offset while keeping the
        // parent's scanline stride. Walking that array sequentially reads the
        // wrong pixels -- it was verified to produce a WRONG result before this
        // was handled, so this test is the guard on that.
        BufferedImage parent = nearTarget(120, 90, 3);

        assertMatchesReference(parent.getSubimage(37, 21, 60, 40), TARGET, THRESHOLD);
        assertMatchesReference(parent.getSubimage(0, 0, 10, 10), TARGET, THRESHOLD);
        assertMatchesReference(parent.getSubimage(119, 89, 1, 1), TARGET, THRESHOLD);
        assertMatchesReference(parent.getSubimage(60, 0, 60, 90), TARGET, THRESHOLD);
    }

    @Test
    @DisplayName("a sub-image of an INT_RGB parent also matches")
    public void subImageOfIntRgbMatchesReference() {
        BufferedImage parent = noise(80, 60, BufferedImage.TYPE_INT_RGB, 9);
        assertMatchesReference(parent.getSubimage(13, 7, 41, 29), TARGET, THRESHOLD);
    }

    @Test
    @DisplayName("the crop path and a pre-cropped image agree")
    public void croppingFinderAgreesWithDirectBinarization() {
        BufferedImage parent = nearTarget(100, 80, 17);
        ImageBinarizer binarizer =
                new DistanceImageBinarizer(new EuclideanColorDistance(), TARGET, THRESHOLD);

        int[][] viaSubImage = binarizer.toBinaryArray(parent.getSubimage(20, 15, 50, 40));
        int[][] viaCopy = binarizer.toBinaryArray(copyOf(parent.getSubimage(20, 15, 50, 40)));

        for (int r = 0; r < viaCopy.length; r++) {
            assertArrayEquals(viaCopy[r], viaSubImage[r], "row " + r);
        }
    }

    private static BufferedImage copyOf(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        out.getGraphics().drawImage(src, 0, 0, null);
        return out;
    }

    // ---------- the threshold boundary, where squaring could go wrong ----------

    @Test
    @DisplayName("a distance exactly equal to the threshold is still 0")
    public void distanceEqualToThresholdIsZero() {
        // Strictly less than, so an exact match stays black. Squaring both sides
        // preserves that, but only because both are non-negative -- worth
        // pinning, since an off-by-one here would silently change every output.
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_3BYTE_BGR);
        image.setRGB(0, 0, 0x000005); // distance 5 from black

        assertEquals(0, new DistanceImageBinarizer(new EuclideanColorDistance(), 0x000000, 5)
                .toBinaryArray(image)[0][0]);
        assertEquals(1, new DistanceImageBinarizer(new EuclideanColorDistance(), 0x000000, 6)
                .toBinaryArray(image)[0][0]);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = { 0, -1, -60, Integer.MIN_VALUE })
    @DisplayName("a threshold of zero or less marks nothing, rather than everything")
    public void nonPositiveThresholdMarksNothing(int threshold) {
        // The trap this guards: a Euclidean distance is never negative, so
        // "distance < threshold" is always false here. Comparing SQUARES would
        // make a negative threshold positive and mark every pixel white.
        BufferedImage image = nearTarget(16, 16, 2);
        int[][] result = new DistanceImageBinarizer(new EuclideanColorDistance(), TARGET, threshold)
                .toBinaryArray(image);

        for (int[] row : result) {
            for (int value : row) {
                assertEquals(0, value);
            }
        }
    }

    @Test
    @DisplayName("a huge threshold marks everything, with no overflow")
    public void hugeThresholdMarksEverything() {
        // threshold * threshold overflows an int well before this; the
        // comparison is done in long for that reason.
        BufferedImage image = noise(16, 16, BufferedImage.TYPE_3BYTE_BGR, 4);
        int[][] result = new DistanceImageBinarizer(new EuclideanColorDistance(), TARGET,
                Integer.MAX_VALUE).toBinaryArray(image);

        for (int[] row : result) {
            for (int value : row) {
                assertEquals(1, value);
            }
        }
    }

    // ---------- an injected finder must still be honored ----------

    /** Counts calls, and uses a metric the squared shortcut would get wrong. */
    private static class AbsDiffCountingFinder implements ColorDistanceFinder {
        int calls = 0;

        @Override
        public double distance(int colorA, int colorB) {
            calls++;
            return Math.abs(colorA - colorB);
        }
    }

    @Test
    @DisplayName("a custom ColorDistanceFinder is consulted for every pixel")
    public void customFinderIsStillUsed() {
        // The fast path bypasses the finder entirely, so it must only engage for
        // the real Euclidean metric. A caller who injects their own metric is
        // entitled to have it used -- and existing tests already depend on that.
        AbsDiffCountingFinder finder = new AbsDiffCountingFinder();
        BufferedImage image = noise(12, 10, BufferedImage.TYPE_3BYTE_BGR, 8);

        int[][] actual = new DistanceImageBinarizer(finder, TARGET, THRESHOLD).toBinaryArray(image);

        assertEquals(12 * 10, finder.calls, "every pixel must reach the injected finder");

        int[][] expected = reference(image, new AbsDiffCountingFinder(), TARGET, THRESHOLD);
        for (int r = 0; r < expected.length; r++) {
            assertArrayEquals(expected[r], actual[r], "row " + r);
        }
    }

    @Test
    @DisplayName("a custom finder is used even with a non-positive threshold")
    public void customFinderRunsForNonPositiveThreshold() {
        // The early return for threshold <= 0 is sound only for Euclidean
        // distance. A custom metric could legitimately return negatives, so it
        // must not be short-circuited.
        ColorDistanceFinder alwaysNegative = (a, b) -> -1.0;
        BufferedImage image = noise(8, 6, BufferedImage.TYPE_3BYTE_BGR, 6);

        int[][] result = new DistanceImageBinarizer(alwaysNegative, TARGET, 0).toBinaryArray(image);

        for (int[] row : result) {
            for (int value : row) {
                assertEquals(1, value, "-1 < 0, so these pixels are white");
            }
        }
    }

    @Test
    @DisplayName("a subclass of EuclideanColorDistance is not assumed to be Euclidean")
    public void euclideanSubclassTakesTheSlowPath() {
        // The check is an exact class match for this reason: a subclass may
        // override distance() with anything.
        class Doubled extends EuclideanColorDistance {
            @Override
            public double distance(int a, int b) {
                return super.distance(a, b) * 100;
            }
        }
        BufferedImage image = nearTarget(10, 8, 13);
        Doubled finder = new Doubled();

        int[][] actual = new DistanceImageBinarizer(finder, TARGET, THRESHOLD).toBinaryArray(image);
        int[][] expected = reference(image, finder, TARGET, THRESHOLD);

        for (int r = 0; r < expected.length; r++) {
            assertArrayEquals(expected[r], actual[r], "row " + r);
        }
    }

    // ---------- shape ----------

    @Test
    @DisplayName("the result is indexed [row][column], matching the image's height and width")
    public void resultShapeIsRowsByColumns() {
        BufferedImage wide = noise(37, 11, BufferedImage.TYPE_3BYTE_BGR, 1);

        int[][] result = new DistanceImageBinarizer(new EuclideanColorDistance(), TARGET, THRESHOLD)
                .toBinaryArray(wide);

        assertEquals(11, result.length, "rows == height");
        assertEquals(37, result[0].length, "columns == width");
        assertTrue(result.length > 0);
    }
}
