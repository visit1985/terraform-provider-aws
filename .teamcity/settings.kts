import jetbrains.buildServer.configs.kotlin.v2019_2.* // ktlint-disable no-wildcard-imports
import jetbrains.buildServer.configs.kotlin.v2019_2.buildFeatures.golang
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2019_2.triggers.schedule
import java.io.File
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

version = "2020.2"

val defaultRegion = DslContext.getParameter("default_region")
val alternateRegion = DslContext.getParameter("alternate_region", "")
val acmCertificateRootDomain = DslContext.getParameter("acm_certificate_root_domain", "")
val sweeperRegions = DslContext.getParameter("sweeper_regions")
val awsAccountID = DslContext.getParameter("aws_account.account_id")
val acctestParallelism = DslContext.getParameter("acctest_parallelism", "")
val tfAccAssumeRoleArn = DslContext.getParameter("tf_acc_assume_role_arn", "")
val awsAlternateAccountID = DslContext.getParameter("aws_alternate_account.account_id", "")
val awsAlternateAccessKeyID = DslContext.getParameter("aws_alternate_account.access_key_id", "")
val awsAlternateSecretAccessKey = DslContext.getParameter("aws_alternate_account.secret_access_key", "")
val tfLog = DslContext.getParameter("tf_log", "")

// Legacy User credentials
val legacyAWSAccessKeyID = DslContext.getParameter("aws_account.legacy_access_key_id", "")
val legacyAWSSecretAccessKey = DslContext.getParameter("aws_account.legacy_secret_access_key", "")

// Assume Role credentials
val awsAccessKeyID = DslContext.getParameter("aws_account.access_key_id", "")
val awsSecretAccessKey = DslContext.getParameter("aws_account.secret_access_key", "")
val accTestRoleARN = DslContext.getParameter("aws_account.role_arn", "")

project {
    if (DslContext.getParameter("build_full", "true").toBoolean()) {
        buildType(FullBuild)
    }

    if (DslContext.getParameter("build_pullrequest", "").toBoolean() || DslContext.getParameter("pullrequest_build", "").toBoolean()) {
        buildType(PullRequest)
    }

    if (DslContext.getParameter("build_sweeperonly", "").toBoolean()) {
        buildType(Sweeper)
    }

    params {
        if (acctestParallelism != "") {
            text("ACCTEST_PARALLELISM", acctestParallelism, allowEmpty = false)
        }
        text("TEST_PATTERN", "TestAcc", display = ParameterDisplay.HIDDEN)
        text("SWEEPER_REGIONS", sweeperRegions, display = ParameterDisplay.HIDDEN, allowEmpty = false)
        text("env.AWS_ACCOUNT_ID", awsAccountID, display = ParameterDisplay.HIDDEN, allowEmpty = false)
        text("env.AWS_DEFAULT_REGION", defaultRegion, allowEmpty = false)
        text("env.TF_LOG", tfLog)

        if (awsAlternateAccountID != "" || awsAlternateAccessKeyID != "" || awsAlternateSecretAccessKey != "") {
            text("env.AWS_ALTERNATE_ACCOUNT_ID", awsAlternateAccountID, display = ParameterDisplay.HIDDEN)
            password("env.AWS_ALTERNATE_ACCESS_KEY_ID", awsAlternateAccessKeyID, display = ParameterDisplay.HIDDEN)
            password("env.AWS_ALTERNATE_SECRET_ACCESS_KEY", awsAlternateSecretAccessKey, display = ParameterDisplay.HIDDEN)
        }

        if (alternateRegion != "") {
            text("env.AWS_ALTERNATE_REGION", alternateRegion)
        }

        if (acmCertificateRootDomain != "") {
            text("env.ACM_CERTIFICATE_ROOT_DOMAIN", acmCertificateRootDomain, display = ParameterDisplay.HIDDEN)
        }

        val securityGroupRulesPerGroup = DslContext.getParameter("security_group_rules_per_group", "")
        if (securityGroupRulesPerGroup != "") {
            text("env.EC2_SECURITY_GROUP_RULES_PER_GROUP_LIMIT", securityGroupRulesPerGroup)
        }

        val branchName = DslContext.getParameter("branch_name", "")
        if (branchName != "") {
            text("BRANCH_NAME", branchName, display = ParameterDisplay.HIDDEN)
        }

        if (tfAccAssumeRoleArn != "") {
            text("env.TF_ACC_ASSUME_ROLE_ARN", tfAccAssumeRoleArn)
        }

        // Legacy User credentials
        if (legacyAWSAccessKeyID != "") {
            password("env.AWS_ACCESS_KEY_ID", legacyAWSAccessKeyID, display = ParameterDisplay.HIDDEN)
        }
        if (legacyAWSSecretAccessKey != "") {
            password("env.AWS_SECRET_ACCESS_KEY", legacyAWSSecretAccessKey, display = ParameterDisplay.HIDDEN)
        }

        // Assume Role credentials
        if (awsAccessKeyID != "") {
            password("AWS_ACCESS_KEY_ID", awsAccessKeyID, display = ParameterDisplay.HIDDEN)
        }
        if (awsSecretAccessKey != "") {
            password("AWS_SECRET_ACCESS_KEY", awsSecretAccessKey, display = ParameterDisplay.HIDDEN)
        }
        text("ACCTEST_ROLE_ARN", accTestRoleARN, display = ParameterDisplay.HIDDEN)

        // Define this parameter even when not set to allow individual builds to set the value
        text("env.TF_ACC_TERRAFORM_VERSION", DslContext.getParameter("terraform_version", ""))

        // These should be overridden in the base AWS project
        param("env.GOPATH", "")
        param("env.GO111MODULE", "") // No longer needed as of Go 1.16
        param("env.GO_VERSION", "") // We're using `goenv` and `.go-version`
    }

    subProject(Services)
}

