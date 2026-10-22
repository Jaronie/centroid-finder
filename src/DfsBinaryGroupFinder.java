import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

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

        // Store ROWS & COLS + start location + list to  + copy of image to pass in
        int ROWS = image.length, COLS = image[0].length;
        List<Group> groupList = new ArrayList<>();


        // 

        // Iterate through image searching for 1
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (image[r][c] == 1) {

                    boolean[][] visited = new boolean[image.length][image[0].length];
                    int size = findGroupSize(image, visited, new Coordinate(r, c));

                    Coordinate centroid = findGroupCentroid(image, size, new Coordinate(r, c));

                    Group newGroup = new Group(size, centroid);

                    groupList.add(newGroup);
                }
            }
        }

        return groupList;
    }

    public int findGroupSize(int[][] image, boolean[][] visited, Coordinate current) {
        if (current.y() < 0 || current.x() < 0 || current.y() >= image.length || 
            current.x() >= image[0].length || visited[current.y()][current.x()] )
            return 0;

            visited[current.y()][current.x()] = true;

            int size = 1;

            for (int[] dir : directions) {
                size += findGroupSize(image, visited, new Coordinate(current.y() + dir[0], current.x() + dir[1]));
            }

            return size;
    }

    public Coordinate findGroupCentroid(int[][] image, int size, Coordinate current) {
        boolean[][] visited = new boolean[image.length][image[0].length];
        int sumX = sumX(image, visited, current);

        visited = new boolean[image.length][image[0].length];
        int sumY = sumY(image, visited, current);

        Coordinate centroid = new Coordinate(sumX / size, sumY / size);

        return centroid;
    }

    public int sumX(int[][] image, boolean[][] visited, Coordinate current) {
        if (current.y() < 0 || current.x() < 0 || current.y() >= image.length || 
            current.x() >= image[0].length || visited[current.y()][current.x()] )
            return 0;

        visited[current.y()][current.x()] = true;

        int sum = current.x();

        for (int[] dir : directions) {
                sum += sumX(image, visited, new Coordinate(current.y() + dir[0], current.x() + dir[1]));
            }

        return sum;
    }

    public int sumY(int[][] image, boolean[][] visited, Coordinate current) {
        if (current.y() < 0 || current.x() < 0 || current.y() >= image.length || 
            current.x() >= image[0].length || visited[current.y()][current.x()] )
            return 0;

        visited[current.y()][current.x()] = true;

        int sum = current.y();

        for (int[] dir : directions) {
                sum += findGroupSize(image, visited, new Coordinate(current.y() + dir[0], current.x() + dir[1]));
            }

        return sum;
    }


    
}
