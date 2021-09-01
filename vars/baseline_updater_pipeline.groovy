import groovy.transform.Field
import net.sf.json.JSON
import net.sf.json.JSONSerializer
import net.sf.json.JsonConfig


@Field final String BASELINE_DIR_MAPPING = [
    "blender": "rpr_blender_autotests",
    "maya": "rpr_maya_autotests",
    "max": "rpr_max_autotests",
    "core": "rpr_core_autotests",
    "usdblender": "usd_blender_autotests",
    "inventor": "usd_inventor_autotests",
    "usdplugin": "rpr_usdplugin_autotests",
    "usdviewer": "rpr_usdviewer_autotests"
]


def call(String resultsPath,
    String caseName,
    String machineConfiguration,
    String toolName) {

    stage("UpdateBaselines") {
        node("Windows") {
            ws("WS/UpdateBaselines") {
                cleanWS()

                toolName = toolName.toLowerCase()
                baselineDirName = BASELINE_DIR_MAPPING[toolName]

                String baselinesPath = "/Baselines/${baselineDirName}"
                String remoteResultsPath = "/volume1/${ResultsPath}"
                String refPathProfile = "/volume1/${baselinesPath}/${MachineConfiguration}" 
                String groupName = ResultsPath.split("/")[-1]
                String reportComparePath = "results/${groupName}/report_compare.json"

                dir("jobs_launcher") {
                    checkoutScm(branchName: 'master', repositoryUrl: 'git@github.com:luxteam/jobs_launcher.git')
                }

                if (CaseName) {
                    downloadFiles(remoteResultsPath + "/report_compare.json", "results/${groupName}")
                    downloadFiles(remoteResultsPath + "/Color/*${CaseName}*", "results/${groupName}/Color")

                    def testCases = readJSON(file: reportComparePath)
        
                    for (testCase in testCases) {
                        if (testCase["test_case"] == CaseName) {
                            JSON serializedJson = JSONSerializer.toJSON([testCase], new JsonConfig());
                            writeJSON(file: reportComparePath, json: serializedJson, pretty: 4)
                            break
                        }
                    }
                } else {
                    downloadFiles(remoteResultsPath, "results")
                }

                python3("jobs_launcher\\common\\scripts\\generate_baselines.py --results_root results --baseline_root baselines")
                uploadFiles("baselines/", refPathProfile)
            }
        }
    }
}