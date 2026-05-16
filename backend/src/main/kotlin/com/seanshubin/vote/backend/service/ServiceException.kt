package com.seanshubin.vote.backend.service

class ServiceException(val category: Category, message: String) : RuntimeException(message) {
    enum class Category {
        UNAUTHORIZED,
        NOT_FOUND,
        CONFLICT,
        UNSUPPORTED,
        MALFORMED_JSON,

        /**
         * The owner has paused the event log for a maintenance window.
         * Mapped to HTTP 503 — the operation is temporarily unavailable but
         * expected to succeed on retry once the owner resumes the log.
         */
        PAUSED,
    }
}
