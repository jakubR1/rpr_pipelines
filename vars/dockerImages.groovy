
def call(Map options){
    images = [
        'live': true,
        'route': true,
        'storage': true,
        'stream': true,
        'web': true
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
            images[k] = false
        }
    }
    // return images.findAll{it.value == true}.collect{key, value -> key}
    return images
}

