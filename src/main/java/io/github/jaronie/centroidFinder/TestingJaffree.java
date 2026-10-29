package io.github.jaronie.centroidFinder;

import com.github.kokorin.jaffree.StreamType;
import com.github.kokorin.jaffree.ffmpeg.FFmpeg;
import com.github.kokorin.jaffree.ffmpeg.Frame;
import com.github.kokorin.jaffree.ffmpeg.FrameConsumer;
import com.github.kokorin.jaffree.ffmpeg.FrameOutput;
import com.github.kokorin.jaffree.ffmpeg.UrlInput;
import com.github.kokorin.jaffree.ffprobe.FFprobe;
import com.github.kokorin.jaffree.ffprobe.FFprobeResult;
import com.github.kokorin.jaffree.ffprobe.Format;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * A scratch harness for evaluating Jaffree as the video front-end for centroidFinder.
 *
 * It does two things against a real video file:
 *
 * 1. Probes the container with ffprobe and prints the metadata we would need in order
 *    to size a run: duration, resolution, frame rate and frame count.
 * 2. Pulls decoded frames out of ffmpeg as BufferedImages, which is the handoff point
 *    to the existing {@link ImageBinarizer} / {@link ImageGroupFinder} pipeline, and
 *    writes them to disk as PNGs so the decode can be eyeballed.
 *
 * Jaffree shells out to ffmpeg and ffprobe, so both must be installed and on the PATH.
 * If they live somewhere outside the PATH, pass the containing directory to
 * {@code FFprobe.atPath(Path)} and {@code FFmpeg.atPath(Path)} instead.
 *
 * Usage:
 *   java TestingJaffree [video] [fps] [maxFrames] [outputDir]
 *
 * Defaults: sampleInput/ensantina.mp4, 1 fps, 10 frames, frameOutput/
 * The fps argument is a sampling rate, not the video's own rate -- ffmpeg drops or
 * duplicates frames to hit it, so "1" means roughly one frame per second of footage.
 */
public class TestingJaffree {

    private static final String DEFAULT_VIDEO = "sampleInput/ensantina.mp4";
    private static final double DEFAULT_FPS = 1.0;
    private static final long DEFAULT_MAX_FRAMES = 10;
    private static final String DEFAULT_OUT_DIR = "frameOutput";

    public static void main(String[] args) {
        Path video = Path.of(args.length > 0 ? args[0] : DEFAULT_VIDEO);
        double fps = args.length > 1 ? Double.parseDouble(args[1]) : DEFAULT_FPS;
        long maxFrames = args.length > 2 ? Long.parseLong(args[2]) : DEFAULT_MAX_FRAMES;
        Path outDir = Path.of(args.length > 3 ? args[3] : DEFAULT_OUT_DIR);

        if (!Files.isRegularFile(video)) {
            System.err.println("No such video file: " + video.toAbsolutePath());
            return;
        }

        try {
            printMetadata(video);
            System.out.println();
            extractFrames(video, fps, maxFrames, outDir);
        } catch (IOException e) {
            System.err.println("Could not write frames: " + e.getMessage());
        } catch (RuntimeException e) {
            // Jaffree wraps a missing binary and a failed ffmpeg run alike in JaffreeException.
            System.err.println("ffmpeg/ffprobe failed: " + e.getMessage());
            System.err.println("Check that both are installed and on your PATH (try: ffprobe -version).");
        }
    }

    /** Prints what ffprobe reports about the container and its video streams. */
    private static void printMetadata(Path video) {
        FFprobeResult probe = FFprobe.atPath()
                .setShowStreams(true)
                .setShowFormat(true)
                .setInput(video)
                .execute();

        Format format = probe.getFormat();
        System.out.println("=== container ===");
        System.out.println("File:      " + video);
        System.out.println("Container: " + format.getFormatName());
        System.out.println("Duration:  " + format.getDuration() + " s");
        System.out.println("Size:      " + format.getSize() + " bytes");
        System.out.println("Bitrate:   " + format.getBitRate() + " bps");

        for (com.github.kokorin.jaffree.ffprobe.Stream stream : probe.getStreams()) {
            if (stream.getCodecType() != StreamType.VIDEO) {
                continue;
            }
            System.out.println();
            System.out.println("=== video stream ===");
            System.out.println("Codec:      " + stream.getCodecName());
            System.out.println("Resolution: " + stream.getWidth() + "x" + stream.getHeight());
            System.out.println("Avg FPS:    " + stream.getAvgFrameRate().doubleValue());

            // Not every container stores a frame count; ffprobe returns null when it is absent.
            Integer frames = stream.getNbFrames();
            System.out.println("Frames:     " + (frames != null ? frames : "not reported"));
        }
    }

    /**
     * Decodes frames at the given sampling rate and writes the first maxFrames of them
     * to outDir as PNGs. Frames arrive on a reader thread via callback, which is the
     * push model Jaffree imposes -- there is no pull-a-frame-at-a-time API.
     */
    private static void extractFrames(Path video, double fps, long maxFrames, Path outDir)
            throws IOException {
        Files.createDirectories(outDir);

        System.out.println("=== frames ===");
        System.out.printf("Sampling %s at %.3f fps, up to %d frames -> %s%n",
                video, fps, maxFrames, outDir);

        FrameConsumer consumer = new FrameConsumer() {
            /** Ticks per second for the video stream, used to turn a PTS into a timestamp. */
            private long timebase = 1;
            private int count = 0;

            @Override
            public void consumeStreams(List<com.github.kokorin.jaffree.ffmpeg.Stream> streams) {
                for (com.github.kokorin.jaffree.ffmpeg.Stream stream : streams) {
                    if (stream.getType() == com.github.kokorin.jaffree.ffmpeg.Stream.Type.VIDEO) {
                        timebase = stream.getTimebase();
                        System.out.printf("Decoding stream %d: %dx%d, timebase 1/%d%n",
                                stream.getId(), stream.getWidth(), stream.getHeight(), timebase);
                    }
                }
            }

            @Override
            public void consume(Frame frame) {
                // Jaffree signals end of stream with a null frame.
                if (frame == null) {
                    System.out.println("End of stream after " + count + " frame(s).");
                    return;
                }

                BufferedImage image = frame.getImage();
                if (image == null) {
                    return; // an audio frame carries samples, not an image
                }

                count++;
                double seconds = (double) frame.getPts() / timebase;
                File out = outDir.resolve(String.format("frame_%03d.png", count)).toFile();
                try {
                    ImageIO.write(image, "png", out);
                } catch (IOException e) {
                    System.err.println("Could not write " + out + ": " + e.getMessage());
                    return;
                }
                System.out.printf("  frame %3d  pts=%-10d t=%7.3fs  %dx%d  type=%d  -> %s%n",
                        count, frame.getPts(), seconds,
                        image.getWidth(), image.getHeight(), image.getType(), out.getName());
            }
        };

        FFmpeg.atPath()
                .addInput(UrlInput.fromPath(video))
                .addOutput(FrameOutput.withConsumer(consumer)
                        .setFrameRate(fps)
                        .setFrameCount(StreamType.VIDEO, maxFrames)
                        .disableStream(StreamType.AUDIO))
                .execute();
    }
}
