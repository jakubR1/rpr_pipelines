import groovy.transform.Field

@Field final String PROJECT_REPO = "git@github.com:Radeon-Pro/WebUsdViewer.git"

def executeBuildWindows(Map options)
{
    Boolean failure = false
    String webrtcPath = "C:\\JN\\thirdparty\\webrtc"

    downloadFiles("/volume1/CIS/radeon-pro/webrtc-win/", webrtcPath.replace("C:", "/mnt/c").replace("\\", "/"), , "--quiet")

    try {
        withEnv(["PATH=c:\\CMake322\\bin;c:\\python37\\;c:\\python37\\scripts\\;${PATH}"]) {
            bat """
                call "C:\\Program Files (x86)\\Microsoft Visual Studio\\2019\\Professional\\VC\\Auxiliary\\Build\\vcvars64.bat" >> ${STAGE_NAME}.EnvVariables.log 2>&1
                cmake --version >> ${STAGE_NAME}.log 2>&1
                python --version >> ${STAGE_NAME}.log 2>&1
                python -m pip install conan >> ${STAGE_NAME}.log 2>&1
                mkdir Build
                echo [WebRTC] >> Build\\LocalBuildConfig.txt
                echo path = ${webrtcPath.replace("\\", "/")}/src >> Build\\LocalBuildConfig.txt
                python Tools/Build.py -v >> ${STAGE_NAME}.log 2>&1
            """

            zip archive: true, dir: "Build/Install", glob: '', zipFile: "WebUsdViewer_Windows.zip"
        }
    } catch(e) {
        println("Error during build on Windows")
        println(e.toString())
        failure = true
    }
    finally {
        archiveArtifacts "*.log"
    }
    

    if (failure) {
        currentBuild.result = "FAILED"
        error "error during build"
    }
}


def executeBuildLinux(Map options)
{
    Boolean failure = false

    downloadFiles("/volume1/CIS/radeon-pro/webrtc-linux/", "${CIS_TOOLS}/../thirdparty/webrtc", "--quiet")

    try {
       sh """
            cmake --version >> ${STAGE_NAME}.log 2>&1
            python --version >> ${STAGE_NAME}.log 2>&1
            python -m pip install conan >> ${STAGE_NAME}.log 2>&1
            mkdir --parents Build
            echo "[WebRTC]" >> Build/LocalBuildConfig.txt
            echo "path = ${CIS_TOOLS}/../thirdparty/webrtc/src" >> Build/LocalBuildConfig.txt
            export OS=
            python Tools/Build.py -v >> ${STAGE_NAME}.log 2>&1
        """

        sh """
            tar -C Build/Install -czvf "WebUsdViewer_Ubuntu20.tar.gz" .
        """
        
        archiveArtifacts "WebUsdViewer_Ubuntu20.tar.gz"
    } catch(e) {
        println("Error during build on Linux")
        println(e.toString())
        failure = true
    } finally {
        archiveArtifacts "*.log"
    }

   if (failure) {
        currentBuild.result = "FAILED"
        error "error during build"
    }
}


def executeBuild(String osName, Map options)
{
    try {
        cleanWS(osName)
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)

        switch(osName) {
            case 'Windows':
                executeBuildWindows(options)
                break
            case 'Ubuntu20':
                executeBuildLinux(options)
                break
            default:
                println "[WARNING] ${osName} is not supported"
        }
    } catch (e) {
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}



def executePreBuild(Map options)
{
    dir('WebUsdViewer') {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)

        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${commitMessage}"
        println "Commit SHA: ${options.commitSHA}"
    }
}


def call(String projectBranch = "",
         String platforms = 'Windows;Ubuntu20',
         Boolean enableNotifications = true) {

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null,
                           [projectBranch:projectBranch,
                            projectRepo:PROJECT_REPO,
                            enableNotifications:enableNotifications,
                            PRJ_NAME:'WebUsdViewer',
                            PRJ_ROOT:'radeon-pro',
                            BUILDER_TAG: 'BuilderWebUsdViewer',
                            executeBuild:true,
                            executeTests:false,
                            BUILD_TIMEOUT:'120'])
}