package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for FrameValidator.
 *
 * FrameValidator is pure logic: given a VideoFrame it answers "is this frame
 * usable?" and never touches a video file, so every test here builds its frames
 * by hand with in-memory BufferedImages. The validator is constructed with the
 * size it expects (in production: the size reported by the FrameExtractor), so
 * each test states that expected size explicitly.
 */
public class FrameValidatorTests {

    private static final int WIDTH = 8;
    private static final int HEIGHT = 4;

    private final FrameValidator validator = new FrameValidator(WIDTH, HEIGHT);

    /** Helper: an opaque black image of the given size. */
    private static BufferedImage image(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    /** Helper: a frame wrapping the given image, with throwaway index/timestamp. */
    private static VideoFrame frame(BufferedImage image) {
        return new VideoFrame(0, 0.0, image);
    }

    // ---------- valid frames ----------

    @Test
    public void testFrameMatchingExpectedSizeIsValid() {
        FrameValidator.ValidationResult result = validator.validate(frame(image(WIDTH, HEIGHT)));

        assertAll(
            () -> assertTrue(result.valid()),
            () -> assertEquals("ok", result.reason())
        );
    }

    @Test
    public void testValidatorIgnoresPixelContent() {
        // An all-white frame and an all-black frame are equally "usable": deciding
        // whether a frame contains a group is the group finder's job, not the
        // validator's.
        BufferedImage white = image(WIDTH, HEIGHT);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                white.setRGB(x, y, 0xFFFFFF);
            }
        }

        assertTrue(validator.validate(frame(white)).valid());
        assertTrue(validator.validate(frame(image(WIDTH, HEIGHT))).valid());
    }

    @Test
    public void testValidatorIgnoresIndexAndTimestamp() {
        // Out-of-order or negative metadata is not a frame-level defect.
        VideoFrame odd = new VideoFrame(-5, -1.25, image(WIDTH, HEIGHT));

        assertTrue(validator.validate(odd).valid());
    }

    @Test
    public void testSquareValidatorAcceptsSquareFrame() {
        FrameValidator square = new FrameValidator(16, 16);

        assertTrue(square.validate(frame(image(16, 16))).valid());
    }

    // ---------- invalid frames ----------

    @Test
    public void testNullFrameIsInvalid() {
        // The validator must not throw on a null frame; it reports it instead.
        FrameValidator.ValidationResult result = validator.validate(null);

        assertAll(
            () -> assertFalse(result.valid()),
            () -> assertEquals("frame is null", result.reason())
        );
    }

    @Test
    public void testNullImageIsInvalid() {
        // Java2DFrameConverter can hand back a null image for a corrupt frame,
        // so a VideoFrame with a null image is a real case, not a hypothetical.
        FrameValidator.ValidationResult result = validator.validate(frame(null));

        assertAll(
            () -> assertFalse(result.valid()),
            () -> assertEquals("image is null", result.reason())
        );
    }

    @Test
    public void testWrongWidthIsInvalid() {
        FrameValidator.ValidationResult result = validator.validate(frame(image(WIDTH + 1, HEIGHT)));

        assertAll(
            () -> assertFalse(result.valid()),
            () -> assertEquals("size 9x4 does not match expected 8x4", result.reason())
        );
    }

    @Test
    public void testWrongHeightIsInvalid() {
        FrameValidator.ValidationResult result = validator.validate(frame(image(WIDTH, HEIGHT + 1)));

        assertAll(
            () -> assertFalse(result.valid()),
            () -> assertEquals("size 8x5 does not match expected 8x4", result.reason())
        );
    }

    @Test
    public void testTransposedSizeIsInvalid() {
        // 4x8 has the same pixel count as 8x4 but is not the same frame shape.
        FrameValidator.ValidationResult result = validator.validate(frame(image(HEIGHT, WIDTH)));

        assertFalse(result.valid());
    }

    @Test
    public void testEmptyImageIsInvalid() {
        // BufferedImage refuses to be constructed with a zero dimension, so the
        // "image is empty" branch is reached with a stub that reports width 0.
        BufferedImage empty = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB) {
            @Override
            public int getWidth() {
                return 0;
            }
        };

        FrameValidator.ValidationResult result = validator.validate(frame(empty));

        assertAll(
            () -> assertFalse(result.valid()),
            () -> assertEquals("image is empty", result.reason())
        );
    }

    // ---------- ValidationResult ----------

    @Test
    public void testValidationResultCarriesItsFields() {
        FrameValidator.ValidationResult result = new FrameValidator.ValidationResult(false, "because");

        assertAll(
            () -> assertFalse(result.valid()),
            () -> assertEquals("because", result.reason())
        );
    }
}
