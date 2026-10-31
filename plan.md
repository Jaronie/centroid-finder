Plan for implementing JavaCV

- We decided to go on with JavaCV after noticing how it works with extracting video metadata, frames, etc.

Since JavaCV can properly extract frames into images I think the most direct approach is possibly these classes:
- Class that extracts frames
- Class that validates frames
- Class that performs logic to find the biggest centroid, closest group of binary that is within range of the threshold
- Class that writes to CSV

The frame extraction class would most likely contain the JavaCV implementation where similarly in our javacv-experiment branch, have the logic that takes a video and extracts images. I think this class could also call methods from:
- DistanceImageBinarizer
- FrameValidator

The frame validation class will take in frames in the form of images and validates it by:
- ensuring the image isn't null, 
- frame dimensions are consistent
- possibly keep track of video length (might not need this)

The class that performs the logic to find the centroid we're looking for could most likely reuse the logic and methods from:
- DfsBinaryGroupFinder
- EuclideanColorDistance

The last class is responsible for writing out the CSV in the three columns:
seconds / x / y /
It'll be similar to ImageSummaryApp, but I think different enough to be written as its own file - also allows for modular use in the program for images and videos

For centroid recognition, like finding the biggest centroids between multiple in the color threshold - I think the thumbs war approach where we test two centroids at a time and in comparison the biggest one is the chosen centroid to track. My only question: How would this work for videos that contain centroids for growing sizes? 

