package io.github.jaronie.centroidFinder;

import java.awt.image.BufferedImage;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

/**
 * Decodes a video file into Frames (BufferedImage + timestamp in seconds).
 * Single-use: iterate once, then close.
 */
public class FrameExtractor implements Iterable<VideoFrame>, AutoCloseable {
    private final FFmpegFrameGrabber grabber;
    private final Java2DFrameConverter converter = new Java2DFrameConverter();
    private final double fps;
    private boolean iterated = false;

    public FrameExtractor(String videoPath) {
        this.grabber = new FFmpegFrameGrabber(videoPath);
        try {
            grabber.start();
        } catch (FFmpegFrameGrabber.Exception e) {
            throw new IllegalArgumentException("Could not open video: " + videoPath, e);
        }
        this.fps = grabber.getFrameRate();
    }

    public int width()  { return grabber.getImageWidth(); }
    public int height() { return grabber.getImageHeight(); }

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

                    BufferedImage image = converter.getBufferedImage(frame); // may be null on bad frame
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

    @Override
    public void close() throws Exception {
        grabber.stop();
        grabber.release();
        converter.close();
    }
}