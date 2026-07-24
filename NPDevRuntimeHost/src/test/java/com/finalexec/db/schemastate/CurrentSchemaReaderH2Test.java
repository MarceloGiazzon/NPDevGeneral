package com.finalexec.db.schemastate;

import javax.sql.DataSource;

/**
 * H2 variant of the CurrentSchemaReader golden test (schema-engine rebuild, P1.3). A fresh in-memory
 * database per instance; the shared assertions live in {@link AbstractCurrentSchemaReaderGoldenTest}.
 */
class CurrentSchemaReaderH2Test extends AbstractCurrentSchemaReaderGoldenTest {

    private final DataSource dataSource = new UrlDataSource(
            "jdbc:h2:mem:CurrentSchemaReaderH2Test" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

    @Override
    protected DataSource dataSource() {
        return dataSource;
    }
}
