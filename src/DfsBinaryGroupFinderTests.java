import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

public class DfsBinaryGroupFinderTests {

    private static final DfsBinaryGroupFinder testImage = new DfsBinaryGroupFinder();

    @Test
    public void testNullImage() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> testImage.findConnectedGroups(null)
        );

        assertEquals("Image cannot be null.", exception.getMessage());
    }

    @Test
    public void testNoForegroundPixels() {
        int[][] image = {
            {0, 0},
            {0, 0}
        };

        assertEquals(java.util.List.of(), testImage.findConnectedGroups(image));
    }

    @Test
    public void testNullRow() {
        int[][] image = {
            {1},
            null
        };

        assertThrows(NullPointerException.class, () -> testImage.findConnectedGroups(image));
    }

}
