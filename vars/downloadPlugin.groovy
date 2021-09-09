def runCurl(String curlCommand, Integer tries=5, Integer oneTryTimeout=90) {
    Integer currentTry = 0
    while (currentTry++ < tries) {
        println("[INFO] Try to download plugin through curl (try #${currentTry})")
        try {
            timeout(time: oneTryTimeout, unit: "SECONDS") {
                if (isUnix()) {
                    sh """
                        ${curlCommand}
                    """
                } else {
                    bat """
                        ${curlCommand}
                    """
                }
            }
            break
        } catch (e) {
            println("[ERROR] Failed to download plugin during try #${currentTry}: ${e.getMessage()}")
            if (currentTry == tries) {
                throw e
            }
        }
    }
}


def call(String osName, String tool, Map options, String credentialsId = '', Integer oneTryTimeout = 90) {
    String customBuildLink = ""
    String extension = ""

    switch(osName) {
        case 'Windows':
            customBuildLink = options['customBuildLinkWindows']
            if (tool.contains("RPRViewer") || tool.contains("RPRInventor")) {
                extension = "exe"
            } else {
                extension = "msi"
            }
            break
        case 'OSX':
            customBuildLink = options['customBuildLinkOSX']
            extension = "dmg"
            break
        case 'Ubuntu':
            customBuildLink = options['customBuildLinkLinux']
            extension = "run"
        case 'Ubuntu18':
            customBuildLink = options['customBuildLinkUbuntu18']
        // Ubuntu20
        default:
            customBuildLink = options['customBuildLinkUbuntu20']
    }

    if (tool.contains("Blender") || tool.startsWith("bin")) {
        extension = "zip"
    }

    print "[INFO] Used specified pre built plugin for ${tool}."

    if (customBuildLink.startsWith("https://builds.rpr")) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'builsRPRCredentials', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            runCurl("curl -L -o ${tool}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
        }
    } else if (customBuildLink.startsWith("https://rpr.cis")) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'jenkinsUser', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
            runCurl("curl -L -o ${tool}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
        }
    } else if (customBuildLink.startsWith("/CIS/")) {
        downloadFiles("/volume1${customBuildLink}", ".")
        if (isUnix()) {
            sh """
                mv ${customBuildLink.split("/")[-1]} ${tool}_${osName}.${extension}
            """
        } else {
            bat """
                move ${customBuildLink.split("/")[-1]} ${tool}_${osName}.${extension}
            """
        }
    } else if (customBuildLink.contains("cis.nas")) {
        withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'reportsNAS', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD'],
            string(credentialsId: "nasIP", variable: "NAS_IP"), string(credentialsId: "nasDomain", variable: "NAS_DOMAIN")]) {

            runCurl("curl -L -o ${tool}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink.replace(NAS_DOMAIN, NAS_IP).replace("https", "http")}\"", 5, oneTryTimeout)

        }
    } else {
        if (credentialsId) {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: credentialsId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD']]) {
                runCurl("curl -L -o ${tool}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
            }
        } else {
            runCurl("curl -L -o ${tool}_${osName}.${extension} -u $USERNAME:$PASSWORD \"${customBuildLink}\"", 5, oneTryTimeout)
        }
    }

    validatePlugin(osName, "${tool}_${osName}.${extension}", options)

    // We haven't any branch so we use sha1 for identifying plugin build
    def pluginSha = sha1 "${tool}_${osName}.${extension}"
    println "Downloaded plugin sha1: ${pluginSha}"

    switch(osName) {
        case 'Windows':
            options['pluginWinSha'] = pluginSha
            break
        case 'OSX':
            options['pluginOSXSha'] = pluginSha
            break
        default:
            options['pluginUbuntuSha'] = pluginSha
    }
}
