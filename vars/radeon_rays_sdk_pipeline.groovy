def executeTestCommand(String osName)
{
    switch(osName) {
        case 'Windows':
            bat """
                ..\\Build\\bin\\Release\\UnitTest.exe  --gtest_output=xml:../${STAGE_NAME}.gtest.xml >> ..\\${STAGE_NAME}.log  2>&1
            """
            break
        case 'OSX':
            sh """
                export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:../Build/bin
                ../Build/bin/UnitTest --gtest_output=xml:../${STAGE_NAME}.gtest.xml >> ../${STAGE_NAME}.log  2>&1
            """
            break
        default:
            sh """
                export LD_LIBRARY_PATH=\$LD_LIBRARY_PATH:../Build/bin
                ../Build/bin/UnitTest --gtest_output=xml:../${STAGE_NAME}.gtest.xml >> ../${STAGE_NAME}.log  2>&1
            """
    }  
}

def executeTests(String osName, String asicName, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)

        outputEnvironmentInfo(osName)
        makeUnstash(name: "app${osName}")

        dir('UnitTest') {
            executeTestCommand(osName)
        }                
    } catch (e) {
        println(e.toString())
        println(e.getMessage())
        throw e
    } finally {
        archiveArtifacts "*.log"
        junit "*.gtest.xml"
    }
}

def executeBuildWindows()
{
    bat """
        mkdir Build
        cd Build
        cmake -DCMAKE_BUILD_TYPE=Release -G "Visual Studio 15 2017 Win64" .. >> ..\\${STAGE_NAME}.log 2>&1
        cmake --build . --config Release >> ..\\${STAGE_NAME}.log 2>&1
    """
}

def executeBuildOSX()
{
    sh """
        mkdir Build
        cd Build
        cmake -DCMAKE_BUILD_TYPE=Release .. >> ../${STAGE_NAME}.log 2>&1
        make >> ../${STAGE_NAME}.log 2>&1
    """
}

def executeBuildLinux()
{
    sh """
        mkdir Build
        cd Build
        cmake -DCMAKE_BUILD_TYPE=Release .. >> ../${STAGE_NAME}.log 2>&1
        make >> ../${STAGE_NAME}.log 2>&1
    """
}

def executePreBuild(Map options)
{
    checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

    options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
    options.commitMessage = bat ( script: "git log --format=%%B -n 1", returnStdout: true ).split('\r\n')[2].trim()
    options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    println "The last commit was written by ${options.commitAuthor}."
    println "Commit message: ${options.commitMessage}"
    println "Commit SHA: ${options.commitSHA}"
}

def executeBuild(String osName, Map options)
{
    try {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)

        switch(osName) {
            case 'Windows': 
                executeBuildWindows()
                break
            case 'OSX':
                executeBuildOSX()
                break
            default: 
                executeBuildLinux()
        }
       
        makeStash(includes: 'Build/bin/**/*', name: "app${osName}")
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }                        
}

def executeDeploy(Map options, List platformList, List testResultList)
{
}

def call(String projectBranch = "", 
         String projectRepo = 'git@github.com:GPUOpen-LibrariesAndSDKs/RadeonRays_SDK.git',
         String platforms = 'Windows:AMD_RXVEGA,AMD_WX9100,NVIDIA_GF1080TI;OSX:AMD_RXVEGA',
         String PRJ_NAME="RadeonRays_SDK",
         Boolean enableNotifications = true) {

    String PRJ_ROOT="rpr-core"
    
    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, this.&executeTests, null, 
                           [projectRepo:projectRepo,
                            projectBranch:projectBranch,
                            enableNotifications:enableNotifications,
                            executeBuild:true,
                            executeTests:true,
                            PRJ_NAME:PRJ_NAME,
                            PRJ_ROOT:PRJ_ROOT,
                            BUILD_TIMEOUT:'10',
                            TEST_TIMEOUT:'10',
                            slackChannel:"${SLACK_BAIKAL_CHANNEL}",
                            slackWorkspace:SlackUtils.SlackWorkspace.BAIKAL,
                            retriesForTestStage:1])
}
