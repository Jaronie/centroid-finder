package io.github.jaronie.centroidFinder;

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Decodes a video file into Frames (BufferedImage + timestamp in seconds).
 * Single-use: iterate once, then close.
 *
 * <p>Frames can be delivered smaller than the source with the scale
 * constructor. Every later stage costs time per pixel, so halving each
 * dimension quarters the work of binarizing and group-finding. FFmpeg does the
 * resizing inside the decode step, which is far cheaper than scaling a
 * BufferedImage afterwards -- and measurably cheaper than analyzing the pixels
 * we are about to throw away.
 *
 * <p>When scaled, {@link #width()} and {@link #height()} report the delivered
 * size, because that is what callers validate frames against;
 * {@link #nativeWidth()} and {@link #nativeHeight()} report the source's own
 * size. Centroids found in a scaled frame are in scaled coordinates -- see
 * {@link ScalingImageGroupFinder} for mapping them back.
 */
public class FrameExtractor implements Iterable<VideoFrame>, AutoCloseable {
    private final FFmpegFrameGrabber grabber;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();
    private final double fps;
    private final int nativeWidth;
    private final int nativeHeight;
    private boolean iterated = false;

    public FrameExtractor(String videoPath) {
        this(videoPath, 1.0);
    }

    /**
     * @param scale fraction of the source size to decode at, in (0, 1].
     *              1.0 decodes at full size and does no resizing at all.
     */
    public FrameExtractor(String videoPath, double scale) {
        if (!(scale > 0.0) || scale > 1.0 || Double.isNaN(scale)) {
            throw new IllegalArgumentException(
                    "Scale must be greater than 0 and at most 1, but was " + scale);
        }

        // The target size is a fraction of the source size, which cannot be
        // known until the file is open -- yet setImageWidth/Height only take
        // effect before start(). So the source is opened once just to read its
        // dimensions, then opened again at the size we actually want. The probe
        // measured about 180 ms, against about 30 s to process a video.
        int[] nativeSize = probeSize(videoPath);
        this.nativeWidth = nativeSize[0];
        this.nativeHeight = nativeSize[1];

        this.grabber = new FFmpegFrameGrabber(videoPath);
        if (scale < 1.0) {
            int targetWidth = alignWidth((int) Math.round(nativeWidth * scale));
            // Even height: odd sizes are awkward for 4:2:0 chroma.
            int targetHeight = (int) Math.round(nativeHeight * scale) & ~1;

            // Only scale if the aligned size is actually smaller and still
            // usable. Otherwise decode at native size -- a slower correct
            // answer beats a faster wrong one. scaleX()/scaleY() report what
            // really happened, so callers are not misled.
            if (targetWidth >= WIDTH_ALIGNMENT && targetWidth < nativeWidth && targetHeight >= 2) {
                grabber.setImageWidth(targetWidth);
                grabber.setImageHeight(targetHeight);
            }
        }
        try {
            grabber.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IllegalArgumentException("Could not open video: " + videoPath, e);
        }
        this.fps = grabber.getFrameRate();
    }

    /**
     * Decoded widths are snapped down to a multiple of this.
     *
     * <p>FFmpeg pads each decoded row out to an alignment boundary, and
     * Java2DFrameConverter infers the channel count as
     * {@code imageStride / imageWidth} using integer division. When the padding
     * is large relative to the row -- which is what happens at small widths --
     * that quotient reaches 4, and the converter builds a corrupt four-channel
     * image out of three-channel data. Every pixel then comes out wrong, with
     * no error raised anywhere.
     *
     * <p>That is not theoretical. A 160x120 source at scale 0.5 asks for 80
     * pixels wide and gets a stride of 384, which is 4.8 bytes per pixel; the
     * white square in the test video was detected as scattered noise.
     *
     * <p>Measured across 160x120, 640x480 and 816x720 sources, every width that
     * is a multiple of 64 came back with a stride of exactly {@code width * 3}
     * -- no padding at all. Snapping down to 64 is therefore the predictable
     * choice. Height needs only to be even; it does not affect the stride.
     */
    private static final int WIDTH_ALIGNMENT = 64;

    /** Largest multiple of {@link #WIDTH_ALIGNMENT} that is no bigger than {@code width}. */
    private static int alignWidth(int width) {
        return (width / WIDTH_ALIGNMENT) * WIDTH_ALIGNMENT;
    }

    /** Opens the file only to read its dimensions, then closes it again. */
    private static int[] probeSize(String videoPath) {
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoPath);
        try {
            probe.start();
            return new int[] { probe.getImageWidth(), probe.getImageHeight() };
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IllegalArgumentException("Could not open video: " + videoPath, e);
        } finally {
            try {
                probe.stop();
                probe.release();
            } catch (FFmpegFrameGrabber.Exception ignored) {
                // Already failing, or already closed; nothing useful to add.
            }
        }
    }

    /** Width of the frames this extractor hands out, after any scaling. */
    public int width()  { return grabber.getImageWidth(); }

    /** Height of the frames this extractor hands out, after any scaling. */
    public int height() { return grabber.getImageHeight(); }

    /** Width of the source video, regardless of scaling. */
    public int nativeWidth()  { return nativeWidth; }

    /** Height of the source video, regardless of scaling. */
    public int nativeHeight() { return nativeHeight; }

    /**
     * The horizontal scale actually in effect: delivered width over source
     * width. Rounding to whole pixels means this can differ slightly from the
     * requested scale, so it is reported rather than assumed.
     */
    public double scaleX() { return (double) width() / nativeWidth; }

    /** The vertical scale actually in effect. */
    public double scaleY() { return (double) height() / nativeHeight; }

    /** Whether frames are being delivered at a reduced size. */
    public boolean isScaled() { return width() != nativeWidth || height() != nativeHeight; }

    @Override
    public Iterator<VideoFrame> iterator() {
        if (iterated) throw new IllegalStateException("FrameExtractor can only be iterated once.");
        iterated = true;

        return new Iterator<>() {
            // index must be declared before next: field initializers run in
            // declaration order, so initializing next first would advance()
            // with index still at its default and then reset it to 0.
            private int index = 0;
            private VideoFrame next = advance();

            private VideoFrame advance() {
                try {
                    Frame frame = grabber.grabImage();
                    if (frame == null) return null; // end of stream

                    BufferedImage converted = converter.getBufferedImage(frame);
                    // The converter reuses its image buffer when the next frame is decoded.
                    BufferedImage image = converted == null ? null : copyOf(converted);
                    double seconds = grabber.getTimestamp() / 1_000_000.0;
                    if (seconds <= 0 && index > 0 && fps > 0) seconds = index / fps; // fallback
                    return new VideoFrame(index++, seconds, image);
                } catch (FFmpegFrameGrabber.Exception e) {
                    throw new IllegalStateException("Error decoding frame " + index, e);
                }
            }

            @Override public boolean hasNext() { return next != null; }

            @Override public VideoFrame next() {
                if (next == null) throw new NoSuchElementException();
                VideoFrame current = next;
                next = advance();
                return current;
            }
        };
    }

    private static BufferedImage copyOf(BufferedImage source) {
        BufferedImage copy = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    @Override
    public void close() throws Exception {
        grabber.stop();
        grabber.release();
        converter.close();
    }
}