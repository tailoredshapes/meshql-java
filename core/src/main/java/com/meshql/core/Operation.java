package com.meshql.core;

/**
 * The one question authorization answers, and its operand.
 *
 * <p>There is a single question in meshql - can this requester see this record,
 * or create it - so there is a single method to ask it, with an operand rather
 * than a family of methods. This absorbs what used to be a separate action
 * check: {@code isAuthorized(READ, envelope)} and
 * {@code isAuthorized(REMOVE, envelope)} are the same question about different
 * intent, not two different questions.
 */
public enum Operation {
    /** Reading a record, by id or through a query. */
    READ,
    /** Writing a record, whether the first version or a later one. */
    CREATE,
    /** Marking a record removed. */
    REMOVE
}
