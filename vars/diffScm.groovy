import hudson.FilePath

def call(){
    String changedProjects = sh (
        script: "git diff --dirstat=files,0 HEAD | sed 's/^[ 0-9.]+% //g'",
        returnStdout: true
    ).trim()
    println "[INFO] Changed projects:"
    println changedProjects
    return changedProjects



}