object PullRequest : BuildType({
    name = "Pull Request"

    vcs {
        root(AbsoluteId(DslContext.getParameter("vcs_root_id")))

        cleanCheckout = true
    }

    failureConditions {
        val defaultPullRequestTimeoutHours: Long = 6
        executionTimeoutMin = Duration.ofHours(defaultPullRequestTimeoutHours).toMinutes().toInt()
    }

    steps {
        script {
            name = "Setup GOENV"
            scriptContent = File("./scripts/setup_goenv.sh").readText()
        }
        script {
            name = "Run Tests"
            scriptContent = File("./scripts/pullrequest_tests/tests.sh").readText()
        }
    }

    features {
        feature {
            type = "JetBrains.SharedResources"
            param("locks-param", "${DslContext.getParameter("aws_account.lock_id")} readLock")
        }
        val alternateAccountLockId = DslContext.getParameter("aws_alternate_account.lock_id", "")
        if (alternateAccountLockId != "") {
            feature {
                type = "JetBrains.SharedResources"
                param("locks-param", "$alternateAccountLockId readLock")
            }
        }
    }
})

object FullBuild : BuildType({
    name = "Service Tests"

    type = BuildTypeSettings.Type.COMPOSITE

    vcs {
        showDependenciesChanges = true
    }

    dependencies {
        snapshot(SetUp) {
            reuseBuilds = ReuseBuilds.NO
            onDependencyFailure = FailureAction.ADD_PROBLEM
            onDependencyCancel = FailureAction.IGNORE
        }

        val testType = DslContext.getParameter("test_type", "")
        val serviceList = if (testType == "orgacct") orgacctServices else services
        serviceList.forEach { (serviceName, displayName) ->
            snapshot(Service(serviceName, displayName).buildType()) {
                reuseBuilds = ReuseBuilds.NO
                onDependencyFailure = FailureAction.ADD_PROBLEM
                onDependencyCancel = FailureAction.IGNORE
            }
        }

        snapshot(CleanUp) {
            reuseBuilds = ReuseBuilds.NO
            onDependencyFailure = FailureAction.IGNORE
            onDependencyCancel = FailureAction.IGNORE
        }
    }

    val runNightly = DslContext.getParameter("run_nightly_build", "")
    if (runNightly.toBoolean()) {
        val triggerTimeRaw = DslContext.getParameter("trigger_time")
        val formatter = DateTimeFormatter.ofPattern("HH':'mm' 'VV")
        val triggerTime = formatter.parse(triggerTimeRaw)
        val triggerDay = if (DslContext.getParameter("trigger_day", "") != "") {
            DslContext.getParameter("trigger_day", "")
        } else {
            "Sun-Thu"
        }
        triggers {
            schedule {
                schedulingPolicy = cron {
                    dayOfWeek = triggerDay
                    val triggerHM = LocalTime.from(triggerTime)
                    hours = triggerHM.getHour().toString()
                    minutes = triggerHM.getMinute().toString()
                    timezone = ZoneId.from(triggerTime).toString()
                }
                branchFilter = "" // For a Composite build, the branch filter must be empty
                triggerBuild = always()
                withPendingChangesOnly = false
                enableQueueOptimization = false
                enforceCleanCheckoutForDependencies = true
            }
        }
    }

    features {
        feature {
            type = "JetBrains.SharedResources"
            param("locks-param", "${DslContext.getParameter("aws_account.lock_id")} writeLock")
        }
        val alternateAccountLockId = DslContext.getParameter("aws_alternate_account.lock_id", "")
        if (alternateAccountLockId != "") {
            feature {
                type = "JetBrains.SharedResources"
                param("locks-param", "$alternateAccountLockId readLock")
            }
        }
    }
})

