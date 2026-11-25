package io.strata.examples.kotlin

import io.strata.annotation.Command
import io.strata.annotation.Entity
import io.strata.annotation.Query

/** A bank account in Kotlin: only business logic. kapt generates AccountEntity and AccountClient. */
@Entity(mount = "/accounts/{id}")
class Account {
    private var balance: Long = 0
    private var history: MutableList<String> = mutableListOf()

    @Command(post = "/deposit")
    fun deposit(amount: Long): Long {
        require(amount > 0) { "amount must be positive" }
        balance += amount
        history += "deposit $amount"
        return balance
    }

    @Command(post = "/withdraw")
    fun withdraw(amount: Long): Long {
        check(amount <= balance) { "insufficient funds" }
        balance -= amount
        history += "withdraw $amount"
        return balance
    }

    @Command(post = "/transfer-in")
    fun transferIn(from: String, amount: Long): Long {
        balance += amount
        history += "from $from $amount"
        return balance
    }

    @Query(get = "")
    fun balance(): Long = balance

    @Query(get = "/history")
    fun history(): List<String> = history
}
