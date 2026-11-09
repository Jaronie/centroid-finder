package io.github.jaronie.centroidServer.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every "app.*" value from application.properties, bound into one typed object.
 *
 * <p>The Express equivalent is a config module that reads process.env once and
 * exports a frozen object, instead of scattering process.env lookups through
 * the codebase.
 *
 * <p>Spring maps the kebab-case keys onto these camelCase components
 * automatically: "app.videos-dir" becomes videosDir. It also converts the text
 * into a Path, so nothing downstream handles raw strings.
 *
 * <p>jarPath is the exception, and it has to be a String. See jarFile() below.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Path videosDir,
        String jarPath,
        Path resultsDir,
        Path thumbnailCacheDir,
        Path logsDir,
        String ffmpegPath) {

    /**
     * The processor jar as an absolute, normalized Path.
     *
     * <p>Resolved here by hand rather than bound directly as a Path, because
     * the configured value starts with "../", which Spring's built-in
     * String-to-Path converter refuses to accept.
     */
    public Path jarFile() {
        return Path.of(jarPath).toAbsolutePath().normalize();
    }
}
