package de.seuhd.campuscoffee.data.client;

import feign.RequestInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for OSM Feign client.
 */
@Configuration
public class OsmFeignClientConfig {
    /**
     * Adds User-Agent header to all OSM API requests. The version comes from the build's
     * {@link BuildProperties}, falling back to {@code dev} when the build-info resource is absent
     * (e.g., running from an IDE without the Maven build).
     *
     * @param buildProperties the build metadata, when available
     * @return RequestInterceptor that adds the User-Agent header
     */
    @Bean
    public RequestInterceptor userAgentInterceptor(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        String userAgent = "CampusCoffee/" + version;
        return requestTemplate ->
            requestTemplate.header("User-Agent", userAgent);
    }
}
