package com.seanshubin.vote.tools.app

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.seanshubin.vote.tools.commands.BackupDynamodb
import com.seanshubin.vote.tools.commands.CheckJava
import com.seanshubin.vote.tools.commands.ConvertScenarios
import com.seanshubin.vote.tools.commands.DbInitDynamodb
import com.seanshubin.vote.tools.commands.DbReset
import com.seanshubin.vote.tools.commands.DbResetDynamodb
import com.seanshubin.vote.tools.commands.DbResetMysql
import com.seanshubin.vote.tools.commands.DbSetup
import com.seanshubin.vote.tools.commands.DbSetupDynamodb
import com.seanshubin.vote.tools.commands.DbSetupMysql
import com.seanshubin.vote.tools.commands.DbTeardown
import com.seanshubin.vote.tools.commands.DbTeardownDynamodb
import com.seanshubin.vote.tools.commands.DbTeardownMysql
import com.seanshubin.vote.tools.commands.GenerateScenarioEventLog
import com.seanshubin.vote.tools.commands.InspectDynamodbAll
import com.seanshubin.vote.tools.commands.InspectDynamodbBallots
import com.seanshubin.vote.tools.commands.InspectDynamodbCandidates
import com.seanshubin.vote.tools.commands.InspectDynamodbElections
import com.seanshubin.vote.tools.commands.InspectDynamodbEventLog
import com.seanshubin.vote.tools.commands.InspectDynamodbRaw
import com.seanshubin.vote.tools.commands.InspectDynamodbRawAll
import com.seanshubin.vote.tools.commands.InspectDynamodbRawKeys
import com.seanshubin.vote.tools.commands.InspectDynamodbSyncState
import com.seanshubin.vote.tools.commands.InspectDynamodbTables
import com.seanshubin.vote.tools.commands.InspectDynamodbUsers
import com.seanshubin.vote.tools.commands.InspectDynamodbVoters
import com.seanshubin.vote.tools.commands.InspectMysqlAll
import com.seanshubin.vote.tools.commands.InspectMysqlRawQuery
import com.seanshubin.vote.tools.commands.InspectMysqlRawSchema
import com.seanshubin.vote.tools.commands.LaunchFreshDynamodb
import com.seanshubin.vote.tools.commands.LaunchFreshMysql
import com.seanshubin.vote.tools.commands.LaunchFromSnapshot
import com.seanshubin.vote.tools.commands.LaunchKeepDynamodb
import com.seanshubin.vote.tools.commands.LaunchKeepMysql
import com.seanshubin.vote.tools.commands.LaunchScenarioDynamodb
import com.seanshubin.vote.tools.commands.LaunchScenarioMysql
import com.seanshubin.vote.tools.commands.NukeDynamodb
import com.seanshubin.vote.tools.commands.PadTables
import com.seanshubin.vote.tools.commands.PurgeDynamodb
import com.seanshubin.vote.tools.commands.PurgeMysql
import com.seanshubin.vote.tools.commands.RestoreDynamodb
import com.seanshubin.vote.tools.commands.RollLogs
import com.seanshubin.vote.tools.commands.RunLocal
import com.seanshubin.vote.tools.commands.ServeFrontend
import com.seanshubin.vote.tools.commands.SetInviteCode
import com.seanshubin.vote.tools.commands.SetupTestBallot
import com.seanshubin.vote.tools.commands.TerminateAll
import com.seanshubin.vote.tools.commands.TestLifecycle
import com.seanshubin.vote.tools.commands.WorktreeSync

class VoteDev : CliktCommand(name = "vote-dev") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Developer tool for the vote project. Run with no arguments to list subcommands."

    override fun run() = Unit
}

fun main(args: Array<String>) {
    VoteDev()
        .subcommands(
            CheckJava(),
            DbSetup(),
            DbTeardown(),
            DbReset(),
            DbSetupMysql(),
            DbResetMysql(),
            DbTeardownMysql(),
            DbSetupDynamodb(),
            DbResetDynamodb(),
            DbTeardownDynamodb(),
            DbInitDynamodb(),
            PurgeMysql(),
            PurgeDynamodb(),
            BackupDynamodb(),
            NukeDynamodb(),
            RestoreDynamodb(),
            TerminateAll(),
            RollLogs(),
            ServeFrontend(),
            LaunchFreshMysql(),
            LaunchFreshDynamodb(),
            LaunchKeepMysql(),
            LaunchKeepDynamodb(),
            LaunchFromSnapshot(),
            RunLocal(),
            InspectMysqlAll(),
            InspectMysqlRawQuery(),
            InspectMysqlRawSchema(),
            InspectDynamodbAll(),
            InspectDynamodbTables(),
            InspectDynamodbUsers(),
            InspectDynamodbElections(),
            InspectDynamodbCandidates(),
            InspectDynamodbVoters(),
            InspectDynamodbBallots(),
            InspectDynamodbEventLog(),
            InspectDynamodbSyncState(),
            InspectDynamodbRaw(),
            InspectDynamodbRawAll(),
            InspectDynamodbRawKeys(),
            ConvertScenarios(),
            GenerateScenarioEventLog(),
            PadTables(),
            LaunchScenarioMysql(),
            LaunchScenarioDynamodb(),
            SetupTestBallot(),
            SetInviteCode(),
            TestLifecycle(),
            WorktreeSync()
        )
        .main(args)
}
