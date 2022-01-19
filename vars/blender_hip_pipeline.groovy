import groovy.transform.Field
import groovy.json.JsonOutput
import universe.*
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger
import groovy.transform.Synchronized


@NonCPS
@Synchronized
def saveBlenderInfo(String osName, String blenderVersion, String blenderHash) {
    if (currentBuild.description) {
        currentBuild.description += "<b>${osName} Blender version/hash:</b> ${blenderVersion}/${blenderHash}<br/>"
    } else {
        currentBuild.description = "<b>${osName} Blender version/hash:</b> ${blenderVersion}/${blenderHash}<br/>"
    }
}

String installBlender(String osName) {
    switch(osName) {
        case "Windows":
            bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe x' + " \"Blender_*.zip\"")
            bat(script: "move blender-* daily_blender_build")

            return "${env.WORKSPACE}\\daily_blender_build\\blender.exe"
        default:
            throw new Exception("Unexpected OS name ${osName}")
    }
}

def executeTestCommand(String osName, String asicName, String blenderLocation, String optionName, Map options) {
    timeout(time: options.TEST_TIMEOUT, unit: 'MINUTES') {
        dir('scripts') {
            switch(osName) {
                case 'Windows':
                    bat """
                        run.bat ${options.renderDevice} ${options.testsPackage} \"${options.tests}\" ${options.resX} ${options.resY} ${options.SPU} ${options.iter} ${options.theshold} Cycles_${optionName}  ${options.toolVersion} ${options.testCaseRetries} ${options.updateRefs} ${blenderLocation} 1>> \"..\\${options.stageName}_${optionName}_${options.currentTry}.log\"  2>&1
                    """
                    break
                case 'OSX':
                    println("Unsupported OS")
                    break

                default:
                    println("Unsupported OS")
            }
        }
    }
}

def executeTests(String osName, String asicName, Map options) {
    // used for mark stash results or not. It needed for not stashing failed tasks which will be retried.
    Boolean stashResults = true
    try {
        String blenderLocation = ""

        withNotifications(title: options["stageName"], options: options, logUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
            timeout(time: "5", unit: "MINUTES") {
                cleanWS(osName)
                checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)
            }
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_BLENDER) {
            makeUnstash(name: getProduct.getStashName(osName), unzip: false, storeOnNAS: options.storeOnNAS)
            blenderLocation = installBlender(osName)
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.DOWNLOAD_SCENES) {
            String assets_dir = isUnix() ? "${CIS_TOOLS}/../TestResources/rpr_blender_autotests_assets" : "/mnt/c/TestResources/rpr_blender_autotests_assets"
            downloadFiles("/volume1/Assets/rpr_blender_autotests/", assets_dir)
        }

        withNotifications(title: options["stageName"], options: options, configuration: NotificationConfiguration.EXECUTE_TESTS) {
            options.cyclesDevices.each { device ->
                executeTestCommand(osName, asicName, blenderLocation, device, options)
                utils.moveFiles(this, osName, "Work", "Work-${device}")
            }
        }

        options.executeTestsFinished = true
    } catch (e) {
        if (options.currentTry < options.nodeReallocateTries) {
            stashResults = false
        }

        if (e instanceof ExpectedExceptionWrapper) {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
            throw new ExpectedExceptionWrapper(e.getMessage(), e.getCause())
        } else {
            GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, "${BUILD_URL}")
            throw new ExpectedExceptionWrapper(NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, e)
        }
    } finally {
        try {
            dir(options.stageName) {
                utils.moveFiles(this, osName, "../*.log", ".")
                utils.moveFiles(this, osName, "../scripts/*.log", ".")
                utils.renameFile(this, osName, "launcher.engine.log", "${options.stageName}_engine_${options.currentTry}.log")
            }
            archiveArtifacts artifacts: "${options.stageName}/*.log", allowEmptyArchive: true

            if (stashResults) {
                options.cyclesDevices.each { device ->
                    dir("Work-${device}/Results/Blender") {
                        makeStash(includes: "**/*", name: "${options.testResultsName}-${device}", storeOnNAS: options.storeOnNAS)
                    }
                }
            } else {
                println "[INFO] Task ${options.tests} on ${options.nodeLabels} labels will be retried."
            }
        } catch (e) {
            // throw exception in finally block only if test stage was finished
            if (options.executeTestsFinished) {
                if (e instanceof ExpectedExceptionWrapper) {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, e.getMessage(), "${BUILD_URL}")
                    throw e
                } else {
                    GithubNotificator.updateStatus("Test", options['stageName'], "failure", options, NotificationConfiguration.FAILED_TO_SAVE_RESULTS, "${BUILD_URL}")
                    throw new ExpectedExceptionWrapper(NotificationConfiguration.FAILED_TO_SAVE_RESULTS, e)
                }
            }
        }
    }
}

