package io.github.jaronie.centroidFinder;

 
import java.awt.image.BufferedImage;


public record VideoFrame(int index, double seconds, BufferedImage image) {
    
}
