import groovy.transform.Field
import groovy.json.JsonOutput
import utils
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import static groovy.io.FileType.FILES
import TestsExecutionType
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException





def executeTestCommand(String osName, String asicName, Map options)
{

    
    switch (osName) {
        case 'Ubuntu20':
            try { 

                sh"""
                    cd tests
                    cd sh
                    chmod 755 *
                    ./remove_container.sh
                    ./run_docker.sh
                """

            } catch (e) {
                currentBuild.result = "FAILURE"
                archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
                println "[ERROR] Failed to build docker ${test}"
                println(e.toString())
                println(e.getMessage())
            }

            for (test in options.tests){
                dir("tests"){
                    try {
                        if (fileExists("${test}.py")) {
                            GithubNotificator.updateStatus("Test", "${asicName}-${osName}-${test}", "in_progress", options, NotificationConfiguration.EXECUTE_TEST, BUILD_URL)
                            println "[INFO] Current test: ${test}.py"
                
                            sh """
                                expect sh/start_test.exp ${test} >> ../${STAGE_NAME}_${test}.log 2>&1
                               """
                             
                            GithubNotificator.updateStatus("Test", "${asicName}-${osName}-${test}", "success", options, NotificationConfiguration.TEST_PASSED, "${BUILD_URL}/${test.replace("_", "_5f")}_20report")
                            
                        } else {
                            currentBuild.result = "FAILURE"
                            GithubNotificator.updateStatus("Test", "${asicName}-${osName}-${test}", "failure", options, NotificationConfiguration.TEST_NOT_FOUND, BUILD_URL)
                            println "[WARNING] ${test}.py wasn't found"
                            }
                    } catch (FlowInterruptedException error) {
                        println("[INFO] Job was aborted during executing tests.")
                        throw error
                    } 
                   
                    if ((readFile("../${STAGE_NAME}_${test}.log")).contains('ERROR') || (readFile("../${STAGE_NAME}_${test}.log")).contains('AssertionError') || (readFile("../${STAGE_NAME}_${test}.log")).contains('FAIL')) { 
                        currentBuild.result = "FAILURE"
                        archiveArtifacts artifacts: "../*.log", allowEmptyArchive: true
                        GithubNotificator.updateStatus("Test", "${asicName}-${osName}-${test}", "failure", options, NotificationConfiguration.TEST_FAILED, "${BUILD_URL}/artifact/${STAGE_NAME}_${test}.log")
                        options.problemMessageManager.saveUnstableReason("Failed to execute ${test}\n")
                        println "[ERROR] Failed to execute ${test}"
                    }
                }
            }

        break
    }
}

def executeTests(String osName, String asicName, Map options)
{
    try {
   
        timeout(time: "300", unit: "MINUTES") {
            cleanWS(osName)
            checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        }

        outputEnvironmentInfo(osName, "${STAGE_NAME}_init_env")
        executeTestCommand(osName, asicName, options)

    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        
        if (e instanceof ExpectedExceptionWrapper) {
            throw new ExpectedExceptionWrapper(e.getMessage(), e.getCause())
        } else {
            throw new ExpectedExceptionWrapper(NotificationConfiguration.REASON_IS_NOT_IDENTIFIED, e)
        }
    } finally {
        archiveArtifacts artifacts: "*.log", allowEmptyArchive: true
    }
}

def executeBuild(String osName, Map options) {}


def executePreBuild(Map options)
{
    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.DOWNLOAD_SOURCE_CODE_REPO) {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
    }

   if (options.platforms.contains('Ubuntu')) {
        options.commitAuthor = sh (script: "git log --pretty='format:%an' -1",returnStdout: true)
        options.commitMessage = sh (script: "git log --pretty='format:%s' -1", returnStdout: true)
        options.commitSHA = sh (script: "git log --pretty='format:%H' -1", returnStdout: true)
    } else {
        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    }
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"

    currentBuild.description = "<b>GitHub repo:</b> ${options.projectRepo}<br/>"

    if (options.projectBranch){
        currentBuild.description += "<b>Project branch:</b> ${options.projectBranch}<br/>"
    } else {
        currentBuild.description += "<b>Project branch:</b> ${env.BRANCH_NAME}<br/>"
    }

    currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
    currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
    currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"

    if (env.BRANCH_NAME) {
        withNotifications(title: "Jenkins build configuration", printMessage: true, options: options, configuration: NotificationConfiguration.CREATE_GITHUB_NOTIFICATOR) {
            GithubNotificator githubNotificator = new GithubNotificator(this, options)
            githubNotificator.init(options)
            options.githubNotificator = githubNotificator
            githubNotificator.initPreBuild(BUILD_URL)
        }
    }

    withNotifications(title: "Jenkins build configuration", options: options, configuration: NotificationConfiguration.CONFIGURE_TESTS) {
        if (options.executeAllTests) {
            dir ("tests") {
                options.tests = []
                def py_files = findFiles(glob: "*.py")
                for (file in py_files) {
                    options.tests << file.name.replaceFirst(~/\.[^\.]+$/, '')
                }
            }   
        } else {
            options.tests = options.tests.split(" ")
        }
        println "[INFO] Tests to be executed: ${options.tests}"

    }

    if (env.BRANCH_NAME && options.githubNotificator) {
        options.githubNotificator.initChecks(options, BUILD_URL, true, false, false)
    }
    
}


def executeDeploy(Map options, List platformList, List testResultList) {}


def call(String projectBranch = "",
         String platforms = 'Ubuntu20:Denoiser',
         Boolean executeAllTests = true,
         String tests = "",
         String customTests = "",
         String notebooksTimeout = 300) {

         
    ProblemMessageManager problemMessageManager = new ProblemMessageManager(this, currentBuild)
    Map options = [stage: "Init", problemMessageManager: problemMessageManager]

    println "Selected tests: ${tests}"
    println "Additional tests: ${customTests}"
    tests = tests.replace(",", " ") + " " + customTests.replace(", ", " ")
    println "All tests to be run: ${tests}"
    println "Test timeout: ${notebooksTimeout}"

    try {
        withNotifications(options: options, configuration: NotificationConfiguration.INITIALIZATION) {
            println "Platforms: ${platforms}"

            options << [projectRepo:"git@github.com:Radeon-Pro/denoiser_pytorch.git",
                        projectBranch:projectBranch,
                        PRJ_NAME:"denoiser_pytorch",
                        PRJ_ROOT:"rpr-ml",
                        problemMessageManager: problemMessageManager,
                        platforms:platforms,
                        executeAllTests:executeAllTests,
                        tests:tests,
                        customTests:customTests,
                        notebooksTimeout:notebooksTimeout,
                        executeBuild:false,
                        executeTests:true,
                        TEST_TIMEOUT:300,
                        retriesForTestStage:1,
                        abortOldAutoBuilds:true]             
        }
        
        multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy, options)
        } 

    catch(e) {
        currentBuild.result = "FAILURE"
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        problemMessageManager.publishMessages()
    }

}