package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.EmailSender

/**
 * Dev / local EmailSender — prints the email to stdout. Used by the
 * Jetty-based [ApplicationRunner] so password reset links can be copied
 * out of the terminal during local development without setting up SES.
 *
 * Production wires up the SES-backed implementation instead.
 */
object ConsoleEmailSender : EmailSender {
    override fun send(to: String, subject: String, body: String) {
        println("=== Email to $to ===")
        println("Subject: $subject")
        println(body)
        println("=== end of email ===")
    }
}
