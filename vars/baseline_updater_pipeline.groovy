import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic


@Field final Map BASELINE_DIR_MAPPING = [
    "blender": "rpr_blender_autotests",
    "maya": "rpr_maya_autotests",
    "max": "rpr_max_autotests",
    "core": "rpr_core_autotests",
    "blender_usd_hydra": "usd_blender_autotests",
    "inventor": "usd_inventor_autotests",
    "USD": "rpr_usdplugin_autotests"
]

@Field final Map PROJECT_MAPPING = [
    "blender": "Blender",
    "maya": "Maya",
    "max": "Max",
    "core": "Core",
    "blender_usd_hydra": "Blender USD Hydra",
    "inventor": "Inventor",
    "USD": "Houdini"
]

@Field final Map ENGINE_REPORT_MAPPING = [
    "full": "Tahoe",
    "full2": "Northstar",
    "tahoe": "tahoe",
    "northstar": "Northstar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL"
]

@Field final Map ENGINE_BASELINES_MAPPING = [
    "full": "",
    "full2": "NorthStar",
    "tahoe": "",
    "northstar": "NorthStar",
    "hdrprplugin": "RPR",
    "hdstormrendererplugin": "GL"
]


@NonCPS
def parseResponse(String response) {
    def jsonSlurper = new groovy.json.JsonSlurperClassic()
    return jsonSlurper.parseText(response)
}


