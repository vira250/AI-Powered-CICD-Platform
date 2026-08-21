package com.aicicd.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Datasource #1 — login database (user accounts from GitHub OAuth). */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.aicicd.platform.auth",
        entityManagerFactoryRef = "loginEntityManagerFactory",
        transactionManagerRef = "loginTransactionManager")
public class LoginDbConfig {

    @Bean
    public DataSource loginDataSource(AppProperties props) {
        AppProperties.Postgres pg = props.postgres();
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.jdbcUrl(pg.loginDb()));
        ds.setUsername(pg.user());
        ds.setPassword(pg.password());
        return ds;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean loginEntityManagerFactory(
            EntityManagerFactoryBuilder builder,
            @Qualifier("loginDataSource") DataSource dataSource) {
        return builder.dataSource(dataSource)
                .packages("com.aicicd.platform.auth")
                .persistenceUnit("login")
                .properties(java.util.Map.of("hibernate.hbm2ddl.auto", "update"))
                .build();
    }

    @Bean
    public PlatformTransactionManager loginTransactionManager(
            @Qualifier("loginEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
