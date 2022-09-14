package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.eclipse.microprofile.config.ConfigProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//https://kubernetes.io/docs/tasks/inject-data-application/downward-api-volume-expose-pod-information/
//https://kubernetes.io/docs/tasks/inject-data-application/downward-api-volume-expose-pod-information/#capabilities-of-the-downward-api
public class KubernetesApi implements Processor {


  String nodePort;
  String nodeName;
  String hostIp;
  String podName;
  String podNameSpace;
  String podServiceAccount;

  Logger log = LoggerFactory.getLogger(this.getClass());

  String environment = "not set";

  /**
   * Get the pod information from the container and associate the kuberneties properties in the route.
   */
  public KubernetesApi() {

    nodePort = ConfigProvider.getConfig().getOptionalValue("LISTEN_PORT", String.class).orElse("");
    nodeName = ConfigProvider.getConfig().getOptionalValue("NODE_NAME", String.class).orElse("");
    hostIp = ConfigProvider.getConfig().getOptionalValue("HOST_IP", String.class).orElse("");
    podName = ConfigProvider.getConfig().getOptionalValue("POD_NAME", String.class).orElse("");
    podNameSpace = ConfigProvider.getConfig().getOptionalValue("POD_NAMESPACE", String.class).orElse("");
    podServiceAccount = ConfigProvider.getConfig().getOptionalValue("POD_SERVICE_ACCOUNT", String.class).orElse("");

    log.debug("POD_NAME={}", podName);
    log.debug("POD_NAMESPACE={}", podNameSpace);
    log.debug("POD_SERVICE_ACCOUNT={}", podServiceAccount);
    log.debug("NODE_NAME={}", nodeName);
    log.debug("HOST_IP={}", hostIp);
    log.debug("nodeport={}", nodePort);

    if (nodeName == null || podNameSpace == null) {
      log.error(
          "Please set the Downward API variables : https://issinterfacewiki.mednet.ucla.edu/doku.php?id=fuse:paul:openshift:steps_to_build_to_gh \nand https://kubernetes.io/docs/tasks/inject-data-application/environment-variable-expose-pod-information");
    } else {

      //  containerName = nodeName;
      if (podNameSpace.length() > 3) {
        try {
          if (podNameSpace.startsWith("prod-")) {
            environment = "P";
          } else if (podNameSpace.startsWith("stage-")) {
            environment = "S";
          } else if (podNameSpace.startsWith("dev-")) {
            environment = "D";
          } else if (podNameSpace.startsWith("test-")) {
            environment = "T";
          } else if (podNameSpace.startsWith("qa-")) {
            environment = "T";
          }
        } catch (Exception ex) {
          ex.printStackTrace();
          environment = "D";
        }
      } else {
        environment = "D";
      }
    }
    log.debug("environment={}", environment);
  }

  // Set headers when called.
  @Override
  public void process(Exchange exchange) throws Exception {

    setHeader(exchange,"nodeport", nodePort);
    setHeader(exchange,"mllpPort", nodePort);

    setHeader(exchange,"nodeName", nodeName);
    setHeader(exchange,"datacenter", nodeName);
    setHeader(exchange,"host", nodeName);

    setHeader(exchange,"environment", environment);
    setHeader(exchange,"hostIp", hostIp);
    setHeader(exchange,"podName", podName);

    setHeader(exchange,"podNameSpace", podNameSpace);
    setHeader(exchange,"container", podNameSpace);

    setHeader(exchange,"podServiceAccount", podServiceAccount);

    log.debug("nodeport={}", exchange.getMessage().getHeader("nodeport", String.class));
    log.debug("mllpPort={}", exchange.getMessage().getHeader("mllpPort", String.class));
    log.debug("nodeName={}", exchange.getMessage().getHeader("nodeName", String.class));
    log.debug("environment={}", exchange.getMessage().getHeader("environment", String.class));
    log.debug("podName={}", exchange.getMessage().getHeader("podName", String.class));
    log.debug("podNameSpace={}", exchange.getMessage().getHeader("podNameSpace", String.class));

  }

  /**
   * Only set headers that have a value.
   * @param exchange exchange to add header to.
   * @param key first part of value to add to header.
   * @param value second part of pair to add to the header.
   */
  private void setHeader(Exchange exchange, String key, String value) {
    if (value != null && !value.isEmpty()) {
      log.debug("setting {} = {}", key,value);
      exchange.getMessage().setHeader(key, value);
    }
  }
}
