package io.github.jaronie.centroidServer.web;

import java.nio.file.Path;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import io.github.jaronie.centroidServer.config.AppProperties;

/**
 * Supplies an AppProperties to the @WebMvcTest slice.
 *
 * <p>Needed because StaticResourceConfig implements WebMvcConfigurer, so
 * @WebMvcTest includes it, but the slice does not load @ConfigurationProperties
 * beans -- leaving StaticResourceConfig with an unsatisfiable dependency.
 *
 * <p>The directories point under target/ so the handlers have somewhere real to
 * resolve against without touching the project's actual data directory.
 */
@TestConfiguration
class WebSliceTestConfig {

    @Bean
    AppProperties appProperties() {
        Path base = Path.of("target", "web-slice-test");
        return new AppProperties(
                base.resolve("videos"),
                base.resolve("fake.jar").toString(),
                base.resolve("results"),
                base.resolve("thumbnails"),
                base.resolve("logs"),
                "ffmpeg");
    }
}
