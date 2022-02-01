import groovy.transform.Field
import groovy.json.JsonOutput
import universe.*
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType
import java.util.concurrent.atomic.AtomicInteger


@Field final String ANARI_SDK_REPO = "git@github.com:KhronosGroup/ANARI-SDK.git"
@Field final String RPR_ANARI_REPO = "git@github.com:GPUOpen-LibrariesAndSDKs/RadeonProRenderANARI.git"


def executeBuildWindows(Map options) {
    GithubNotificator.updateStatus("Build", "Windows", "in_progress", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/${STAGE_NAME}.log")

    utils.removeDir(this, "Windows", "%ProgramFiles(x86)%\\anari")

    dir("AnariSDK\\build") {
        bat """
            set BUILD_TESTING=ON
            cmake .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . -t install >> ../../${STAGE_NAME}.log 2>&1
        """
    }

    dir("RadeonProRenderAnari\\build") {
        bat """
            cmake .. >> ../../${STAGE_NAME}.log 2>&1
            cmake --build . >> ../../${STAGE_NAME}.log 2>&1
        """

        dir("Debug") {
            bat """
                copy "%ProgramFiles(x86)%\\anari\\bin" .
                %CIS_TOOLS%\\7-Zip\\7z.exe a Anari_Windows.zip .
            """

            makeArchiveArtifacts(name: "Anari_Windows.zip", storeOnNAS: options.storeOnNAS)
            makeStash(includes: "Anari_Windows.zip", name: getProduct.getStashName("Windows"), preZip: false, storeOnNAS: options.storeOnNAS)
        }
    }

    GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE)
}


def executeBuild(String osName, Map options) {
    try {
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            dir('AnariSDK') {
                checkoutScm(branchName: options.anariSdkBranch, repositoryUrl: ANARI_SDK_REPO)
            }
            dir('RadeonProRenderAnari') {
                checkoutScm(branchName: options.rprAnariBranch, repositoryUrl: RPR_ANARI_REPO)
            }
        }

        outputEnvironmentInfo(osName)

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "OSX":
                case "MacOS_ARM":
                    // TODO: add building on MacOS
                    println("Do not support")
                default:
                    // TODO: add building on Linux
                    println("Do not support")
            }
        }

        options[getProduct.getIdentificatorKey(osName)] = options.commitSHA
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}


def executePreBuild(Map options) {
    options['executeBuild'] = true
    options['executeTests'] = true

    // manual job
    if (!env.BRANCH_NAME) {
        println "[INFO] Manual job launch detected"
    // auto job
    } else {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options["githubNotificator"] = githubNotificator
            githubNotificator.initPreBuild("${BUILD_URL}")
            options.projectBranchName = githubNotificator.branchName
        }

        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
        } else if (env.BRANCH_NAME == "master" || env.BRANCH_NAME == "develop") {
           println "[INFO] ${env.BRANCH_NAME} branch was detected"
        } else {
            println "[INFO] ${env.BRANCH_NAME} branch was detected"
        }
    }

    dir('RadeonProRenderAnari') {
        withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
            checkoutScm(branchName: options.rprAnariBranch, repositoryUrl: RPR_ANARI_REPO, disableSubmodules: true)
        }

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = options.commitSHA[0..6]
        options.branchName = env.BRANCH_NAME ?: options.projectBranch

        println(bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim());
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${options.commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
        println "Commit shortSHA: ${options.commitShortSHA}"
        println "Branch name: ${options.branchName}"

        if (options.projectBranch) {
            currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
        } else {
            currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
        }

        currentBuild.description = "<b>Anari SDK branch:</b> ${options.anariSdkBranch}<br/>"
        currentBuild.description = "<b>RPR Anari branch:</b> ${options.rprAnariBranch}<br/>"
        currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        // TODO: init autotests repo

        if (env.BRANCH_NAME && options.githubNotificator) {
            options.githubNotificator.initChecks(options, "${BUILD_URL}")
        }
    }

    if (options.flexibleUpdates && multiplatform_pipeline.shouldExecuteDelpoyStage(options)) {
        // TODO: support flexible updating
        //options.reportUpdater = new ReportUpdater(this, env, options)
        //options.reportUpdater.init(this.&getReportBuildArgs)
    }
}


def call(String anariSdkBranch = "main",
    String rprAnariBranch = "",
    String testsBranch = "master",
    String platforms = "Windows;Ubuntu20;OSX",
    String updateRefs = "No",
    String testsPackage = "",
    String tests = "")
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager

    def nodeRetry = []

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

            options << [anariSdkBranch: anariSdkBranch,
                        rprAnariBranch:rprAnariBranch,
                        testRepo:"git@github.com:luxteam/jobs_test_blender.git",
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        testsPackage:testsPackage,
                        testsPackageOriginal:testsPackage,
                        tests:tests,
                        PRJ_NAME:"RadeonProRenderAnari",
                        PRJ_ROOT:"rpr-plugins",
                        reportName:'Test_20Report',
                        gpusCount:gpusCount,
                        TEST_TIMEOUT:30,
                        ADDITIONAL_XML_TIMEOUT:15,
                        NON_SPLITTED_PACKAGE_TIMEOUT:30,
                        DEPLOY_TIMEOUT:20,
                        nodeRetry: nodeRetry,
                        platforms:platforms,
                        storeOnNAS: true,
                        flexibleUpdates: true
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null, options)
    } catch(e) {
        throw e
    } finally {
        String problemMessage = options.problemMessageManager.publishMessages()
    }
}
