def main(Map options) {
    timestamps {
        def updateTasks = [:]
        nodes = nodesByLabel "${options.tags}"

        println("---SELECTED NODES:")
        println(nodes)

        nodes.each() {
            updateTasks["${it}"] = {
                stage("Driver update ${it}") {
                    node("${it}") {
                        timeout(time: "60", unit: "MINUTES") {
                            try {
                                DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"
                                
                                bat "${CIS_TOOLS}\\driver_detection\\amd_request.bat \"${DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\page.html >> page_download_${it}.log 2>&1 "

                                withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
                                    python3("-m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${it}.log 2>&1")
                                    status = bat(returnStatus: true, script: "python ${CIS_TOOLS}\\driver_detection\\parse_driver.py --html_path ${env.WORKSPACE}\\page.html --installer_dst ${env.WORKSPACE}\\driver.exe --drivers_dir C:\\AMD >> parse_stage_${it}.log 2>&1")
                                    if (status == 404) {
                                        println("[INFO] Newer driver not found")
                                    }
                                }
                            } catch(e) {
                                println(e.toString());
                                println(e.getMessage());
                                currentBuild.result = "FAILURE";
                            } finally {
                                archiveArtifacts "*.log"
                            }
                        }
                    }
                }
            }
        }

        parallel updateTasks
        return 0
    }
}

def call(Boolean productionDriver = False,
        String platforms = "",
        String tags = "")
{
    main([productionDriver:productionDriver,
        platforms:platforms,
        tags:tags])
}
