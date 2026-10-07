package com.t.tcine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan("com.t.tcine.domain.search.config")
@EnableScheduling
public class TcineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcineApplication.class, args);
    }

}
