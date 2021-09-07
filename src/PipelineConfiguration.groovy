/**
 * Class which provides a configuration of some pipeline
 */
public class PipelineConfiguration {

    List supportedOS
    Map productExtensions
    String artifactNameBeginning

    /**
     * Main constructor
     *
     * @param supportedOS list of supported OS
     * @param productExtensions map with extension of product for each OS
     * @param artifactNameBeginning beginning of the name of the artifact which is same in any case
     */
    PipelineConfiguration(Map params) {
        this.supportedOS = params["supportedOS"]
        this.productExtensions = params["productExtensions"]
        this.artifactNameBeginning = params["artifactNameBeginning"]
    }

}