object SetUp : BuildType({
    name = "1. Set Up"

    vcs {
        root(AbsoluteId(DslContext.getParameter("vcs_root_id")))

        cleanCheckout = true
    }

    steps {
        script {
            name = "Setup GOENV"
            scriptContent = File("./scripts/setup_goenv.sh").readText()
        }
        script {
            name = "Run provider unit tests"
            scriptContent = File("./scripts/provider_tests/unit_tests.sh").readText()
        }
        script {
            name = "Run provider acceptance tests"
            scriptContent = File("./scripts/provider_tests/acceptance_tests.sh").readText()
        }
        script {
            name = "Pre-Sweeper"
            executionMode = BuildStep.ExecutionMode.RUN_ON_FAILURE
            scriptContent = File("./scripts/sweeper.sh").readText()
        }
    }

    features {
        golang {
            testFormat = "json"
        }
    }
})

object Services : Project({
    id = DslContext.createId("Services")

    name = "Services"

    val buildChain = sequential {
        buildType(SetUp)

        val testType = DslContext.getParameter("test_type", "")
        val serviceList = if (testType == "orgacct") orgacctServices else services
        parallel(options = { onDependencyFailure = FailureAction.IGNORE }) {
            serviceList.forEach { (serviceName, displayName) ->
                buildType(Service(serviceName, displayName).buildType())
            }
        }

        buildType(CleanUp, options = {
            reuseBuilds = ReuseBuilds.NO
            onDependencyFailure = FailureAction.IGNORE
            onDependencyCancel = FailureAction.IGNORE
        })
    }
    buildChain.buildTypes().forEach { buildType(it) }
})

object CleanUp : BuildType({
    name = "3. Clean Up"

    vcs {
        root(AbsoluteId(DslContext.getParameter("vcs_root_id")))

        cleanCheckout = true
    }

    steps {
        script {
            name = "Setup GOENV"
            enabled = false
            scriptContent = File("./scripts/setup_goenv.sh").readText()
        }
        script {
            name = "Post-Sweeper"
            enabled = false
            scriptContent = File("./scripts/sweeper.sh").readText()
        }
    }
})

object Sweeper : BuildType({
    name = "Sweeper"

    vcs {
        root(AbsoluteId(DslContext.getParameter("vcs_root_id")))

        cleanCheckout = true
    }

    steps {
        script {
            name = "Setup GOENV"
            scriptContent = File("./scripts/setup_goenv.sh").readText()
        }
        script {
            name = "Sweeper"
            scriptContent = File("./scripts/sweeper.sh").readText()
        }
    }

    val triggerTimeRaw = DslContext.getParameter("sweeper_trigger_time", "")
    if (triggerTimeRaw != "") {
        val formatter = DateTimeFormatter.ofPattern("HH':'mm' 'VV")
        val triggerTime = formatter.parse(triggerTimeRaw)
        triggers {
            schedule {
                schedulingPolicy = daily {
                    val triggerHM = LocalTime.from(triggerTime)
                    hour = triggerHM.getHour()
                    minute = triggerHM.getMinute()
                    timezone = ZoneId.from(triggerTime).toString()
                }
                branchFilter = "+:refs/heads/main"
                triggerBuild = always()
                withPendingChangesOnly = false
                enableQueueOptimization = false
                enforceCleanCheckoutForDependencies = true
            }
        }
    }

    features {
        feature {
            type = "JetBrains.SharedResources"
            param("locks-param", "${DslContext.getParameter("aws_account.lock_id")} writeLock")
        }
    }
})
