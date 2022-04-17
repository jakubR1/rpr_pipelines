
def call(Map options){
    images = [
        'live': false,
        'route': false,
        'storage': false,
        'stream': false,
        'web': false
    ]
    for (image in images){
        image_name = "$options.remoteHost:$options.remotePort/${image}.$options.deployEnvironment"
        res = sh(
            script: "docker images | grep $image_name",
            returnStdout: true,
            returnStatus: true
        )
        println res
        if (res == 1){
            images[image] = true
        }else{
            images[image] = false
        }
    }
    return images.findAll{it.value == true}.collect{key, value -> key}
}

