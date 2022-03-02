import utils

/**
 * Install Radeon ProRender Material Library support for all available tools
 * @param osName - os on which the check will take place
 * @param options - extra info like Jenkins stage name or current try try of that stage
 */
 
def call(String osName, Map options) {
    String installerRemotePath = "/volume1/CIS/bin-storage"

    switch(osName) {
        case 'Windows':
            // download files from NAS
            String installerLocalPathRsync = "/mnt/c/TestResources"
            String installerLocalPath = "C:\\TestResources"
            String installerName = "RadeonProRenderMaterialLibrary.msi"

            downloadFiles("${installerRemotePath}/${installerName}", "${installerLocalPathRsync}/")

            uninstallMSI("Radeon%Material%", options.stageName, options.currentTry)
            // msiexec doesn't work with relative paths
            bat """
                start /wait msiexec /i ${installerLocalPath}\\${installerName} /passive
            """
            break

        case 'OSX':
            println("[WARNING] Matlib installation is not supported on OSX")
            break

        case 'MacOS_ARM':
            println("[WARNING] Matlib installation is not supported on MacOS ARM")
            break

        default:
            // download files from NAS
            String installerLocalPath = "${CIS_TOOLS}/../TestResources"
            String installerName = "RadeonProRenderMaterialLibrary.run"

            downloadFiles("${installerRemotePath}/${installerName}", "${installerLocalPath}/")

            try {
                sh """
                    /home/\$USER/local/share/rprmaterials/uninstall.py --just-do-it 
                """
            } catch (e) {
                println(e.toString())
                println("[WARNING] Could not uninstall MatLib")
            }

            sh """
                chmod +x ${installerLocalPath}/${installerName}
                ${installerLocalPath}/${installerName} --nox11 -- --just-do-it
            """
    }
}