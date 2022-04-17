
def call(Map options){
    containersName = ['live', 'route', 'storage', 'stream', 'web']
    containersToBuild = []
    for (name in containersName){
        cName = "$options.remoteHost:5000/${name}.$options.deployEnvironment"
        res = sh(
            script: "docker images | grep $cName",
            returnStdout: true,
            returnStatus: true
        )
        println res
        if (res == 1){
            return false
        }
    }
    return true
}

