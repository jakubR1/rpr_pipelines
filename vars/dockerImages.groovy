
def call(Map options){
    containersName = ['live', 'route', 'storage', 'stream', 'web']
    containersToBuild = []
    for (name in containersName){
        cName = "$options.remoteHost:5000/${name}.$options.deployEnvironment"
        res = sh(
            script: "docker images | grep $cName",
            returnStdout: true
        ).trim()
        println res
        if (res){
            return false
        }
    }
    return true
}

