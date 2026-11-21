package com.studentstorefront.exception

class ResourceNotFoundException(
    message: String = "The requested resource was not found"
) : RuntimeException(message)
