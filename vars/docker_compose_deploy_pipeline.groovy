/**
* Pipeline to deploy services that has such deploy structure {repoRoot}/deploy/{type}/docker-compose.yml
* As default works with gitlabURL credentials if gitServerUrl is not null
*
* @param project Name of project on git ( {gitServerUrl}/{repoGroup}/{project}.git )
* @param repoGroup Part of url means folder when repo stores ( {gitServerUrl}/{repoGroup}/{project}.git ) (It could be null)
* @param branch git branch name
* @param nodeName Jenkins node label
* @param type deploy type (same as dir name in your project deploy dir {repoRoot}/deploy/{type})
* @param gitUrlCredentialsId Jenkins credentialsId with string Url of git host (http://git.example.com)
* @param gitUserCredentialsId Jenkins credentialsId with user access data for git host
* @param composePath custom docker-compose file path from project root (if set, type is ignored) (some-dir/another/docker-compose.yml)
*/
def call(
    String project,
    String repoGroup,
    String branch,
    String nodeName,
    String type = "custom",
    String gitUrlCredentialsId = "gitlabURL",
    String gitUserCredentialsId = "radeonprorender-gitlab",
    String composePath = "deploy/${type}/docker-compose.yml"
) {
    def gitServerUrl = withCredentials([string(credentialsId: gitUrlCredentialsId, variable: "GIT_URL")]) { GIT_URL }
    def jnPath = "${type}/${project}"
    def urlParts = [ gitServerUrl, repoGroup, "${project}.git" ]
    def repositoryUrl = urlParts.findAll { it != null }.join("/")
    println """
        [INFO] Project repository: ${repositoryUrl}
        [INFO] Project branch: ${branch}
        [INFO] Docker-compose file path: ${composePath}
    """
    node(nodeName) {
        dir(jnPath) {
            checkoutScm(branchName: branch, repositoryUrl: repositoryUrl, credentialsId: gitUserCredentialsId)
            stage('Build') {
                sh "sudo docker-compose -f ${composePath} build"
            }
            stage('Up') {
                sh "sudo docker-compose -f ${composePath} up -d"
            }
            stage("Cleanup") {
                sh """
                    docker volume prune -f
                    docker image prune -f
                """
            }
        }
    }
}
