package io.strata.examples.counter;

import org.springframework.stereotype.Component;

/** An ordinary Spring bean that an entity uses. */
@Component
public class Greeter {
    public String greet(String name, long visits) {
        return "Hello " + name + ", visit #" + visits;
    }
}
