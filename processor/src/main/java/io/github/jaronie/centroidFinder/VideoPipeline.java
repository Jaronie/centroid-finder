package io.github.jaronie.centroidFinder;

import java.io.IOException;
import java.util.List;

/**
 * For every frame: validate -> find groups (existing image logic) -> write the
 * largest group's centroid, or -1,-1 if the frame is invalid or has no group.
 * Always writes exactly one row per frame so the time axis stays continuous.
 *
 * <p>The single exception is a null element in the stream. It is counted as an
 * invalid frame and logged, but produces no row, because a row needs a
 * timestamp and a null frame has none. A real {@link FrameExtractor} never
 * yields null, so this only arises for a hand-built stream.
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

                // A null frame is the one invalid frame that cannot produce a
                // row: index() and seconds() are what a row is built from, and
                // dereferencing the frame we just rejected is precisely what
                // used to throw here. Counting and logging it is all that can
                // honestly be done -- inventing a timestamp would put a bogus
                // point on the time axis, which is worse than omitting it.
                if (frame == null) {
                    System.err.println("Frame skipped: " + check.reason());
                    continue;
                }

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