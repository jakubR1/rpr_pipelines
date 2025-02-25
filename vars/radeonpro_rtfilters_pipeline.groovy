def executeGenTestRefCommand(String osName, Map options)
{

}

def executeTestCommand(String osName, Map options)
{
    dir("Build/unittests") {
        switch(osName) {
            case 'Windows':
                bat """
                    call Release\\RTF_UnitTests.exe --gtest_output=xml:../../${STAGE_NAME}.gtest.xml >> ..\\..\\${STAGE_NAME}.log 2>&1
                """
                break
            case 'OSX':
                println "not supported"
                break
            default:
                println "not supported"
        }
    }
}

def executeTests(String osName, String asicName, Map options)
{
    cleanWS(osName)
    String REF_PATH_PROFILE="${options.REF_PATH}/${asicName}-${osName}"
    String JOB_PATH_PROFILE="${options.JOB_PATH}/${asicName}-${osName}"
    
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        
        outputEnvironmentInfo(osName)
        makeUnstash(name: "app${osName}")
        bat "rmdir /s /q shaders"
        makeUnstash(name: "shaders${osName}")
        
        if (options['updateRefs']) {
            println("Updating Reference Images")
            executeGenTestRefCommand(osName, options)
            
        } else {
            println("Execute Tests")
            executeTestCommand(osName, options)
        }
    } catch (e) {
        println(e.toString());
        println(e.getMessage());
        throw e
    } finally {
        archiveArtifacts "*.log"
        junit "*.gtest.xml"
    }
}

def executeBuildWindows(Map options)
{
    bat """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} -G "Visual Studio 15 2017 Win64" .. >> ..\\${STAGE_NAME}.log 2>&1
        cmake --build . --config Release >> ..\\${STAGE_NAME}.log 2>&1
    """
}

def executeBuildOSX(Map options)
{
    sh """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} .. >> ../${STAGE_NAME}.log 2>&1
        make -j 4 >> ../${STAGE_NAME}.log 2>&1
    """
}

def executeBuildLinux(Map options)
{
    sh """
        mkdir Build
        cd Build
        cmake ${options['cmakeKeys']} .. >> ../${STAGE_NAME}.log 2>&1
        make -j 8 >> ../${STAGE_NAME}.log 2>&1
    """
}

def executePreBuild(Map options)
{
    dir('RadeonProVulkanWrapper') {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
    }
}

def executeBuild(String osName, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)

        switch (osName) {
            case 'Windows': 
                executeBuildWindows(options) 
                break
            case 'OSX':
                executeBuildOSX(options)
                break
            default: 
                executeBuildLinux(options)
        }
        
        makeStash(includes: 'Build/**/*', name: "app${osName}")
        makeStash(includes: 'shaders/**/*/', name: "shaders${osName}")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
        //zip archive: true, dir: 'Build', glob: '', zipFile: "${osName}Build.zip"
    }                        
}

def executeDeploy(Map options, List platformList, List testResultList)
{
    if (options['executeTests'] && testResultList) {
        cleanWS()

        dir("BuildsArtifacts") {
            platformList.each() {
                try {
                    dir(it) {
                        makeUnstash(name: "app${it}")
                    }
                } catch(e) {
                    println(e.toString())
                    println("Can't unstash ${osName} build")
                }
            }
        }
        zip archive: true, dir: 'BuildsArtifacts', glob: '', zipFile: "BuildsArtifacts.zip"
    }
}

def call(String projectBranch = "", 
         String platforms = 'Windows:AMD_RXVEGA,AMD_WX9100,NVIDIA_GF1080TI',
         String PRJ_ROOT='rpr-core',
         String PRJ_NAME='RadeonProRTFilters',
         String projectRepo='git@github.com:Radeon-Pro/RadeonProRTFilters.git',
         Boolean updateRefs = false, 
         Boolean enableNotifications = true,
         String cmakeKeys = "") {

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, this.&executeDeploy,
                           [projectBranch:projectBranch,
                            updateRefs:updateRefs, 
                            enableNotifications:enableNotifications,
                            PRJ_NAME:PRJ_NAME,
                            PRJ_ROOT:PRJ_ROOT,
                            projectRepo:projectRepo,
                            executeBuild:true,
                            executeTests:true,
                            BUILD_TIMEOUT:'10',
                            TEST_TIMEOUT:'20',
                            slackChannel:"${SLACK_BAIKAL_CHANNEL}",
                            slackWorkspace:SlackUtils.SlackWorkspace.BAIKAL,
                            cmakeKeys:cmakeKeys,
                            retriesForTestStage:1])
}
