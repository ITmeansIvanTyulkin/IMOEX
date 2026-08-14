package com.moex.cointegration;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap so {@code @WebMvcTest} under {@code com.moex.cointegration.*}
 * finds a {@code @SpringBootConfiguration} without loading the full TRINITY stack.
 */
@SpringBootApplication
public class TestBootApplication {
}
