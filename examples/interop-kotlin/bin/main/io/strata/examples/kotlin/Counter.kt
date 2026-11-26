package io.strata.examples.kotlin

import com.typesafe.config.Config
import io.strata.EntityType
import io.strata.Reply
import io.strata.Strata

/** A counter defined in Kotlin. Handlers are plain Kotlin lambdas (SAM conversion). */
object Counter {
    val type: EntityType<Long> = EntityType.builder("counter", Long::class.javaObjectType)
        .initial { 0L }
        .command("increment") { s: Long -> Reply.update(s + 1, s + 1) }
        .command("add", Long::class.javaObjectType) { s: Long, n: Long -> Reply.update(s + n, s + n) }
        .query("get") { s: Long -> s }
        .build()

    /** Starts a node from [config], increments [id] [times] times and returns the final count. */
    fun demo(config: Config, id: String, times: Int): Long =
        Strata.start(config).use { rt ->
            rt.register(type)
            repeat(times) { rt.ref(type, id).call("increment", Long::class.javaObjectType).toCompletableFuture().join() }
            rt.ref(type, id).call("get", Long::class.javaObjectType).toCompletableFuture().join()
        }
}