def downloadBlender(String osName, String blenderLink, String blenderVersion) {
    String packageName

    switch(osName) {
        case "Windows":
            packageName = "Blender_${osName}_${blenderVersion}.zip"
            break
        case "MacOS":
        case "MacOS_ARM":
            packageName = "Blender_${osName}_${blenderVersion}.dmg"
            break
        default:
            packageName = "Blender_${osName}_${blenderVersion}.tar.xz"
    }

    if (isUnix()) {
        sh """
            curl --retry 5 -L -J -o "${packageName}" "${blenderLink}"
        """
    } else {
        bat """
            curl --retry 5 -L -J -o "${packageName}" "${blenderLink}"
        """
    }

    return packageName
}

def executeBuild(String osName, Map options) {
    outputEnvironmentInfo(osName)

    withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_BLENDER) {
        String blenderLink = python3("${CIS_TOOLS}/find_daily_blender_link.py --os_name ${osName}").trim().split("\n")[-1]

        String blenderVersion = blenderLink.split("-")[1]
        String blenderHash = blenderLink.split("-")[2].split("\\.")[1]

        saveBlenderInfo(osName, blenderVersion, blenderHash)

        String packageName = downloadBlender(osName, blenderLink, blenderVersion)

        String artifactURL = makeArchiveArtifacts(name: packageName, storeOnNAS: options.storeOnNAS)

        makeStash(includes: packageName, name: getProduct.getStashName(osName), preZip: false, storeOnNAS: options.storeOnNAS)
    }
}

def executePreBuild(Map options) {
    // manual job
    if (!env.BRANCH_NAME) {
        println "[INFO] Manual job launch detected"
        options['executeBuild'] = true
        options['executeTests'] = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options['executeBuild'] = true
            options['executeTests'] = true
            options['testsPackage'] = "regression.json"
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
           options['executeBuild'] = true
           options['executeTests'] = true
           options['testsPackage'] = "regression.json"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
            options['testsPackage'] = "regression.json"
        }
    }

    def tests = []
    options.timeouts = [:]

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        dir('jobs_test_blender') {
            checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

            options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            dir('jobs_launcher') {
                options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            }
            println "[INFO] Test branch hash: ${options['testsBranch']}"

            if (options.testsPackage != "none") {
                def groupNames = readJSON(file: "jobs/${options.testsPackage}")["groups"].collect { it.key }
                options.tests = groupNames.join(" ")
                options.testsPackage = "none"
            }

            options.testsList = [""]
        }
    }
}

