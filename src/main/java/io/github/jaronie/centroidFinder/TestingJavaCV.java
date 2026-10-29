package io.github.jaronie.centroidFinder;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A scratch harness for evaluating JavaCV as the video front-end for centroidFinder.
 *
 * It does two things against a real video file:
 *
 * 1. Opens the file and prints the metadata we would need in order to size a run:
 *    duration, resolution, frame rate and frame count.
 * 2. Pulls decoded frames out as BufferedImages, which is the handoff point to the
 *    existing {@link ImageBinarizer} / {@link ImageGroupFinder} pipeline, and writes
 *    them to disk as PNGs so the decode can be eyeballed.
 *
 * Unlike Jaffree, JavaCV bundles its own ffmpeg as a native library, so there is
 * nothing to install and no subprocess -- decoding happens inside this JVM.
 *
 * Usage:
 *   java TestingJavaCV [video] [fps] [maxFrames] [outputDir]
 *
 * Defaults: sampleInput/ensantina.mp4, 1 fps, 10 frames, frameOutput/
 * JavaCV has no server-side frame rate filter, so the fps argument is converted into
 * a "keep every Nth frame" step. Every frame is still decoded to get there; the step
 * only decides which ones are converted to images and saved.
 */
public class TestingJavaCV {

    private static final String DEFAULT_VIDEO = "sampleInput/ensantina.mp4";
    private static final double DEFAULT_FPS = 1.0;
    private static final int DEFAULT_MAX_FRAMES = 10;
    private static final String DEFAULT_OUT_DIR = "frameOutput";

    public static void main(String[] args) {
        File video = new File(args.length > 0 ? args[0] : DEFAULT_VIDEO);
        double fps = args.length > 1 ? Double.parseDouble(args[1]) : DEFAULT_FPS;
        int maxFrames = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MAX_FRAMES;
        Path outDir = Path.of(args.length > 3 ? args[3] : DEFAULT_OUT_DIR);

        if (!video.isFile()) {
            System.err.println("No such video file: " + video.getAbsolutePath());
            return;
        }

        try {
            printMetadata(video);
            System.out.println();
            extractFrames(video, fps, maxFrames, outDir);
        } catch (Exception e) {
            System.err.println("Could not read " + video + ": " + e.getMessage());
        }
    }

    /** Prints what the grabber reports about the container and its streams. */
    private static void printMetadata(File video) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video)) {
            grabber.start();

            System.out.println("=== container ===");
            System.out.println("File:      " + video);
            System.out.println("Container: " + grabber.getFormat());
            System.out.println("Duration:  " + grabber.getLengthInTime() / 1_000_000.0 + " s");
            System.out.println("Size:      " + video.length() + " bytes");

            System.out.println();
            System.out.println("=== video stream ===");
            System.out.println("Codec:      " + grabber.getVideoCodecName());
            System.out.println("Resolution: " + grabber.getImageWidth() + "x" + grabber.getImageHeight());
            System.out.println("Avg FPS:    " + grabber.getFrameRate());
            System.out.println("Frames:     " + grabber.getLengthInVideoFrames());
            System.out.println("Bitrate:    " + grabber.getVideoBitrate() + " bps");

            System.out.println();
            System.out.println("=== audio stream ===");
            if (grabber.getAudioChannels() > 0) {
                System.out.println("Codec:      " + grabber.getAudioCodecName());
                System.out.println("Channels:   " + grabber.getAudioChannels());
                System.out.println("Sample rate:" + grabber.getSampleRate() + " Hz");
            } else {
                System.out.println("none");
            }

            grabber.stop();
        }
    }

    /**
     * Decodes frames and writes every Nth one to outDir as PNGs, stopping once
     * maxFrames have been saved.
     */
    private static void extractFrames(File video, double fps, int maxFrames, Path outDir)
            throws Exception {
        Files.createDirectories(outDir);

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(video);
             Java2DFrameConverter converter = new Java2DFrameConverter()) {
            grabber.start();

            // Turn a target frame rate into a "keep every Nth frame" step.
            int step = Math.max(1, (int) Math.round(grabber.getFrameRate() / fps));

            System.out.println("=== frames ===");
            System.out.printf("Sampling %s at ~%.3f fps (every %d frames), up to %d frames -> %s%n",
                    video, fps, step, maxFrames, outDir);

            int saved = 0;
            int index = 0;
            Frame frame;
            while (saved < maxFrames && (frame = grabber.grabImage()) != null) {
                if (index % step == 0) {
                    // The converter reuses one image buffer between calls, so this
                    // BufferedImage is only valid until the next grabImage(). Writing it
                    // out here is safe; keeping a reference to it would not be.
                    BufferedImage image = converter.getBufferedImage(frame);

                    saved++;
                    File out = outDir.resolve(String.format("frame_%03d.png", saved)).toFile();
                    ImageIO.write(image, "png", out);

                    System.out.printf("  frame %3d  srcIndex=%-6d t=%7.3fs  %dx%d  type=%d  -> %s%n",
                            saved, index, frame.timestamp / 1_000_000.0,
                            image.getWidth(), image.getHeight(), image.getType(), out.getName());
                }
                index++;
            }

            System.out.println("Saved " + saved + " frame(s) after decoding " + index + ".");
            grabber.stop();
        }
    }
}
