package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for ScalingImageGroupFinder, which maps centroids found in a
 * downscaled frame back to the source video's coordinates.
 *
 * <p>Getting this wrong is quiet: the CSV still has plausible numbers, they are
 * just all wrong by a constant factor. So these tests pin the mapping
 * precisely, including the image being passed through untouched -- the
 * resizing happens in the decoder, not here.
 */
public class ScalingImageGroupFinderTests {

    /** Records the image it was given and returns a fixed answer. */
    private static class StubFinder implements ImageGroupFinder {
        private final List<Group> answer;
        BufferedImage received;
        int calls = 0;

        StubFinder(List<Group> answer) {
            this.answer = answer;
        }

        @Override
        public List<Group> findConnectedGroups(BufferedImage image) {
            received = image;
            calls++;
            return answer;
        }
    }

    private static Group group(int size, int x, int y) {
        return new Group(size, new Coordinate(x, y));
    }

    private static BufferedImage image(int w, int h) {
        return new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
    }

    // ---------- the mapping ----------

    @Test
    @DisplayName("a centroid in a half-size frame doubles back to source coordinates")
    public void halfScaleDoublesCoordinates() {
        StubFinder stub = new StubFinder(List.of(group(10, 50, 40)));
        ScalingImageGroupFinder finder = new ScalingImageGroupFinder(stub, 0.5, 0.5);

        List<Group> result = finder.findConnectedGroups(image(100, 80));

        assertAll(
            () -> assertEquals(1, result.size()),
            () -> assertEquals(100, result.get(0).centroid().x()),
            () -> assertEquals(80, result.get(0).centroid().y())
        );
    }

    @Test
    @DisplayName("x and y are scaled independently")
    public void axesScaleIndependently() {
        // Rounding to whole pixels can leave the two axes with slightly
        // different scales, so they must not share one factor.
        StubFinder stub = new StubFinder(List.of(group(1, 10, 10)));
        ScalingImageGroupFinder finder = new ScalingImageGroupFinder(stub, 0.5, 0.25);

        List<Group> result = finder.findConnectedGroups(image(20, 20));

        assertAll(
            () -> assertEquals(20, result.get(0).centroid().x()),
            () -> assertEquals(40, result.get(0).centroid().y())
        );
    }

    @Test
    @DisplayName("every group in the list is mapped, and order is preserved")
    public void allGroupsAreMappedInOrder() {
        StubFinder stub = new StubFinder(List.of(
                group(100, 10, 20),
                group(50, 30, 40),
                group(25, 0, 0)));
        ScalingImageGroupFinder finder = new ScalingImageGroupFinder(stub, 0.5, 0.5);

        List<Group> result = finder.findConnectedGroups(image(80, 80));

        assertAll(
            () -> assertEquals(3, result.size()),
            // The pipeline takes groups.get(0) as the largest, so the order the
            // delegate established has to survive.
            () -> assertEquals(100, result.get(0).size()),
            () -> assertEquals(20, result.get(0).centroid().x()),
            () -> assertEquals(40, result.get(0).centroid().y()),
            () -> assertEquals(60, result.get(1).centroid().x()),
            () -> assertEquals(80, result.get(1).centroid().y()),
            () -> assertEquals(0, result.get(2).centroid().x()),
            () -> assertEquals(0, result.get(2).centroid().y())
        );
    }

    @Test
    @DisplayName("group sizes are left as measured, in scaled pixels")
    public void sizesAreNotRescaled() {
        // Documented behavior: only the ordering of sizes matters to the
        // pipeline, and every group in a frame scales by the same factor, so
        // the order is unaffected. Converting a size to a source-pixel area
        // would be an estimate, not a measurement.
        StubFinder stub = new StubFinder(List.of(group(37, 10, 10)));

        List<Group> result = new ScalingImageGroupFinder(stub, 0.5, 0.5)
                .findConnectedGroups(image(40, 40));

        assertEquals(37, result.get(0).size());
    }

