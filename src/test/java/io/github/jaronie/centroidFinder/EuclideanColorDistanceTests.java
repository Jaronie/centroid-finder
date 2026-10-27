package test.java.io.github.jaronie.centroidFinder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.github.jaronie.centroidFinder.ColorDistanceFinder;
import java.io.github.jaronie.centroidFinder.EuclideanColorDistance;

import org.junit.jupiter.api.Test;

/* 

To run tests in CLI, paste below code:

javac -cp lib/junit-platform-console-standalone-6.0.3.jar -d out src/*.java
java -jar lib/junit-platform-console-standalone-6.0.3.jar execute \
  --class-path out --select-class EuclideanColorDistanceTests
  
*/

public class EuclideanColorDistanceTests {

    /** Tolerance for floating point comparisons. */
    private static final double DELTA = 1e-9;

    private final EuclideanColorDistance finder = new EuclideanColorDistance();

    // Tests start here
    @Test
    public void testIdenticalColorsHaveZeroDistance() {
        assertEquals(0.0, finder.distance(0x000000, 0x000000), DELTA);
        assertEquals(0.0, finder.distance(0xFFFFFF, 0xFFFFFF), DELTA);
        assertEquals(0.0, finder.distance(0x1A2B3C, 0x1A2B3C), DELTA);
    }

    @Test
    public void testBlackToWhiteIsMaximumDistance() {
        // sqrt(255^2 * 3) = 255 * sqrt(3)
        double expected = 255 * Math.sqrt(3);
        assertEquals(expected, finder.distance(0x000000, 0xFFFFFF), DELTA);
    }

    @Test
    public void testDistanceAlongRedChannelOnly() {
        assertEquals(255.0, finder.distance(0x000000, 0xFF0000), DELTA);
    }

    @Test
    public void testDistanceAlongGreenChannelOnly() {
        assertEquals(255.0, finder.distance(0x000000, 0x00FF00), DELTA);
    }

    @Test
    public void testDistanceAlongBlueChannelOnly() {
        assertEquals(255.0, finder.distance(0x000000, 0x0000FF), DELTA);
    }

    @Test
    public void testChannelsAreNotMixedUp() {
        // Pure red and pure blue differ in two channels by 255 each.
        assertEquals(255 * Math.sqrt(2), finder.distance(0xFF0000, 0x0000FF), DELTA);
        assertEquals(255 * Math.sqrt(2), finder.distance(0xFF0000, 0x00FF00), DELTA);
        assertEquals(255 * Math.sqrt(2), finder.distance(0x00FF00, 0x0000FF), DELTA);
    }

    @Test
    public void testKnownPythagoreanTriple() {
        // (3, 4, 0) -> 5
        assertEquals(5.0, finder.distance(0x000000, 0x030400), DELTA);
    }

    @Test
    public void testDistanceIsSymmetric() {
        int colorA = 0x123456;
        int colorB = 0xFEDCBA;
        assertEquals(finder.distance(colorA, colorB), finder.distance(colorB, colorA), DELTA);
    }

    @Test
    public void testArbitraryColorPair() {
        // 0x102030 -> (16, 32, 48), 0x405060 -> (64, 80, 96)
        // differences: 48, 48, 48 -> sqrt(3 * 48^2) = 48 * sqrt(3)
        assertEquals(48 * Math.sqrt(3), finder.distance(0x102030, 0x405060), DELTA);
    }

    @Test
    public void testSmallDifferenceInSingleChannel() {
        assertEquals(1.0, finder.distance(0x808080, 0x808081), DELTA);
    }

    @Test
    public void testAlphaBitsAreIgnored() {
        // Only the low 24 bits (0xRRGGBB) are significant, so a set alpha byte
        // must not change the result.
        double expected = finder.distance(0x102030, 0x405060);
        assertEquals(expected, finder.distance(0xFF102030, 0xFF405060), DELTA);
    }

    @Test
    public void testDistanceIsNeverNegative() {
        assertTrue(finder.distance(0xFFFFFF, 0x000000) >= 0.0);
        assertTrue(finder.distance(0x00FF00, 0xFF00FF) >= 0.0);
    }

    @Test
    public void testTriangleInequality() {
        int colorA = 0x000000;
        int colorB = 0x7F7F7F;
        int colorC = 0xFFFFFF;
        double direct = finder.distance(colorA, colorC);
        double viaB = finder.distance(colorA, colorB) + finder.distance(colorB, colorC);
        assertTrue(direct <= viaB + DELTA);
    }

    @Test
    public void testUsableThroughInterfaceReference() {
        ColorDistanceFinder asInterface = new EuclideanColorDistance();
        assertEquals(255.0, asInterface.distance(0x000000, 0xFF0000), DELTA);
    }
}
