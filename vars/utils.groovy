import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import hudson.model.Result
import groovy.json.JsonOutput

/**
 * self in methods params is a context of executable pipeline. Without it you can't call Jenkins methods.
 */
class utils {

    enum Color{
        //text colors
        RED("\033[91m"), 
        BLACK("\033[30m"),
        BLUE("\033[34m"),
        GREEN("\033[92m"),
        YELLOW("\033[93m"),
        WHITE("\033[97m"),
        ORANGE("\033[38;5;208m"),
        DEFAULT("\033[39m"),
        
        //background colors
        BLACKB("\033[40m"),
        REDB("\033[41m"),
        GREENB("\033[42m"),
        YELLOWB("\033[43m"),
        BLUEB("\033[44m"),
        WHITEB("\033[107m"),
        DEFAULTB("\033[49m")
        
        private String value
        
        Color(String value) {
            this.value = value
        }
        String getValue() {
            value
        }
    }

    static void printColor(Object self, String content, String textColor, String backgroundColor){
        Color text = textColor
        Color background = backgroundColor
        ansiColor('xterm') {
            self.println(background.value + text.value + content + "\033[0m")
        }
    }

    static void printError(Object self, String content){
        utils.printColor(this, "[ERROR] " + content, "RED", "DEFAULTB")
    }

    static void printDebug(Object self, String content){
        utils.printColor(this, "[DEBUG CRITICAL] " + content, "RED", "DEFAULTB")
    }

    static void printInfo(Object self, String content){
        utils.printColor(this, "[INFO] " + content, "BLUE", "DEFAULTB")
    }

    static void printWarning(Object self, String content){
        utils.printColor(this, "[WARNING] " + content, "ORANGE", "DEFAULTB")
    }

    static int getTimeoutFromXML(Object self, String tests, String keyword, Integer additional_xml_timeout) {
        try {
            Integer xml_timeout = 0
            for (test in tests.split()) {
                String xml = self.readFile("jobs/Tests/${test}/test.job-manifest.xml")
                for (xml_string in xml.split("<")) {
                    if (xml_string.contains("${keyword}") && xml_string.contains("timeout")) {
                        xml_timeout += Math.round((xml_string.findAll(/\d+/)[0] as Double).div(60))
                    }
                }
            }
            
            return xml_timeout + additional_xml_timeout
        } catch (e) {
            self.println(e)
            return -1
        }
        return -1
    }

    static def setForciblyBuildResult(RunWrapper currentBuild, String buildResult) {
        currentBuild.build().@result = Result.fromString(buildResult)
    }

    static def isTimeoutExceeded(Exception e) {
        Boolean result = false
        String exceptionClassName = e.getClass().toString()
        if (exceptionClassName.contains("FlowInterruptedException")) {
            //sometimes FlowInterruptedException generated by 'timeout' block doesn't contain exception cause
            if (!e.getCause()) {
                result = true
            } else {
                for (cause in e.getCauses()) {
                    String causeClassName = cause.getClass().toString()
                    if (causeClassName.contains("ExceededTimeout") || causeClassName.contains("TimeoutStepExecution")) {
                        result = true
                        break
                    }
                }
            }
        }
        return result
    }

    static def markNodeOffline(Object self, String nodeName, String offlineMessage) {
        try {
            def nodes = jenkins.model.Jenkins.instance.getLabel(nodeName).getNodes()
            nodes[0].getComputer().doToggleOffline(offlineMessage)
            self.println("[INFO] Node '${nodeName}' was marked as failed")
        } catch (e) {
            self.println("[ERROR] Failed to mark node '${nodeName}' as offline")
            self.println(e)
            throw e
        }
    }

    static def sendExceptionToSlack(Object self, String jobName, String buildNumber, String buildUrl, String webhook, String channel, String message) {
        try {
            def slackMessage = [
                attachments: [[
                    "title": "${jobName} [${buildNumber}]",
                    "title_link": "${buildUrl}",
                    "color": "#720000",
                    "text": message
                ]],
                channel: channel
            ]
            self.httpRequest(
                url: webhook,
                httpMode: 'POST',
                requestBody: JsonOutput.toJson(slackMessage)
            )
            self.println("[INFO] Exception was sent to Slack")
        } catch (e) {
            self.println("[ERROR] Failed to send exception to Slack")
            self.println(e)
        }
    }

