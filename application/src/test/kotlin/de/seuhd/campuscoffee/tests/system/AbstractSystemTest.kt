package de.seuhd.campuscoffee.tests.system

import de.seuhd.campuscoffee.Application
import de.seuhd.campuscoffee.api.mapper.PosDtoMapper
import de.seuhd.campuscoffee.api.mapper.ReviewDtoMapper
import de.seuhd.campuscoffee.api.mapper.UserDtoMapper
import de.seuhd.campuscoffee.domain.ports.api.PosService
import de.seuhd.campuscoffee.domain.ports.api.ReviewService
import de.seuhd.campuscoffee.domain.ports.api.UserService
import de.seuhd.campuscoffee.tests.SystemTestUtils.configureClient
import de.seuhd.campuscoffee.tests.SystemTestUtils.configurePostgresContainers
import de.seuhd.campuscoffee.tests.SystemTestUtils.getPostgresContainer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Abstract base class for system tests. Sets up the Spring Boot test context, manages the PostgreSQL
 * testcontainer, and configures the [RestTestClient][de.seuhd.campuscoffee.tests.SystemTestUtils].
 */
@SpringBootTest(
    classes = [Application::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
abstract class AbstractSystemTest {
    @Autowired
    protected lateinit var posService: PosService

    @Autowired
    protected lateinit var userService: UserService

    @Autowired
    protected lateinit var reviewService: ReviewService

    @Autowired
    protected lateinit var posDtoMapper: PosDtoMapper

    @Autowired
    protected lateinit var userDtoMapper: UserDtoMapper

    @Autowired
    protected lateinit var reviewDtoMapper: ReviewDtoMapper

    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    fun beforeEach() {
        // reviews reference POS and users via foreign keys, so they must be cleared first
        reviewService.clear()
        posService.clear()
        userService.clear()
        configureClient(port)
    }

    @AfterEach
    fun afterEach() {
        reviewService.clear()
        posService.clear()
        userService.clear()
    }

    protected companion object {
        // One container shared across all system tests: a companion-object val is a single instance
        // initialized once, so it is already shared without @JvmStatic.
        protected val postgresContainer: PostgreSQLContainer<*> = getPostgresContainer().apply { start() }

        // Spring only invokes @DynamicPropertySource on a static method; @JvmStatic turns this
        // companion function into a real JVM static so the container's JDBC settings reach the context.
        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            configurePostgresContainers(registry, postgresContainer)
        }
    }
}
