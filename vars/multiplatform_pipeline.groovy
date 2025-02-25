import java.text.SimpleDateFormat
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import hudson.plugins.git.GitException
import java.nio.channels.ClosedChannelException
import hudson.remoting.RequestAbortedException
import java.lang.IllegalArgumentException
import java.time.*
import java.time.format.DateTimeFormatter
import jenkins.model.Jenkins
import groovy.transform.Synchronized
import java.util.Iterator
import TestsExecutionType
import groovy.transform.Field


@Field List platformList = []
@Field List testResultList = []
@Field def executeDeploy


@NonCPS
@Synchronized
def getNextTest(Iterator iterator) {
    if (iterator.hasNext()) {
        return iterator.next()
    } else {
        return null
    }
}

@NonCPS
@Synchronized
def changeTestsCount(Map testsLeft, int count, String engine) {
    if (testsLeft && engine) {
        testsLeft[engine] += count
        println("Number of tests left for '${engine}' engine change by '${count}'. Tests left: ${testsLeft[engine]}")
    }
}


def executeTestsNode(String osName, String gpuNames, def executeTests, Map options, Map testsLeft)
{
    if (gpuNames && options['executeTests']) {
        def testTasks = [:]
        gpuNames.split(',').each() {
            String asicName = it

            String taskName = "Test-${asicName}-${osName}"
            
            testTasks[taskName] = {
                stage(taskName) {
                    // if not split - testsList doesn't exists
                    // TODO: replace testsList check to splitExecution var
                    options.testsList = options.testsList ?: ['']

                    def testerLabels
                    if (options.TESTER_TAG) {
                        if (options.TESTER_TAG.contains("PC-") || options.TESTER_TAG.contains("LC-")) {
                            // possibility to test some disabled tester machine
                            testerLabels = "${osName} && ${options.TESTER_TAG} && gpu${asicName}"
                        } else {
                            testerLabels = "${osName} && ${options.TESTER_TAG} && gpu${asicName} && !Disabled"
                        }
                    } else {
                        testerLabels = "${osName} && Tester && gpu${asicName} && !Disabled"
                    }

                    Iterator testsIterator = options.testsList.iterator()
                    Integer launchingGroupsNumber = 1
                    if (!options["parallelExecutionType"] || options["parallelExecutionType"] == TestsExecutionType.TAKE_ONE_NODE_PER_GPU) {
                        launchingGroupsNumber = 1
                    } else if (options["parallelExecutionType"] == TestsExecutionType.TAKE_ALL_NODES) {
                        List possibleNodes = nodesByLabel label: testerLabels, offline: true
                        launchingGroupsNumber = possibleNodes.size() ?: 1
                    }

                    Map testsExecutors = [:]

                    for (int i = 0; i < launchingGroupsNumber; i++) {
                        testsExecutors["Test-${asicName}-${osName}-${i}"] = {
                            String testName = getNextTest(testsIterator)
                            while (testName != null) {
                                String engine = null
                                if (options.engines) {
                                    engine = testName.split("-")[-1]
                                }
                                if (options.skippedTests && options.skippedTests.containsKey(testName) && options.skippedTests[testName].contains("${asicName}-${osName}")) {
                                    println("Test group ${testName} on ${asicName}-${osName} fully skipped")
                                    testName = getNextTest(testsIterator)
                                    changeTestsCount(testsLeft, -1, engine)
                                    continue
                                } 
                                // if there number of errored groups in succession is more than 
                                if (options["errorsInSuccession"] && 
                                        ((engine && options["errorsInSuccession"]["${osName}-${asicName}-${engine}"] && options["errorsInSuccession"]["${osName}-${asicName}-${engine}"].intValue() >= 3)
                                        || (options["errorsInSuccession"]["${osName}-${asicName}"] && options["errorsInSuccession"]["${osName}-${asicName}"].intValue() >= 3))) {
                                    println("Test group ${testName} on ${asicName}-${osName} aborted due to exceeded number of errored groups in succession")
                                    testName = getNextTest(testsIterator)
                                    changeTestsCount(testsLeft, -1, engine)
                                    continue
                                }
                                if (options["abort${osName}"]) {
                                    println("Test group ${testName} on ${asicName}-${osName} aborted due to current context")
                                    testName = getNextTest(testsIterator)
                                    changeTestsCount(testsLeft, -1, engine)
                                    continue
                                }

                                // check that the current configuration shouldn't be skipped
                                if (options.containsKey("skipCallback")) {
                                    Boolean skip = false

                                    if (engine) {
                                        // remove engine name from testName
                                        List testNameParts = testName.split("-") as List
                                        String rawTestName = testNameParts.subList(0, testNameParts.size() - 1).join("-")
                                        skip = options["skipCallback"](options, asicName, osName, rawTestName, engine)
                                    } else {
                                        skip = options["skipCallback"](options, asicName, osName, testName)
                                    }

                                    if (skip) {
                                        println("Test group ${testName} on ${asicName}-${osName} is skipped on pipeline's layer")
                                        testName = getNextTest(testsIterator)
                                        changeTestsCount(testsLeft, -1, engine)
                                        continue
                                    }
                                }

                                println("Scheduling ${osName}:${asicName} ${testName}")

                                Map newOptions = options.clone()
                                newOptions["stage"] = "Test"
                                newOptions["asicName"] = asicName
                                newOptions["osName"] = osName
                                newOptions["taskName"] = taskName
                                newOptions['testResultsName'] = testName ? "testResult-${asicName}-${osName}-${testName}" : "testResult-${asicName}-${osName}"
                                newOptions['stageName'] = testName ? "${asicName}-${osName}-${testName}" : "${asicName}-${osName}"
                                if (!options.splitTestsExecution && testName) {
                                    // case for non splitted projects with multiple engines (e.g. Streaming SDK with multiple games)
                                    newOptions['engine'] = testName
                                    newOptions['tests'] = options.tests
                                } else {
                                    newOptions['tests'] = testName ?: options.tests
                                }

                                def retringFunction = { nodesList, currentTry ->
                                    try {
                                        executeTests(osName, asicName, newOptions)
                                    } catch(Exception e) {
                                        // save expected exception message for add it in report
                                        String expectedExceptionMessage = ""
                                        if (e instanceof ExpectedExceptionWrapper) {
                                            if (e.abortCurrentOS) {
                                                options["abort${osName}"] = true
                                            }
                                            expectedExceptionMessage = e.getMessage()
                                            // check that cause isn't more specific expected exception
                                            if (e.getCause() instanceof ExpectedExceptionWrapper) {
                                                if (e.getCause().abortCurrentOS) {
                                                    options["abort${osName}"] = true
                                                }
                                                expectedExceptionMessage = e.getCause().getMessage()
                                            }
                                        }

                                        println "[ERROR] Failed during tests on ${env.NODE_NAME} node"
                                        println "Exception: ${e.toString()}"
                                        println "Exception message: ${e.getMessage()}"
                                        println "Exception cause: ${e.getCause()}"
                                        println "Exception stack trace: ${e.getStackTrace()}"

                                        String exceptionClassName = e.getClass().toString()
                                        if (exceptionClassName.contains("FlowInterruptedException")) {
                                            e.getCauses().each(){
                                                // UserInterruption aborting by user
                                                // ExceededTimeout aborting by timeout
                                                // CancelledCause for aborting by new commit
                                                String causeClassName = it.getClass().toString()
                                                println "Interruption cause: ${causeClassName}"
                                                if (causeClassName.contains("CancelledCause")) {
                                                    expectedExceptionMessage = "Build was aborted by new commit."
                                                } else if (causeClassName.contains("UserInterruption") || causeClassName.contains("ExceptionCause")) {
                                                    expectedExceptionMessage = "Build was aborted by user."
                                                } else if (utils.isTimeoutExceeded(e) && !expectedExceptionMessage.contains("timeout")) {
                                                    expectedExceptionMessage = "Timeout exceeded (pipelines layer)."
                                                }
                                            }
                                        } else if (exceptionClassName.contains("ClosedChannelException") || exceptionClassName.contains("RemotingSystemException") || exceptionClassName.contains("InterruptedException")) {
                                            expectedExceptionMessage = "Lost connection with machine."
                                        }

                                        // add info about retry to options
                                        boolean added = false;
                                        String testsOrTestPackage
                                        if (newOptions['splitTestsExecution']) {
                                            testsOrTestPackage = newOptions['tests']
                                        } else {
                                            //all non splitTestsExecution builds (e.g. any build of core)
                                            if (testName && !testName.startsWith("-")) {
                                                testsOrTestPackage = testName
                                            } else {
                                                testsOrTestPackage = 'DefaultExecution'
                                            }
                                        }

                                        if (!expectedExceptionMessage) {
                                            expectedExceptionMessage = "Unexpected exception."
                                        }

                                        if (options.containsKey('nodeRetry')) {
                                            // parse united suites
                                            testsOrTestPackageParts = testsOrTestPackage.split("-")
                                            for (failedSuite in testsOrTestPackageParts[0].split()) {
                                                String suiteName
                                                // check engine availability
                                                if (testsOrTestPackageParts.length > 1) {
                                                    suiteName = "${failedSuite}-${testsOrTestPackageParts[1]}"
                                                } else {
                                                    suiteName = "${failedSuite}"
                                                }
                                                Map tryInfo = [host:env.NODE_NAME, link:"${testsOrTestPackage}.${env.NODE_NAME}.retry_${currentTry}.crash.log", exception: expectedExceptionMessage, time: LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))]

                                                retryLoops: for (testers in options['nodeRetry']) {
                                                    if (testers['Testers'].equals(nodesList)){
                                                        for (suite in testers['Tries']) {
                                                            if (suite[suiteName]) {
                                                                suite[suiteName].add(tryInfo)
                                                                added = true
                                                                break retryLoops
                                                            }
                                                        }
                                                        // add list for test group if it doesn't exists
                                                        testers['Tries'].add([(suiteName): [tryInfo]])
                                                        added = true
                                                        break retryLoops
                                                    }
                                                }

                                                if (!added){
                                                    options['nodeRetry'].add([Testers: nodesList, gpuName: asicName, osName: osName, Tries: [[(suiteName): [tryInfo]]]])
                                                }
                                            }

                                            println options['nodeRetry'].inspect()
                                        }

                                        throw e
                                    }
                                }

                                try {
                                    Integer retries_count = options.retriesForTestStage ?: -1
                                    run_with_retries(testerLabels, options.TEST_TIMEOUT, retringFunction, true, "Test", newOptions, [], retries_count, osName)
                                } catch(FlowInterruptedException e) {
                                    options.buildWasAborted = true
                                    e.getCauses().each(){
                                        String causeClassName = it.getClass().toString()
                                        if (causeClassName.contains("CancelledCause") || causeClassName.contains("UserInterruption")) {
                                            throw e
                                        }
                                    }
                                } catch (e) {
                                    // Ignore other exceptions
                                }
                                testName = getNextTest(testsIterator)
                                changeTestsCount(testsLeft, -1, engine)
                            }
                        }                        
                    }

                    parallel testsExecutors
                }
            }
        }
        parallel testTasks
    } else {
        println "[WARNING] No tests found for ${osName}"
    }
}

