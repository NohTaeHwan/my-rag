package com.nohtaehwan.rag.testing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.nohtaehwan.rag.testing.IsolatedDatabaseGuard.Mode;
import com.zaxxer.hikari.HikariDataSource;

/**
 * {@link IsolatedDatabaseGuard}의 판정 로직을 DB 연결 없이 검증한다.
 * 실제 JDBC metadata 대신 URL·current_database() 값을 문자열로 넣는다.
 */
class IsolatedDatabaseGuardTest {

    private static final String MIGRATION_DB = "rag_migration_test";
    private static final String MIGRATION_URL = "jdbc:postgresql://127.0.0.1:55432/" + MIGRATION_DB;

    @Test
    void opt_in이_없으면_실패한다() {
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, null, MIGRATION_DB, MIGRATION_URL, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("isolatedMigrationTest");
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "false", MIGRATION_DB, MIGRATION_URL, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 기대_DB_이름이_없으면_실패한다() {
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", null, MIGRATION_URL, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 격리_loopback_URL과_일치하는_DB_이름이면_통과한다() {
        assertThatCode(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB, MIGRATION_URL, MIGRATION_DB))
                .doesNotThrowAnyException();
        assertThatCode(() -> IsolatedDatabaseGuard.check(
                Mode.INTEGRATION, "true", "rag_integration_test",
                "jdbc:postgresql://127.0.0.1:55432/rag_integration_test", "rag_integration_test"))
                .doesNotThrowAnyException();
    }

