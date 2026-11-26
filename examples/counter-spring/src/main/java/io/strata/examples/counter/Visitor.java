package io.strata.examples.counter;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;

/** Counts visits; greets through a Spring bean. Spring supplies Greeter, Strata owns the entity. */
@Entity(mount = "/visitors/{id}")
public class Visitor {
    private long visits;

    @Inject Greeter greeter;

    @Command(post = "/visit")
    public String visit(String name) {
        return greeter.greet(name, ++visits);
    }
}
