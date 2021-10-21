import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

/**
 * Implementation of archive artifacts through custom machine
 * 
 * @param params Map with parameters
 * Possible elements:
 *     name - Name of artifact
 *     storeOnNAS - Specify store data on NAS or publish artifacts in Jenkins build
 *     createLink (optional) - Make zip archive for stash (default - true)
 */
def call(Map params) {
    try {
        String artifactName = params["name"]
        Boolean storeOnNAS = params.containsKey("storeOnNAS") ? params["storeOnNAS"] : false
        Boolean createLink = params.containsKey("createLink") ? params["createLink"] : true

        String artifactURL

        if (storeOnNAS) {
            String path = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/Artifacts/"
            makeStash(includes: artifactName, name: artifactName, allowEmpty: true, customLocation: path, preZip: false, postUnzip: false, storeOnNAS: true)

            withCredentials([string(credentialsId: "nasURL", variable: "REMOTE_HOST"),
                string(credentialsId: "nasURLFrontend", variable: "REMOTE_URL")]) {
                artifactURL = "${REMOTE_URL}/${env.JOB_NAME}/${env.BUILD_NUMBER}/Artifacts/${artifactName}"
            }

            withCredentials([usernamePassword(credentialsId: "reportsNAS", usernameVariable: "NAS_USER", passwordVariable: "NAS_PASSWORD")]) {
                artifactURL = artifactURL.replace("https://", "https://${NAS_USER}:${NAS_PASSWORD}@")

                if (createLink) {
                    rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${artifactURL}">[BUILD: ${BUILD_ID}] ${artifactName}</a></h3>"""
                }
            }
        } else {
            artifactURL = "${BUILD_URL}artifact/${artifactName}"
            
            archiveArtifacts(artifactName)

            if (createLink) {
                rtp nullAction: "1", parserName: "HTML", stableText: """<h3><a href="${artifactURL}">[BUILD: ${BUILD_ID}] ${artifactName}</a></h3>"""
            }
        }

        return artifactURL
    } catch (e) {
        println("[ERROR] Failed to archive artifacts")
        println(e.toString())
        e.printStackTrace()
        throw e
    }

}
