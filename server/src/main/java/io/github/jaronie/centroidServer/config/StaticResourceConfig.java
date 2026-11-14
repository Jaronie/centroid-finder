package io.github.jaronie.centroidServer.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves two real directories over HTTP. This is the direct counterpart of
 * express.static():
 *
 * <pre>
 *   app.use('/videos',  express.static(videosDir));
 *   app.use('/results', express.static(resultsDir));
 * </pre>
 *
 * <p>Implementing WebMvcConfigurer is how you adjust Spring MVC's defaults
 * without replacing them. Spring finds every bean of this type at startup and
 * calls these hook methods, so adding another override later (CORS, for your
 * front-end, is the likely next one) does not disturb anything already here.
 */
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public StaticResourceConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/videos/**")
                .addResourceLocations(location(props.videosDir()));

        registry.addResourceHandler("/results/**")
                .addResourceLocations(location(props.resultsDir()));
    }

    /**
     * Turns a directory into the "file:/abs/path/" form Spring expects.
     *
     * <p>The trailing slash is mandatory. Without it Spring concatenates the
     * requested filename straight onto the directory name and every request
     * 404s, with nothing in the log to explain why.
     */
    private String location(Path dir) {
        Path absolute = dir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(absolute);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create directory " + absolute, e);
        }
        return absolute.toUri().toString().endsWith("/")
                ? absolute.toUri().toString()
                : absolute.toUri() + "/";
    }
}