def call(String jobName,
    String buildID,
    String resultPath,
    String caseName,
    String engine,
    String toolName) {

    stage("UpdateBaselines") {
        node("Windows") {
            ws("WS/UpdateBaselines") {
                cleanWS()

                toolName = toolName.toLowerCase()
                baselineDirName = BASELINE_DIR_MAPPING[toolName]

                def resultPathParts = resultPath.split("/")[0].split("-")
                String gpuName = resultPathParts[0]
                String osName = resultPathParts[1]

                String machineConfiguration
                String reportName

                if (engine == "None" || engine == "\"") {
                    engine = ""
                }

                if (engine) {
                    reportName = "Test_Report_${ENGINE_REPORT_MAPPING[engine.toLowerCase()]}"

                    String engineBaselineName = ENGINE_BASELINES_MAPPING[engine.toLowerCase()]
                    machineConfiguration = engineBaselineName ? "${gpuName}-${osName}-${engineBaselineName}" : "${gpuName}-${osName}"
                } else {
                    reportName = "Test_Report"
                    machineConfiguration = "${gpuName}-${osName}"
                }

                String baselinesPath = "/Baselines/${baselineDirName}"
                String remoteResultPath = "/volume1/web/${jobName}/${buildID}/${reportName}/${resultPath}"
                String refPathProfile = "/volume1/${baselinesPath}/${machineConfiguration}" 
                String groupName = resultPath.split("/")[-1]
                String reportComparePath = "results/${groupName}/report_compare.json"

                String platform = resultPath.split('-')[0]
                currentBuild.description = "<b>Configuration:</b> ${PROJECT_MAPPING[toolName]} (${engine ? platform + '-' + ENGINE_REPORT_MAPPING[engine.toLowerCase()] : platform})<br/>"
                if (caseName) {
                    currentBuild.description += "<b>Group:</b> ${groupName} / <b>Case:</b> ${caseName}<br/>"
                } else {
                    currentBuild.description += "<b>Group:</b> ${groupName}<br/>"
                }

                dir("jobs_launcher") {
                    checkoutScm(branchName: 'master', repositoryUrl: 'git@github.com:luxteam/jobs_launcher.git')
                }

                if (caseName) {
                    downloadFiles(remoteResultPath + "/report_compare.json", "results/${groupName}")
                    downloadFiles(remoteResultPath + "/Color/*${caseName}*", "results/${groupName}/Color")
                    downloadFiles(remoteResultPath + "/*${caseName}*.json", "results/${groupName}")

                    def testCases = readJSON(file: reportComparePath)
        
                    for (testCase in testCases) {
                        if (testCase["test_case"] == caseName) {
                            JSON serializedJson = JSONSerializer.toJSON([testCase], new JsonConfig());
                            writeJSON(file: reportComparePath, json: serializedJson, pretty: 4)
                            break
                        }
                    }
                } else {
                    downloadFiles(remoteResultPath, "results")
                }

                def testCaseInfo
                dir ("results/${groupName}") {
                    // read information about some test case to reach UMS entities ids
                    testCaseInfo = readJSON(file: findFiles(glob: "*_RPR.json")[0].name)[0]
                }

                python3("jobs_launcher\\common\\scripts\\generate_baselines.py --results_root results --baseline_root baselines")
                uploadFiles("baselines/", refPathProfile)

                // Update baselines on UMS
                if (testCaseInfo.job_id_prod || testCaseInfo.job_id_dev) {
                    withCredentials([string(credentialsId: "prodUniverseURL", variable: "PROD_UMS_URL")]) {
                        // Receive token
                        def response = httpRequest(
                            consoleLogResponseBody: true,
                            authentication: "universeMonitoringSystem",
                            httpMode: "POST",
                            url: PROD_UMS_URL + "/user/login",
                            validResponseCodes: "200"
                        )

                        def token = readJSON(text: "${response.content}")["token"]

                        // Find necessary result suite
                        response = httpRequest(
                            consoleLogResponseBody: true,
                            customHeaders: [
                                [name: "Authorization", value: "Token ${token}"]
                            ],
                            httpMode: "GET",
                            ignoreSslErrors: true,
                            url: PROD_UMS_URL + "/api/build?id=${testCaseInfo.build_id_prod}&jobId=${testCaseInfo.job_id_prod}"
                        )

                        def content = parseResponse(response.content)

                        def targetSuiteResultId

                        String platformName = "${osName}-${gpuName}"

                        suiteLoop: for (suite in content["suites"]) {
                            if (suite["suite"]["name"] == groupName) {
                                for (suiteResult in suite["envs"]) {
                                    if (suiteResult["env"] == platformName) {
                                        targetSuiteResultId = suiteResult["_id"]
                                        break suiteLoop
                                    }
                                }
                            }
                        }

                        if (caseName) {
                            // Receive list of test case result
                            response = httpRequest(
                                consoleLogResponseBody: true,
                                customHeaders: [
                                    [name: "Authorization", value: "Token ${token}"]
                                ],
                                httpMode: "GET",
                                ignoreSslErrors: true,
                                url: PROD_UMS_URL + "/api/testSuiteResult?jobId=${testCaseInfo.job_id_prod}&id=${targetSuiteResultId}"
                            )

                            content = parseResponse(response.content)

                            def targetCaseResultId

                            for (testCaseResult in content["results"]) {
                                if (testCaseResult["test_case"]["name"] == caseName) {
                                    targetCaseResultId = testCaseResult["_id"]
                                    break
                                }
                            }

                            httpRequest(
                                consoleLogResponseBody: true,
                                customHeaders: [
                                    [name: "Authorization", value: "Token ${token}"]
                                ],
                                httpMode: "POST",
                                ignoreSslErrors: true,
                                url: PROD_UMS_URL + "/baselines/testCaseResult?id=${targetCaseResultId}&product_id=${testCaseInfo.job_id_prod}"
                            )
                        } else {
                            // Make baselines the whole test suite result
                            httpRequest(
                                consoleLogResponseBody: true,
                                customHeaders: [
                                    [name: "Authorization", value: "Token ${token}"]
                                ],
                                httpMode: "POST",
                                ignoreSslErrors: true,
                                url: PROD_UMS_URL + "/baselines/testSuiteResult?id=${targetSuiteResultId}&product_id=${testCaseInfo.job_id_prod}"
                            )
                        }
                    }
                }
            }
        }
    }
}