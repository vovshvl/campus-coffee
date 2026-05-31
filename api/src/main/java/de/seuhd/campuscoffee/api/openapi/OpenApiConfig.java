package de.seuhd.campuscoffee.api.openapi;

import de.seuhd.campuscoffee.api.exceptions.ErrorResponse;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI/Swagger configuration for the CampusCoffee API.
 * Defines global API metadata such as title, version, and description. The version is set at runtime
 * from the build (see {@link #apiVersionCustomizer}) rather than hard-coded in the annotation.
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "CampusCoffee API",
                description = "REST API for managing points of sale (cafes and coffee shops) on campus, "
                        + "their users, and reviews."
        )
)
public class OpenApiConfig {
    /**
     * Sets the OpenAPI document version from the build's {@link BuildProperties}, falling back to
     * {@code dev} when the build-info resource is absent. Keeps the version in one place: the pom.
     *
     * @param buildProperties the build metadata, when available
     * @return OpenApiCustomizer that sets the API version
     */
    @Bean
    public OpenApiCustomizer apiVersionCustomizer(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build != null ? build.getVersion() : "dev";
        return openApi -> openApi.getInfo().setVersion(version);
    }

    /**
     * Registers the ErrorResponse schema in the OpenAPI components section.
     * This is required because the ErrorResponse is not explicitly referenced in the controller but only used
     * programmatically as part of our custom OpenAPI annotations.
     * This ensures that ErrorResponse appears in the Swagger UI schemas list
     * and can be referenced by error responses throughout the API.
     *
     * @return OpenApiCustomizer that adds ErrorResponse to components/schemas
     */
    @Bean
    public OpenApiCustomizer errorResponseSchemaCustomizer() {
        return openApi -> {
            var schemas = ModelConverters.getInstance().read(ErrorResponse.class);
            schemas.forEach((name, schema) ->
                    openApi.getComponents().addSchemas(name, schema)
            );
        };
    }
}
