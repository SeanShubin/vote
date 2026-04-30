package com.seanshubin.vote.backend.integration

import aws.sdk.kotlin.services.ses.SesClient
import aws.sdk.kotlin.services.ses.model.Body
import aws.sdk.kotlin.services.ses.model.Content
import aws.sdk.kotlin.services.ses.model.Destination
import aws.sdk.kotlin.services.ses.model.Message
import aws.sdk.kotlin.services.ses.model.SendEmailRequest
import com.seanshubin.vote.contract.EmailSender
import kotlinx.coroutines.runBlocking

/**
 * AWS SES email sender for production. The Lambda execution role grants
 * `ses:SendEmail` and `ses:SendRawEmail` for the configured sender — there
 * are no SMTP credentials in code or config.
 *
 * SES caveat (one-time AWS console step): the sending account starts in
 * "sandbox mode" where only verified destination addresses can receive.
 * To send to arbitrary user emails for password resets, request production
 * access from the SES console (free, usually approved within a day).
 */
class SesEmailSender(
    private val region: String,
    private val fromAddress: String,
) : EmailSender {

    override fun send(to: String, subject: String, body: String) {
        runBlocking {
            SesClient { region = this@SesEmailSender.region }.use { client ->
                client.sendEmail(SendEmailRequest {
                    source = fromAddress
                    destination = Destination { toAddresses = listOf(to) }
                    message = Message {
                        this.subject = Content { data = subject }
                        this.body = Body { text = Content { data = body } }
                    }
                })
            }
        }
    }
}
