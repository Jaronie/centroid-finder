package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

public class LargestComponentSelectorTests {

    @Test
    public void selectsLargestComponentThatMeetsMinimumSizeFromStillImage() {
        BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
        paint(image, 0, 0, 30, 20, 0xFFFFFF);
        paint(image, 4, 4, 12, 10, 0x000000); // 120-pixel main component
        paint(image, 20, 4, 6, 10, 0x000000); // 60-pixel component below minimum
        paint(image, 2, 17, 2, 2, 0x000000);  // 4-pixel speck

        ImageGroupFinder finder = new BinarizingImageGroupFinder(
                new DistanceImageBinarizer(new EuclideanColorDistance(), 0x000000, 1),
                new DfsBinaryGroupFinder());
        List<Group> groups = finder.findConnectedGroups(image);
        LargestComponentSelector selector = new LargestComponentSelector(100);

        assertEquals(
                new Group(120, new Coordinate(9, 8)),
                selector.select(groups).orElseThrow());
    }

    @Test
    public void returnsEmptyWhenEveryComponentIsBelowMinimum() {
        LargestComponentSelector selector = new LargestComponentSelector(100);

        assertTrue(selector.select(List.of(
                new Group(99, new Coordinate(2, 3)),
                new Group(1, new Coordinate(0, 0)))).isEmpty());
    }

    @Test
    public void rejectsNonPositiveMinimumSize() {
        assertThrows(IllegalArgumentException.class, () -> new LargestComponentSelector(0));
    }

    private static void paint(BufferedImage image, int x, int y, int width, int height, int color) {
        for (int row = y; row < y + height; row++) {
            for (int column = x; column < x + width; column++) {
                image.setRGB(column, row, color);
            }
        }
    }
}
