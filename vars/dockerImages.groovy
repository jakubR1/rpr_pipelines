
def call(Map options){
    containersName = ['live', 'route', 'storage', 'stream', 'web']
    containersToBuild = []
    for (name in containersName){
        cName = "$options.remoteHost/${name}.$options.deployEnvironment"
        res = sh(
            script: "docker images | grep $cName",
            returnStdout: true
        )
        if (res.trim()){
            return false
        }
    }
    return true
}