    @Test
    void 실제_DB_이름이나_URL의_DB_이름이_기대값과_다르면_실패한다() {
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB, MIGRATION_URL, "rag_db"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB,
                "jdbc:postgresql://127.0.0.1:55432/other_migration_test", MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 기대_DB_이름이_모드_용도와_맞지_않으면_실패한다() {
        // migration DB 이름으로 통합 테스트를 돌리면 두 테스트가 같은 DB를 공유하게 된다.
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.INTEGRATION, "true", MIGRATION_DB, MIGRATION_URL, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loopback이_아닌_host는_실패한다() {
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB,
                "jdbc:postgresql://10.0.0.5:55432/" + MIGRATION_DB, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB,
                "jdbc:postgresql://spark.tailnet.ts.net:55432/" + MIGRATION_DB, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB,
                "jdbc:postgresql://127.0.0.1:55432,10.0.0.5:55432/" + MIGRATION_DB, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 운영형_대상은_실패한다() {
        // 기본 앱 DB 이름
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", "rag_db", "jdbc:postgresql://127.0.0.1:55432/rag_db", "rag_db"))
                .isInstanceOf(IllegalStateException.class);
        // 기존 rag-postgres 포트(5432) — 명시 또는 생략
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB,
                "jdbc:postgresql://127.0.0.1:5432/" + MIGRATION_DB, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB,
                "jdbc:postgresql://127.0.0.1/" + MIGRATION_DB, MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        // query parameter로 host/DB를 바꿀 여지를 막는다.
        assertThatThrownBy(() -> IsolatedDatabaseGuard.check(
                Mode.MIGRATION, "true", MIGRATION_DB, MIGRATION_URL + "?PGDBNAME=rag_db", MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 연결 전에 거부돼야 하는 URL이면 실제 접속(getConnection)이 일어나지 않아야 한다. */
    @Test
    void 금지_대상_URL이면_연결을_시도하지_않고_실패한다() throws Exception {
        for (String url : new String[] {
                "jdbc:postgresql://127.0.0.1:5432/rag_db",
                "jdbc:postgresql://127.0.0.1:5432/" + MIGRATION_DB,
                "jdbc:postgresql://127.0.0.1:55432/rag_db",
                MIGRATION_URL + "?PGDBNAME=rag_db"}) {
            HikariDataSource hikari = new HikariDataSource();
            hikari.setJdbcUrl(url);
            HikariDataSource dataSource = spy(hikari);

            assertThatThrownBy(() -> IsolatedDatabaseGuard.verify(dataSource, Mode.MIGRATION, "true", MIGRATION_DB))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("격리 DB 가드 차단");
            verify(dataSource, never()).getConnection();
        }
    }

    @Test
    void 연결_전에_URL을_확인할_수_없는_DataSource면_연결하지_않고_실패한다() throws Exception {
        DataSource unknown = mock(DataSource.class);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.verify(unknown, Mode.MIGRATION, "true", MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        verify(unknown, never()).getConnection();

        // jdbcUrl이 맞아도 dataSourceClassName이 있으면 Hikari가 jdbcUrl을 쓰지 않으므로 거부한다.
        HikariDataSource hikari = new HikariDataSource();
        hikari.setJdbcUrl(MIGRATION_URL);
        hikari.setDataSourceClassName("org.postgresql.ds.PGSimpleDataSource");
        HikariDataSource dataSource = spy(hikari);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.verify(dataSource, Mode.MIGRATION, "true", MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        verify(dataSource, never()).getConnection();

        // jdbcUrl이 맞아도 직접 주입된 DataSource가 있으면 Hikari는 그쪽으로 접속하므로 거부한다.
        DataSource injected = mock(DataSource.class);
        HikariDataSource withInjected = new HikariDataSource();
        withInjected.setJdbcUrl(MIGRATION_URL);
        withInjected.setDataSource(injected);
        HikariDataSource injectedSpy = spy(withInjected);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.verify(injectedSpy, Mode.MIGRATION, "true", MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("격리 DB 가드 차단");
        verify(injectedSpy, never()).getConnection();
        verify(injected, never()).getConnection();

        // JNDI도 jdbcUrl보다 우선하는 경로이므로 거부한다.
        HikariDataSource withJndi = new HikariDataSource();
        withJndi.setJdbcUrl(MIGRATION_URL);
        withJndi.setDataSourceJNDI("java:comp/env/jdbc/rag");
        HikariDataSource jndiSpy = spy(withJndi);
        assertThatThrownBy(() -> IsolatedDatabaseGuard.verify(jndiSpy, Mode.MIGRATION, "true", MIGRATION_DB))
                .isInstanceOf(IllegalStateException.class);
        verify(jndiSpy, never()).getConnection();
    }

    /** Compose가 bind하는 127.0.0.1:55432와 모드별 고정 이름 외에는 연결 전에 거부한다. */
    @Test
    void 격리_Compose_대상이_아닌_포트_host_이름은_연결하지_않고_실패한다() throws Exception {
        String[][] cases = {
                {"jdbc:postgresql://127.0.0.1:5432/" + MIGRATION_DB, MIGRATION_DB},
                {"jdbc:postgresql://127.0.0.1:55433/" + MIGRATION_DB, MIGRATION_DB},
                {"jdbc:postgresql://127.0.0.1:59513/" + MIGRATION_DB, MIGRATION_DB},
                {"jdbc:postgresql://localhost:55432/" + MIGRATION_DB, MIGRATION_DB},
                {"jdbc:postgresql://[::1]:55432/" + MIGRATION_DB, MIGRATION_DB},
                // 'test'·'migration'을 포함해도 고정 이름이 아니면 거부한다.
                {"jdbc:postgresql://127.0.0.1:55432/other_migration_test", "other_migration_test"},
                {"jdbc:postgresql://127.0.0.1:55432/rag_migration_test2", "rag_migration_test2"}};
        for (String[] c : cases) {
            HikariDataSource hikari = new HikariDataSource();
            hikari.setJdbcUrl(c[0]);
            HikariDataSource dataSource = spy(hikari);

            assertThatThrownBy(() -> IsolatedDatabaseGuard.verify(dataSource, Mode.MIGRATION, "true", c[1]))
                    .as(c[0])
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("격리 DB 가드 차단");
            verify(dataSource, never()).getConnection();
        }
    }
}
