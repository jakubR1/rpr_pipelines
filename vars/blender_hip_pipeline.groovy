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

def executeTestCommand(String osName, String asicName, Map options) {
    // TODO: to be implemented
}

def executeTests(String osName, String asicName, Map options) {
    // TODO: to be implemented
}

def downloadBlender(String osName, String blenderLink) {
    String packageName

    switch(osName) {
        case "Windows":
            packageName = "${osName}.zip"
            break
        case "MacOS":
        case "MacOS_ARM":
            packageName = "${osName}.dmg"
            break
        default:
            packageName = "${osName}.tar.xz"
    }

    bat """
        curl --retry 5 -L -J -o "${packageName}" "${blenderLink}"
    """

    return packageName
}

def executeBuild(String osName, Map options) {
    outputEnvironmentInfo(osName)

    withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_BLENDER) {
        String blenderLink = python3("${CIS_TOOLS}/find_daily_blender_link.py --os_name ${osName}")

        String blenderVersion = blenderLink.split("-")[1]
        String blenderHash = blenderLink.split("-")[3].split("\\.")[1]

        saveBlenderInfo(osName, blenderVersion, blenderHash)

        String packageName = downloadBlender(osName, blenderLink)

        String artifactURL = makeArchiveArtifacts(name: packageName, storeOnNAS: options.storeOnNAS)
    }
}

def getReportBuildArgs(String engineName, Map options) {
    // TODO: to be implemented
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
        dir('jobs_test_hybrid_vs_ns') {
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
    // TODO: to be implemented
}


def call(String testsBranch = "master",
    String platforms = 'Windows',
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

            println "Platforms: ${platforms}"
            println "Tests: ${tests}"
            println "Tests package: ${testsPackage}"

            options << [testRepo:"git@github.com:luxteam/jobs_test_blender.git",
                        testsBranch:testsBranch,
                        enableNotifications:enableNotifications,
                        testsPackage:testsPackage,
                        testsPackageOriginal:testsPackage,
                        tests:tests,
                        PRJ_NAME:"BlenderHIP",
                        PRJ_ROOT:"rpr-plugins",
                        reportName:'Test_20Report',
                        gpusCount:gpusCount,
                        TEST_TIMEOUT:75,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:60,
                        DEPLOY_TIMEOUT:30,
                        TESTER_TAG:testerTag,
                        nodeRetry: nodeRetry,
                        errorsInSuccession: errorsInSuccession,
                        platforms:platforms,
                        testCaseRetries:testCaseRetries,
                        storeOnNAS: true
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
