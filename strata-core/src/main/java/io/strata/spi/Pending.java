package io.strata.spi;

import java.util.List;

/**
 * An invocation that started but didn't commit (its node stopped mid-command): what was called,
 * and the results of the service calls it made so far, in order.
 */
public record Pending(String invocationId, byte[] header, List<byte[]> entries) {}
