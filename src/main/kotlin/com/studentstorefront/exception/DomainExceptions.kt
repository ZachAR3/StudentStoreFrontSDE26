package com.studentstorefront.exception

/**
 * A request that is well-formed but breaks a business rule. The message is
 * written for end users and is returned as-is (400).
 */
open class DomainValidationException(message: String) : RuntimeException(message)

/**
 * The request conflicts with the current state of a resource, for example a
 * listing that is already sold or an identifier that is already taken (409).
 * The message is written for end users and is returned as-is.
 */
class ConflictException(message: String) : RuntimeException(message)

/** Too many failed attempts for one account; the client should wait [retryAfterSeconds] (429). */
class TooManyAttemptsException(val retryAfterSeconds: Long) :
    RuntimeException("Too many failed login attempts. Try again in ${(retryAfterSeconds + 59) / 60} minute(s).")
