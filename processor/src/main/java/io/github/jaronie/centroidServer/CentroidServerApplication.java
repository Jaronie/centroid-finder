package io.github.jaronie.centroidServer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the centroidFinder HTTP API.
 *
 * <p>{@code @SpringBootApplication} does three things: marks this as a config
 * class, turns on auto-configuration (which is what starts the embedded Tomcat),
 * and switches on component scanning. That scan covers this class's own package
 * and every package beneath it -- which is why the controllers and services go
 * in sub-packages of {@code io.github.jaronie.centroidServer}, not beside it.
 */
@SpringBootApplication
public class CentroidServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CentroidServerApplication.class, args);
    }
}
