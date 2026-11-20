package com.fashionagent.gateway;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 통합 테스트가 붙을 진짜 PostgreSQL.
 * <ul>
 * <li>기본: Testcontainers로 postgres:17(운영과 같은 큰 버전)을 띄웁니다. CI(GitHub Actions)는 Docker가 있어서 늘 돕니다.</li>
 * <li>Docker가 없는 곳: TEST_DB_URL(·TEST_DB_USER·TEST_DB_PASSWORD)을 주면 그 DB에 붙습니다.</li>
 * <li>둘 다 없으면 통합 테스트만 건너뜁니다(@EnabledIf로 {@link #available()}).</li>
 * </ul>
 */
public final class TestDatabase {

    private static final String URL = System.getenv("TEST_DB_URL");
    private static PostgreSQLContainer container;

    private TestDatabase() {
    }

    public static boolean available() {
        return (URL != null && !URL.isBlank()) || DockerClientFactory.instance().isDockerAvailable();
    }

    public static synchronized void register(DynamicPropertyRegistry r) {
        if (URL != null && !URL.isBlank()) {
            r.add("spring.datasource.url", () -> URL);
            r.add("spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USER", "postgres"));
            r.add("spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", "postgres"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer("postgres:17-alpine");
            container.start();
        }
        r.add("spring.datasource.url", container::getJdbcUrl);
        r.add("spring.datasource.username", container::getUsername);
        r.add("spring.datasource.password", container::getPassword);
    }
}
