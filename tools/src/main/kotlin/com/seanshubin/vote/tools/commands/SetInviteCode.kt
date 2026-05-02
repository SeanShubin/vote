package com.seanshubin.vote.tools.commands

import aws.sdk.kotlin.services.ssm.SsmClient
import aws.sdk.kotlin.services.ssm.model.ParameterType
import aws.sdk.kotlin.services.ssm.model.PutParameterRequest
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.seanshubin.vote.tools.lib.Output
import kotlinx.coroutines.runBlocking

/**
 * Rotate the registration invite code in AWS SSM Parameter Store. The
 * Lambda re-reads the parameter at most every 5 minutes, so the new code
 * starts being enforced within one TTL window.
 *
 * Pass an empty value (`vote-dev set-invite-code ""`) to disable the gate
 * entirely — registration becomes open until a non-empty code is set.
 */
class SetInviteCode : CliktCommand(name = "set-invite-code") {
    private val value by argument(
        name = "value",
        help = "New invite code. Pass \"\" to disable the gate (registration becomes open).",
    )

    private val parameterName by option(
        "--parameter-name",
        help = "SSM parameter name. Defaults to the production stack's parameter.",
    ).default("/pairwisevote-frontend/invite-code")

    private val region by option(
        "--region",
        help = "AWS region. Defaults to us-east-1 (where the stack is deployed).",
    ).default("us-east-1")

    override fun help(context: Context) =
        "Update the shared registration invite code in AWS SSM Parameter Store. " +
            "Takes effect within ~5 minutes of the next Lambda invocation."

    override fun run() = runBlocking {
        // SSM rejects zero-length values, so an empty argument becomes a
        // single-space sentinel. The provider's isNotBlank() check still
        // treats it as "no code configured", which is how the gate is disabled.
        val effectiveValue = if (value.isEmpty()) " " else value
        val display = if (value.isEmpty()) "<empty — gate disabled>" else value
        Output.banner("Setting $parameterName in $region to: $display")

        SsmClient { region = this@SetInviteCode.region }.use { client ->
            client.putParameter(
                PutParameterRequest {
                    name = parameterName
                    this.value = effectiveValue
                    type = ParameterType.String
                    overwrite = true
                }
            )
        }

        Output.success("Updated. Lambdas will pick up the new value within 5 minutes.")
    }
}
