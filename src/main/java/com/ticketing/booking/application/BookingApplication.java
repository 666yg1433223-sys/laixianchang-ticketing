package com.ticketing.booking.application;

import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.config.RuntimeServices;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication(scanBasePackages = "com.ticketing.booking")
public class BookingApplication {
    public static void main(String[] args) { SpringApplication.run(BookingApplication.class, args); }

    @Bean
    static BeanFactoryPostProcessor startupBoundary(Environment env) {
        return factory -> factory.registerSingleton("bootstrapReady", Bootstrap.prepare(env));
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(Bootstrap.Ready ready) { return ready.dataSource(); }

    @Bean
    org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(DataSource dataSource) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        return jdbc;
    }

    @Bean
    RuntimeServices runtimeServices(Bootstrap.Ready ready) { return ready.runtime(); }
}
