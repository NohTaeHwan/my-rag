package com.nohtaehwan.rag.testing;

import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 파괴적 DB 테스트(flyway.clean, insert/delete)가 격리 테스트 DB에서만 실행되도록 막는 fail-closed 가드.
 *
 * <p>통과 조건(하나라도 어기면 {@link IllegalStateException} — skip이 아니라 실패):
 * <ul>
 *   <li>모드별 opt-in 시스템 프로퍼티가 {@code true}</li>
 *   <li>{@value #EXPECTED_DB_NAME_PROPERTY} 시스템 프로퍼티가 모드별 고정 이름({@code rag_migration_test}/
 *       {@code rag_integration_test})과 정확히 일치 — migration·통합 테스트가 같은 DB를 공유하지 못한다</li>
 *   <li>연결 전: Hikari가 jdbcUrl + PostgreSQL 드라이버로만 접속하도록 설정돼 있고(직접 주입 DataSource·JNDI·
 *       dataSourceClassName·dataSourceProperties·다른 드라이버 없음) 그 URL이 아래 URL 조건을 만족 —
 *       아니면 {@code getConnection()}을 호출하지 않는다</li>
 *   <li>실제 JDBC metadata URL이 {@code docker-compose.isolated-test.yml}의 bind와 같은 {@code 127.0.0.1:55432},
 *       query parameter 없음, 경로의 DB 이름 = 기대 이름</li>
 *   <li>실제 연결의 {@code current_database()} = 기대 이름</li>
 * </ul>
 *
 * <p>Gradle {@code isolatedMigrationTest}/{@code isolatedIntegrationTest} task가 opt-in과 기대 이름을 넣는다.
 */
public final class IsolatedDatabaseGuard {

    /** 격리 DB의 정확한 기대 이름을 담는 시스템 프로퍼티. */
    public static final String EXPECTED_DB_NAME_PROPERTY = "isolatedTest.expectedDbName";

    /** docker-compose.isolated-test.yml의 host bind({@code 127.0.0.1:55432})와 일치해야 한다. */
    private static final String ISOLATED_HOST = "127.0.0.1";
    private static final int ISOLATED_PORT = 55432;
    private static final String POSTGRES_DRIVER = "org.postgresql.Driver";

    /** 가드 사용처. opt-in 프로퍼티와 허용되는 유일한 DB 이름을 정한다. */
    public enum Mode {
        MIGRATION("isolatedMigrationTest", "rag_migration_test"),
        INTEGRATION("isolatedIntegrationTest", "rag_integration_test");

        private final String optInProperty;
        private final String dbName;

        Mode(String optInProperty, String dbName) {
            this.optInProperty = optInProperty;
            this.dbName = dbName;
        }
    }

    private IsolatedDatabaseGuard() {
    }

    /**
     * 시스템 프로퍼티와 실제 연결 metadata로 대상 DB를 검증한다. opt-in·기대 이름과 설정된 JDBC URL은
     * 연결 전에 먼저 확인하므로, 금지 대상 URL이면 {@link DataSource#getConnection()}을 호출하지 않는다.
     *
     * @param dataSource 테스트가 쓰려는 DataSource
     * @param mode       사용처
     * @throws IllegalStateException 격리 대상으로 확인되지 않은 경우
     */
    public static void verify(DataSource dataSource, Mode mode) {
        verify(dataSource, mode, System.getProperty(mode.optInProperty), System.getProperty(EXPECTED_DB_NAME_PROPERTY));
    }

    /**
     * {@link #verify(DataSource, Mode)} 본체. 시스템 프로퍼티 대신 값을 직접 받는다(단위 테스트용).
     *
     * @param dataSource     테스트가 쓰려는 DataSource
     * @param mode           사용처
     * @param optIn          opt-in 프로퍼티 값
     * @param expectedDbName 기대 DB 이름
     * @throws IllegalStateException 격리 대상으로 확인되지 않은 경우
     */
    static void verify(DataSource dataSource, Mode mode, String optIn, String expectedDbName) {
        checkInputs(mode, optIn, expectedDbName);
        // 연결 전 선검사: JAVA_TOOL_OPTIONS 등으로 spring.datasource.url이 바뀌어도 rag_db에 접속하기 전에 막는다.
        checkUrl(expectedDbName, configuredJdbcUrl(dataSource));
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT current_database()")) {
            rs.next();
            check(mode, optIn, expectedDbName, connection.getMetaData().getURL(), rs.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException("격리 DB identity를 확인하지 못했다", e);
        }
    }

