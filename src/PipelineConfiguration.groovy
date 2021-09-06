/**
 * Class which provides a configuration of some pipeline
 */
public class PipelineConfiguration {

    List supportedOS
    Map productExtensions

    /**
     * Main constructor
     *
     * @param supportedOS list of supported OS
     * @param productExtensions map with extension of product for each OS
     */
    PipelineConfiguration(Map params) {
        this.supportedOS = params["supportedOS"]
        this.productExtensions = params["productExtensions"]
    }

}