    static def stashTestData(Object self, Map options, Boolean publishOnNAS = false) {
        if (publishOnNAS) {
            String engine = ""
            String stashName = ""
            String reportName = ""
            List testsResultsParts = options.testResultsName.split("-") as List
            if (options.containsKey("engines") && options.containsKey("enginesNames")) {
                engine = testsResultsParts[-1]
                // Remove "testResult" prefix and engine from stash name
                stashName = testsResultsParts.subList(1, testsResultsParts.size() - 1).join("-")
            } else {
                // Remove "testResult" prefix from stash name
                stashName = testsResultsParts.subList(1, testsResultsParts.size()).join("-")
            }

            if (engine) {
                String engineName = options.enginesNames[options.engines.indexOf(engine)]
                reportName = "Test_Report_${engineName}"
            } else {
                reportName = "Test_Report"
            }

            String path = "/volume1/web/${self.env.JOB_NAME}/${self.env.BUILD_NUMBER}/${reportName}/${stashName}/"
            self.makeStash(includes: '**/*', name: stashName, allowEmpty: true, customLocation: path, preZip: true, postUnzip: true, storeOnNAS: true)
            self.makeStash(includes: '*.json', excludes: '*/events/*.json', name: options.testResultsName, allowEmpty: true, storeOnNAS: true)
        } else {
            self.makeStash(includes: '**/*', name: options.testResultsName, allowEmpty: true)
        }
    }

    static def publishReport(Object self, String buildUrl, String reportDir, String reportFiles, String reportName, String reportTitles = "", Boolean publishOnNAS = false) {
        Map params

        if (publishOnNAS) {
            String remotePath = "/volume1/web/${self.env.JOB_NAME}/${self.env.BUILD_NUMBER}/${reportName}/".replace(" ", "_")

            self.dir(reportDir) {
                // upload report to NAS in archive and unzip it
                self.makeStash(includes: '**/*', name: "report", allowEmpty: true, customLocation: remotePath, preZip: true, postUnzip: true, storeOnNAS: true)
            }

            String reportLinkBase

            self.withCredentials([self.string(credentialsId: "nasURL", variable: "REMOTE_HOST"),
                self.string(credentialsId: "nasURLFrontend", variable: "REMOTE_URL")]) {
                reportLinkBase = self.REMOTE_URL
            }

            reportLinkBase = "${reportLinkBase}/${self.env.JOB_NAME}/${self.env.BUILD_NUMBER}/${reportName}/".replace(" ", "_")
            
            self.dir("redirect_links") {
                self.withCredentials([self.usernamePassword(credentialsId: "reportsNAS", usernameVariable: "NAS_USER", passwordVariable: "NAS_PASSWORD")]) {
                    String authReportLinkBase = reportLinkBase.replace("https://", "https://${self.NAS_USER}:${self.NAS_PASSWORD}@")

                    reportFiles.split(",").each { reportFile ->
                        if (self.isUnix()) {
                            self.sh(script: '$CIS_TOOLS/make_redirect_page.sh ' + " \"${authReportLinkBase}${reportFile.trim()}\" \".\" \"${reportFile.trim().replace('/', '_')}\"")
                        } else {
                            self.bat(script: '%CIS_TOOLS%\\make_redirect_page.bat ' + " \"${authReportLinkBase}${reportFile.trim()}\"  \".\" \"${reportFile.trim().replace('/', '_')}\"")
                        }
                    }
                }
            }
            
            def updateReportFiles = []
            reportFiles.split(",").each() { reportFile ->
                updateReportFiles << reportFile.trim().replace("/", "_")
            }
            
            updateReportFiles = updateReportFiles.join(", ")

            params = [allowMissing: false,
                          alwaysLinkToLastBuild: false,
                          keepAll: true,
                          reportDir: "redirect_links",
                          reportFiles: updateReportFiles,
                          // TODO: custom reportName (issues with escaping)
                          reportName: reportName]
        } else {
            params = [allowMissing: false,
                          alwaysLinkToLastBuild: false,
                          keepAll: true,
                          reportDir: reportDir,
                          reportFiles: reportFiles,
                          // TODO: custom reportName (issues with escaping)
                          reportName: reportName]
        }

        if (reportTitles) {
            params['reportTitles'] = reportTitles
        }
        self.publishHTML(params)
        try {
            self.httpRequest(
                url: "${buildUrl}/${reportName.replace('_', '_5f').replace(' ', '_20')}/",
                authentication: 'jenkinsCredentials',
                httpMode: 'GET'
            )
            self.println("[INFO] Report exists.")
        } catch(e) {
            self.println("[ERROR] Can't access report")
            throw new Exception("Can't access report", e)
        }
    }

