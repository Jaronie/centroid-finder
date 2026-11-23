Findings in Centroid Finder Logic  -
* Refactoring Code
- The class that handles cropping (CroppingImageGroupFinderTests) could be repurposed into the pipeline ImageSummaryApp class, similar to the VideoPipeline class. 
- For app output, thumbnails are currently out of the sampleOutput/ directory, so we could possibly factor in the code to put thumbnail images into that specific folder rather outside of everything else(?)
- JavaDocs could possibly help us understand our code better, too!
- Making it only find centroid every few frames as opposed to every frame to speed up load time
-
* Tests
- Files such as the DistanceImageBinarizerTest and EuclideanColorDistanceTests contain unused imports. It's most likely safe to remove them since tests create their own instance of each class.
- We could possibly improve on adding some more tests to our video logic.
-
* Improving error handling
- So far, error handling in the image branch seems well-written. But to refactor code I think it could work more efficient if given its own class file to implement such as the ApiException class in our server branch.
- 
* Features / Improving performance / Hardening security
- Image extentions: With BufferedImage, it doesn't matter what image extension (.png, .jpg...) we use. Though, for image rendering and extracting the binarized image, we could probably consider multiple image formats just for the sake of feature adding to the app overall.
- Aesthetic: Improving appearance of pages
- New feature for future -- dropper functionality to change color
- Make loading screen remove ability to continue submitting requests while it loads