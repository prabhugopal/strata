package io.strata.examples.counter;

import io.strata.EntityType;
import io.strata.StrataRuntime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Non-blocking: each call hands off to the entity and completes when it has committed. */
@RestController
class CounterController {
    private final StrataRuntime strata;
    private final EntityType<Long> counter;

    CounterController(StrataRuntime strata, EntityType<Long> counter) {
        this.strata = strata;
        this.counter = counter;
    }

    @PostMapping("/counters/{id}/increment")
    Mono<Long> increment(@PathVariable String id) {
        return Mono.fromCompletionStage(strata.ref(counter, id).call("increment", Long.class));
    }

    @PostMapping("/counters/{id}/add")
    Mono<Long> add(@PathVariable String id, @RequestBody Long amount) {
        return Mono.fromCompletionStage(strata.ref(counter, id).call("add", amount, Long.class));
    }

    @GetMapping("/counters/{id}")
    Mono<Long> get(@PathVariable String id) {
        return Mono.fromCompletionStage(strata.ref(counter, id).call("get", Long.class));
    }
}