def executeDeploy(Map options, List platformList, List testResultList) {
    try {
        if (options['executeTests'] && testResultList) {
            withNotifications(title: "Building test report", options: options, startUrl: "${BUILD_URL}", configuration: NotificationConfiguration.DOWNLOAD_TESTS_REPO) {
                checkoutScm(branchName: "northstar_baselines_compare_dev", repositoryUrl: "git@github.com:luxteam/jobs_launcher.git")
            }

            dir("summaryTestResults") {
                testResultList.each {
                    dir("RPR") {
                        dir(it.replace("testResult-", "")) {
                            try {
                                makeUnstash(name: "${it}-HIP", storeOnNAS: options.storeOnNAS)
                            } catch (e) {
                                println("Can't unstash ${it}-HIP")
                                println(e.toString())
                            }
                        }
                    }

                    dir("NorthStar") {
                        dir(it.replace("testResult-", "")) {
                            try {
                                makeUnstash(name: "${it}-${options.cyclesDevices[1]}", storeOnNAS: options.storeOnNAS)
                            } catch (e) {
                                println("Can't unstash ${it}-${options.cyclesDevices[1]}")
                                println(e.toString())
                            }
                        }
                    }
                }
            }

            try {
                GithubNotificator.updateStatus("Deploy", "Building test report", "in_progress", options, NotificationConfiguration.BUILDING_REPORT, "${BUILD_URL}")
                withEnv(["FIRST_ENGINE_NAME=${options.cyclesDevices[0]}", "SECOND_ENGINE_NAME=${options.cyclesDevices[1]}", "SHOW_SYNC_TIME=false", 
                    "SHOW_RENDER_LOGS=true", "REPORT_TOOL=BlenderHIP", "USE_BASELINES=false"]) {

                    bat """
                        build_performance_comparison_report.bat summaryTestResults\\\\RPR summaryTestResults\\\\NorthStar summaryTestResults
                    """
                }
            } catch (e) {
                String errorMessage = utils.getReportFailReason(e.getMessage())
                GithubNotificator.updateStatus("Deploy", "Building test report", "failure", options, errorMessage, "${BUILD_URL}")
                if (utils.isReportFailCritical(e.getMessage())) {
                    options.problemMessageManager.saveSpecificFailReason(errorMessage, "Deploy")
                    println """
                        [ERROR] Failed to build test report.
                        ${e.toString()}
                    """
                    if (!options.testDataSaved) {
                        try {
                            // Save test data for access it manually anyway
                            utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", "Test Report", "Summary Report, Performance Report, Compare Report")
                            options.testDataSaved = true
                        } catch(e1) {
                            println """
                                [WARNING] Failed to publish test data.
                                ${e1.toString()}
                                ${e.toString()}
                            """
                        }
                    }
                    throw e
                } else {
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(errorMessage)
                }
            }

            Map summaryTestResults = ["passed": 0, "failed": 0, "error": 0]
            try {
                def summaryReport = readJSON file: 'summaryTestResults/summary_report.json'

                summaryReport.each { configuration ->
                    summaryTestResults["passed"] += configuration.value["summary"]["passed"]
                    summaryTestResults["failed"] += configuration.value["summary"]["failed"]
                    summaryTestResults["error"] += configuration.value["summary"]["error"]
                }

                if (summaryTestResults["error"] > 0) {
                    println("[INFO] Some tests marked as error. Build result = FAILURE.")
                    currentBuild.result = "FAILURE"
                    options.problemMessageManager.saveGlobalFailReason(NotificationConfiguration.SOME_TESTS_ERRORED)
                }
                else if (summaryTestResults["failed"] > 0) {
                    println("[INFO] Some tests marked as failed. Build result = UNSTABLE.")
                    currentBuild.result = "UNSTABLE"
                    options.problemMessageManager.saveUnstableReason(NotificationConfiguration.SOME_TESTS_FAILED)
                }
            } catch(e) {
                println """
                    [ERROR] CAN'T GET TESTS STATUS
                    ${e.toString()}
                """
                options.problemMessageManager.saveUnstableReason(NotificationConfiguration.CAN_NOT_GET_TESTS_STATUS)
                currentBuild.result = "UNSTABLE"
            }

            withNotifications(title: "Building test report", options: options, configuration: NotificationConfiguration.PUBLISH_REPORT) {
                utils.publishReport(this, "${BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html", \
                    "Test Report", "Summary Report, Performance Report")
                if (summaryTestResults) {
                    // add in description of status check information about tests statuses
                    // Example: Report was published successfully (passed: 69, failed: 11, error: 0)
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, "${NotificationConfiguration.REPORT_PUBLISHED} Results: passed - ${summaryTestResults.passed}, failed - ${summaryTestResults.failed}, error - ${summaryTestResults.error}.", "${BUILD_URL}/Test_20Report")
                } else {
                    GithubNotificator.updateStatus("Deploy", "Building test report", "success", options, NotificationConfiguration.REPORT_PUBLISHED, "${BUILD_URL}/Test_20Report")
                }
            }
        }
    } catch (e) {
        println(e.toString())
        throw e
    }
}


def call(String testsBranch = "master",
    String platforms = 'Windows',
    String configuration = 'AMD_HIP_CPU',
    Boolean enableNotifications = true,
    String testsPackage = "",
    String tests = "",
    String testerTag = "Blender",
    Integer testCaseRetries = 3)
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []
    Map errorsInSuccession = [:]

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            gpusCount = 0
            platforms.split(';').each() { platform ->
                List tokens = platform.tokenize(':')
                if (tokens.size() > 1) {
                    gpuNames = tokens.get(1)
                    gpuNames.split(',').each() {
                        gpusCount += 1
                    }
                }
            }

            List cyclesDevices = configuration.split("_", 2)[1].split("_") as List

            println "Platforms: ${platforms}"
            println "Tests: ${tests}"
            println "Tests package: ${testsPackage}"
            println "Cycles render devices: ${cyclesDevices}"

            options << [testRepo:"git@github.com:luxteam/jobs_test_blender.git",
                        testsBranch:testsBranch,
                        enableNotifications:enableNotifications,
                        testsPackage:testsPackage,
                        testsPackageOriginal:testsPackage,
                        tests:tests,
                        cyclesDevices:cyclesDevices,
                        PRJ_NAME:"BlenderHIP",
                        PRJ_ROOT:"rpr-plugins",
                        reportName:'Test_20Report',
                        gpusCount:gpusCount,
                        TEST_TIMEOUT:75,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:60,
                        DEPLOY_TIMEOUT:30,
                        TESTER_TAG:testerTag,
                        resX: "0",
                        resY: "0",
                        SPU: "25",
                        iter: "50",
                        theshold: "0.05",
                        nodeRetry: nodeRetry,
                        errorsInSuccession: errorsInSuccession,
                        platforms:platforms,
                        testCaseRetries:testCaseRetries,
                        storeOnNAS: true
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
