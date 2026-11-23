[CentroidCsvWriterTests.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/test/java/io/github/jaronie/centroidFinder/CentroidCsvWriterTests.java) is effectively empty. Cover headers, missing directories, overwrite behavior, rounding, I/O failures, and close/flush behavior.
- Add end-to-end tests combining:
- Real frame extraction.
- Real binarization.
- Group detection.
- Cropping.
- CSV output.

Fix the null-frame path in [VideoPipeline.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/VideoPipeline.java). It currently validates the frame as invalid but dereferences it afterward. Your test explicitly documents this as a known bug.

Add input validation throughout the core algorithms:
- Empty or ragged binary arrays in [DfsBinaryGroupFinder.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/DfsBinaryGroupFinder.java).
- Null images and malformed target/threshold values in [DistanceImageBinarizer.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/DistanceImageBinarizer.java).
- Ragged or empty arrays in toBufferedImage.

Consider frame sampling or adaptive processing rather than analyzing every frame, while still writing exactly one CSV row per frame.

Centralize logging rather than mixing println, System.err, and stack traces.

(/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/CentroidCsvWriter.java) should handle missing parent directories or create them deliberately, rather than leaving that policy implicit.

In [EuclideanColorDistance.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/EuclideanColorDistance.java), comparing squared distances would remove the per-pixel square root, provided threshold behavior remains explicitly documented.

Add an automated test baseline for the server. The server source files are not present in the visible workspace snapshot, only compiled artifacts and test reports, so server code coverage cannot currently be reviewed reliably.

Add tests for malformed CLI arguments and invalid video/image files in [VideoSummaryApp.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/VideoSummaryApp.java) and [ImageSummaryApp.java](/home/seadrah/Documents/SDEV 334/centroidFinder/src/main/java/io/github/jaronie/centroidFinder/ImageSummaryApp.java).

The system only selects the largest connected component. That can misidentify borders, shadows, letterboxing, or another bright/dark object. The crop feature helps, but tracking should also consider:
- Maximum component size.
- Minimum component size.
- Temporal continuity with the previous centroid.
- Rejection of implausible jumps.