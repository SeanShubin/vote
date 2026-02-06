package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.PasswordUtil

class FakePasswordUtil : PasswordUtil {
    override fun createSaltAndHash(password: String): PasswordUtil.SaltAndHash {
        return PasswordUtil.SaltAndHash("fake-salt", "hash-for:$password")
    }

    override fun passwordMatches(password: String, salt: String, hash: String): Boolean {
        return hash == "hash-for:$password"
    }
}
