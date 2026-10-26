import java.util.List;
import java.util.ArrayList;

public class DfsBinaryGroupFinder implements BinaryGroupFinder {
   /**
    * Finds connected pixel groups of 1s in an integer array representing a binary image.
    * 
    * The input is a non-empty rectangular 2D array containing only 1s and 0s.
    * If the array or any of its subarrays are null, a NullPointerException
    * is thrown. If the array is otherwise invalid, an IllegalArgumentException
    * is thrown.
    *
    * Pixels are considered connected vertically and horizontally, NOT diagonally.
    * The top-left cell of the array (row:0, column:0) is considered to be coordinate
    * (x:0, y:0). Y increases downward and X increases to the right. For example,
    * (row:4, column:7) corresponds to (x:7, y:4).
    *
    * The method returns a list of sorted groups. The group's size is the number 
    * of pixels in the group. The centroid of the group
    * is computed as the average of each of the pixel locations across each dimension.
    * For example, the x coordinate of the centroid is the sum of all the x
    * coordinates of the pixels in the group divided by the number of pixels in that group.
    * Similarly, the y coordinate of the centroid is the sum of all the y
    * coordinates of the pixels in the group divided by the number of pixels in that group.
    * The division should be done as INTEGER DIVISION.
    *
    * The groups are sorted in DESCENDING order according to Group's compareTo method.
    * 
    * @param image a rectangular 2D array containing only 1s and 0s
    * @return the found groups of connected pixels in descending order
    */

    private static final int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public List<Group> findConnectedGroups(int[][] image) {

        // Throw NullPointerException if null val provided
        if (image == null) throw new NullPointerException("Image cannot be null.");

        // Check for size
        if (image.length == 0 || image[0].length == 0) throw new IllegalArgumentException("Image cannot have size of zero.");

        // Store ROWS & COLS + start location + list to return
        int ROWS = image.length, COLS = image[0].length;

        // Check for jagged edges + invalid values
        for (int r = 0; r < image.length; r++) {
            if (image[r].length != image[0].length) throw new IllegalArgumentException("Image must be rectangular.");
            
            for (int c = 0; c < image[0].length; c++) {
                if (image[r][c] != 1 && image[r][c] != 0) throw new IllegalArgumentException("Image must contain only 1 and 0.");
            }
        }



        List<Group> groupList = new ArrayList<>();

        // Create visited matrix, call findGroupSize
        boolean[][] visited = new boolean[image.length][image[0].length];

        // Iterate through image searching for 1
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (image[r][c] == 1 && visited[r][c] == false) {

                    
                    int size = findGroupSize(image, visited, new Coordinate(c, r));
                    System.out.println(size);

                    // Check if size is zero (meaning has already been visited)
                    if (size == 0) continue;

                    // Find centroid with findGroupCentroid
                    Coordinate centroid = findGroupCentroid(image, size, new Coordinate(c, r));

                    // Initialize Group
                    Group newGroup = new Group(size, centroid);

                    // Add to list
                    groupList.add(newGroup);
                }
            }
        }
        
        // Sort list
        groupList.sort((a, b) -> b.compareTo(a));
        
        return groupList;
    }

    private int findGroupSize(int[][] image, boolean[][] visited, Coordinate current) {
        // Base case checks

        if (current.y() < 0 || current.x() < 0 || current.y() >= image.length || 
            current.x() >= image[0].length || visited[current.y()][current.x()] ||
            image[current.y()][current.x()] == 0)
            return 0;

            // Set visited
            visited[current.y()][current.x()] = true;

            // Define size
            int size = 1;

            // Iterate through directions, add to size
            for (int[] dir : directions) {
                size += findGroupSize(image, visited, new Coordinate(current.x() + dir[0], current.y() + dir[1]));
            }

            return size;
    }

    private Coordinate findGroupCentroid(int[][] image, int size, Coordinate current) {
        // Create visited matrix to store visited spots, call sumX helper
        boolean[][] visited = new boolean[image.length][image[0].length];
        int sumX = sumX(image, visited, current);

        // Re-initialize visited to clear it, call sumY
        visited = new boolean[image.length][image[0].length];
        int sumY = sumY(image, visited, current);

        // Create centroid Coordinate
        Coordinate centroid = new Coordinate(sumX / size, sumY / size);

        return centroid;
    }

    private int sumX(int[][] image, boolean[][] visited, Coordinate current) {
        // Base case checks
        if (current.y() < 0 || current.x() < 0 || current.y() >= image.length || 
            current.x() >= image[0].length || visited[current.y()][current.x()] || image[current.y()][current.x()] == 0)
            return 0;

        // Update visited
        visited[current.y()][current.x()] = true;

        // Initialize sum
        int sum = current.x();

        // Check other values
        for (int[] dir : directions) {
                sum += sumX(image, visited, new Coordinate(current.x() + dir[0], current.y() + dir[1]));
            }

        return sum;
    }

    private int sumY(int[][] image, boolean[][] visited, Coordinate current) {
        // Base case checks
        if (current.y() < 0 || current.x() < 0 || current.y() >= image.length || 
            current.x() >= image[0].length || visited[current.y()][current.x()] || image[current.y()][current.x()] == 0)
            return 0;

        // Update visited
        visited[current.y()][current.x()] = true;

        // Make sum = Y
        int sum = current.y();

        // Sum from rest of coordinates
        for (int[] dir : directions) {
                sum += sumY(image, visited, new Coordinate(current.x() + dir[0], current.y() + dir[1]));
        }

        return sum;
    }
}
