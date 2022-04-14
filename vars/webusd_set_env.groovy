

def call(Map options){
    try{
        dir ('WebUsdWebServer') {
            if (options.deployEnvironment.contains("test")) {
                filename = "/home/user/JN/.envs/webusd.env.test"
            }else{
                filename = "/home/user/JN/.envs/webusd.env.${options.deployEnvironment}"
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