
def call(Map options){
    images = [
        'live': false,
        'route': false,
        'storage': false,
        'stream': false,
        'web': false
    ]
    images.each{k, v ->
        image_name = "$options.remoteHost:$options.remotePort/${k}.$options.deployEnvironment"
        res = sh(
            script: "docker images | grep $image_name",
            returnStdout: true,
            returnStatus: true
        )
        println res
        if (res == 1){
            images[k] = true
        }
    }
    return images.findAll{it.value == true}.collect{key, value -> key}
}

