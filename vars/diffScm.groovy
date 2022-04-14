import hudson.FilePath

def call(){
    // def tree = [:].withDefault{ owner.call() }
    // TreeMap dependencies = tree
    // dependencies.WebUsdLiveServer.WebUsdAssetResolver = 'WebUsdAssetResolver/'
    // dependencies.WebUsdLiveServer.bbc = 'abc'
    // dependencies.WebUsdStreamServer.WebUsdAssetResolver = 'abc'
    // dependencies.WebUsdStreamServer.abc = 'abc'

    String changedFiles = sh (
        script: "git diff --dirstat=files,0 HEAD | sed 's/^[ 0-9.]+% //g'",
        returnStdout: true
    ).trim()
    println changedFiles



}