    /**
     * 연결 없이 DataSource에 설정된 JDBC URL을 꺼낸다. Spring Boot 기본 풀인 Hikari만 허용한다.
     * Hikari는 직접 주입된 DataSource → JNDI → dataSourceClassName → jdbcUrl 순으로 접속 경로를 고르므로
     * jdbcUrl 외 경로가 하나라도 설정돼 있거나 PostgreSQL 외 드라이버가 지정되면 거부한다.
     *
     * @param dataSource 대상 DataSource
     * @return 설정된 JDBC URL
     * @throws IllegalStateException 연결 전에 URL을 확정할 수 없는 경우
     */
    private static String configuredJdbcUrl(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw fail("연결 전에 URL을 확인할 수 없는 DataSource다: "
                    + (dataSource == null ? null : dataSource.getClass().getName()));
        }
        if (hikari.getDataSource() != null || hikari.getDataSourceJNDI() != null
                || hikari.getDataSourceClassName() != null || !hikari.getDataSourceProperties().isEmpty()) {
            throw fail("직접 주입 DataSource/JNDI/dataSourceClassName/dataSourceProperties 설정은 허용하지 않는다");
        }
        if (hikari.getDriverClassName() != null && !POSTGRES_DRIVER.equals(hikari.getDriverClassName())) {
            throw fail("PostgreSQL 외 드라이버는 허용하지 않는다: " + hikari.getDriverClassName());
        }
        return hikari.getJdbcUrl();
    }

    /**
     * 판정 로직 본체(DB 비연결 단위 테스트용).
     *
     * @param mode            사용처
     * @param optIn           opt-in 프로퍼티 값
     * @param expectedDbName  기대 DB 이름
     * @param jdbcUrl         JDBC metadata URL
     * @param currentDatabase {@code current_database()} 결과
     * @throws IllegalStateException 하나라도 어긋나는 경우
     */
    static void check(Mode mode, String optIn, String expectedDbName, String jdbcUrl, String currentDatabase) {
        checkInputs(mode, optIn, expectedDbName);
        checkUrl(expectedDbName, jdbcUrl);
        if (!expectedDbName.equals(currentDatabase)) {
            throw fail("실제 연결 DB(" + currentDatabase + ")가 기대값(" + expectedDbName + ")과 다르다");
        }
    }

    /** JDBC URL이 정확히 127.0.0.1:55432·query 없음·기대 DB 이름인지 확인한다. */
    private static void checkUrl(String expectedDbName, String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw fail("PostgreSQL JDBC URL이 아니다: " + jdbcUrl);
        }
        URI uri;
        try {
            uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        } catch (IllegalArgumentException e) {
            throw fail("JDBC URL을 해석할 수 없다: " + jdbcUrl);
        }
        // 다중 host(쉼표)는 server-based authority로 해석되지 않아 host가 null이 된다.
        if (!ISOLATED_HOST.equals(uri.getHost()) || uri.getPort() != ISOLATED_PORT) {
            throw fail("격리 DB 대상은 " + ISOLATED_HOST + ":" + ISOLATED_PORT + "만 허용한다: "
                    + uri.getHost() + ":" + uri.getPort());
        }
        if (uri.getRawQuery() != null) {
            throw fail("query parameter가 있는 URL은 허용하지 않는다");
        }
        String urlDbName = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!expectedDbName.equals(urlDbName)) {
            throw fail("URL의 DB 이름(" + urlDbName + ")이 기대값(" + expectedDbName + ")과 다르다");
        }
    }

    private static void checkInputs(Mode mode, String optIn, String expectedDbName) {
        if (!"true".equals(optIn)) {
            throw fail("-D" + mode.optInProperty + "=true opt-in이 없다");
        }
        if (!mode.dbName.equals(expectedDbName)) {
            throw fail(EXPECTED_DB_NAME_PROPERTY + "는 '" + mode.dbName + "'여야 한다: " + expectedDbName);
        }
    }

    private static IllegalStateException fail(String reason) {
        return new IllegalStateException("격리 DB 가드 차단 — " + reason);
    }

    /**
     * 통합 테스트 컨텍스트 기동 시 Spring Boot Flyway 자동 migrate 직전에 가드를 통과시킨다.
     * Flyway는 isolatedIntegrationTest task에서만 켜진다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class GuardedFlywayConfig {

        /** 가드 확인 후 migrate하는 전략. */
        @Bean
        FlywayMigrationStrategy guardedFlywayMigrationStrategy() {
            return flyway -> {
                verify(flyway.getConfiguration().getDataSource(), Mode.INTEGRATION);
                flyway.migrate();
            };
        }
    }
}
