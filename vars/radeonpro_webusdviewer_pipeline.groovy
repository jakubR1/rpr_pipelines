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
                python3--version >> ${STAGE_NAME}.log 2>&1
                python3 -m pip install conan >> ${STAGE_NAME}.log 2>&1
                mkdir Build
                echo [WebRTC] >> Build\\LocalBuildConfig.txt
                echo path = ${webrtcPath.replace("\\", "/")}/src >> Build\\LocalBuildConfig.txt
                python3 Tools/Build.py -v >> ${STAGE_NAME}.log 2>&1
            """
            println("[INFO] Start building & sending docker containers to repo")
            bat """
                python3 Tools/Docker.py -ba -da -v
            """
            println("[INFO] Finish building & sending docker containers to repo")
            if (options.generateArtifact){
                zip archive: true, dir: "Build/Install", glob: '', zipFile: "WebUsdViewer_Windows.zip"
            }
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
        if (options.doBuild){
            println "[INFO] Start build" 
            sh """
                cmake --version >> ${STAGE_NAME}.log 2>&1
                python3 --version >> ${STAGE_NAME}.log 2>&1
                python3 -m pip install conan >> ${STAGE_NAME}.log 2>&1
                mkdir --parents Build
                echo "[WebRTC]" >> Build/LocalBuildConfig.txt
                echo "path = ${CIS_TOOLS}/../thirdparty/webrtc/src" >> Build/LocalBuildConfig.txt
                export OS=
                python3 Tools/Build.py -v >> ${STAGE_NAME}.log 2>&1
            """
        }else{
            println "[INFO] Skip build because changes changes do not affect projects $options.changedProjects" 
        }
        changedProjects = options.changedProjects.join(' ')
        println changedProjects
        println("[INFO] Start building & sending docker containers to repo")
        sh """
                export WEBUSD_BUILD_REMOTE_HOST=172.31.0.91
                export WEBUSD_BUILD_LIVE_CONTAINER_NAME=172.31.0.91:5000/live
                export WEBUSD_BUILD_ROUTE_CONTAINER_NAME=172.31.0.91:5000/route
                export WEBUSD_BUILD_STORAGE_CONTAINER_NAME=172.31.0.91:5000/storage
                export WEBUSD_BUILD_STREAM_CONTAINER_NAME=172.31.0.91:5000/stream
                export WEBUSD_BUILD_WEB_CONTAINER_NAME=172.31.0.91:5000/web
                python3 Tools/Docker.py -ba -da -v -c $options.deployEnvironment
        """
        println("[INFO] Finish building & sending docker containers to repo")
        sh "rm WebUsdWebServer/.env.production"
        if (options.generateArtifact){
            sh """
                tar -C Build/Install -czvf "WebUsdViewer_Ubuntu20.tar.gz" .
            """
            archiveArtifacts "WebUsdViewer_Ubuntu20.tar.gz"
        }

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
    // diffScm(options.filesToBuild)
    try {
        // cleanWS(osName)
        // checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
        outputEnvironmentInfo(osName)
        webusd_set_env(deployEnvironment: options.deployEnvironment, osName: osName)

        options.changedProjects = diffScm()
        options.doBuild = false
        for (f in changedProjects){
            if (f in options.projectsToBuild){
                options.doBuild = true
            }
        }

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

def diffSCM(){
    // def tree = [:].withDefault{ owner.call() }
    // TreeMap dependencies = tree
    // dependencies.WebUsdLiveServer.WebUsdAssetResolver = 'WebUsdAssetResolver/'
    // dependencies.WebUsdLiveServer.bbc = 'abc'
    // dependencies.WebUsdStreamServer.WebUsdAssetResolver = 'abc'
    // dependencies.WebUsdStreamServer.abc = 'abc'

    String changedFiles = sh (
        script: "git diff --dirstat=files,0 HEAD | sed 's/^[ 0-9.]+% //g'",
        returnStdout: true
    ).trim()
    // for (f in changedFiles){
    //     if (f == "")
    // }
    println changedFiles



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

def executeDeploy(Map options, List platformList, List testResultList)
{
    println "[INFO] Start deploying on $options.DEPLOY_TAG agent in $options.deployEnvironment environment"
    try{
        println "[INFO] Send deploy command"
        res = sh(
            script: "curl -X 'GET' --insecure 'https://172.31.0.91/deploy?configuration=${options.deployEnvironment}' -H 'accept: application/json'",
            returnStdout: true
        )
        println res
        println "[INFO] Successfully sended"
    }catch (e){
        println "[ERROR] Error during deploy"
        println(e.toString())
        failure = true
    }
    
    if (failure){
        currentBuild.result = "FAILED"
        error "error during deploy"
    }
}

def call(
    String projectBranch = "",
    String platforms = 'Windows;Ubuntu20',
    Boolean enableNotifications = true,
    Boolean generateArtifact = true,
    Boolean isDeploy = true,
    String deployEnvironment = 'test1;test2;test3;dev;prod;'
) {
    projectsToBuild = ['USD', 'WebUsdAssetResolver', 'WebUsdLiveServer', 'WebUsdStreamServer']
    this.$diffSCM()
    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, this.&executeDeploy,
                            [projectBranch:projectBranch,
                            projectRepo:PROJECT_REPO,
                            projectsToBuild: projectsToBuild
                            enableNotifications:enableNotifications,
                            generateArtifact:generateArtifact,
                            deployEnvironment: deployEnvironment,
                            deploy:deploy, 
                            PRJ_NAME:'WebUsdViewer',
                            PRJ_ROOT:'radeon-pro',
                            BUILDER_TAG: 'BuilderWebUsdViewer',
                            executeBuild:true,
                            executeTests:false,
                            executeDeploy:true,
                            BUILD_TIMEOUT:'120',
                            DEPLOY_TAG: 'WebViewerDeployment'
                            ])
}