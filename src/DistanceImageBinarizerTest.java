import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class DistanceImageBinarizerTest {

    /** Stub: distance is simply |a - b|, so results are easy to predict. */
    private static class AbsDiffStub implements ColorDistanceFinder {
        @Override
        public double distance(int colorA, int colorB) {
            return Math.abs(colorA - colorB);
        }
    }

    /** Stub: records the arguments it was called with. */
    private static class RecordingStub implements ColorDistanceFinder {
        int lastA, lastB, calls = 0;

        @Override
        public double distance(int colorA, int colorB) {
            lastA = colorA;
            lastB = colorB;
            calls++;
            return 0;
        }
    }

    private static java.awt.image.BufferedImage makeImage(int width, int height, int fill) {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, fill);
            }
        }
        return img;
    }

    // ---------- toBinaryArray ----------

    @Test
    public void toBinaryArray_nonSquareImage_hasCorrectDimensions() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 0, 10);
        int[][] result = b.toBinaryArray(makeImage(3, 2, 0)); // width 3, height 2

        assertEquals(2, result.length);       // rows = height
        assertEquals(3, result[0].length);    // cols = width
    }

    @Test
    public void toBinaryArray_pixelBelowThreshold_isOne() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 100, 10);
        int[][] result = b.toBinaryArray(makeImage(1, 1, 95)); // distance 5

        assertEquals(1, result[0][0]);
    }

    @Test
    public void toBinaryArray_pixelAboveThreshold_isZero() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 100, 10);
        int[][] result = b.toBinaryArray(makeImage(1, 1, 120)); // distance 20

        assertEquals(0, result[0][0]);
    }

    @Test
    public void toBinaryArray_distanceEqualToThreshold_isZero() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 100, 10);
        int[][] result = b.toBinaryArray(makeImage(1, 1, 110)); // distance exactly 10

        assertEquals(0, result[0][0]); // strictly less than
    }

    @Test
    public void toBinaryArray_pixelPositionMapsToCorrectRowAndColumn() {
        java.awt.image.BufferedImage img = makeImage(4, 2, 200); // all far from target
        img.setRGB(3, 1, 100);                                   // x=3, y=1 matches target

        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 100, 10);
        int[][] result = b.toBinaryArray(img);

        assertEquals(1, result[1][3]); // row = y, col = x
        int ones = 0;
        for (int[] row : result) for (int v : row) ones += v;
        assertEquals(1, ones);
    }

    @Test
    public void toBinaryArray_passesPlainRgbWithoutAlphaToDistanceFinder() {
        RecordingStub stub = new RecordingStub();
        DistanceImageBinarizer b = new DistanceImageBinarizer(stub, 0xABCDEF, 10);

        b.toBinaryArray(makeImage(1, 1, 0x123456));

        assertEquals(1, stub.calls);
        assertEquals(0x123456, stub.lastA);   // pixel color, alpha stripped
        assertEquals(0xABCDEF, stub.lastB);   // target color
    }

    @Test
    public void toBinaryArray_callsDistanceFinderOncePerPixel() {
        RecordingStub stub = new RecordingStub();
        DistanceImageBinarizer b = new DistanceImageBinarizer(stub, 0, 10);

        b.toBinaryArray(makeImage(5, 3, 0));

        assertEquals(15, stub.calls);
    }

    // ---------- toBufferedImage ----------

    @Test
    public void toBufferedImage_hasCorrectDimensions() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 0, 10);
        java.awt.image.BufferedImage img = b.toBufferedImage(new int[][]{{0, 1, 0}, {1, 0, 1}});

        assertEquals(3, img.getWidth());
        assertEquals(2, img.getHeight());
    }

    @Test
    public void toBufferedImage_whiteAndBlackPixelsHaveCorrectColors() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 0, 10);
        java.awt.image.BufferedImage img = b.toBufferedImage(new int[][]{{1, 0}});

        assertEquals(0xFFFFFF, img.getRGB(0, 0) & 0xFFFFFF); // white
        assertEquals(0x000000, img.getRGB(1, 0) & 0xFFFFFF); // black
    }

    @Test
    public void toBufferedImage_pixelPositionMapsCorrectly() {
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 0, 10);
        int[][] binary = {
                {0, 0, 0},
                {0, 0, 1}   // row 1, col 2 -> x=2, y=1
        };
        java.awt.image.BufferedImage img = b.toBufferedImage(binary);

        assertEquals(0xFFFFFF, img.getRGB(2, 1) & 0xFFFFFF);
        assertEquals(0x000000, img.getRGB(0, 0) & 0xFFFFFF);
    }

    // ---------- round trip ----------

    @Test
    public void roundTrip_binaryArrayToImageAndBack_preservesValues() {
        // Target 0xFFFFFF with threshold 10: white pixels -> 1, black pixels -> 0
        DistanceImageBinarizer b = new DistanceImageBinarizer(new AbsDiffStub(), 0xFFFFFF, 10);
        int[][] original = {{1, 0, 1}, {0, 1, 0}};

        int[][] result = b.toBinaryArray(b.toBufferedImage(original));

        assertArrayEquals(original[0], result[0]);
        assertArrayEquals(original[1], result[1]);
    }
}