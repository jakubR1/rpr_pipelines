import utils

class PreBuildFunctions {

    static void setRepoInfo(Object self, Map options, String tool) {
        options.commitAuthor = self.bat (script: 'git show -s --format=%%an HEAD ', returnStdout: true).split('\r\n')[2].trim()
        options.commitMessage = self.bat (script: 'git log --format=%%B -n 1', returnStdout: true).split('\r\n')[2].trim()
        options.commitSHA = self.bat (script: 'git log --format=%%H -1 ', returnStdout: true).split('\r\n')[2].trim()
        options.commitShortSHA = options.commitSHA[0..6]
        options.branchName = self.env.BRANCH_NAME ?: options.projectBranch

        self.println(self.bat (script: "git log --format=%%s -n 1", returnStdout: true).split('\r\n')[2].trim());
        self.println "The last commit was written by ${options.commitAuthor}."
        self.println "Commit message: ${options.commitMessage}"
        self.println "Commit SHA: ${options.commitSHA}"
        self.println "Commit shortSHA: ${options.commitShortSHA}"
        self.println "Branch name: ${options.branchName}"
    }

    static void setBranchPostfix(Object self, Map options) {
        options['branch_postfix'] = ''
        if (self.env.BRANCH_NAME && self.env.BRANCH_NAME == 'master') {
            options['branch_postfix'] = 'release'
        } else if (self.env.BRANCH_NAME && self.env.BRANCH_NAME != 'master' && self.env.BRANCH_NAME != 'develop') {
            options['branch_postfix'] = self.env.BRANCH_NAME.replace('/', '-')
        } else if (options.projectBranch && options.projectBranch != 'master' && options.projectBranch != 'develop') {
            options['branch_postfix'] = options.projectBranch.replace('/', '-')
        }
    }

    static void setBuildDescription(Object self, Map options, String tool) {

        if (options['isPreBuilt']) {
            self.currentBuild.description = "<b>Project branch:</b> Prebuilt plugin<br/>"
        } else{
            self.currentBuild.description = "<b>Project branch:</b> ${options.projectBranchName}<br/>"
        }

        self.currentBuild.description += tool != 'Houdini' ? "<b>Version:</b> ${options.pluginVersion}<br/>" : "<b>Version:</b> ${options.majorVersion}.${options.minorVersion}.${options.patchVersion}<br/>"
        self.currentBuild.description += "<b>Commit author:</b> ${options.commitAuthor}<br/>"
        self.currentBuild.description += "<b>Commit message:</b> ${options.commitMessage}<br/>"
        self.currentBuild.description += "<b>Commit SHA:</b> ${options.commitSHA}<br/>"
    }

    static void setParamsByCommitMessage(Map options) {
        if (options.commitMessage.contains('CIS:BUILD')) {
            options['executeBuild'] = true
        }

        if (options.commitMessage.contains('CIS:TESTS')) {
            options['executeBuild'] = true
            options['executeTests'] = true
        }
    }

    static void setInitParams(Object self, Map options, String testPackage, String tool) {
        if (options['isPreBuilt']) {
            self.println '[INFO] Build was detected as prebuilt. Build stage will be skipped'
            options['executeBuild'] = false
            options['executeTests'] = true
        // manual job
        } else if (options.forceBuild && tool != 'Core') {
            self.println '[INFO] Manual job launch detected'
            options['executeBuild'] = true
            options['executeTests'] = true
        // auto job
        } else {
            if (tool != 'Core'){
                options['testsPackage'] = testPackage
            }
            if (self.env.CHANGE_URL) {
                if (!(tool in ['Core'] )){
                    options['executeBuild'] = true
                    options['executeTests'] = true
                }
                self.println '[INFO] Branch was detected as Pull Request'
            } else if (self.env.BRANCH_NAME == 'master' && self.env.BRANCH_NAME == 'develop') {
                options['executeBuild'] = true
                options['executeTests'] = true
                self.println "[INFO] ${self.env.BRANCH_NAME} branch was detected"
            } else if(self.env.BRANCH_NAME == 'master' && tool == 'Core'){
                println("[INFO] ${self.env.BRANCH_NAME} branch was detected")
                options.collectTrackedMetrics = true
            } else {
                self.println "[INFO] ${self.env.BRANCH_NAME} branch was detected"
            }
        }
    }

    static void saveRepositoryInfo(Object self, Map options) {
        options['testsBranch'] = self.bat (script: 'git log --format=%%H -1 ', returnStdout: true).split('\r\n')[2].trim()
        self.dir ('jobs_launcher') {
            options['jobsLauncherBranch'] = self.bat (script: 'git log --format=%%H -1 ', returnStdout: true).split('\r\n')[2].trim()
        }
        self.println "[INFO] Test branch hash: ${options['testsBranch']}"
    }

    static void configureTestBlock(Object self, Map options, String tool) {
        def tests = []
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
                self.println("[INFO] Tests package '${options.testsPackage}' can be splitted")
            } else {
                // save tests which user wants to run with non-splitted tests package
                if (options.tests) {
                    tempTests = options.tests.split(" ") as List
                }
                self.println("[INFO] Tests package '${options.testsPackage}' can't be splitted")
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

            options.tests = utils.uniteSuites(self, "jobs/weights.json", tempTests)
            options.tests.each() {
                def xml_timeout = utils.getTimeoutFromXML(self, "${it}", "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
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
            options.tests = utils.uniteSuites(self, "jobs/weights.json", options.tests.split(" ") as List)
            options.tests.each() {
                def xml_timeout = utils.getTimeoutFromXML(self, it, "simpleRender.py", options.ADDITIONAL_XML_TIMEOUT)
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
    }
}


