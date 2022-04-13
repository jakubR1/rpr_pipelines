import hudson.FilePath

def call(){
    def tree = { [:].withDefault{ owner.call() } }
    dependencies = tree()
    dependencies.WebUsdLiveServer.WebUsdAssetResolver = 'WebUsdAssetResolver/'
    dependencies.WebUsdLiveServer.bbc = 'abc'
    dependencies.WebUsdStreamServer.WebUsdAssetResolver = 'abc'
    dependencies.WebUsdStreamServer.abc = 'abc'

    String changedFiles = sh (
        script: "git diff --dirstat=files,0 HEAD~1 | sed -E 's/^[ 0-9.]+% //g' | sed -E 's/\/.*$//g'",
        returnStdout: true
    ).trim()
    println changedFiles



}
