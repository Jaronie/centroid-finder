import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts metadata and frame data from a video file using JavaCV (FFmpeg bundled, runs locally).
 *
 * Requires Java 17+ (uses records).
 */
public final class TestingJavaCV {

    private TestingJavaCV() {}

    // ------------------------------------------------------------------
    // Data types
    // ------------------------------------------------------------------

    /** Basic facts about a video file. Duration is in microseconds. */
    public record VideoMetadata(
            String path,
            String format,
            int width,
            int height,
            double frameRate,
            int frameCount,
            long durationMicros,
            String videoCodec,
            int videoBitrate,
            boolean hasAudio,
            String audioCodec,
            int audioChannels,
            int sampleRate,
            Map<String, String> containerMetadata,
            Map<String, String> videoStreamMetadata) {

        public double durationSeconds() {
            return durationMicros / 1_000_000.0;
        }
    }

    /** Receives each extracted frame. The image is a safe copy that can be kept. */
    @FunctionalInterface
    public interface FrameConsumer {
        void accept(int frameIndex, long timestampMicros, BufferedImage image) throws Exception;
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    public static VideoMetadata readMetadata(File video) throws Exception {
        requireFile(video);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.start();

            boolean hasAudio = grabber.getAudioChannels() > 0;
            VideoMetadata metadata = new VideoMetadata(
                    video.getAbsolutePath(),
                    grabber.getFormat(),
                    grabber.getImageWidth(),
                    grabber.getImageHeight(),
                    grabber.getFrameRate(),
                    grabber.getLengthInVideoFrames(),
                    grabber.getLengthInTime(),
                    grabber.getVideoCodecName(),
                    grabber.getVideoBitrate(),
                    hasAudio,
                    hasAudio ? grabber.getAudioCodecName() : null,
                    grabber.getAudioChannels(),
                    grabber.getSampleRate(),
                    Map.copyOf(grabber.getMetadata()),
                    Map.copyOf(grabber.getVideoMetadata()));

            grabber.stop();
            return metadata;
        }
    }

    // ------------------------------------------------------------------
    // Frame extraction
    // ------------------------------------------------------------------

    /**
     * Streams frames to the consumer without holding them all in memory.
     *
     * @param step 1 = every frame, 10 = every 10th frame, and so on
     * @return the number of frames passed to the consumer
     */
    public static int forEachFrame(File video, int step, FrameConsumer consumer) throws Exception {
        requireFile(video);
        if (step < 1) {
            throw new IllegalArgumentException("step must be >= 1");
        }

        int delivered = 0;
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            Frame frame;
            int index = 0;
            while ((frame = grabber.grabImage()) != null) {
                if (index % step == 0) {
                    consumer.accept(index, frame.timestamp, copyOf(converter.getBufferedImage(frame)));
                    delivered++;
                }
                index++;
            }
            grabber.stop();
        }
        return delivered;
    }

    /** Loads frames into memory. Use maxFrames to avoid running out of heap on long videos. */
    public static List<BufferedImage> extractFrames(File video, int step, int maxFrames) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        try {
            forEachFrame(video, step, (index, timestamp, image) -> {
                if (frames.size() >= maxFrames) {
                    throw new StopExtraction();
                }
                frames.add(image);
            });
        } catch (StopExtraction ignored) {
            // reached maxFrames
        }
        return frames;
    }

    /** Grabs the frame at (or just after) the given timestamp in microseconds. Returns null past the end. */
    public static BufferedImage extractFrameAt(File video, long timestampMicros) throws Exception {
        requireFile(video);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();
            grabber.setTimestamp(timestampMicros);

            Frame frame = grabber.grabImage();
            BufferedImage result = frame == null ? null : copyOf(converter.getBufferedImage(frame));
            grabber.stop();
            return result;
        }
    }

    /** Saves every Nth frame as image files named frame_0000.png, frame_0010.png, and so on. */
    public static List<File> saveFrames(File video, Path outputDir, int step, String imageFormat) throws Exception {
        Files.createDirectories(outputDir);
        List<File> saved = new ArrayList<>();

        forEachFrame(video, step, (index, timestamp, image) -> {
            File out = outputDir.resolve(String.format("frame_%04d.%s", index, imageFormat)).toFile();
            if (!ImageIO.write(image, imageFormat, out)) {
                throw new IllegalStateException("No ImageIO writer for format: " + imageFormat);
            }
            saved.add(out);
        });
        return saved;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The converter reuses its image buffer between frames, so hand out copies. */
    private static BufferedImage copyOf(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static void requireFile(File video) {
        if (video == null || !video.isFile()) {
            throw new IllegalArgumentException("Video file not found: " + video);
        }
    }

    /** Internal signal used to stop early from inside forEachFrame. */
    private static final class StopExtraction extends RuntimeException {
        StopExtraction() {
            super(null, null, false, false);
        }
    }

    // ------------------------------------------------------------------
    // Demo
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        File video = new File(args.length > 0 ? args[0] : "input.mp4");

        VideoMetadata meta = readMetadata(video);
        System.out.printf("%s%n  %dx%d @ %.2f fps, %d frames, %.2f s%n  format=%s, video=%s, audio=%s%n",
                meta.path(), meta.width(), meta.height(), meta.frameRate(), meta.frameCount(),
                meta.durationSeconds(), meta.format(), meta.videoCodec(),
                meta.hasAudio() ? meta.audioCodec() : "none");

        List<File> saved = saveFrames(video, Path.of("frames"), 30, "png");
        System.out.println("Saved " + saved.size() + " frames to ./frames");
    }
}