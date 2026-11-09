package io.github.jaronie.centroidFinder;

import java.io.IOException;
import java.util.List;

/**
 * For every frame: validate -> find groups (existing image logic) -> write the
 * largest group's centroid, or -1,-1 if the frame is invalid or has no group.
 * Always writes exactly one row per frame so the time axis stays continuous.
 */
public class VideoPipeline {
    public record Summary(int frames, int invalidFrames, int framesWithoutGroup) {}

    private final ImageGroupFinder groupFinder;
    private final FrameValidator validator;
    private final CentroidCsvWriter csv;

    public VideoPipeline(ImageGroupFinder groupFinder, FrameValidator validator, CentroidCsvWriter csv) {
        this.groupFinder = groupFinder;
        this.validator = validator;
        this.csv = csv;
    }

    public Summary run(Iterable<VideoFrame> frames) throws IOException {
        int total = 0, invalid = 0, noGroup = 0;

        for (VideoFrame frame : frames) {
            total++;

            FrameValidator.ValidationResult check = validator.validate(frame);
            if (!check.valid()) {
                invalid++;
                System.err.println("Frame " + frame.index() + " skipped: " + check.reason());
                csv.writeMissing(frame.seconds());
                continue;
            }

            List<Group> groups = groupFinder.findConnectedGroups(frame.image());
            if (groups.isEmpty()) {
                noGroup++;
                csv.writeMissing(frame.seconds());
            } else {
                Coordinate c = groups.get(0).centroid(); // already sorted largest-first
                csv.write(frame.seconds(), c.x(), c.y());
            }
        }
        return new Summary(total, invalid, noGroup);
    }
}