    static def deepcopyCollection(Object self, def collection) {
        def bos = new ByteArrayOutputStream()
        def oos = new ObjectOutputStream(bos)
        oos.writeObject(collection)
        oos.flush()
        def bin = new ByteArrayInputStream(bos.toByteArray())
        def ois = new ObjectInputStream(bin)
        return ois.readObject()
    }

    static def getReportFailReason(String exceptionMessage) {
        if (!exceptionMessage) {
            return "Failed to build report."
        }
        String[] messageParts = exceptionMessage.split(" ")
        Integer exitCode = messageParts[messageParts.length - 1].isInteger() ? messageParts[messageParts.length - 1].toInteger() : null

        if (exitCode && exitCode < 0) {
            switch(exitCode) {
                case -1:
                    return "Failed to build summary report."
                    break
                case -2:
                    return "Failed to build performance report."
                    break
                case -3:
                    return "Failed to build compare report."
                    break
                case -4:
                    return "Failed to build local reports."
                    break
                case -5:
                    return "Several plugin versions"
                    break
            }
        }
        return "Failed to build report."
    }

    static def getTestsFromCommitMessage(String commitMessage) {
        String[] messageParts = commitMessage.split("\n")
        for (part in messageParts) {
            if (part.contains("CIS TESTS:")) {
                String testsRow = part.replace("CIS TESTS:", "").trim()
                // split by ';', ',' or space characters
                String[] tests = testsRow.split("\\s*\\;\\s*|\\s*\\,\\s*|\\s+")
                return tests.join(" ")
            }
        }
        return ""
    }

    static def isReportFailCritical(String exceptionMessage) {
        if (!exceptionMessage) {
            return true
        }
        String[] messageParts = exceptionMessage.split(" ")
        Integer exitCode = messageParts[messageParts.length - 1].isInteger() ? messageParts[messageParts.length - 1].toInteger() : null

        // Unexpected fails
        return exitCode >= 0
    }

