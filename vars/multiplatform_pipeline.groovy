import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

def executeTestsNode(String osName, String gpuNames, def executeTests, Map options)
{
    if(gpuNames && options['executeTests'])
    {
        def testTasks = [:]
        gpuNames.split(',').each()
        {
            String asicName = it
            testTasks["Test-${it}-${osName}"] =
            {
                stage("Test-${asicName}-${osName}")
                {
                    // if not split - testsList doesn't exists
                    options.testsList = options.testList ?: ''

                    options.testsList.each()
                    { testName ->
                        println("Scheduling ${osName}:${asicName} ${testName}")
                        // reallocate node for each test
                        node("${osName} && Tester && OpenCL && gpu${asicName}")
                        {
                            timeout(time: "${options.TEST_TIMEOUT}", unit: 'MINUTES')
                            {
                                ws("WS/${options.PRJ_NAME}_Test")
                                {
                                    Map newOptions = options.clone()
                                    newOptions['testResultsName'] = testName ? "testResult-${asicName}-${osName}-${testName}" : "testResult-${asicName}-${osName}"
                                    newOptions['stageName'] = testName ? "${asicName}-${osName}-${testName}" : "${asicName}-${osName}"
                                    newOptions['tests'] = testName ? testName : options.tests
                                    executeTests(osName, asicName, newOptions)
                                }
                            }
                        }
                    }
                }
            }
        }
        parallel testTasks
    }
    else
    {
        echo "No tests found for ${osName}"
    }
}

def executePlatform(String osName, String gpuNames, def executeBuild, def executeTests, Map options)
{
    def retNode =  
    {   
        try
        {
            if(!options['skipBuild'] && options['executeBuild'])
            {
                node("${osName} && ${options.BUILDER_TAG}")
                {
                    stage("Build-${osName}")
                    {
                        timeout(time: "${options.BUILD_TIMEOUT}", unit: 'MINUTES')
                        {
                            ws("WS/${options.PRJ_NAME}_Build")
                            {
                                executeBuild(osName, options)
                            }
                        }
                    }
                }
            }
            executeTestsNode(osName, gpuNames, executeTests, options)
        }
        catch (e)
        {
            println(e.toString());
            println(e.getMessage());     
            currentBuild.result = "FAILED"
            throw e
        }
    }
    return retNode
}

def call(String platforms, def executePreBuild, def executeBuild, def executeTests, def executeDeploy, Map options) {
    
    try
    {
        properties([[$class: 'BuildDiscarderProperty', strategy: 	
                     [$class: 'LogRotator', artifactDaysToKeepStr: '', 	
                      artifactNumToKeepStr: '10', daysToKeepStr: '', numToKeepStr: '']]]);
        timestamps
        {
            String PRJ_PATH="${options.PRJ_ROOT}/${options.PRJ_NAME}"
            String REF_PATH="${PRJ_PATH}/ReferenceImages"
            String JOB_PATH="${PRJ_PATH}/${JOB_NAME}/Build-${BUILD_ID}".replace('%2F', '_')
            options['PRJ_PATH']="${PRJ_PATH}"
            options['REF_PATH']="${REF_PATH}"
            options['JOB_PATH']="${JOB_PATH}"
    
            // if tag empty - set default Builder
            options['BUILDER_TAG'] = options.get('BUILDER_TAG', null) ?: 'Builder'

            // if timeout doesn't set - use default
            // value in minutes
            options['PREBUILD_TIMEOUT'] = options['PREBUILD_TIMEOUT'] ?: 15
            options['BUILD_TIMEOUT'] = options['BUILD_TIMEOUT'] ?: 60
            options['TEST_TIMEOUT'] = options['TEST_TIMEOUT'] ?: 600
            options['DEPLOY_TIMEOUT'] = options['DEPLOY_TIMEOUT'] ?: 30

            def platformList = [];
            def testResultList = [];

            try
            {
                if(executePreBuild)
                {
                    node("Windows && PreBuild")
                    {
                        ws("WS/${options.PRJ_NAME}_PreBuild")
                        {
                            stage("PreBuild")
                            {
                                timeout(time: "${options.PREBUILD_TIMEOUT}", unit: 'MINUTES') 
                                {
                                    executePreBuild(options)
                                    if(!options['executeBuild'])
                                    {
                                        // var for slack notifications
                                        options.CBR = 'SKIPPED'
                                        echo "Build SKIPPED"
                                    }
                                }
                            }
                        }
                    }
                }
                
                def tasks = [:]

                platforms.split(';').each()
                {
                    List tokens = it.tokenize(':')
                    String osName = tokens.get(0)
                    String gpuNames = ""
                    if (tokens.size() > 1)
                    {
                        gpuNames = tokens.get(1)
                    }
                    
                    platformList << osName
                    if(gpuNames)
                    {
                        gpuNames.split(',').each()
                        {
                            options.tests.each()
                            { testName ->
                                String asicName = it
                                testResultList << "testResult-${asicName}-${osName}${testName}"
                            }
                        }
                    }

                    tasks[osName]=executePlatform(osName, gpuNames, executeBuild, executeTests, options)
                }
                parallel tasks
            }
            finally
            {
                node("Windows && ReportBuilder")
                {
                    stage("Deploy")
                    {
                        timeout(time: "${options.DEPLOY_TIMEOUT}", unit: 'MINUTES')
                        {
                            ws("WS/${options.PRJ_NAME}_Deploy") {

                                try
                                {
                                    if(executeDeploy && options['executeTests'])
                                    {
                                        executeDeploy(options, platformList, testResultList)
                                    }

                                    dir('_publish_artifacts_html_')
                                    {
                                        deleteDir()
                                        appendHtmlLinkToFile("artifacts.html", "${options.PRJ_PATH}", 
                                                             "https://builds.rpr.cis.luxoft.com/${options.PRJ_PATH}")
                                        appendHtmlLinkToFile("artifacts.html", "${options.REF_PATH}", 
                                                             "https://builds.rpr.cis.luxoft.com/${options.REF_PATH}")
                                        appendHtmlLinkToFile("artifacts.html", "${options.JOB_PATH}", 
                                                             "https://builds.rpr.cis.luxoft.com/${options.JOB_PATH}")

                                        archiveArtifacts "artifacts.html"
                                    }
                                    publishHTML([allowMissing: false, 
                                                 alwaysLinkToLastBuild: false, 
                                                 keepAll: true, 
                                                 reportDir: '_publish_artifacts_html_', 
                                                 reportFiles: 'artifacts.html', reportName: 'Project\'s Artifacts', reportTitles: 'Artifacts'])
                                }
                                catch (e) {
                                    println(e.toString());
                                    println(e.getMessage());
                                    currentBuild.result = "FAILED"
                                    throw e
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // catch if job was aborted by user
    catch (FlowInterruptedException e)
    {
        println(e.toString());
        println(e.getMessage());
        options.CBR = "ABORTED"
        echo "Job was ABORTED by user: ${currentBuild.result}"
    }
    catch (e)
    {
        println(e.toString());
        println(e.getMessage());
        currentBuild.result = "FAILED"
        throw e
    }
    finally
    {
        echo "enableNotifications = ${options.enableNotifications}"
        if("${options.enableNotifications}" == "true")
        {
            sendBuildStatusNotification(currentBuild.result, 
                                        options.get('slackChannel', ''), 
                                        options.get('slackBaseUrl', ''),
                                        options.get('slackTocken', ''),
                                        options)
        }
    }
}
