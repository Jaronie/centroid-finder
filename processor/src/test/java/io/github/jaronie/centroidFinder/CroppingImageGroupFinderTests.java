package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for CroppingImageGroupFinder.
 *
 * Two things have to hold for the crop to be safe to bolt onto the pipeline:
 * the delegate must only ever see the region of interest, and the centroids
 * coming back out must be in full-frame coordinates so the CSV stays
 * comparable across runs with and without a crop. Most tests here use a
 * recording stub for the first half and a real binarize-and-find stack for the
 * second.
 */
public class CroppingImageGroupFinderTests {

    private static final int BLACK = 0x000000;
    private static final int WHITE = 0xFFFFFF;

    /** Records the image it was handed and returns a canned answer. */
    private static class RecordingFinder implements ImageGroupFinder {
        private final List<Group> answer;
        private BufferedImage received;
        private int calls = 0;

        RecordingFinder(List<Group> answer) {
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

    /** A black image with a white rectangle painted into it. */
    private static BufferedImage imageWithRect(int w, int h, int rx, int ry, int rw, int rh) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean inside = x >= rx && x < rx + rw && y >= ry && y < ry + rh;
                image.setRGB(x, y, inside ? WHITE : BLACK);
            }
        }
        return image;
    }

    /** The real stack: find connected groups of white pixels. */
    private static ImageGroupFinder whiteFinder() {
        return new BinarizingImageGroupFinder(
                new DistanceImageBinarizer(new EuclideanColorDistance(), WHITE, 10),
                new DfsBinaryGroupFinder());
    }

    // ---------- construction ----------

    @Test
    public void testNullDelegateRejected() {
        NullPointerException exception = assertThrows(
            NullPointerException.class,
            () -> new CroppingImageGroupFinder(null, 0, 0, 10, 10)
        );

        assertEquals("Delegate group finder cannot be null.", exception.getMessage());
    }

    @Test
    public void testNegativeOriginRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new CroppingImageGroupFinder(whiteFinder(), -1, 0, 10, 10));
    }

    @Test
    public void testNonPositiveSizeRejected() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new CroppingImageGroupFinder(whiteFinder(), 0, 0, 0, 10)),
            () -> assertThrows(IllegalArgumentException.class,
                    () -> new CroppingImageGroupFinder(whiteFinder(), 0, 0, 10, -5))
        );
    }

    @Test
    public void testFromInsetTrimsAllFourSides() {
        CroppingImageGroupFinder finder =
                CroppingImageGroupFinder.fromInset(whiteFinder(), 10, 100, 80);

        assertAll(
            () -> assertEquals(10, finder.x()),
            () -> assertEquals(10, finder.y()),
            () -> assertEquals(80, finder.width()),
            () -> assertEquals(60, finder.height())
        );
    }

    @Test
    public void testZeroInsetKeepsTheWholeFrame() {
        CroppingImageGroupFinder finder =
                CroppingImageGroupFinder.fromInset(whiteFinder(), 0, 100, 80);

        assertAll(
            () -> assertEquals(0, finder.x()),
            () -> assertEquals(100, finder.width()),
            () -> assertEquals(80, finder.height())
        );
    }

    @Test
    public void testNegativeInsetRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> CroppingImageGroupFinder.fromInset(whiteFinder(), -1, 100, 80));
    }

    @Test
    public void testInsetThatConsumesTheFrameRejected() {
        // 50 from each side of a 100-wide frame leaves nothing.
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> CroppingImageGroupFinder.fromInset(whiteFinder(), 50, 100, 80)
        );

        assertTrue(exception.getMessage().contains("leaves no pixels"));
    }

    // ---------- what the delegate sees ----------

    @Test
    public void testDelegateOnlySeesTheRegion() {
        RecordingFinder delegate = new RecordingFinder(List.of());
        BufferedImage frame = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);

        new CroppingImageGroupFinder(delegate, 10, 20, 50, 40).findConnectedGroups(frame);

        assertAll(
            () -> assertEquals(1, delegate.calls),
            () -> assertEquals(50, delegate.received.getWidth()),
            () -> assertEquals(40, delegate.received.getHeight())
        );
    }

    @Test
    public void testFullFrameRegionPassesTheImageStraightThrough() {
        // No crop to do, so no subimage wrapper and no translation.
        RecordingFinder delegate = new RecordingFinder(List.of(group(5, 3, 4)));
        BufferedImage frame = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);

        List<Group> groups = new CroppingImageGroupFinder(delegate, 0, 0, 100, 80)
                .findConnectedGroups(frame);

        assertAll(
            () -> assertSame(frame, delegate.received),
            () -> assertEquals(List.of(group(5, 3, 4)), groups)
        );
    }

    @Test
    public void testNullImageRejected() {
        assertThrows(NullPointerException.class,
            () -> new CroppingImageGroupFinder(whiteFinder(), 0, 0, 10, 10).findConnectedGroups(null));
    }

    // ---------- coordinate translation ----------

    @Test
    public void testCentroidsComeBackInFullFrameCoordinates() {
        RecordingFinder delegate = new RecordingFinder(List.of(group(9, 2, 3), group(4, 0, 0)));
        BufferedImage frame = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);

        List<Group> groups = new CroppingImageGroupFinder(delegate, 10, 20, 50, 40)
                .findConnectedGroups(frame);

        assertEquals(List.of(group(9, 12, 23), group(4, 10, 20)), groups);
    }

    @Test
    public void testTranslationLeavesSizesAndOrderAlone() {
        RecordingFinder delegate = new RecordingFinder(List.of(group(100, 1, 1), group(50, 2, 2), group(10, 3, 3)));
        BufferedImage frame = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);

        List<Group> groups = new CroppingImageGroupFinder(delegate, 5, 5, 50, 40)
                .findConnectedGroups(frame);

        assertAll(
            () -> assertEquals(List.of(100, 50, 10), groups.stream().map(Group::size).toList()),
            () -> assertEquals(group(100, 6, 6), groups.get(0))
        );
    }

    @Test
    public void testEmptyResultStaysEmpty() {
        RecordingFinder delegate = new RecordingFinder(List.of());
        BufferedImage frame = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);

        assertTrue(new CroppingImageGroupFinder(delegate, 5, 5, 20, 20)
                .findConnectedGroups(frame).isEmpty());
    }

    // ---------- clipping to the image ----------

    @Test
    public void testRegionLargerThanTheFrameIsClipped() {
        RecordingFinder delegate = new RecordingFinder(List.of());
        BufferedImage frame = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);

        new CroppingImageGroupFinder(delegate, 10, 10, 999, 999).findConnectedGroups(frame);

        assertAll(
            () -> assertEquals(20, delegate.received.getWidth()),
            () -> assertEquals(10, delegate.received.getHeight())
        );
    }

    @Test
    public void testRegionEntirelyOutsideTheFrameReturnsNoGroups() {
        RecordingFinder delegate = new RecordingFinder(List.of(group(1, 0, 0)));
        BufferedImage frame = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);

        List<Group> groups = new CroppingImageGroupFinder(delegate, 100, 100, 10, 10)
                .findConnectedGroups(frame);

        assertAll(
            () -> assertTrue(groups.isEmpty()),
            () -> assertEquals(0, delegate.calls, "delegate should not be called at all")
        );
    }

    // ---------- end to end with the real detection stack ----------

    @Test
    public void testCropExcludesABlobOutsideTheRegion() {
        // A 6x6 blob at the top-left (36 px) and a 4x4 blob in the middle (16 px).
        BufferedImage frame = imageWithRect(40, 40, 0, 0, 6, 6);
        for (int y = 18; y < 22; y++) {
            for (int x = 18; x < 22; x++) {
                frame.setRGB(x, y, WHITE);
            }
        }

        List<Group> uncropped = whiteFinder().findConnectedGroups(frame);
        List<Group> cropped = new CroppingImageGroupFinder(whiteFinder(), 10, 10, 20, 20)
                .findConnectedGroups(frame);

        assertAll(
            // Without a crop the corner blob wins on size...
            () -> assertEquals(36, uncropped.get(0).size()),
            // ...and with the crop it is not even a candidate.
            () -> assertEquals(1, cropped.size()),
            () -> assertEquals(16, cropped.get(0).size())
        );
    }

    @Test
    public void testCroppedCentroidMatchesTheUncroppedOneForTheSameBlob() {
        // The same blob must get the same full-frame centroid whether or not a
        // crop is in play -- that is what keeps CSVs comparable.
        BufferedImage frame = imageWithRect(60, 60, 25, 30, 8, 6);

        Group whole = whiteFinder().findConnectedGroups(frame).get(0);
        Group viaCrop = new CroppingImageGroupFinder(whiteFinder(), 15, 15, 40, 40)
                .findConnectedGroups(frame).get(0);

        assertEquals(whole, viaCrop);
    }

    @Test
    public void testBlobStraddlingTheEdgeIsTruncatedNotDropped() {
        // Half of the blob lies outside the region: the visible half is still
        // found, so its size shrinks and its centroid shifts inward.
        BufferedImage frame = imageWithRect(40, 40, 6, 10, 8, 4);

        Group whole = whiteFinder().findConnectedGroups(frame).get(0);
        Group viaCrop = new CroppingImageGroupFinder(whiteFinder(), 10, 0, 30, 40)
                .findConnectedGroups(frame).get(0);

        assertAll(
            () -> assertEquals(32, whole.size()),
            () -> assertEquals(16, viaCrop.size()),
            () -> assertTrue(viaCrop.centroid().x() > whole.centroid().x())
        );
    }

    @Test
    public void testInsetFinderIgnoresABorderThatHugsTheFrameEdge() {
        // The shape of the real failure: a border stripe down the right edge
        // (40x3 = 120 px) out-sizes the subject in the middle (8x8 = 64 px).
        BufferedImage frame = imageWithRect(40, 40, 37, 0, 3, 40);
        for (int y = 16; y < 24; y++) {
            for (int x = 16; x < 24; x++) {
                frame.setRGB(x, y, WHITE);
            }
        }

        Group withoutCrop = whiteFinder().findConnectedGroups(frame).get(0);
        Group withInset = CroppingImageGroupFinder.fromInset(whiteFinder(), 5, 40, 40)
                .findConnectedGroups(frame).get(0);

        assertAll(
            () -> assertEquals(120, withoutCrop.size(), "border should dominate without a crop"),
            () -> assertEquals(64, withInset.size(), "subject should win once the border is cropped out"),
            () -> assertEquals(new Coordinate(19, 19), withInset.centroid())
        );
    }

    @Test
    public void testCroppedFinderDropsInToThePipelineUnchanged() {
        // VideoPipeline takes any ImageGroupFinder, so the crop is transparent
        // to it: same contract, full-frame centroid.
        BufferedImage frame = imageWithRect(40, 40, 37, 0, 3, 40);
        for (int y = 16; y < 24; y++) {
            for (int x = 16; x < 24; x++) {
                frame.setRGB(x, y, WHITE);
            }
        }

        ImageGroupFinder finder = CroppingImageGroupFinder.fromInset(whiteFinder(), 5, 40, 40);
        List<Group> groups = finder.findConnectedGroups(frame);

        assertFalse(groups.isEmpty());
        assertEquals(new Coordinate(19, 19), groups.get(0).centroid());
    }
}
