package com.t.tcine;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

@SpringBootTest
class TcineApplicationTests {

    @Autowired
    private Environment env;

    @Test
    void contextLoads() {
        String apiKey = env.getProperty("ai.api-key");
        System.out.println(">>> LOADED ai.api-key: " + (apiKey != null && !apiKey.isBlank() ? "PRESENT (len=" + apiKey.length() + ")" : "NULL/EMPTY"));
        Assertions.assertNotNull(apiKey);
        Assertions.assertFalse(apiKey.isBlank());
    }

}
