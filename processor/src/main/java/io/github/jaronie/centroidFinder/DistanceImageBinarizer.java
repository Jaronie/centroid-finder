package io.github.jaronie.centroidFinder;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;

/**
 * An implementation of the ImageBinarizer interface that uses color distance
 * to determine whether each pixel should be black or white in the binary image.
 * 
 * The binarization is based on the Euclidean distance between a pixel's color and a reference target color.
 * If the distance is less than the threshold, the pixel is considered white (1);
 * otherwise, it is considered black (0).
 * 
 * The color distance is computed using a provided ColorDistanceFinder, which defines how to compare two colors numerically.
 * The targetColor is represented as a 24-bit RGB integer in the form 0xRRGGBB.
 */
public class DistanceImageBinarizer implements ImageBinarizer {
    private final ColorDistanceFinder distanceFinder;
    private final int threshold;
    private final int targetColor;

    /**
     * Constructs a DistanceImageBinarizer using the given ColorDistanceFinder,
     * target color, and threshold.
     * 
     * The distanceFinder is used to compute the Euclidean distance between a pixel's color and the target color.
     * The targetColor is represented as a 24-bit hex RGB integer (0xRRGGBB).
     * The threshold determines the cutoff for binarization: pixels with distances less than
     * the threshold are marked white, and others are marked black.
     *
     * @param distanceFinder an object that computes the distance between two colors
     * @param targetColor the reference color as a 24-bit hex RGB integer (0xRRGGBB)
     * @param threshold the distance threshold used to decide whether a pixel is white or black
     */
    public DistanceImageBinarizer(ColorDistanceFinder distanceFinder, int targetColor, int threshold) {
        this.distanceFinder = distanceFinder;
        this.targetColor = targetColor;
        this.threshold = threshold;
    }

    /**
     * Converts the given BufferedImage into a binary 2D array using color distance and a threshold.
     * Each entry in the returned array is either 0 or 1, representing a black or white pixel.
     * A pixel is white (1) if its Euclidean distance to the target color is less than the threshold.
     *
     * @param image the input RGB BufferedImage
     * @return a 2D binary array where 1 represents white and 0 represents black
     */
    @Override
    public int[][] toBinaryArray(BufferedImage image) {
        final int height = image.getHeight();
        final int width = image.getWidth();
        int[][] binarizedArray = new int[height][width];

        // Whether the squared-distance shortcut below is valid. It is sound only
        // for the real Euclidean metric -- an injected finder may compute
        // anything at all, and callers are entitled to have it consulted for
        // every pixel. An exact class match, not instanceof: a subclass could
        // override distance().
        final boolean euclidean = distanceFinder.getClass() == EuclideanColorDistance.class;

        if (euclidean && threshold <= 0) {
            // A Euclidean distance is never negative, so no pixel can be
            // strictly below a threshold of zero or less. This also guards the
            // comparison below: squaring a negative threshold would flip the
            // test and mark every pixel white.
            return binarizedArray;
        }

        if (hasDirectRgbSamples(image)) {
            fillFromRaster(image, binarizedArray, width, height, euclidean);
        } else {
            fillFromGetRgb(image, binarizedArray, width, height, euclidean);
        }

        return binarizedArray;
    }

    /**
     * Whether the raster's samples can be read as R,G,B directly.
     *
     * <p>True for the usual RGB layouts, including TYPE_3BYTE_BGR, which is what
     * JavaCV's Java2DFrameConverter produces for every decoded video frame.
     *
     * <p>False for grayscale and indexed images, where a raster sample is a
     * luminance value or a palette index rather than a color component.
     * Converting those correctly is exactly what getRGB() does, so they take
     * the slow path instead of getting a subtly wrong answer.
     */
    private static boolean hasDirectRgbSamples(BufferedImage image) {
        return image.getColorModel().getColorSpace().getType() == ColorSpace.TYPE_RGB
                && image.getRaster().getNumBands() >= 3;
    }

