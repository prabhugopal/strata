package io.strata.pekko;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An annotated entity exercising every argument and result shape. */
@Entity
public class Cart {
    private Map<String, Integer> lines = new LinkedHashMap<>();
    private String note;

    Cart() {}

    @Command
    public int add(String item, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        return lines.merge(item, quantity, Integer::sum);
    }

    @Command
    public void addAll(List<String> items) { items.forEach(i -> lines.merge(i, 1, Integer::sum)); }

    @Command(name = "set-note")
    public void note(String note) { this.note = note; }

    /** Changes state, then fails: the change must not survive. */
    @Command
    public void addThenFail(String item) {
        lines.put(item, 99);
        throw new IllegalStateException("payment declined");
    }

    @Command
    public void clear() { lines.clear(); }

    @Query
    public List<String> items() { return new ArrayList<>(lines.keySet()); }

    @Query
    public int quantity(String item) { return lines.getOrDefault(item, 0); }

    @Query
    public String currentNote() { return note; }
}
