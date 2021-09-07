def getIdentificatorKey(String osName) {
    return "plugin${osName}Identificator"
}

def getStashName(String osName) {
    return "app${osName}"
}


def call(String osName, Map options, Boolean unzipArtifact = false) {
    if (!options["configuration"].supportedOS.contains(osName)) {
        throw new Exception("Unsupported OS")
    }

    String identificatorKey = getIdentificatorKey(osName)
    String stashName = getStashName(osName)
    String extension = options["configuration"]["productExtensions"][osName]
    String tool = options["configuration"]["artifactNameBeginning"]

    if (options["isPreBuilt"]) {

        println "[INFO] Product Identificator (${osName}): ${options[identificatorKey]}"

        if (options[identificatorKey] && fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}")) {
            println "[INFO] The product ${options[identificatorKey]}.${extension} exists in the storage."
        } else {
            println "[INFO] The product does not exist in the storage. Downloading and copying..."

            if (isUnix()) {
                clearBinariesUnix()
            } else {
                clearBinariesWin()
            }

            println "[INFO] The product does not exist in the storage. Downloading and copying..."
            downloadPlugin(osName, options)

            if (isUnix()) {
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    mv ${tool}*.${extension} "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}.${extension}"
                """
            } else {
                bat """
                    IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
                    move ${tool}*.${extension} "${CIS_TOOLS}\\..\\PluginsBinaries\\${options[identificatorKey]}.${extension}"
                """
            }
        }

    } else {
        if (!options[identificatorKey]) {
            throw new Exception("Missing identificator key for ${osName}")
        }

        if (fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}_${osName}.${extension}")) {
            println "[INFO] The plugin ${options[identificatorKey]}_${osName}.${extension} exists in the storage."
        } else {
            if (isUnix()) {
                clearBinariesUnix()
            } else {
                clearBinariesWin()
            }

            println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
            makeUnstash(name: stashName, unzip: unzipArtifact, storeOnNAS: options.storeOnNAS)

            if (isUnix()) {
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    mv ${tool}*.${extension} "${CIS_TOOLS}/../PluginsBinaries/${options[identificatorKey]}_${osName}.${extension}"
                """
            } else {
                bat """
                    IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
                    move ${tool}*.${extension} "${CIS_TOOLS}\\..\\PluginsBinaries\\${options[identificatorKey]}_${osName}.${extension}"
                """
            }
        }
    }
}