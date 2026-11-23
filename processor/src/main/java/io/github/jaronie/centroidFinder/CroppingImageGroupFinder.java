package io.github.jaronie.centroidFinder;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * An ImageGroupFinder that restricts the search to a rectangular region of
 * interest (ROI) and delegates the actual detection to another finder.
 *
 * This exists because "largest group" is only a useful rule when the subject is
 * the largest matching blob in the frame. Dark borders, letterboxing, or any
 * fixed object sharing the subject's color can easily out-size it; cropping
 * them out of consideration restores the rule without changing how groups are
 * found or ranked.
 *
 * Centroids are translated back into FULL-FRAME coordinates before being
 * returned, so callers (and the CSV) never see ROI-relative numbers. Group
 * sizes are unaffected, and because the translation shifts every centroid
 * equally, the delegate's descending sort order is preserved.
 */
public class CroppingImageGroupFinder implements ImageGroupFinder {

    private final ImageGroupFinder delegate;
    private final int x;
    private final int y;
    private final int width;
    private final int height;

    /**
     * Constructs a finder that searches only the given rectangle.
     *
     * @param delegate the finder applied to the cropped image
     * @param x        left edge of the region, in full-frame pixels
     * @param y        top edge of the region, in full-frame pixels
     * @param width    region width in pixels; must be positive
     * @param height   region height in pixels; must be positive
     * @throws NullPointerException     if delegate is null
     * @throws IllegalArgumentException if the origin is negative or the size is not positive
     */
    public CroppingImageGroupFinder(ImageGroupFinder delegate, int x, int y, int width, int height) {
        if (delegate == null) {
            throw new NullPointerException("Delegate group finder cannot be null.");
        }
        if (x < 0 || y < 0) {
            throw new IllegalArgumentException("Crop origin cannot be negative: (" + x + "," + y + ")");
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Crop size must be positive: " + width + "x" + height);
        }
        this.delegate = delegate;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    /**
     * Constructs a finder that trims an equal margin from all four sides of a
     * frame of the given size.
     *
     * @param delegate    the finder applied to the cropped image
     * @param inset       pixels to trim from each side; must be non-negative
     * @param frameWidth  full frame width in pixels
     * @param frameHeight full frame height in pixels
     * @throws IllegalArgumentException if the inset is negative or leaves nothing behind
     */
    public static CroppingImageGroupFinder fromInset(ImageGroupFinder delegate, int inset,
                                                     int frameWidth, int frameHeight) {
        if (inset < 0) {
            throw new IllegalArgumentException("Inset cannot be negative: " + inset);
        }
        int remainingWidth = frameWidth - 2 * inset;
        int remainingHeight = frameHeight - 2 * inset;
        if (remainingWidth <= 0 || remainingHeight <= 0) {
            throw new IllegalArgumentException("Inset " + inset + " leaves no pixels in a "
                    + frameWidth + "x" + frameHeight + " frame.");
        }
        return new CroppingImageGroupFinder(delegate, inset, inset, remainingWidth, remainingHeight);
    }

    public int x() { return x; }

    public int y() { return y; }

    public int width() { return width; }

    public int height() { return height; }

    /**
     * Finds connected groups within the region of interest only.
     *
     * The region is clipped to the image, so a frame smaller than the configured
     * rectangle is searched as far as it goes rather than throwing.
     *
     * @param image the full frame to search
     * @return the delegate's groups, with centroids in full-frame coordinates,
     *         or an empty list if the region falls entirely outside the image
     */
    @Override
    public List<Group> findConnectedGroups(BufferedImage image) {
        if (image == null) {
            throw new NullPointerException("Image cannot be null.");
        }

        int cropX = Math.min(x, image.getWidth());
        int cropY = Math.min(y, image.getHeight());
        int cropWidth = Math.min(width, image.getWidth() - cropX);
        int cropHeight = Math.min(height, image.getHeight() - cropY);

        if (cropWidth <= 0 || cropHeight <= 0) {
            return List.of();
        }

        // No crop needed: hand the frame straight through, untranslated.
        if (cropX == 0 && cropY == 0 && cropWidth == image.getWidth() && cropHeight == image.getHeight()) {
            return delegate.findConnectedGroups(image);
        }

        BufferedImage region = image.getSubimage(cropX, cropY, cropWidth, cropHeight);
        List<Group> found = delegate.findConnectedGroups(region);

        List<Group> translated = new ArrayList<>(found.size());
        for (Group group : found) {
            Coordinate centroid = group.centroid();
            translated.add(new Group(group.size(),
                    new Coordinate(centroid.x() + cropX, centroid.y() + cropY)));
        }
        return translated;
    }
}
