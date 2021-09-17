def getPreparedUE(Map options) {
    String targetFolderPath = "${CIS_TOOLS}\\..\\PreparedUE\\${options.ueSha}"

    if (!fileExists(targetFolderPath)) {
        println("[INFO] UnrealEngine will be downloaded and configured")

        dir("RPRHybrid-UE") {
            checkoutScm(branchName: options.ueBranch, repositoryUrl: options.ueRepo)
        }

        bat("0_SetupUE.bat > \"0_SetupUE.log\" 2>&1")

        println("[INFO] Prepared UE is ready. Saving it for use in future builds...")

        bat """
            xcopy /s/y/i RPRHybrid-UE ${targetFolderPath} >> nul
        """
    } else {
        println("[INFO] Prepared UnrealEngine found. Copying it...")

        dir("RPRHybrid-UE") {
            bat """
                xcopy /s/y/i ${targetFolderPath} . >> nul
            """
        }
    }
}


def executeBuildWindows(Map options) {
    bat("if exist \"TOYSHOP_BINARY\" rmdir /Q /S TOYSHOP_BINARY")
    bat("if exist \"RPRHybrid-UE\" rmdir /Q /S RPRHybrid-UE")

    utils.removeFile(this, "Windows", "*.log")

    dir("ParagonGame") {
        withCredentials([string(credentialsId: "artNasIP", variable: 'ART_NAS_IP')]) {
            String paragonGameURL = "svn://" + ART_NAS_IP + "/ToyShopUnreal"
            checkoutScm(checkoutClass: "SubversionSCM", repositoryUrl: paragonGameURL, credentialsId: "artNasUser")
        }
    }

    dir("RPRHybrid") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo)
    }

    // download build scripts
    downloadFiles("/volume1/CIS/bin-storage/HybridParagon/BuildScripts/*", ".")

    // copy prepared UE if it exists
    getPreparedUE(options)

    // download textures
    downloadFiles("/volume1/CIS/bin-storage/HybridParagon/textures/*", "textures")

    bat("mkdir TOYSHOP_BINARY")

    bat("1_UpdateRPRHybrid.bat > \"1_UpdateRPRHybrid.log\" 2>&1")
    bat("2_CopyDLLsFromRPRtoUE.bat > \"2_CopyDLLsFromRPRtoUE.log\" 2>&1")
    bat("3_UpdateUE4.bat > \"3_UpdateUE4.log\" 2>&1")

    // the last script can return non-zero exit code, but build can be ok
    try {
        bat("4_PackageParagon_ToyShop.bat > \"4_PackageParagon.log\" 2>&1")
    } catch (e) {
        println(e.getMessage())
    }

    dir("TOYSHOP_BINARY\\WindowsNoEditor") {
        String ARTIFACT_NAME = "ParagonGame.zip"
        bat(script: '%CIS_TOOLS%\\7-Zip\\7z.exe a' + " \"${ARTIFACT_NAME}\" .")
        makeArchiveArtifacts(name: ARTIFACT_NAME, storeOnNAS: options.storeOnNAS)
    }
}


def executeBuild(String osName, Map options) {
    try {
        outputEnvironmentInfo(osName)
        
        withNotifications(title: osName, options: options, configuration: NotificationConfiguration.BUILD_SOURCE_CODE) {
            GithubNotificator.updateStatus("Build", osName, "in_progress", options, "Checkout has been finished. Trying to build...")

            switch(osName) {
                case "Windows":
                    executeBuildWindows(options)
                    break
                default:
                    println("${osName} is not supported")
            }
        }
    } catch (e) {
        println(e.getMessage())
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}

def executePreBuild(Map options) {
    dir("RPRHybrid") {
        checkoutScm(branchName: options.projectBranch, repositoryUrl: options.projectRepo, disableSubmodules: true)
    
        options.commitAuthor = bat (script: "git show -s --format=%%an HEAD ",returnStdout: true).split('\r\n')[2].trim()
        commitMessage = bat (script: "git log --format=%%B -n 1", returnStdout: true)
        options.commitSHA = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
        println "The last commit was written by ${options.commitAuthor}."
        println "Commit message: ${commitMessage}"
        println "Commit SHA: ${options.commitSHA}"

        options.commitMessage = []
        commitMessage = commitMessage.split('\r\n')
        commitMessage[2..commitMessage.size()-1].collect(options.commitMessage) { it.trim() }
        options.commitMessage = options.commitMessage.join('\n')

        println "Commit list message: ${options.commitMessage}"

        options.githubApiProvider = new GithubApiProvider(this)

        // get UE hash to know it should be rebuilt or not
        options.ueSha = options.githubApiProvider.getBranch(
            options.ueRepo.replace("git@github.com:", "https://github.com/"). replace(".git", ""),
            options.ueBranch.replace("origin/", "")
        )["commit"]["sha"]

        println("UE target commit hash: ${options.ueSha}")
    }
}


def call(String projectBranch = "",
         String ueBranch = "rpr_material_serialization_particles",
         String platforms = "Windows") {

    multiplatform_pipeline(platforms, this.&executePreBuild, this.&executeBuild, null, null,
                           [platforms:platforms,
                            PRJ_NAME:"HybridParagon",
                            projectRepo:"git@github.com:Radeon-Pro/RPRHybrid.git",
                            projectBranch:projectBranch,
                            ueRepo:"git@github.com:Radeon-Pro/RPRHybrid-UE.git",
                            ueBranch:ueBranch,
                            BUILDER_TAG:"BuilderU",
                            TESTER_TAG:"HybridTester",
                            executeBuild:true,
                            executeTests:true,
                            BUILD_TIMEOUT: 180,
                            retriesForTestStage:1,
                            storeOnNAS: true])
}
