package io.github.jaronie.centroidFinder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Usage: java VideoSummaryApp &lt;input_video&gt; &lt;hex_target_color&gt; &lt;threshold&gt; [output_csv]
 *                            [--inset=N | --crop=X,Y,W,H] [--scale=F]
 *
 * The optional crop restricts detection to part of each frame. It is worth
 * reaching for when something fixed in the frame -- a dark border, a shadowed
 * edge, letterboxing -- matches the target color and out-sizes the subject,
 * since the pipeline always reports the largest group. Centroids in the CSV
 * stay in full-frame coordinates either way.
 *
 * --scale=F decodes frames at a fraction of the source size, which is the
 * single most effective way to speed up a long video: every stage after the
 * decoder costs time per pixel, so --scale=0.5 quarters the pixel count. On
 * the sample video it took a full run from 30s to 14s, and --scale=0.25 to 8s.
 * The CSV still reports full-frame coordinates.
 *
 * The cost is accuracy, and not evenly. Median error against a full-size run
 * was about 2 px at --scale=0.5, which is the rounding one would expect. But
 * in roughly 3% of frames the reported centroid jumped by over 100 px, because
 * shrinking the frame changes each group's pixel count slightly and that can
 * flip which group is the largest -- so the pipeline starts reporting a
 * different object. Scaling does not introduce that fragility, it only
 * provokes it. Prefer a crop over an aggressive scale when the frame has more
 * than one thing matching the target color.
 *
 * Decoded widths snap down to a multiple of 64 (an FFmpeg row-padding
 * constraint), so the effective scale can be slightly smaller than requested;
 * the app prints what it actually used. Both crop options take coordinates and
 * insets in source pixels, so a crop does not have to be recomputed when the
 * scale changes.
 */
public class VideoSummaryApp {

    private static final String USAGE =
            "Usage: java VideoSummaryApp <input_video> <hex_target_color> <threshold> [output_csv] "
            + "[--inset=N | --crop=X,Y,W,H] [--scale=F]";

    /** A parsed crop request: either a symmetric inset or an explicit rectangle. */
    private record CropOption(Integer inset, int[] rect) {}

    public static void main(String[] args) {
        // Split flags out of the positional arguments so the crop option can go
        // anywhere on the command line and old invocations keep working.
        List<String> positional = new ArrayList<>();
        CropOption crop = null;
        double scale = 1.0;

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
            } else if (arg.startsWith("--scale=")) {
                String value = arg.substring(arg.indexOf('=') + 1).trim();
                try {
                    scale = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    System.err.println("--scale must be a number greater than 0 and at most 1, "
                            + "for example --scale=0.5");
                    return;
                }
                if (!(scale > 0.0) || scale > 1.0 || Double.isNaN(scale)) {
                    System.err.println("--scale must be greater than 0 and at most 1, but was " + value);
                    return;
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

        try (FrameExtractor extractor = new FrameExtractor(positional.get(0), scale)) {

            if (extractor.isScaled()) {
                // The effective scale, not the requested one: widths snap down
                // to a multiple of 64, so --scale=0.75 on an 816-wide video
                // actually decodes at 576, a scale of 0.706.
                System.out.printf(
                        "Decoding at %dx%d instead of %dx%d (effective scale %.3f x %.3f): "
                        + "%.0f%% of the pixels.%n",
                        extractor.width(), extractor.height(),
                        extractor.nativeWidth(), extractor.nativeHeight(),
                        extractor.scaleX(), extractor.scaleY(),
                        100.0 * extractor.scaleX() * extractor.scaleY());
            } else if (scale < 1.0) {
                System.out.printf(
                        "Requested scale %.3f could not be applied to a %dx%d video; "
                        + "decoding at full size.%n",
                        scale, extractor.nativeWidth(), extractor.nativeHeight());
            }

            if (crop != null) {
                // The frame size is only known once the video is open, which is
                // what an inset needs to resolve into a rectangle. This runs
                // before the CSV is opened so a bad crop leaves no stray file.
                //
                // The crop is given in source pixels, but the finder underneath
                // sees scaled frames, so the rectangle is scaled to match.
                CroppingImageGroupFinder cropping = applyCrop(groupFinder,
                        scaleCrop(crop, extractor.scaleX(), extractor.scaleY()),
                        extractor.width(), extractor.height());
                System.out.printf("Searching %dx%d region at (%d,%d) of each %dx%d frame.%n",
                        cropping.width(), cropping.height(), cropping.x(), cropping.y(),
                        extractor.width(), extractor.height());
                groupFinder = cropping;
            }

            // Outermost, so centroids are mapped back to source coordinates
            // after any cropping has already been undone. A no-op at scale 1.
            if (extractor.isScaled()) {
                groupFinder = new ScalingImageGroupFinder(
                        groupFinder, extractor.scaleX(), extractor.scaleY());
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

    /**
     * Converts a crop request from source pixels into the scaled frame's
     * pixels, so --crop and --inset mean the same region whatever --scale is.
     *
     * <p>Width and height round up and the origin rounds down, so the scaled
     * rectangle never falls short of the region that was asked for: losing a
     * boundary pixel could clip the subject, while including one extra cannot.
     * CroppingImageGroupFinder clamps anything that overruns the frame.
     */
    private static CropOption scaleCrop(CropOption crop, double scaleX, double scaleY) {
        if (scaleX == 1.0 && scaleY == 1.0) {
            return crop;
        }
        if (crop.inset() != null) {
            // An inset is applied to both axes, so it can only use one scale.
            // They differ only by pixel rounding; the smaller keeps the
            // searched region from shrinking.
            double scale = Math.min(scaleX, scaleY);
            return new CropOption((int) Math.floor(crop.inset() * scale), null);
        }
        int[] r = crop.rect();
        return new CropOption(null, new int[] {
                (int) Math.floor(r[0] * scaleX),
                (int) Math.floor(r[1] * scaleY),
                Math.max(1, (int) Math.ceil(r[2] * scaleX)),
                Math.max(1, (int) Math.ceil(r[3] * scaleY)) });
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
