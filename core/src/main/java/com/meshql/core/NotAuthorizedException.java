package com.meshql.core;

/**
 * Thrown when the auth plugin refuses a write.
 *
 * <p>Refused reads return nothing, because an unauthorized record and a missing
 * one are the same answer to a caller who may not know it exists. A refused
 * write has no such empty answer, so it says so.
 */
public class NotAuthorizedException extends RuntimeException {
    public NotAuthorizedException(String message) {
        super(message);
    }
}