def executePlatform(String osName, String gpuNames, def executeBuild, def executeTests, Map options, Map testsLeft)
{
    def retNode = {
        try {

            try {
                if (options['executeBuild'] && executeBuild) {
                    stage("Build-${osName}") {
                        def builderLabels = "${osName} && ${options.BUILDER_TAG}"
                        def retringFunction = { nodesList, currentTry ->
                            executeBuild(osName, options)
                        }
                        run_with_retries(builderLabels, options.BUILD_TIMEOUT, retringFunction, false, "Build", options, ['FlowInterruptedException', 'IOException'], -1, osName, true)
                    }
                }
            } catch (e1) {
                if (options.engines) {
                    options.engines.each { engine ->
                        changeTestsCount(testsLeft, -options.testsInfo["testsPer-${engine}-${osName}"], engine)
                    }
                }
                throw e1
            }

            if (options.containsKey('tests') && options.containsKey('testsPackage')){
                if (options['testsPackage'] != 'none' || options['tests'].size() == 0 || !(options['tests'].size() == 1 && options['tests'].get(0).length() == 0)){ // BUG: can throw exception if options['tests'] is string with length 1
                    executeTestsNode(osName, gpuNames, executeTests, options, testsLeft)
                }
            } else {
                executeTestsNode(osName, gpuNames, executeTests, options, testsLeft)
            }

        } catch (e) {
            println "[ERROR] executePlatform throw the exception"
            println "Exception: ${e.toString()}"
            println "Exception message: ${e.getMessage()}"
            println "Exception cause: ${e.getCause()}"
            println "Exception stack trace: ${e.getStackTrace()}"

            String exceptionClassName = e.getClass().toString()
            if (exceptionClassName.contains("FlowInterruptedException")) {
                options.buildWasAborted = true
            }

            currentBuild.result = "FAILURE"
            options.FAILED_STAGES.add(e.toString())
            throw e
        }
    }
    return retNode
}

