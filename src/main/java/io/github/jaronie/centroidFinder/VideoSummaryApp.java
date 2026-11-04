package io.github.jaronie.centroidFinder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage: java VideoSummaryApp &lt;input_video&gt; &lt;hex_target_color&gt; &lt;threshold&gt; [output_csv]
 *                            [--inset=N | --crop=X,Y,W,H]
 *
 * The optional crop restricts detection to part of each frame. It is worth
 * reaching for when something fixed in the frame -- a dark border, a shadowed
 * edge, letterboxing -- matches the target color and out-sizes the subject,
 * since the pipeline always reports the largest group. Centroids in the CSV
 * stay in full-frame coordinates either way.
 */
public class VideoSummaryApp {

    private static final String USAGE =
            "Usage: java VideoSummaryApp <input_video> <hex_target_color> <threshold> [output_csv] "
            + "[--inset=N | --crop=X,Y,W,H]";

    /** A parsed crop request: either a symmetric inset or an explicit rectangle. */
    private record CropOption(Integer inset, int[] rect) {}

    public static void main(String[] args) {
        // Split flags out of the positional arguments so the crop option can go
        // anywhere on the command line and old invocations keep working.
        List<String> positional = new ArrayList<>();
        CropOption crop = null;

        for (String arg : args) {
            if (arg.startsWith("--inset=") || arg.startsWith("--crop=")) {
                if (crop != null) {
                    System.err.println("Specify at most one of --inset or --crop.");
                    return;
                }
                crop = parseCrop(arg);
                if (crop == null) {
                    return; // parseCrop already explained the problem
                }
            } else if (arg.startsWith("--")) {
                System.err.println("Unknown option: " + arg);
                System.out.println(USAGE);
                return;
            } else {
                positional.add(arg);
            }
        }

        if (positional.size() < 3) {
            System.out.println(USAGE);
            return;
        }

        int threshold;
        int targetColor;
        try {
            threshold = Integer.parseInt(positional.get(2));
        } catch (NumberFormatException e) {
            System.err.println("Threshold must be an integer.");
            return;
        }
        try {
            targetColor = Integer.parseInt(positional.get(1), 16);
        } catch (NumberFormatException e) {
            System.err.println("Invalid hex target color. Please provide a color in RRGGBB format.");
            return;
        }
        String csvPath = positional.size() > 3 ? positional.get(3) : "centroids.csv";

        // Same objects as the image app: nothing video-specific in the detection logic.
        ColorDistanceFinder distanceFinder = new EuclideanColorDistance();
        ImageBinarizer binarizer = new DistanceImageBinarizer(distanceFinder, targetColor, threshold);
        ImageGroupFinder groupFinder = new BinarizingImageGroupFinder(binarizer, new DfsBinaryGroupFinder());

        try (FrameExtractor extractor = new FrameExtractor(positional.get(0))) {

            if (crop != null) {
                // The frame size is only known once the video is open, which is
                // what an inset needs to resolve into a rectangle. This runs
                // before the CSV is opened so a bad crop leaves no stray file.
                CroppingImageGroupFinder cropping = applyCrop(groupFinder, crop,
                        extractor.width(), extractor.height());
                System.out.printf("Searching %dx%d region at (%d,%d) of each %dx%d frame.%n",
                        cropping.width(), cropping.height(), cropping.x(), cropping.y(),
                        extractor.width(), extractor.height());
                groupFinder = cropping;
            }

            try (CentroidCsvWriter csv = new CentroidCsvWriter(Path.of(csvPath), false)) {
                // The validator still checks the FULL frame size: cropping narrows
                // where groups are searched for, not what counts as a usable frame.
                FrameValidator validator = new FrameValidator(extractor.width(), extractor.height());
                VideoPipeline pipeline = new VideoPipeline(groupFinder, validator, csv);
                VideoPipeline.Summary s = pipeline.run(extractor);

                System.out.printf("Done: %d frames, %d invalid, %d without a group. Saved %s%n",
                        s.frames(), s.invalidFrames(), s.framesWithoutGroup(), csvPath);
            }
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
        } catch (Exception e) {
            System.err.println("Error processing video: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** Parses --inset=N or --crop=X,Y,W,H. Returns null (after reporting) if malformed. */
    private static CropOption parseCrop(String arg) {
        String value = arg.substring(arg.indexOf('=') + 1).trim();

        if (arg.startsWith("--inset=")) {
            try {
                return new CropOption(Integer.parseInt(value), null);
            } catch (NumberFormatException e) {
                System.err.println("--inset must be a whole number of pixels, for example --inset=40");
                return null;
            }
        }

        String[] parts = value.split(",");
        if (parts.length != 4) {
            System.err.println("--crop takes four comma-separated numbers: --crop=X,Y,WIDTH,HEIGHT");
            return null;
        }
        int[] rect = new int[4];
        for (int i = 0; i < 4; i++) {
            try {
                rect[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                System.err.println("--crop values must be whole numbers: --crop=X,Y,WIDTH,HEIGHT");
                return null;
            }
        }
        return new CropOption(null, rect);
    }

    /** Builds the cropping finder once the frame size is known. */
    private static CroppingImageGroupFinder applyCrop(ImageGroupFinder delegate, CropOption crop,
                                                      int frameWidth, int frameHeight) {
        if (crop.inset() != null) {
            return CroppingImageGroupFinder.fromInset(delegate, crop.inset(), frameWidth, frameHeight);
        }
        int[] r = crop.rect();
        return new CroppingImageGroupFinder(delegate, r[0], r[1], r[2], r[3]);
    }
}
