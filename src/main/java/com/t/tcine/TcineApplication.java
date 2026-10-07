package com.t.tcine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TcineApplication {

    public static void main(String[] args) {
        SpringApplication.run(TcineApplication.class, args);
    }

}