def shouldExecuteDelpoyStage(Map options) {
    if (options['executeTests']) {
        if (options.containsKey('tests') && options.containsKey('testsPackage')){
            if (options['testsPackage'] == 'none' && options['tests'].size() == 1 && options['tests'].get(0).length() == 0){
                return false
            }
        }
    } else {
        return false
    }

    return true
}

def makeDeploy(Map options, String engine = "") {
    Boolean executeDeployStage = shouldExecuteDelpoyStage(options)

    if (executeDeploy && executeDeployStage) {
        String stageName

        if (options.enginesNames) {
            stageName = engine ? "Deploy-${options.enginesNames[options.engines.indexOf(engine)]}" : "Deploy"
        } else {
            stageName = engine ? "Deploy-${engine}" : "Deploy"
        }

        stage(stageName) {
            def reportBuilderLabels = ""

            if (options.DEPLOY_TAG) {
                reportBuilderLabels = options.DEPLOY_TAG
            } else if (options.PRJ_NAME == "RadeonProImageProcessor" || options.PRJ_NAME == "RadeonML") {
                reportBuilderLabels = "Windows && GitPublisher && !NoDeploy"
            } else {
                reportBuilderLabels = "Windows && Tester && !NoDeploy"
            }

            options["stage"] = "Deploy"
            def retringFunction = { nodesList, currentTry ->
                if (engine) {
                    executeDeploy(options, platformList, testResultList, engine)
                } else {
                    executeDeploy(options, platformList, testResultList)
                }

                if (engine && options.engines.size() > 1 && options.reportUpdater) {
                    options.reportUpdater.updateReport()
                }

                try {
                    dir("summaryTestResults") {
                        if (fileExists("summary_status.json")) {
                            if (engine) {
                                if (options.enginesNames && options.engines) {
                                    String originalEngineName = options.enginesNames[options.engines.indexOf(engine)]
                                    bat("move summary_status.json summary_status_${originalEngineName}.json")
                                    archiveArtifacts artifacts: "summary_status_${originalEngineName}.json"
                                } else {
                                    bat("move summary_status.json summary_status_${engine}.json")
                                    archiveArtifacts artifacts: "summary_status_${engine}.json"
                                }
                            } else {
                                archiveArtifacts artifacts: "summary_status.json"
                            }
                        }
                    }
                } catch (e) {
                    println("[ERROR] Failed to save summary_status.json")
                }

                println("[INFO] Deploy stage finished without unexpected exception. Clean workspace")
                cleanWS("Windows")
            }
            run_with_retries(reportBuilderLabels, options.DEPLOY_TIMEOUT, retringFunction, false, "Deploy", options, [], 3)
        }
    }
}

