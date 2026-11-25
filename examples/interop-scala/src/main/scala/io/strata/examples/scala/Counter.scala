package io.strata.examples.scala

import com.typesafe.config.Config
import io.strata.{EntityType, Reply, Strata}

/** A counter defined in Scala 3. Handlers are plain Scala lambdas (SAM conversion). */
object Counter:
  val tpe: EntityType[java.lang.Long] =
    EntityType.builder("counter", classOf[java.lang.Long])
      .initial(() => 0L)
      .command("increment", (s: java.lang.Long) => Reply.update[java.lang.Long, java.lang.Long](s + 1, s + 1))
      .command("add", classOf[java.lang.Long], (s: java.lang.Long, n: java.lang.Long) => Reply.update[java.lang.Long, java.lang.Long](s + n, s + n))
      .query("get", (s: java.lang.Long) => s)
      .build()

  /** Starts a node from `config`, increments `id` `times` times and returns the final count. */
  def demo(config: Config, id: String, times: Int): Long =
    val rt = Strata.start(config)
    try
      rt.register(tpe)
      for _ <- 1 to times do rt.ref(tpe, id).call("increment", classOf[java.lang.Long]).toCompletableFuture.join()
      rt.ref(tpe, id).call("get", classOf[java.lang.Long]).toCompletableFuture.join()
    finally rt.close()
