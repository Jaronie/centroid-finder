import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class DfsBinaryGroupFinderTests {

    private static final DfsBinaryGroupFinder testImage = new DfsBinaryGroupFinder();

    // ---------- invalid input ----------

    @Test
    public void testNullImage() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testImage.findConnectedGroups(null)
        );

        assertEquals("Image cannot be null.", exception.getMessage());
    }

    @Test
    public void testNullRow() {
        int[][] image = {
            {1},
            null
        };

        assertThrows(NullPointerException.class, () -> testImage.findConnectedGroups(image));
    }

    @Test
    public void testNullRowInMiddleOfImage() {
        int[][] image = {
            {1, 0, 1},
            null,
            {0, 1, 1}
        };

        assertThrows(NullPointerException.class, () -> testImage.findConnectedGroups(image));
    }

    @Test
    public void testEmptyOuterArray() {
        int[][] image = {};

        assertThrows(IllegalArgumentException.class, () -> testImage.findConnectedGroups(image));
    }

    @Test
    public void testEmptyRows() {
        int[][] image = {
            {},
            {}
        };

        assertThrows(IllegalArgumentException.class, () -> testImage.findConnectedGroups(image));
    }

    @Test
    public void testJaggedImage() {
        int[][] image = {
            {1, 1},
            {1}
        };

        assertThrows(IllegalArgumentException.class, () -> testImage.findConnectedGroups(image));
    }

    @Test
    public void testValueGreaterThanOne() {
        int[][] image = {
            {1, 0},
            {0, 2}
        };

        assertThrows(IllegalArgumentException.class, () -> testImage.findConnectedGroups(image));
    }

    @Test
    public void testNegativeValue() {
        int[][] image = {
            {1, 0},
            {-1, 0}
        };

        assertThrows(IllegalArgumentException.class, () -> testImage.findConnectedGroups(image));
    }

    // ---------- no groups ----------

    @Test
    public void testNoForegroundPixels() {
        int[][] image = {
            {0, 0},
            {0, 0}
        };

        assertEquals(List.of(), testImage.findConnectedGroups(image));
    }

    @Test
    public void testSingleZeroPixel() {
        int[][] image = {
            {0}
        };

        assertEquals(List.of(), testImage.findConnectedGroups(image));
    }

    @Test
    public void testNoForegroundPixelsNonSquare() {
        int[][] image = {
            {0, 0, 0, 0},
            {0, 0, 0, 0}
        };

        assertEquals(List.of(), testImage.findConnectedGroups(image));
    }

    // ---------- single group ----------

    @Test
    public void testSingleOnePixel() {
        int[][] image = {
            {1}
        };

        assertEquals(List.of(new Group(1, new Coordinate(0, 0))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testHorizontalLine() {
        int[][] image = {
            {1, 1, 1}
        };

        // x values 0, 1, 2 -> 3 / 3 = 1; all y values 0
        assertEquals(List.of(new Group(3, new Coordinate(1, 0))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testVerticalLine() {
        int[][] image = {
            {1},
            {1},
            {1}
        };

        // all x values 0; y values 0, 1, 2 -> 3 / 3 = 1
        assertEquals(List.of(new Group(3, new Coordinate(0, 1))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testCentroidUsesIntegerDivision() {
        int[][] image = {
            {1, 1, 1, 1}
        };

        // x values sum to 6; 6 / 4 truncates to 1 (not 1.5 rounded to 2)
        assertEquals(List.of(new Group(4, new Coordinate(1, 0))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testAllOnes() {
        int[][] image = {
            {1, 1, 1},
            {1, 1, 1},
            {1, 1, 1}
        };

        assertEquals(List.of(new Group(9, new Coordinate(1, 1))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testSinglePixelInWideImageKeepsXAndYDistinct() {
        int[][] image = {
            {0, 0, 0, 1},
            {0, 0, 0, 0}
        };

        // row 0, column 3 -> (x:3, y:0)
        assertEquals(List.of(new Group(1, new Coordinate(3, 0))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testSinglePixelInTallImageKeepsXAndYDistinct() {
        int[][] image = {
            {0, 0},
            {0, 0},
            {0, 0},
            {1, 0}
        };

        // row 3, column 0 -> (x:0, y:3)
        assertEquals(List.of(new Group(1, new Coordinate(0, 3))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testLShapedGroup() {
        int[][] image = {
            {1, 0, 0},
            {1, 0, 0},
            {1, 1, 1}
        };

        // x values 0, 0, 0, 1, 2 -> 3 / 5 = 0; y values 0, 1, 2, 2, 2 -> 7 / 5 = 1
        assertEquals(List.of(new Group(5, new Coordinate(0, 1))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testSnakeShapedGroupIsOneGroup() {
        int[][] image = {
            {1, 1, 1},
            {0, 0, 1},
            {1, 1, 1}
        };

        // x sum 3 + 2 + 3 = 8 -> 8 / 7 = 1; y sum 0 + 1 + 6 = 7 -> 7 / 7 = 1
        assertEquals(List.of(new Group(7, new Coordinate(1, 1))), testImage.findConnectedGroups(image));
    }

    @Test
    public void testRingAroundHoleIsOneGroup() {
        int[][] image = {
            {1, 1, 1},
            {1, 0, 1},
            {1, 1, 1}
        };

        // x sum 3 + 2 + 3 = 8 -> 8 / 8 = 1; y sum 0 + 2 + 6 = 8 -> 8 / 8 = 1
        assertEquals(List.of(new Group(8, new Coordinate(1, 1))), testImage.findConnectedGroups(image));
    }

    // ---------- multiple groups ----------

    @Test
    public void testDiagonalPixelsAreNotConnected() {
        int[][] image = {
            {1, 0},
            {0, 1}
        };

        // equal sizes, so ordered by descending x then descending y
        assertEquals(
            List.of(
                new Group(1, new Coordinate(1, 1)),
                new Group(1, new Coordinate(0, 0))
            ),
            testImage.findConnectedGroups(image)
        );
    }

    @Test
    public void testTwoGroupsSameSizeSortedByDescendingX() {
        int[][] image = {
            {1, 0, 1}
        };

        assertEquals(
            List.of(
                new Group(1, new Coordinate(2, 0)),
                new Group(1, new Coordinate(0, 0))
            ),
            testImage.findConnectedGroups(image)
        );
    }

    @Test
    public void testTwoGroupsSameSizeAndXSortedByDescendingY() {
        int[][] image = {
            {1},
            {0},
            {1}
        };

        assertEquals(
            List.of(
                new Group(1, new Coordinate(0, 2)),
                new Group(1, new Coordinate(0, 0))
            ),
            testImage.findConnectedGroups(image)
        );
    }

    @Test
    public void testThreeGroupsSortedByDescendingSize() {
        int[][] image = {
            {1, 1, 0, 1},
            {1, 1, 0, 0},
            {0, 0, 0, 1}
        };

        // block of 4 at (0,0)-(1,1): x sum 2 -> 0, y sum 2 -> 0
        assertEquals(
            List.of(
                new Group(4, new Coordinate(0, 0)),
                new Group(1, new Coordinate(3, 2)),
                new Group(1, new Coordinate(3, 0))
            ),
            testImage.findConnectedGroups(image)
        );
    }

    @Test
    public void testIsolatedCenterIsItsOwnGroup() {
        int[][] image = {
            {1, 1, 1, 1, 1},
            {1, 0, 0, 0, 1},
            {1, 0, 1, 0, 1},
            {1, 0, 0, 0, 1},
            {1, 1, 1, 1, 1}
        };

        // ring: 16 pixels, x sum 32 -> 2, y sum 32 -> 2; center: single pixel at (2,2)
        assertEquals(
            List.of(
                new Group(16, new Coordinate(2, 2)),
                new Group(1, new Coordinate(2, 2))
            ),
            testImage.findConnectedGroups(image)
        );
    }

    @Test
    public void testGroupsAreReturnedInDescendingOrder() {
        int[][] image = {
            {1, 0, 1, 1},
            {0, 0, 1, 1},
            {1, 1, 0, 0},
            {1, 1, 0, 1}
        };

        List<Group> groups = testImage.findConnectedGroups(image);

        for (int i = 1; i < groups.size(); i++) {
            assertTrue(
                groups.get(i - 1).compareTo(groups.get(i)) >= 0,
                "groups should be sorted in descending order, but index " + (i - 1)
                    + " was smaller than index " + i
            );
        }
    }

    // ---------- larger / structural ----------

    @Test
    public void testLargeSingleGroupDoesNotOverflow() {
        int size = 50;
        int[][] image = new int[size][size];
        for (int[] row : image) {
            Arrays.fill(row, 1);
        }

        // all 2500 pixels connected; x and y sums are each 2500 * 49 / 2 = 61250 -> 24
        assertEquals(
            List.of(new Group(size * size, new Coordinate(24, 24))),
            testImage.findConnectedGroups(image)
        );
    }

    @Test
    public void testCheckerboardProducesOneGroupPerPixel() {
        int[][] image = {
            {1, 0, 1},
            {0, 1, 0},
            {1, 0, 1}
        };

        assertEquals(5, testImage.findConnectedGroups(image).size());
    }

    @Test
    public void testInputImageIsNotModified() {
        int[][] image = {
            {1, 1, 0},
            {0, 1, 0},
            {0, 0, 1}
        };
        int[][] copy = {
            {1, 1, 0},
            {0, 1, 0},
            {0, 0, 1}
        };

        testImage.findConnectedGroups(image);

        assertTrue(Arrays.deepEquals(copy, image), "findConnectedGroups should not modify its input");
    }
}
