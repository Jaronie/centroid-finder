package io.github.jaronie.centroidFinder;

import java.nio.file.Path;

/**
 * Usage: java VideoSummaryApp <input_video> <hex_target_color> <threshold> [output_csv]
 */
public class VideoSummaryApp {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java VideoSummaryApp <input_video> <hex_target_color> <threshold> [output_csv]");
            return;
        }

        int threshold;
        int targetColor;
        try {
            threshold = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            System.err.println("Threshold must be an integer.");
            return;
        }
        try {
            targetColor = Integer.parseInt(args[1], 16);
        } catch (NumberFormatException e) {
            System.err.println("Invalid hex target color. Please provide a color in RRGGBB format.");
            return;
        }
        String csvPath = args.length > 3 ? args[3] : "centroids.csv";

        // Same objects as the image app: nothing video-specific in the detection logic.
        ColorDistanceFinder distanceFinder = new EuclideanColorDistance();
        ImageBinarizer binarizer = new DistanceImageBinarizer(distanceFinder, targetColor, threshold);
        ImageGroupFinder groupFinder = new BinarizingImageGroupFinder(binarizer, new DfsBinaryGroupFinder());

        try (FrameExtractor extractor = new FrameExtractor(args[0]);
             CentroidCsvWriter csv = new CentroidCsvWriter(Path.of(csvPath), false)) {

            FrameValidator validator = new FrameValidator(extractor.width(), extractor.height());
            VideoPipeline pipeline = new VideoPipeline(groupFinder, validator, csv);
            VideoPipeline.Summary s = pipeline.run(extractor);

            System.out.printf("Done: %d frames, %d invalid, %d without a group. Saved %s%n",
                    s.frames(), s.invalidFrames(), s.framesWithoutGroup(), csvPath);
        } catch (Exception e) {
            System.err.println("Error processing video: " + e.getMessage());
            e.printStackTrace();
        }
    }
}