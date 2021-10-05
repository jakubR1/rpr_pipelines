import java.util.concurrent.atomic.AtomicBoolean

/**
 * Class which provides flexible rebuilding of test reports
 */
public class ReportUpdater {

    def context
    def env
    def options

    // locks for each report (to prevent updating of report by two parallel branches of build)
    Map locks = [:]

    /**
     * Main constructor
     *
     * @param context
     * @param env env variable of the current pipeline
     * @param options map with options
     */
    ReportUpdater(context, env, options) {
        this.context = context
        this.env = env
        this.options = options
    }

    /**
     * Init function (it prepares necessary environment to rebuild reports in future)
     * 
     * @param buildArgsFunc fuction to get string with arguments for build script
     */
    def init(def buildArgsFunc) {
        String remotePath = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/".replace(" ", "_")

        context.withCredentials([context.string(credentialsId: "nasURL", variable: "REMOTE_HOST")]) {
            context.bat('%CIS_TOOLS%\\clone_test_repo.bat' + ' %REMOTE_HOST%' + " ${remotePath} ${options.testRepo} ${options.testsBranch}")
        }

        if (options.engines) {
            options.engines.each { engine ->
                String engineName = options.enginesNames[options.engines.indexOf(engine)]
                String reportName = "Test Report ${engineName}"

                context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                    reportName, "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                    ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])

                String rebuiltScript = context.readFile("..\\..\\cis_tools\\update_report_template.sh")

                rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                    .replace("<report_name>", reportName.replace(" ", "_")).replace("<build_script_args>", buildArgsFunc(engineName, options)) \
                    .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", env.JENKINS_URL)

                // replace DOS EOF by Unix EOF
                rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

                context.writeFile(file: "update_report_${engine}.sh", text: rebuiltScript)

                context.uploadFiles("update_report_${engine}.sh", "${remotePath}/jobs_test_repo/jobs_launcher")

                locks[engine] = new AtomicBoolean(false)

                updateReport(engine)
            }
        } else {
            String reportName = "Test Report"

            context.utils.publishReport(context, "${context.BUILD_URL}", "summaryTestResults", "summary_report.html, performance_report.html, compare_report.html", \
                reportName, "Summary Report, Performance Report, Compare Report" , options.storeOnNAS, \
                ["jenkinsBuildUrl": context.BUILD_URL, "jenkinsBuildName": context.currentBuild.displayName])

            String rebuiltScript = context.readFile("..\\..\\cis_tools\\update_report_template.sh")

            rebuiltScript = rebuiltScript.replace("<jobs_started_time>", options.JOB_STARTED_TIME).replace("<build_name>", options.baseBuildName) \
                .replace("<report_name>", reportName.replace(" ", "_")).replace("<build_script_args>", buildArgsFunc(options)) \
                .replace("<build_id>", env.BUILD_ID).replace("<job_name>", env.JOB_NAME).replace("<jenkins_url>", env.JENKINS_URL)

            // replace DOS EOF by Unix EOF
            rebuiltScript = rebuiltScript.replaceAll("\r\n", "\n")

            context.writeFile(file: "update_report.sh", text: rebuiltScript)

            context.uploadFiles("update_report.sh", "${remotePath}/jobs_test_repo/jobs_launcher")

            locks["default"] = new AtomicBoolean(false)

            updateReport()
        }
    }

    /**
     * Function to update report
     * 
     * @param engine (optional) engine of the target report (if project supports splitting by engines)
     */
    def updateReport(String engine) {
        String lockKey = engine ?: "default"

        try {
            if (locks[lockKey].compareAndSet(false, true)) {
                String remotePath = "/volume1/web/${env.JOB_NAME}/${env.BUILD_NUMBER}/jobs_test_repo/jobs_launcher".replace(" ", "_")
                String scriptName = engine ? "update_report_${engine}.sh" : "update_report.sh"

                context.withCredentials([context.string(credentialsId: "nasURL", variable: "REMOTE_HOST")]) {
                    if (context.isUnix()) {
                        context.sh(script: '$CIS_TOOLS/update_report.sh' + ' $REMOTE_HOST' + " ${remotePath} ${scriptName}")
                    } else {
                        context.bat(script: '%CIS_TOOLS%\\update_report.bat' + ' %REMOTE_HOST%' + " ${remotePath} ${scriptName}")
                    }
                }

                locks[lockKey].getAndSet(false)
            } else {
                context.println("[INFO] Report update skipped")
            }
        } catch (e) {
            context.println("[ERROR] Can't update test report")
            context.println(e.toString())
            context.println(e.getMessage())

            locks[lockKey].getAndSet(false)
        }
    }

}
