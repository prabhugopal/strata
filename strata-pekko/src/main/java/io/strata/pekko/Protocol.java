package io.strata.pekko;

import org.apache.pekko.actor.typed.ActorRef;

import java.util.Optional;

/** Messages to entity actors. Arguments and results travel as JSON bytes, so any JVM language works. */
final class Protocol {
    private Protocol() {}

    /** Everything an entity actor receives. */
    interface Msg {}

    /** Messages that may cross nodes; serialized with Jackson CBOR (see reference.conf). */
    interface Remote {}

    record Invoke(String command, byte[] arg, ActorRef<Result> replyTo) implements Msg, Remote {}

    record GetState(ActorRef<Result> replyTo) implements Msg, Remote {}

    /** A reply: {@code value} (JSON) or {@code error}. */
    record Result(byte[] value, String error) implements Remote {
        static Result ok(byte[] value) { return new Result(value, null); }
        static Result failed(String error) { return new Result(null, error); }
    }

    /** Sent by sharding to stop an entity during passivation or hand-over. */
    enum Stop implements Msg, Remote { INSTANCE }

    // Local only: an entity talking to itself about asynchronous store I/O.
    record Loaded(Optional<io.strata.spi.StoredState> state, Throwable error) implements Msg {}
    record Saved(long revision, Throwable error) implements Msg {}
    enum Idle implements Msg { INSTANCE }
}
