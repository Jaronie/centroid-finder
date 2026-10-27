package java.io.github.jaronie.centroidFinder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for BinarizingImageGroupFinder.
 *
 * BinarizingImageGroupFinder does not compute anything itself. All it does is
 * wire two collaborators together: it hands the image to an ImageBinarizer,
 * hands that binary array to a BinaryGroupFinder, and returns the result.
 *
 * So these tests do NOT use the real DistanceImageBinarizer or
 * DfsBinaryGroupFinder. If they did, a bug in either one would break these
 * tests too, and we could not tell which class was actually at fault. Instead
 * each test plugs in a hand-written fake collaborator whose behavior we
 * control, which lets us check the one thing this class is responsible for:
 * the wiring.
 */
public class BinarizingImageGroupFinderTests {

    /**
     * A stand-in for ImageBinarizer. It records the image it was called with,
     * counts the calls, and returns whatever array the test told it to return.
     *
     * Recording the arguments is what makes it a "spy": the test can ask it
     * afterward what it saw.
     */
    private static class RecordingBinarizer implements ImageBinarizer {
        private final int[][] arrayToReturn;
        BufferedImage imageReceived;
        int callCount = 0;

        RecordingBinarizer(int[][] arrayToReturn) {
            this.arrayToReturn = arrayToReturn;
        }

        @Override
        public int[][] toBinaryArray(BufferedImage image) {
            this.imageReceived = image;
            this.callCount++;
            return arrayToReturn;
        }

        @Override
        public BufferedImage toBufferedImage(int[][] image) {
            // Not part of this unit's job. If it ever gets called, that is a bug
            // worth failing loudly on rather than quietly returning null.
            throw new UnsupportedOperationException("toBufferedImage should not be called here");
        }
    }

    /**
     * A stand-in for BinaryGroupFinder. Same idea: record the array it was
     * handed and return a canned list of groups.
     */
    private static class RecordingGroupFinder implements BinaryGroupFinder {
        private final List<Group> groupsToReturn;
        int[][] arrayReceived;
        int callCount = 0;

        RecordingGroupFinder(List<Group> groupsToReturn) {
            this.groupsToReturn = groupsToReturn;
        }

        @Override
        public List<Group> findConnectedGroups(int[][] image) {
            this.arrayReceived = image;
            this.callCount++;
            return groupsToReturn;
        }
    }

    /** A fake whose only job is to blow up, so we can test error propagation. */
    private static class ThrowingBinarizer implements ImageBinarizer {
        @Override
        public int[][] toBinaryArray(BufferedImage image) {
            throw new NullPointerException("Image cannot be null.");
        }

        @Override
        public BufferedImage toBufferedImage(int[][] image) {
            throw new UnsupportedOperationException();
        }
    }

    /** A tiny throwaway image used only as a token to follow through the call chain. */
    private static BufferedImage sampleImage() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
    }

    // Tests start here

    @Test
    public void testPassesTheGivenImageToTheBinarizer() {
        BufferedImage image = sampleImage();
        RecordingBinarizer binarizer = new RecordingBinarizer(new int[][] {{0}});
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(List.of());

        new BinarizingImageGroupFinder(binarizer, groupFinder).findConnectedGroups(image);

        // assertSame, not assertEquals: we want the exact same object, proving
        // the image was handed straight through and not copied or recreated.
        assertSame(image, binarizer.imageReceived);
        assertEquals(1, binarizer.callCount);
    }

    @Test
    public void testPassesTheBinarizerOutputToTheGroupFinder() {
        int[][] binaryArray = {
            {1, 0},
            {0, 1}
        };
        RecordingBinarizer binarizer = new RecordingBinarizer(binaryArray);
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(List.of());

        new BinarizingImageGroupFinder(binarizer, groupFinder).findConnectedGroups(sampleImage());

        assertSame(binaryArray, groupFinder.arrayReceived);
        assertEquals(1, groupFinder.callCount);
    }

    @Test
    public void testReturnsExactlyWhatTheGroupFinderReturned() {
        List<Group> expected = List.of(
            new Group(5, new Coordinate(3, 4)),
            new Group(2, new Coordinate(1, 1))
        );
        RecordingBinarizer binarizer = new RecordingBinarizer(new int[][] {{1}});
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(expected);

        List<Group> actual =
            new BinarizingImageGroupFinder(binarizer, groupFinder).findConnectedGroups(sampleImage());

        assertEquals(expected, actual);
    }

    @Test
    public void testPreservesGroupFinderOrderWithoutReSorting() {
        // The BinaryGroupFinder contract already guarantees descending order, so
        // this class must not reorder. Here the fake deliberately returns groups
        // in a non-sorted order; a correct implementation hands them back as-is.
        Group small = new Group(1, new Coordinate(0, 0));
        Group large = new Group(9, new Coordinate(2, 2));
        List<Group> outOfOrder = List.of(small, large);

        RecordingBinarizer binarizer = new RecordingBinarizer(new int[][] {{1}});
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(outOfOrder);

        List<Group> actual =
            new BinarizingImageGroupFinder(binarizer, groupFinder).findConnectedGroups(sampleImage());

        assertEquals(List.of(small, large), actual);
    }

    @Test
    public void testReturnsEmptyListWhenNoGroupsFound() {
        RecordingBinarizer binarizer = new RecordingBinarizer(new int[][] {
            {0, 0},
            {0, 0}
        });
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(List.of());

        List<Group> actual =
            new BinarizingImageGroupFinder(binarizer, groupFinder).findConnectedGroups(sampleImage());

        assertTrue(actual.isEmpty());
    }

    @Test
    public void testCallsEachCollaboratorExactlyOncePerCall() {
        RecordingBinarizer binarizer = new RecordingBinarizer(new int[][] {{1}});
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(List.of());
        BinarizingImageGroupFinder finder = new BinarizingImageGroupFinder(binarizer, groupFinder);

        finder.findConnectedGroups(sampleImage());
        finder.findConnectedGroups(sampleImage());

        assertEquals(2, binarizer.callCount);
        assertEquals(2, groupFinder.callCount);
    }

    @Test
    public void testBinarizerExceptionPropagates() {
        // This class should not swallow a failure from its collaborator. The
        // fake throws on purpose so we can confirm the exception escapes, and
        // that the group finder is never reached.
        RecordingGroupFinder groupFinder = new RecordingGroupFinder(List.of());
        BinarizingImageGroupFinder finder =
            new BinarizingImageGroupFinder(new ThrowingBinarizer(), groupFinder);

        assertThrows(NullPointerException.class, () -> finder.findConnectedGroups(null));
        assertEquals(0, groupFinder.callCount);
    }
}
