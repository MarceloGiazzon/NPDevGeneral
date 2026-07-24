package com.finalexec.db.schemastate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Minimal {@link DataSource} over {@link DriverManager} for the CurrentSchemaReader golden tests —
 * one shape shared by the H2 (no credentials) and Postgres (credentialed) variants, so the golden
 * assertions live once in {@link AbstractCurrentSchemaReaderGoldenTest}. Avoids a compile-time driver
 * dependency in the test module.
 */
final class UrlDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    UrlDataSource(String url) {
        this(url, null, null);
    }

    UrlDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override public Connection getConnection() throws SQLException {
        return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
    }

    @Override public Connection getConnection(String username, String pwd) throws SQLException {
        return DriverManager.getConnection(url, username, pwd);
    }

    @Override public PrintWriter getLogWriter() { return null; }

    @Override public void setLogWriter(PrintWriter out) { }

    @Override public void setLoginTimeout(int seconds) { }

    @Override public int getLoginTimeout() { return 0; }

    @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }

    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
}
