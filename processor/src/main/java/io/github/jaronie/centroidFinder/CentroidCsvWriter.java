package io.github.jaronie.centroidFinder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes "seconds,x,y" rows. Use (-1, -1) for frames with no centroid.
 */
public class CentroidCsvWriter implements AutoCloseable {
    public static final int NO_CENTROID = -1;

    private final BufferedWriter out;

    public CentroidCsvWriter(Path path, boolean writeHeader) throws IOException {
        this.out = Files.newBufferedWriter(path);
        if (writeHeader) {
            out.write("seconds,x,y");
            out.newLine();
        }
    }

    public void write(double seconds, int x, int y) throws IOException {
        out.write(String.format(Locale.ROOT, "%.3f,%d,%d", seconds, x, y));
        out.newLine();
    }

    public void writeMissing(double seconds) throws IOException {
        write(seconds, NO_CENTROID, NO_CENTROID);
    }

    @Override
    public void close() throws IOException {
        out.close(); // flushes
    }
}