    /**
     * Reads pixels a row at a time straight out of the raster.
     *
     * <p>This is the whole optimization. getRGB() per pixel goes through the
     * image's color model on every call, which on a 816x720 frame measured
     * about 7.7 ms; a bulk row read plus a squared-distance comparison measured
     * about 1.3 ms, for the same output.
     *
     * <p>Raster.getPixels is used rather than reaching into the DataBuffer
     * directly because it accounts for the raster's origin and scanline stride.
     * A sub-image -- which is what CroppingImageGroupFinder passes in -- shares
     * its parent's array with an offset and keeps the parent's stride, so a
     * sequential walk of the backing array reads the wrong pixels entirely.
     */
    private void fillFromRaster(BufferedImage image, int[][] out,
                                int width, int height, boolean euclidean) {
        final Raster raster = image.getRaster();
        final int bands = raster.getNumBands();
        final int minX = raster.getMinX();
        final int minY = raster.getMinY();

        // Band order is R,G,B for every RGB layout here; any alpha band sorts
        // after them and is simply never read.
        final int targetRed = (targetColor >> 16) & 0xFF;
        final int targetGreen = (targetColor >> 8) & 0xFF;
        final int targetBlue = targetColor & 0xFF;
        final long thresholdSquared = (long) threshold * threshold;

        // One buffer for the whole image rather than one per row: getPixels
        // allocates when handed null, and that allocation was a measurable part
        // of the cost at 11,000 frames per video.
        final int[] row = new int[width * bands];

        for (int r = 0; r < height; r++) {
            raster.getPixels(minX, minY + r, width, 1, row);
            final int[] outRow = out[r];

            if (euclidean) {
                for (int c = 0, i = 0; c < width; c++, i += bands) {
                    final int dr = row[i] - targetRed;
                    final int dg = row[i + 1] - targetGreen;
                    final int db = row[i + 2] - targetBlue;
                    // Comparing squares avoids a Math.sqrt per pixel. Both sides
                    // are non-negative, so the comparison is equivalent.
                    outRow[c] = (long) (dr * dr + dg * dg + db * db) < thresholdSquared ? 1 : 0;
                }
            } else {
                for (int c = 0, i = 0; c < width; c++, i += bands) {
                    final int rgb = (row[i] << 16) | (row[i + 1] << 8) | row[i + 2];
                    outRow[c] = distanceFinder.distance(rgb, targetColor) < threshold ? 1 : 0;
                }
            }
        }
    }

    /**
     * The original per-pixel path, kept for images whose raster samples are not
     * color components: grayscale and indexed images, where getRGB's color
     * conversion is the correct answer and cannot be short-cut.
     */
    private void fillFromGetRgb(BufferedImage image, int[][] out,
                                int width, int height, boolean euclidean) {
        final int targetRed = (targetColor >> 16) & 0xFF;
        final int targetGreen = (targetColor >> 8) & 0xFF;
        final int targetBlue = targetColor & 0xFF;
        final long thresholdSquared = (long) threshold * threshold;

        for (int r = 0; r < height; r++) {
            final int[] outRow = out[r];
            for (int c = 0; c < width; c++) {
                final int rgb = image.getRGB(c, r) & 0xFFFFFF;

                if (euclidean) {
                    final int dr = ((rgb >> 16) & 0xFF) - targetRed;
                    final int dg = ((rgb >> 8) & 0xFF) - targetGreen;
                    final int db = (rgb & 0xFF) - targetBlue;
                    outRow[c] = (long) (dr * dr + dg * dg + db * db) < thresholdSquared ? 1 : 0;
                } else {
                    outRow[c] = distanceFinder.distance(rgb, targetColor) < threshold ? 1 : 0;
                }
            }
        }
    }

    /**
     * Converts a binary 2D array into a BufferedImage.
     * Each value should be 0 (black) or 1 (white).
     * Black pixels are encoded as 0x000000 and white pixels as 0xFFFFFF.
     *
     * @param image a 2D array of 0s and 1s representing the binary image
     * @return a BufferedImage where black and white pixels are represented with standard RGB hex values
     */
    @Override
    public BufferedImage toBufferedImage(int[][] image) {

        BufferedImage bufferedImage = new BufferedImage(image[0].length, image.length, BufferedImage.TYPE_INT_RGB);

        for (int r = 0; r < image.length; r++) {
            for (int c = 0; c < image[0].length; c++) {
                if (image[r][c] == 1) {
                    bufferedImage.setRGB(c, r, 0xFFFFFF);
                } else {
                    bufferedImage.setRGB(c, r, 0x00000);
                }
            }
        }

        return bufferedImage;
    }
}
