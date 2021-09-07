def getShaKey(String osName) {
    return "plugin${osName}Sha"
}

def getStashKey(String osName) {
    return "app${osName}"
}


def call(String osName, Map options) {
    if (!options["configuration"].supportedOS.contains(osName)) {
        throw new Exception("Unsupported OS")
    }

    String shaKey = getShaKey(osName)
    String stashKey = getStashKey(osName)
    String extension = options["configuration"]["productExtensions"][osName]
    String tool = options["configuration"]["artifactNameBeginning"]

    if (options["isPreBuilt"]) {

        println "[INFO] Product SHA (${osName}): ${options[shaKey]}"

        if (options[shaKey] && fileExists("${CIS_TOOLS}/../PluginsBinaries/${options[shaKey]}.${extension}")) {
        	println "[INFO] The product ${options[shaKey]}.${extension} exists in the storage."
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
                    mv ${tool}*.${extension} "${CIS_TOOLS}/../PluginsBinaries/${options[shaKey]}.${extension}"
                """
    		} else {
                bat """
                    IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
                    move ${tool}*.${extension} "${CIS_TOOLS}\\..\\PluginsBinaries\\${options[shaKey]}.${extension}"
                """
    		}
        }

    } else {
        if (fileExists("${CIS_TOOLS}/../PluginsBinaries/${options.commitSHA}_${osName}.${extension}")) {
            println "[INFO] The plugin ${options.commitSHA}_${osName}.${extension} exists in the storage."
        } else {
        	if (isUnix()) {
                clearBinariesUnix()
    		} else {
    			clearBinariesWin()
    		}

            println "[INFO] The plugin does not exist in the storage. Unstashing and copying..."
            makeUnstash(name: stashKey, unzip: false, storeOnNAS: options.storeOnNAS)

        	if (isUnix()) {
                sh """
                    mkdir -p "${CIS_TOOLS}/../PluginsBinaries"
                    mv ${tool}*.${extension} "${CIS_TOOLS}/../PluginsBinaries/${options.commitSHA}_${osName}.${extension}"
                """
    		} else {
	            bat """
	                IF NOT EXIST "${CIS_TOOLS}\\..\\PluginsBinaries" mkdir "${CIS_TOOLS}\\..\\PluginsBinaries"
	                move ${tool}*.${extension} "${CIS_TOOLS}\\..\\PluginsBinaries\\${options.commitSHA}_${osName}.${extension}"
	            """
    		}
        }
    }
}