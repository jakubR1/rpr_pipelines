import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig


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

                if (engine != "None" || engine != "\"") {
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

                python3("jobs_launcher\\common\\scripts\\generate_baselines.py --results_root results --baseline_root baselines")
                uploadFiles("baselines/", refPathProfile)
            }
        }
    }
}