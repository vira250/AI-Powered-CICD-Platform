package com.aicicd.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Datasource #2 — repo database (repos, pipelines, reports, deployments). */
@Configuration
@EnableJpaRepositories(
        basePackages = {"com.aicicd.platform.repo", "com.aicicd.platform.pipeline",
                "com.aicicd.platform.deployment"},
        entityManagerFactoryRef = "repoEntityManagerFactory",
        transactionManagerRef = "repoTransactionManager")
public class RepoDbConfig {

    @Primary
    @Bean
    public DataSource repoDataSource(AppProperties props) {
        AppProperties.Postgres pg = props.postgres();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.jdbcUrl(pg.repoDb()));
        ds.setUsername(pg.user());
        ds.setPassword(pg.password());
        return ds;
    }

    @Primary
    @Bean
    public LocalContainerEntityManagerFactoryBean repoEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("repoDataSource") DataSource dataSource) {
        return builder.dataSource(dataSource)
                .packages("com.aicicd.platform.repo", "com.aicicd.platform.pipeline",
                        "com.aicicd.platform.deployment")
                .persistenceUnit("repo")
                .build();
    }

    @Primary
    @Bean
    public PlatformTransactionManager repoTransactionManager(
            @Qualifier("repoEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
