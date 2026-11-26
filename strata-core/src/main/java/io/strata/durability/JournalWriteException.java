package io.strata.durability;

/**
 * A service call's outcome couldn't be recorded. The invocation stops without finishing; when
 * the entity is loaded again it resumes, and that call runs again (it wasn't recorded).
 */
public final class JournalWriteException extends RuntimeException {
    public JournalWriteException(String message, Throwable cause) { super(message, cause); }
}
