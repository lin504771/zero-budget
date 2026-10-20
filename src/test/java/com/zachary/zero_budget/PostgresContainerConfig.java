package com.zachary.zero_budget;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a throwaway Postgres in Docker for tests and points the application at it.
 *
 * <p>How the pieces fit:
 * <ul>
 *   <li>{@code @TestConfiguration} is like {@code @Configuration} (a class that declares beans),
 *       but it only exists in tests. A test opts in with {@code @Import(PostgresContainerConfig.class)}.</li>
 *   <li>The {@code @Bean} method returns a container object. Spring manages its lifecycle: it
 *       starts the container when the application context is created and stops it when the
 *       context closes.</li>
 *   <li>{@code @ServiceConnection} is the key line. Spring Boot sees "this bean is a Postgres
 *       container", asks Docker which random port it was given and what the generated
 *       credentials are, and configures the {@code DataSource} to use them. That replaces the
 *       {@code spring.datasource.*} properties in {@code application.properties}, which is why
 *       tests never touch your docker-compose database.</li>
 * </ul>
 *
 * <p>Flyway then runs {@code V1__init.sql} (and any later migrations) on the fresh database as
 * the context starts, so every test run proves the migrations work from scratch.
 *
 * <p>{@code proxyBeanMethods = false} skips an unneeded Spring optimisation (it normally wraps
 * the class in a proxy so that bean methods calling each other return the same instance). It is
 * the convention for configuration classes whose bean methods don't call each other.
 *
 * <p>Containers are slow to start (seconds), so this config is shared. Spring caches an
 * application context and reuses it across test classes that have an identical setup, so test
 * classes with the same annotations and imports share one container instead of starting their own.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    // Same major version as docker-compose.yml, so tests run against what you run locally.
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
