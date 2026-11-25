package io.strata.examples.scala

import io.strata.annotation.{Command, Entity, Query}

/**
 * A bank account in Scala 3: only business logic. Scala has no Java annotation processing, so
 * Strata builds the entity type by reflection from `strata.entity.classes` (application.conf).
 */
@Entity(mount = "/accounts/{id}")
class Account:
  private var total: Long = 0L
  private var log: Vector[String] = Vector.empty

  @Command(post = "/deposit")
  def deposit(amount: Long): Long =
    require(amount > 0, "amount must be positive")
    total += amount
    log :+= s"deposit $amount"
    total

  @Command(post = "/withdraw")
  def withdraw(amount: Long): Long =
    if amount > total then throw IllegalStateException("insufficient funds")
    total -= amount
    log :+= s"withdraw $amount"
    total

  @Query(get = "")
  def balance(): Long = total

  @Query(get = "/history")
  def history(): Vector[String] = log
