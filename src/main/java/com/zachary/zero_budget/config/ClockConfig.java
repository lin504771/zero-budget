package com.zachary.zero_budget.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes a {@link Clock} available for injection.
 *
 * <p>Code that asks {@code LocalDate.now()} is hard to test, because the answer changes every
 * day. Code that is <em>given</em> a Clock can be handed a frozen one in tests
 * ({@code Clock.fixed(...)}), so "is this date in the future?" always gives the same answer.
 *
 * <p>Spring does not provide a Clock on its own, so we declare one. {@code @Configuration}
 * marks a class that defines beans, and each {@code @Bean} method's return value becomes a
 * bean that other classes can receive through their constructors.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
