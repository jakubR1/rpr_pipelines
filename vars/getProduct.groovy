def getIdentificatorKey(String osName) {
    return "plugin${osName}Identificator"
}

def getStashName(String osName) {
    return "app${osName}"
}

def saveDownloadedInstaller(String artifactNameBase, String extension, String identificatorValue) {
    if (isUnix()) {
        sh """
            mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
            mv ${artifactNameBase}*.${extension} "${CIS_TOOLS}/../PluginsBinaries/${identificatorValue}.${extension}"
        """
    } else {
        bat """
            IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
            move ${artifactNameBase}*.${extension} "${CIS_TOOLS}\\..\\PluginsBinaries\\${identificatorValue}.${extension}"
        """
    }
}


def call(String osName, Map options, String unzipDestination = "", Boolean saveInstaller = true) {
    if (!options["configuration"].supportedOS.contains(osName)) {
        throw new Exception("Unsupported OS")
    }

    String identificatorKey = getIdentificatorKey(osName)
    String stashName = getStashName(osName)
    String extension = options["configuration"]["productExtensions"][osName]
    // the name of the artifact without OS name / version. It must be same for any OS / version
    String artifactNameBase = options["configuration"]["artifactNameBase"]

    if (options["isPreBuilt"]) {

        println "[INFO] Product Identificator (${osName}): ${options[identificatorKey]}"

        if (options[identificatorKey] && fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}")) {
            println "[INFO] The product ${options[identificatorKey]}.${extension} exists in the storage."

            if (unzipDestination) {
                unzip zipFile: "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}", dir: unzipDestination, quiet: true
            }
        } else {
            println "[INFO] The product does not exist in the storage. Downloading and copying..."

            if (isUnix()) {
                clearBinariesUnix()
            } else {
                clearBinariesWin()
            }

            println "[INFO] The product does not exist in the storage. Downloading and copying..."
            downloadPlugin(osName, options)

            if (saveInstaller) {
                saveDownloadedInstaller(artifactNameBase, extension, options[identificatorKey])
            }

            if (unzipDestination) {
                unzip zipFile: "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}", dir: unzipDestination, quiet: true
            }
        }

    } else {
        if (!options[identificatorKey]) {
            throw new Exception("Missing identificator key for ${osName}")
        }

        if (fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}")) {
            println "[INFO] The plugin ${options[identificatorKey]}.${extension} exists in the storage."

            if (unzipDestination) {
                unzip zipFile: "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}", dir: unzipDestination, quiet: true
            }
        } else {
            if (isUnix()) {
                clearBinariesUnix()
            } else {
                clearBinariesWin()
            }

            println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
            makeUnstash(name: stashName, unzip: false, storeOnNAS: options.storeOnNAS)

            if (saveInstaller) {
                saveDownloadedInstaller(artifactNameBase, extension, options[identificatorKey])
            }

            if (unzipDestination) {
                unzip zipFile: "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}", dir: unzipDestination, quiet: true
            }
        }
    }
}