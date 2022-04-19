import hudson.FilePath

def call(Map options){
    sh (
        script: "git fetch",
        returnStdout: false
    )
    changedProjects = sh (
        script: "git diff --name-only --right-only HEAD...$options.projectBranch | cut -d/ -f 1-1 | sort | uniq",
        returnStdout: true
    ).split("\n")
    println "[INFO] Changed projects:"
    println changedProjects
    return changedProjects



}