    @Test
    @DisplayName("coordinates round to the nearest source pixel")
    public void coordinatesRoundToNearest() {
        // 7 / 0.3 = 23.33 -> 23; 9 / 0.3 = 30.0 -> 30.
        StubFinder stub = new StubFinder(List.of(group(1, 7, 9)));

        List<Group> result = new ScalingImageGroupFinder(stub, 0.3, 0.3)
                .findConnectedGroups(image(30, 30));

        assertAll(
            () -> assertEquals(23, result.get(0).centroid().x()),
            () -> assertEquals(30, result.get(0).centroid().y())
        );
    }

    // ---------- pass-through behavior ----------

    @Test
    @DisplayName("the image is handed to the delegate unchanged")
    public void imageIsPassedThroughUntouched() {
        // The decoder already did the resizing. Scaling the image again here
        // would both cost time and blur what the binarizer sees.
        StubFinder stub = new StubFinder(List.of());
        BufferedImage frame = image(64, 48);

        new ScalingImageGroupFinder(stub, 0.5, 0.5).findConnectedGroups(frame);

        assertSame(frame, stub.received, "the delegate must see the very same image");
    }

    @Test
    @DisplayName("an empty result stays empty")
    public void emptyResultIsEmpty() {
        StubFinder stub = new StubFinder(List.of());

        assertTrue(new ScalingImageGroupFinder(stub, 0.5, 0.5)
                .findConnectedGroups(image(10, 10)).isEmpty());
    }

    @Test
    @DisplayName("scale 1.0 returns the delegate's own list, unmodified")
    public void identityScaleIsAPassThrough() {
        List<Group> answer = List.of(group(5, 3, 4));
        StubFinder stub = new StubFinder(answer);

        List<Group> result = new ScalingImageGroupFinder(stub, 1.0, 1.0)
                .findConnectedGroups(image(10, 10));

        assertAll(
            () -> assertEquals(3, result.get(0).centroid().x()),
            () -> assertEquals(4, result.get(0).centroid().y()),
            () -> assertSame(answer, result, "no need to rebuild the list for an identity mapping")
        );
    }

    @Test
    @DisplayName("the delegate is called exactly once per frame")
    public void delegateCalledOncePerFrame() {
        StubFinder stub = new StubFinder(List.of(group(1, 1, 1)));
        ScalingImageGroupFinder finder = new ScalingImageGroupFinder(stub, 0.5, 0.5);

        finder.findConnectedGroups(image(10, 10));
        finder.findConnectedGroups(image(10, 10));

        assertEquals(2, stub.calls);
    }

    // ---------- construction ----------

    @Test
    @DisplayName("a null delegate or a non-positive scale is rejected at construction")
    public void invalidConstructionIsRejected() {
        StubFinder stub = new StubFinder(List.of());

        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new ScalingImageGroupFinder(null, 0.5, 0.5)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new ScalingImageGroupFinder(stub, 0.0, 0.5)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new ScalingImageGroupFinder(stub, 0.5, -0.5)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new ScalingImageGroupFinder(stub, Double.NaN, 0.5))
        );
    }

    @Test
    @DisplayName("the scales are reported back")
    public void scalesAreReadable() {
        ScalingImageGroupFinder finder =
                new ScalingImageGroupFinder(new StubFinder(List.of()), 0.5, 0.25);

        assertAll(
            () -> assertEquals(0.5, finder.scaleX()),
            () -> assertEquals(0.25, finder.scaleY())
        );
    }

    // ---------- composition with cropping ----------

    @Test
    @DisplayName("wrapping the crop finder gives source coordinates for a cropped scaled frame")
    public void composesWithCropping() {
        // The real wiring: binarize and search inside a cropped region of a
        // scaled frame, then map the result back to source coordinates. The
        // crop offset is in scaled pixels and must be undone BEFORE the scale,
        // which is why the scaling finder is the outer one.
        StubFinder inner = new StubFinder(List.of(group(9, 5, 5)));
        CroppingImageGroupFinder cropping = new CroppingImageGroupFinder(inner, 10, 20, 30, 30);
        ScalingImageGroupFinder scaling = new ScalingImageGroupFinder(cropping, 0.5, 0.5);

        List<Group> result = scaling.findConnectedGroups(image(100, 100));

        // (5,5) inside the crop -> (15,25) in the scaled frame -> (30,50) in source.
        assertAll(
            () -> assertEquals(30, result.get(0).centroid().x()),
            () -> assertEquals(50, result.get(0).centroid().y())
        );
    }
}