// TODO: pass platforms only through options (it allows to modify it in PreBuild stage)
def call(String platforms, def executePreBuild, def executeBuild, def executeTests, def executeDeploy, Map options) {
    try {
        this.executeDeploy = executeDeploy

        try {
            setupBuildStoragePolicy()
        } catch (e) {
            println("[ERROR] Failed to setup build storage policty.")
            println(e.toString())
        }

        try {
            options.baseBuildName = currentBuild.displayName
            if (env.BuildPriority) {
                currentBuild.displayName = "${currentBuild.displayName} (Priority: ${env.BuildPriority})"
                println("[INFO] Priority was set by BuildPriority parameter")
            } else {
                def jenkins = Jenkins.getInstance();        
                def views = Jenkins.getInstance().getViews()
                String jobName = env.JOB_NAME.split('/')[0]

                def jobsViews = []
                for (view in views) {
                    if (view.contains(jenkins.getItem(jobName))) {
                        jobsViews.add(view.getDisplayName())
                    }
                }

                if (jobsViews.contains('Autojobs')) {
                    currentBuild.displayName = "${currentBuild.displayName} (Priority: 20)"
                } else if (jobsViews.contains('Large_autojobs') || jobsViews.contains('Plugins Weekly')) {
                    currentBuild.displayName = "${currentBuild.displayName} (Priority: 30)"
                } else {
                    currentBuild.displayName = "${currentBuild.displayName} (Priority: 40)"
                }
                println("[INFO] Priority was set based on view of job")
            }
        } catch (e) {
            println("[ERROR] Failed to add priority into build name")
            println(e.toString())
        }

        if (env.CHANGE_URL) {
            def buildNumber = env.BUILD_NUMBER as int
            if (buildNumber > 1) milestone(buildNumber - 1)
            milestone(buildNumber) 
        } 

        def date = new Date()
        dateFormatter = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss")
        dateFormatter.setTimeZone(TimeZone.getTimeZone("GMT+3:00"))
        options.JOB_STARTED_TIME = dateFormatter.format(date)
        
        timestamps
        {
            String PRJ_PATH="${options.PRJ_ROOT}/${options.PRJ_NAME}"
            String REF_PATH="${PRJ_PATH}/ReferenceImages"
            String JOB_PATH="${PRJ_PATH}/${JOB_NAME}/Build-${BUILD_ID}".replace('%2F', '_')
            options['PRJ_PATH']="${PRJ_PATH}"
            options['REF_PATH']="${REF_PATH}"
            options['JOB_PATH']="${JOB_PATH}"

            if(options.get('BUILDER_TAG', '') == '')
                options['BUILDER_TAG'] = 'Builder'

            // if timeout doesn't set - use default (value in minutes)
            options['PREBUILD_TIMEOUT'] = options['PREBUILD_TIMEOUT'] ?: 20
            options['BUILD_TIMEOUT'] = options['BUILD_TIMEOUT'] ?: 40
            options['TEST_TIMEOUT'] = options['TEST_TIMEOUT'] ?: 20
            options['DEPLOY_TIMEOUT'] = options['DEPLOY_TIMEOUT'] ?: 20

            options['FAILED_STAGES'] = []

            Map testsLeft = [:]

            try {
                if (executePreBuild) {
                    node("Windows && PreBuild") {
                        ws("WS/${options.PRJ_NAME}_Build") {
                            stage("PreBuild") {
                                try {
                                    timeout(time: "${options.PREBUILD_TIMEOUT}", unit: 'MINUTES') {
                                        options["stage"] = "PreBuild"
                                        executePreBuild(options)
                                        if(!options['executeBuild']) {
                                            options.CBR = 'SKIPPED'
                                            echo "Build SKIPPED"
                                        }
                                    }
                                } catch (e) {
                                    println("[ERROR] Failed during prebuild stage on ${env.NODE_NAME}")
                                    println(e.toString())
                                    println(e.getMessage())
                                    String exceptionClassName = e.getClass().toString()
                                    if (exceptionClassName.contains("FlowInterruptedException")) {
                                        e.getCauses().each(){
                                            String causeClassName = it.getClass().toString()
                                            if (causeClassName.contains("ExceededTimeout")) {
                                                if (options.problemMessageManager) {
                                                    options.problemMessageManager.saveSpecificFailReason(NotificationConfiguration.TIMEOUT_EXCEEDED, "PreBuild")
                                                }
                                            }
                                        }
                                    }
                                    if (options.problemMessageManager) {
                                        options.problemMessageManager.saveGeneralFailReason(NotificationConfiguration.UNKNOWN_REASON, "PreBuild")
                                    }
                                    GithubNotificator.closeUnfinishedSteps(options, NotificationConfiguration.PRE_BUILD_STAGE_FAILED)
                                    throw e
                                }
                            }
                        }
                    }
                }

                options.testsInfo = [:]
                if (options.engines) {
                    options['testsList'].each() { testName ->
                        String engine = testName.split("-")[-1]
                        if (!options.testsInfo.containsKey("testsPer-" + engine)) {
                            options.testsInfo["testsPer-${engine}"] = 0
                        }
                        options.testsInfo["testsPer-${engine}"]++
                    }
                }

                Map tasks = [:]

                if (options.platforms) {
                    platforms = options.platforms
                }

                platforms.split(';').each() {
                    if (it) {
                        List tokens = it.tokenize(':')
                        String osName = tokens.get(0)
                        String gpuNames = ""

                        Map newOptions = options.clone()
                        newOptions["stage"] = "Build"
                        newOptions["osName"] = osName

                        if (tokens.size() > 1) {
                            gpuNames = tokens.get(1)
                        }

                        platformList << osName
                        if (gpuNames) {
                            gpuNames.split(',').each() {
                                // if not split - testsList doesn't exists
                                newOptions.testsList = newOptions.testsList ?: ['']
                                newOptions['testsList'].each() { testName ->
                                    String asicName = it
                                    String testResultItem = testName ? "testResult-${asicName}-${osName}-${testName}" : "testResult-${asicName}-${osName}"
                                    testResultList << testResultItem
                                }

                                if (options.engines) {
                                    options.engines.each { engine ->
                                        if (!testsLeft.containsKey(engine)) {
                                            testsLeft[engine] = 0
                                        }
                                        testsLeft[engine] += (options.testsInfo["testsPer-${engine}"] ?: 0)

                                        if (!options.testsInfo.containsKey("testsPer-" + engine + "-" + osName)) {
                                            options.testsInfo["testsPer-${engine}-${osName}"] = 0
                                        }
                                        options.testsInfo["testsPer-${engine}-${osName}"] += (options.testsInfo["testsPer-${engine}"] ?: 0)
                                    }
                                }
                            }
                        }

                        tasks[osName]=executePlatform(osName, gpuNames, executeBuild, executeTests, newOptions, testsLeft)
                    }
                }

                println "Tests Info: ${options.testsInfo}"

                println "Tests Left: ${testsLeft}"

                if (options.engines) {
                    options.engines.each { engine ->
                        String stageName

                        if (options.enginesNames) {
                            stageName = "Deploy-${options.enginesNames[options.engines.indexOf(engine)]}"
                        } else {
                            stageName = "Deploy-${engine}"
                        }

                        tasks[stageName] = {
                            if (testsLeft[engine] != null) {
                                while (testsLeft[engine] != 0) {
                                    sleep(120)
                                }
                                makeDeploy(options, engine)
                            }
                        }
                    }
                }

                parallel tasks
            } catch (e) {
                println(e.toString())
                println(e.getMessage())
                currentBuild.result = "FAILURE"
                String exceptionClassName = e.getClass().toString()
                if (exceptionClassName.contains("FlowInterruptedException")) {
                    options.buildWasAborted = true
                    e.getCauses().each(){
                        // UserInterruption aborting by user
                        // ExceededTimeout aborting by timeout
                        // CancelledCause for aborting by new commit
                        String causeClassName = it.getClass().toString()
                        if (causeClassName.contains("CancelledCause")) {
                            executeDeploy = null
                        }
                    }
                }
            } finally {
                if (!options.engines) {
                    makeDeploy(options)
                } else {
                    Map tasks = [:]

                    options.engines.each {
                        if (testsLeft && testsLeft[it] != 0) {
                            // Build was aborted. Make reports from existing data
                            String stageName

                            if (options.enginesNames) {
                                stageName = "Deploy-${options.enginesNames[options.engines.indexOf(it)]}"
                            } else {
                                stageName = "Deploy-${it}"
                            }
                            tasks[stageName] = {
                                makeDeploy(options, it)
                            }
                        }
                    }

                    if (tasks) {
                        parallel tasks
                    }
                }
            }
        }
    } catch (FlowInterruptedException e) {
        println(e.toString())
        println(e.getMessage())
        options.buildWasAborted = true
        println("Job was ABORTED by user. Job status: ${currentBuild.result}")
    } catch (e) {
        currentBuild.result = "FAILURE"
        throw e
    } finally {
        println("enableNotifications = ${options.enableNotifications}")
        if ("${options.enableNotifications}" == "true") {
            SlackUtils.sendBuildStatusNotification(this,
                                        currentBuild.result,
                                        options.get('slackWorkspace', SlackUtils.SlackWorkspace.DEFAULT),
                                        options.get('slackChannel', 'cis_stream'),
                                        options)
        }

        println("Send Slack message to debug channels")
        SlackUtils.sendBuildStatusToDebugChannel(this, options)

        println("[INFO] BUILD RESULT: ${currentBuild.result}")
        println("[INFO] BUILD CURRENT RESULT: ${currentBuild.currentResult}")
    }
}
