package io.strata.spi;

import io.strata.StrataRuntime;

/**
 * Something that starts alongside the runtime when its module is on the classpath (found with
 * ServiceLoader), such as strata-http's server. Each extension decides from config whether to
 * run, and registers what it opens with {@link StrataRuntime#closeWith}.
 */
public interface StrataExtension {
    void start(StrataRuntime runtime);
}