    static def renameFile(Object self, String osName, String oldName, String newName) {
        try {
            switch(osName) {
                case 'Windows':
                    self.bat """
                        rename \"${oldName}\" \"${newName}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        mv ${oldName} ${newName}
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't rename file")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def moveFiles(Object self, String osName, String source, String destination) {
        try {
            switch(osName) {
                case 'Windows':
                    source = source.replace('/', '\\\\')
                    destination = destination.replace('/', '\\\\')
                    self.bat """
                        move \"${source}\" \"${destination}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        mv ${source} ${destination}
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't move files")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def copyFile(Object self, String osName, String source, String destination) {
        try {
            switch(osName) {
                case 'Windows':
                    source = source.replace('/', '\\\\')
                    destination = destination.replace('/', '\\\\')
                    self.bat """
                        echo F | xcopy /s/y/i \"${source}\" \"${destination}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        cp ${source} ${destination}
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't copy files")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    static def removeFile(Object self, String osName, String fileName) {
        try {
            switch(osName) {
                case 'Windows':
                    self.bat """
                        if exist \"${fileName}\" del \"${fileName}\"
                    """
                    break
                // OSX & Ubuntu18
                default:
                    self.sh """
                        rm -rf \"${fileName}\"
                    """
            }
        } catch(Exception e) {
            self.println("[ERROR] Can't remove file")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    }

    @NonCPS
    static def parseJson(Object self, String jsonString) {
        try {
            def jsonSlurper = new groovy.json.JsonSlurperClassic()

            return jsonSlurper.parseText(jsonString)
        } catch(Exception e) {
            self.println("[ERROR] Can't parse JSON. Inputted value: " + jsonString)
            self.println(e.toString())
            self.println(e.getMessage())
        }
        return ""
    }

    /**
     * Download file using curl from given link
     * @param filelink - full url to file
     * @param outputDir - path to the directory where the file will be downloaded (default is current dir)
     * @param credentialsId - custom Jenkins credentials
     * @param extraParams - map with additional curl param, where keys and values are equivalent for curl
     * @return relative path to downloaded file
     */
    static String downloadFile(Object self, String filelink, String outputDir = "./", String credentialsId = "", Map extraParams = [:]) {
        String filename = filelink.split("/").last()
        String command = "curl -L -o ${outputDir}${filename} "
        if (credentialsId)
            self.withCredentials([self.usernamePassword(credentialsId: credentialsId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                command += "-u ${self.USERNAME}:${self.PASSWORD} "
            }
        extraParams?.each { command += "-${it.key} ${it.value} " }
        command += "${filelink}"
        if (self.isUnix()) {
            self.sh command
        } else {
            self.bat command
        }
        return outputDir + filename
    }

    /**
     * Compares 2 images
     * @param img1 - path to first image
     * @param img2 - path to second image
     * @return percentage difference of images (0 - similar, 100 - different)
     */
    static Double compareImages(Object self, String img1, String img2) {
        return self.python3("./jobs_launcher/common/scripts/CompareMetrics.py --img1 ${img1} --img2 ${img2}").with {
            (self.isUnix() ? it : it.split(" ").last()) as Double
        }
    }

    static String escapeCharsByUnicode(String text) {
        def unsafeCharsRegex = /['"\\&$ <>|:\n\t]/

        return text.replaceAll(unsafeCharsRegex, {
            "\\u${Integer.toHexString(it.codePointAt(0)).padLeft(4, '0')}"
        })
    }

    static String incrementVersion(Map params) {
        Object self = params["self"]
        String currentVersion = params["currentVersion"]
        Integer index = params["index"] ?: 1
        String delimiter = params["delimiter"] ?: "\\."

        String[] indexes = currentVersion.split(delimiter)
        Integer targetIndex = (indexes[index - 1] as Integer) + 1
        indexes[index - 1] = targetIndex as String

        return indexes.join(delimiter.replace("\\", ""))
    }

    /**
     * Unite test suites to optimize execution of small suites
     * @param weightsFile - path to file with weight of each suite
     * @param suites - List of suites which will be executed during build
     * @param maxWeight - maximum summary weight of united suites
     * @param maxLength - maximum lenght of string with name of each united suite (it's necessary for prevent issues with to log path lengts on Deploy stage)
     * @return Return List of String objects (each string contains united suites in one run). Return suites argument if some exception appears
     */
    static List uniteSuites(Object self, String weightsFile, List suites, Integer maxWeight=3600, Integer maxLength=40) {
        List unitedSuites = []

        try {
            def weights = self.readJSON(file: weightsFile)
            List suitesLeft = suites.clone()
            weights["weights"].removeAll {
                !suites.contains(it["suite_name"])
            }
            while (weights["weights"]) {
                List buffer = []
                Integer currentLength = 0
                Integer currentWeight = 0
                for (suite in weights["weights"]) {
                    if (currentWeight == 0 || (currentWeight + suite["value"] <= maxWeight)) {
                        buffer << suite["suite_name"]
                        currentWeight += suite["value"]
                        currentLength += suite["suite_name"].length() + 1
                        if (currentLength > maxLength) {
                            break
                        }
                    }
                }
                weights["weights"].removeAll {
                    buffer.contains(it["suite_name"])
                }
                suitesLeft.removeAll {
                    buffer.contains(it)
                }
                unitedSuites << buffer.join(" ")
            }

            // add split suites which doesn't have information about their weight
            unitedSuites.addAll(suitesLeft)

            return unitedSuites
        } catch (e) {
            self.println("[ERROR] Can't unit test suites")
            self.println(e.toString())
            self.println(e.getMessage())
        }
        return suites
    }

    /**
     * @param command - executable command
     * @return clear bat stdout without original command
     */
    static String getBatOutput(Object self, String command) {
        return self.bat(script: "@${command}", returnStdout: true).trim()
    }

    /**
     * Reboot current node
     * @param osName - OS name of current node
     */
    static def reboot(Object self, String osName) {
        try {
            switch(osName) {
                case "Windows":
                    self.bat """
                        shutdown /r /f /t 0
                    """
                    break
                case "OSX":
                    self.sh """
                        sudo shutdown -r now
                    """
                // Ubuntu
                default:
                    self.sh """
                        shutdown -h now
                    """
            }
        } catch (e) {
            self.println("[ERROR] Failed to reboot machine")
            self.println(e.toString())
            self.println(e.getMessage())
        }
    } 
}