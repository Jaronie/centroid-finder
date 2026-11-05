package io.github.jaronie.centroidFinder;

import java.awt.image.BufferedImage;

/**
 * Frame-level checks only (is this frame usable?). Whether the frame contains
 * a group is NOT decided here.
 */
public class FrameValidator {
    public record ValidationResult(boolean valid, String reason) {
        static ValidationResult ok() { return new ValidationResult(true, "ok"); }
        static ValidationResult fail(String reason) { return new ValidationResult(false, reason); }
    }

    private final int expectedWidth;
    private final int expectedHeight;

    public FrameValidator(int expectedWidth, int expectedHeight) {
        this.expectedWidth = expectedWidth;
        this.expectedHeight = expectedHeight;
    }

    public ValidationResult validate(VideoFrame frame) {
        if (frame == null) return ValidationResult.fail("frame is null");
        BufferedImage image = frame.image();
        if (image == null) return ValidationResult.fail("image is null");
        if (image.getWidth() <= 0 || image.getHeight() <= 0) return ValidationResult.fail("image is empty");
        if (image.getWidth() != expectedWidth || image.getHeight() != expectedHeight) {
            return ValidationResult.fail("size " + image.getWidth() + "x" + image.getHeight()
                    + " does not match expected " + expectedWidth + "x" + expectedHeight);
        }
        return ValidationResult.ok();
    }
}