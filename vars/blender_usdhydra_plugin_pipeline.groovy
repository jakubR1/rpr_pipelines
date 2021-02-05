import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import TestsExecutionType


def executeTestCommand(String osName, String asicName, Map options)
{
}


def executeTests(String osName, String asicName, Map options)
{
}


def executeBuildWindows(Map options)
{
    dir('RadeonProRenderBlenderAddon') {
        GithubNotificator.updateStatus("Build", "Windows", "pending", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-Windows.log")
        
        withEnv(["PATH=c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
            bat """
                set HDUSD_LIBS_DIR=..\\..\\libs
                python tools\\create_zip_addon.py >> ..\\..\\${STAGE_NAME}.log  2>&1
            """
        }

        dir(".build") {
            bat """
                rename hdusd*.zip BlenderUSDHydraAddon_${options.pluginVersion}_Windows.zip
            """

            if (options.branch_postfix) {
                bat """
                    rename BlenderUSDHydraAddon*zip *.(${options.branch_postfix}).zip
                """
            }

            archiveArtifacts "BlenderUSDHydraAddon*.zip"
            String BUILD_NAME = options.branch_postfix ? "BlenderUSDHydraAddon_${options.pluginVersion}_Windows.(${options.branch_postfix}).zip" : "BlenderUSDHydraAddon_${options.pluginVersion}_Windows.zip"
            String pluginUrl = "${BUILD_URL}/artifact/${BUILD_NAME}"
            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${pluginUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""

            if (options.sendToUMS) {
                dir("../../../jobs_launcher") {
                    sendToMINIO(options, "Windows", "..\\RadeonProRenderBlenderAddon\\BlenderPkg\\.build", BUILD_NAME)                            
                }
            }

            bat """
                rename BlenderUSDHydraAddon*.zip BlenderUSDHydraAddon_Windows.zip
            """

            stash includes: "BlenderUSDHydraAddon_Windows.zip", name: "appWindows"

            GithubNotificator.updateStatus("Build", "Windows", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, pluginUrl)
        }
    }
}


def executeBuildOSX(Map options)
{
}


def executeBuildLinux(String osName, Map options)
{
    dir('RadeonProRenderBlenderAddon') {
        GithubNotificator.updateStatus("Build", "${osName}", "pending", options, NotificationConfiguration.BUILD_SOURCE_CODE_START_MESSAGE, "${BUILD_URL}/artifact/Build-${osName}.log")
        
        sh """
            export HDUSD_LIBS_DIR=../../libs
            python3 tools/create_zip_addon.py >> ../../${STAGE_NAME}.log  2>&1
        """

        dir(".build") {
            bat """
                rename hdusd*.zip BlenderUSDHydraAddon_${options.pluginVersion}_${osName}.zip
            """

            if (options.branch_postfix) {
                bat """
                    rename BlenderUSDHydraAddon*zip *.(${options.branch_postfix}).zip
                """
            }

            archiveArtifacts "BlenderUSDHydraAddon*.zip"
            String BUILD_NAME = options.branch_postfix ? "BlenderUSDHydraAddon_${options.pluginVersion}_${osName}.(${options.branch_postfix}).zip" : "BlenderUSDHydraAddon_${options.pluginVersion}_${osName}.zip"
            String pluginUrl = "${BUILD_URL}/artifact/${BUILD_NAME}"
            rtp nullAction: '1', parserName: 'HTML', stableText: """<h3><a href="${pluginUrl}">[BUILD: ${BUILD_ID}] ${BUILD_NAME}</a></h3>"""

            if (options.sendToUMS) {
                dir("../../../jobs_launcher") {
                    sendToMINIO(options, "${osName}", "../RadeonProRenderBlenderAddon/BlenderPkg/.build", BUILD_NAME)                            
                }
            }

            bat """
                rename BlenderUSDHydraAddon*.zip BlenderUSDHydraAddon_${osName}.zip
            """

            stash includes: "BlenderUSDHydraAddon_${osName}.zip", name: "app${osName}"

            GithubNotificator.updateStatus("Build", "${osName}", "success", options, NotificationConfiguration.BUILD_SOURCE_CODE_END_MESSAGE, pluginUrl)
        }
    }
}


def executeBuild(String osName, Map options)
{
    try {

        receiveFiles("${options.PRJ_ROOT}/${options.PRJ_NAME}/3rdparty/${osName}/libs/*", "libs")

        dir("RadeonProRenderBlenderAddon") {
            withNotifications(title: osName, options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkOutBranchOrScm(options["projectBranch"], options["projectRepo"], false, options["prBranchName"], options["prRepoName"])
            }
        }

        outputEnvironmentInfo(osName)

        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                case "OSX":
                    println("Unsupported OS")
                    break
                default:
                    executeBuildLinux(osName, options)
                    
            }
        }
    } catch (e) {
        throw e
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}


def executePreBuild(Map options)
{
    // manual job with prebuilt plugin
    if (options.isPreBuilt) {
        println "[INFO] Build was detected as prebuilt. Build stage will be skipped"
        currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        options.executeBuild = false
        options.executeTests = true
    // manual job
    } else if (options.forceBuild) {
        println "[INFO] Manual job launch detected"
        options['executeBuild'] = true
        options['executeTests'] = true
    // auto job
    } else {
        if (env.CHANGE_URL) {
            println "[INFO] Branch was detected as Pull Request"
            options.executeBuild = true
            options.executeTests = true
            options.testsPackage = "regression.json"
            GithubNotificator githubNotificator = new GithubNotificator(this, pullRequest)
            options.githubNotificator = githubNotificator
            githubNotificator.initPreBuild("${BUILD_URL}")
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

    // branch postfix
    options["branch_postfix"] = ""
    if (env.BRANCH_NAME && env.BRANCH_NAME == "master") {
        options["branch_postfix"] = "release"
    } else if(env.BRANCH_NAME && env.BRANCH_NAME != "master" && env.BRANCH_NAME != "develop") {
        options["branch_postfix"] = env.BRANCH_NAME.replace('/', '-')
    } else if(options.projectBranch && options.projectBranch != "master" && options.projectBranch != "develop") {
        options["branch_postfix"] = options.projectBranch.replace('/', '-')
    }

    if (!options['isPreBuilt']) {
        dir('RadeonProRenderBlenderAddon') {
            withNotifications(title: "Version increment", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
                checkOutBranchOrScm(options["projectBranch"], options["projectRepo"], true)
            }

            options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
            options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
            options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
            options.commitShortSHA = options.commitSHA[0..6]

            println(bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim());
            println "The last commit was written by ${options.commitAuthor}."
            println "Commit message: ${options.commitMessage}"
            println "Commit SHA: ${options.commitSHA}"
            println "Commit shortSHA: ${options.commitShortSHA}"

            if (options.projectBranch) {
                currentBuild.description = "<b>Project branch:</b> ${options.projectBranch}<br/>"
            } else {
                currentBuild.description = "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
            }

            withNotifications(title: "Version increment", options: options, configuration: NotificationConfiguration.INCREMENT_VERSION) {
                options.pluginVersion = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\hdusd\\__init__.py", '"version": (', ', ').replace(', ', '.')

                if (options['incrementVersion']) {
                    if (env.BRANCH_NAME == "develop" && options.commitAuthor != "radeonprorender") {

                        options.pluginVersion = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\hdusd\\__init__.py", '"version": (', ', ')
                        println "[INFO] Incrementing version of change made by ${options.commitAuthor}."
                        println "[INFO] Current build version: ${options.pluginVersion}"

                        def new_version = version_inc(options.pluginVersion, 3, ', ')
                        println "[INFO] New build version: ${new_version}"
                        version_write("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\hdusd\\__init__.py", '"version": (', new_version, ', ')

                        options.pluginVersion = version_read("${env.WORKSPACE}\\RadeonProRenderBlenderAddon\\src\\hdusd\\__init__.py", '"version": (', ', ', "true").replace(', ', '.')
                        println "[INFO] Updated build version: ${options.pluginVersion}"

                        bat """
                            git add src/hdusd/__init__.py
                            git commit -m "buildmaster: version update to ${options.pluginVersion}"
                            git push origin HEAD:develop
                        """

                        //get commit's sha which have to be build
                        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
                        options.projectBranch = options.commitSHA
                        println "[INFO] Project branch hash: ${options.projectBranch}"
                    } else {
                        if (options.commitMessage.contains("CIS:BUILD")) {
                            options['executeBuild'] = true
                        }

                        if (options.commitMessage.contains("CIS:TESTS")) {
                            options['executeBuild'] = true
                            options['executeTests'] = true
                        }
                        // get a list of tests from commit message for auto builds
                        options.tests = utils.getTestsFromCommitMessage(options.commitMessage)
                        println "[INFO] Test groups mentioned in commit message: ${options.tests}"
                    }
                }
                currentBuild.description += "<b>Version:</b> ${options.pluginVersion}<br/>"
                currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
                currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
                currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
            }
        }
    }

    if (env.CHANGE_URL) {
        options.githubNotificator.initPR(options, "${BUILD_URL}", false)
    }

    withNotifications(title: "Version increment", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {

    }
}


def executeDeploy(Map options, List platformList, List testResultList)
{
}


def appendPlatform(String filteredPlatforms, String platform) {
    if (filteredPlatforms) {
        filteredPlatforms +=  ";" + platform
    } else {
        filteredPlatforms += platform
    }
    return filteredPlatforms
}


def call(String projectRepo = "git@github.com:GPUOpen-LibrariesAndSDKs/BlenderUSDHydraAddon.git",
    String projectBranch = "",
    String testsBranch = "master",
    String platforms = 'Windows',
    String updateRefs = 'No',
    Boolean enableNotifications = true,
    Boolean incrementVersion = true,
    Boolean forceBuild = false
    )
{
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [:]
    options["stage"] = "Init"
    options["problemMessageManager"] = problemMessageManager
 
    String PRJ_NAME="BlenderUSDHydraPlugin"
    String PRJ_ROOT="rpr-plugins"

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            println "Platforms: ${platforms}"

            options << [projectRepo:projectRepo,
                        projectBranch:projectBranch,
                        testsBranch:testsBranch,
                        updateRefs:updateRefs,
                        enableNotifications:enableNotifications,
                        PRJ_NAME:PRJ_NAME,
                        PRJ_ROOT:PRJ_ROOT,
                        incrementVersion:incrementVersion,
                        forceBuild:forceBuild,
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        ]
        }

        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
    } catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }

}
