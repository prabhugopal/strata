package io.strata.examples.counter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Strata finds @Entity classes and serves their routes on Spring WebFlux; nothing to wire. */
@SpringBootApplication
public class CounterApplication {
    public static void main(String[] args) {
        SpringApplication.run(CounterApplication.class, args);
    }
}
