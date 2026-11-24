package io.github.jaronie.centroidFinder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the CSV format written to temporary files, including the optional
 * header, timestamp precision, coordinates, and missing-centroid sentinel.
 * Closing the writer before reading also checks that buffered output is flushed.
 */
public class CentroidCsvWriterTests {
    @TempDir
    Path tempDir;

    @Test
    public void writesHeaderAndFormattedCoordinates() throws IOException {
        Path csvPath = tempDir.resolve("centroids.csv");
        try (CentroidCsvWriter csv = new CentroidCsvWriter(csvPath, true)) {
            csv.write(1.23456, 12, 34);
        }

        assertEquals(List.of("seconds,x,y", "1.235,12,34"), Files.readAllLines(csvPath));
    }

    @Test
    public void writesMissingCentroidWithSentinelCoordinates() throws IOException {
        Path csvPath = tempDir.resolve("missing.csv");
        try (CentroidCsvWriter csv = new CentroidCsvWriter(csvPath, false)) {
            csv.writeMissing(0.5);
        }

        assertEquals(List.of("0.500,-1,-1"), Files.readAllLines(csvPath));
    }
}
