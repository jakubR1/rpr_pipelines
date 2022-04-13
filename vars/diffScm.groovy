import hudson.FilePath

def call(){
    def tree = { [:].withDefault{ owner.call() } }
    println 'Hello from test script'
    // def folder = System.properties['user.dir']
    // def baseDir = new File(folder);
    // files = baseDir.listFiles();
    // println files
    sh "git submodule foreach --quiet 'echo \$name'"



}
