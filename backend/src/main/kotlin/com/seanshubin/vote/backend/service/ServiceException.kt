package com.seanshubin.vote.backend.service

class ServiceException(val category: Category, message: String) : RuntimeException(message) {
    enum class Category {
        UNAUTHORIZED,
        NOT_FOUND,
        CONFLICT,
        UNSUPPORTED,
        MALFORMED_JSON
    }
}
