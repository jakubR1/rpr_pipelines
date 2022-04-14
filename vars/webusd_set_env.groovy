

def call(Map options){
    try{
        dir ('WebUsdWebServer') {
            if (options.deployEnvironment.contains("test")) {
                filename = ".env.test.local"
            }else{
                filename = ".env.${options.deployEnvironment}.local"
            }
            switch(options.osName) {
                case 'Windows':
                    bat " "
                    break
                case 'Ubuntu20':
                    sh "cp $filename .env.production"
                    break
                default:
                    println "[WARNING] ${osName} is not supported"
            }

        }
    }catch(e){
        currentBuild.result = "FAILED"
        throw e
    } finally {
        archiveArtifacts "*.log"
    }
}