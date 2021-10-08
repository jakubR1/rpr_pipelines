def call(Map options){
    checkoutScm(branchName: options.testsBranch, repositoryUrl: options.testRepo)

    options['testsBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    dir ('jobs_launcher') {
        options['jobsLauncherBranch'] = bat (script: "git log --format=%%H -1 ", returnStdout: true).split('\r\n')[2].trim()
    }
    println "[INFO] Test branch hash: ${options['testsBranch']}"

    def packageInfo

    if (options.testsPackage != "none") {
        packageInfo = readJSON file: "jobs/${options.testsPackage}"
        options.isPackageSplitted = packageInfo["split"]
        // if it's build of manual job and package can be splitted - use list of tests which was specified in params (user can change list of tests before run build)
        if (options.forceBuild && options.isPackageSplitted && options.tests) {
            options.testsPackage = "none"
        }
    }

    if (options.testsPackage != "none") {
        def tempTests = []

        if (options.isPackageSplitted) {
            println("[INFO] Tests package '${options.testsPackage}' can be splitted")
        } else {
            // save tests which user wants to run with non-splitted tests package
            if (options.tests) {
                tempTests = options.tests.split(" ") as List
            }
            println("[INFO] Tests package '${options.testsPackage}' can't be splitted")
        }

        // modify name of tests package if tests package is non-splitted (it will be use for run package few time with different engines)
        String modifiedPackageName = "${options.testsPackage}~"
        options.groupsUMS = tempTests.clone()

        // receive list of group names from package
        List groupsFromPackage = []

        if (packageInfo["groups"] instanceof Map) {
            groupsFromPackage = packageInfo["groups"].keySet() as List
        } else {
            // iterate through all parts of package
            packageInfo["groups"].each() {
                groupsFromPackage.addAll(it.keySet() as List)
            }
        }

        groupsFromPackage.each() {
            if (options.isPackageSplitted) {
                tempTests << it
                options.groupsUMS << it
            } else {
                if (tempTests.contains(it)) {
                    // add duplicated group name in name of package group name for exclude it
                    modifiedPackageName = "${modifiedPackageName},${it}"
                } else {
                    options.groupsUMS << it
                }
            }
        }

        options.tests = utils.uniteSuites(this, "jobs/weights.json", tempTests)
        options.tests.each() {
            def xml_timeout = utils.getTimeoutFromXML(this, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
            options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
        }
        options.engines.each { engine ->
            options.tests.each() {
                tests << "${it}-${engine}"
            }
        }

        modifiedPackageName = modifiedPackageName.replace('~,', '~')

        if (options.isPackageSplitted) {
            options.testsPackage = "none"
        } else {
            options.testsPackage = modifiedPackageName
            // check that package is splitted to parts or not
            if (packageInfo["groups"] instanceof Map) {
                options.engines.each { engine ->
                    tests << "${modifiedPackageName}-${engine}"
                } 
                options.timeouts[options.testsPackage] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
            } else {
                // add group stub for each part of package
                options.engines.each { engine ->
                    for (int i = 0; i < packageInfo["groups"].size(); i++) {
                        tests << "${modifiedPackageName}-${engine}".replace(".json", ".${i}.json")
                    }
                }

                for (int i = 0; i < packageInfo["groups"].size(); i++) {
                    options.timeouts[options.testsPackage.replace(".json", ".${i}.json")] = options.NON_SPLITTED_PACKAGE_TIMEOUT + options.ADDITIONAL_XML_TIMEOUT
                }
            }
        }
    } else if (options.tests) {
        options.groupsUMS = options.tests.split(" ") as List
        options.tests = utils.uniteSuites(this, "jobs/weights.json", options.tests.split(" ") as List)
        options.tests.each() {
            def xml_timeout = utils.getTimeoutFromXML(this, it, "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
            options.timeouts["${it}"] = (xml_timeout > 0) ? xml_timeout : options.TEST_TIMEOUT
        }
        options.engines.each { engine ->
            options.tests.each() {
                tests << "${it}-${engine}"
            }
        }
    } else {
        options.executeTests = false
    }
    options.tests = tests
    

    if (env.BRANCH_NAME && options.githubNotificator) {
        options.githubNotificator.initChecks(options, "${BUILD_URL}")
    }

    options.testsList = options.tests

    println "timeouts: ${options.timeouts}"

    if (options.sendToUMS) {
        options.universeManager.createBuilds(options)
    }
}