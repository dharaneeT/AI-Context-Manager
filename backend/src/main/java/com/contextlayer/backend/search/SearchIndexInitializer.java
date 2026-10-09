package com.contextlayer.backend.search;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitializer implements ApplicationRunner {

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (!"PostgreSQL".equalsIgnoreCase(product)) {
                log.info("Full-text indexes skipped: database is {}, not PostgreSQL", product);
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(FtsSql.CHUNK_INDEX);
                statement.execute(FtsSql.SUMMARY_INDEX);
            }
            log.info("Full-text indexes are in place");
        } catch (SQLException e) {
            log.warn("Could not create full-text indexes: {}", e.getMessage());
        }
    }
}