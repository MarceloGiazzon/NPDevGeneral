package com.finalexec.db;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Minimal {@link DataSource} over a single JDBC URL, shared by {@link ExportMain} and
 * {@link ImportMain}. Every sibling {@code *Main} class (see {@link PromoteMain}'s own private copy)
 * duplicates a class shaped exactly like this one instead of sharing it, since each is meant to stay
 * independently copy-pastable -- these two are new and share one on purpose rather than adding a
 * 6th/7th private copy of the same twelve methods.
 */
final class DataTransferUrlDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    DataTransferUrlDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return (user == null && password == null)
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
    }

    @Override
    public Connection getConnection(String username, String pass) throws SQLException {
        return DriverManager.getConnection(url, username, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(getClass().getName());
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
