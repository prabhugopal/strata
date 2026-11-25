package io.strata.examples.counter;

import io.strata.EntityType;
import io.strata.Reply;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class CounterApplication {

    /** A counter entity: Strata routes each id to one place and commits before replying. */
    @Bean
    EntityType<Long> counter() {
        return EntityType.builder("counter", Long.class)
                .initial(() -> 0L)
                .command("increment", s -> Reply.update(s + 1, s + 1))
                .command("add", Long.class, (s, n) -> Reply.update(s + n, s + n))
                .query("get", s -> s)
                .build();
    }

    public static void main(String[] args) {
        SpringApplication.run(CounterApplication.class, args);
    }
}
