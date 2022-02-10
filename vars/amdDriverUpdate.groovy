DRIVER_PAGE_URL = "https://www.amd.com/en/support/graphics/amd-radeon-6000-series/amd-radeon-6800-series/amd-radeon-rx-6800-xt"

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
                        timeout(time: "30", unit: "MINUTES") {
                            bat "${CIS_TOOLS}\\amd_request.bat \"${DRIVER_PAGE_URL}\" ${env.WORKSPACE}\\page.html >> page_download_${it}.log 2>&1 "

                            withEnv(["PATH=c:\\python39\\;c:\\python39\\scripts\\;${PATH}"]) {
                                bat "python -m pip install -r ${CIS_TOOLS}\\driver_detection\\requirements.txt >> parse_stage_${it}.log 2>&1"
                                bat "python ${CIS_TOOLS}\\parse_driver.py --file ${env.WORKSPACE}\\page.html --output ${env.WORKSPACE}\\driver.exe >> parse_stage_${it}.log 2>&1"
                            }
                        }
                    }
                }
            }
        }

        parallel windowsUpdateTasks
        return 0
    }
}

def call(Boolean productionDriver = False,
        String url = "",
        String platforms = "",
        String tags = "")
{
    main([productionDriver:productionDriver,
        url:url,
        platforms:platforms,
        tags:tags])
}
