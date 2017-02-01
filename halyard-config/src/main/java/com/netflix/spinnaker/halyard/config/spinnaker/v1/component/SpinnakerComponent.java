/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.halyard.config.spinnaker.v1.component;

import com.amazonaws.util.IOUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.halyard.config.config.v1.HalconfigParser;
import com.netflix.spinnaker.halyard.config.errors.v1.HalconfigException;
import com.netflix.spinnaker.halyard.config.errors.v1.config.IllegalConfigException;
import com.netflix.spinnaker.halyard.config.model.v1.node.DeploymentConfiguration;
import com.netflix.spinnaker.halyard.config.model.v1.node.Halconfig;
import com.netflix.spinnaker.halyard.config.model.v1.node.Node;
import com.netflix.spinnaker.halyard.config.model.v1.node.NodeFilter;
import com.netflix.spinnaker.halyard.config.model.v1.problem.Problem;
import com.netflix.spinnaker.halyard.config.model.v1.problem.ProblemBuilder;
import com.netflix.spinnaker.halyard.config.services.v1.DeploymentService;
import com.netflix.spinnaker.halyard.config.spinnaker.v1.BillOfMaterials;
import com.netflix.spinnaker.halyard.config.spinnaker.v1.SpinnakerEndpoints;
import com.netflix.spinnaker.halyard.config.spinnaker.v1.profileRegistry.ComponentProfileRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.yaml.snakeyaml.Yaml;
import retrofit.RetrofitError;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A Component is a Spinnaker service whose config is to be generated by Halyard.
 *
 * For example: Clouddriver is a component, and Haylard will generate clouddriver.yml
 */
abstract public class SpinnakerComponent {
  @Autowired
  HalconfigParser parser;

  @Autowired
  Yaml yamlParser;

  @Autowired
  ObjectMapper objectMapper;

  @Autowired
  DeploymentService deploymentService;

  @Autowired
  ComponentProfileRegistry componentProfileRegistry;

  final String EDIT_WARNING =
      commentPrefix() + "WARNING\n" +
      commentPrefix() + "This file was autogenerated, and _will_ be overwritten by Halyard.\n" +
      commentPrefix() + "Any edits you make here _will_ be lost.\n";

  protected abstract String commentPrefix();

  public abstract String getComponentName();

  public ComponentConfig getFullConfig(NodeFilter filter, SpinnakerEndpoints endpoints) {
    DeploymentConfiguration deploymentConfiguration = deploymentService.getDeploymentConfiguration(filter);
    ComponentConfig result = generateFullConfig(
        getBaseConfig(deploymentConfiguration),
        deploymentConfiguration,
        endpoints);
    result.setConfigContents(EDIT_WARNING + result.getConfigContents());
    return result;
  }

  public abstract String getConfigFileName();

  /**
   * Overwrite this for components that need to specialize their config.
   *
   * @param baseConfig the base halconfig returned from the config storage.
   * @param deploymentConfiguration the deployment configuration being translated into Spinnaker config.
   * @return the fully written configuration.
   */
  protected ComponentConfig generateFullConfig(String baseConfig, DeploymentConfiguration deploymentConfiguration, SpinnakerEndpoints endpoints) {
    return new ComponentConfig().setConfigContents(baseConfig);
  }

  /**
   * @return the base config (typically found in a component's ./halconfig/ directory) for
   * the version of the component specified by the Spinnaker version in the loaded halconfig.
   */
  private String getBaseConfig(DeploymentConfiguration deploymentConfiguration) {
    String componentName = getComponentName();
    String configFileName = getConfigFileName();
    try {
      String componentVersion = getVersion(deploymentConfiguration);
      String componentObjectName = String.join("/", componentName, componentVersion, configFileName);

      return IOUtils.toString(componentProfileRegistry.getObjectContents(componentObjectName));
    } catch (RetrofitError | IOException e) {
      throw new HalconfigException(
          new ProblemBuilder(Problem.Severity.FATAL,
              "Unable to retrieve a profile for \"" + componentName + "\": " + e.getMessage())
              .build()
      );
    }
  }

  public String getVersion(DeploymentConfiguration deploymentConfiguration) {
    Halconfig currentConfig = parser.getHalconfig(true);
    String version = deploymentConfiguration.getVersion();
    if (version == null || version.isEmpty()) {
      throw new IllegalConfigException(
          new ProblemBuilder(Problem.Severity.FATAL,
              "In order to load a Spinnaker Component's profile, you must specify a version of Spinnaker in your halconfig.")
              .build()
      );
    }

    String componentName = getComponentName();
    try {
      String bomName = "bom/" + version + ".yml";

      BillOfMaterials bom = objectMapper.convertValue(
          yamlParser.load(componentProfileRegistry.getObjectContents(bomName)),
          BillOfMaterials.class
      );

      return bom.getServices().getComponentVersion(componentName);
    } catch (RetrofitError | IOException e) {
      throw new HalconfigException(
          new ProblemBuilder(Problem.Severity.FATAL,
              "Unable to retrieve a profile for \"" + componentName + "\": " + e.getMessage())
              .build()
      );
    }
  }

  /**
   * @return a list of patterns to match profiles required by this component.
   */
  abstract protected List<Pattern> profilePatterns();

  public List<String> profilePaths(File[] allProfiles) {
    List<Pattern> patterns = profilePatterns();
    return Arrays.stream(allProfiles)
        .filter(f -> patterns
            .stream()
            .filter(p -> p.matcher(f.getName()).find()).count() > 0)
        .map(File::getAbsolutePath)
        .collect(Collectors.toList());
  }

  List<String> nodeFiles(Node node) {
    List<String> files = new ArrayList<>();

    Consumer<Node> fileFinder = n -> files.addAll(n.localFiles().stream().map(f -> {
      try {
        f.setAccessible(true);
        return (String) f.get(n);
      } catch (IllegalAccessException e) {
        throw new RuntimeException("Failed to get local files for node " + n.getNodeName(), e);
      } finally {
        f.setAccessible(false);
      }
    }).filter(Objects::nonNull).collect(Collectors.toList()));
    node.recursiveConsume(fileFinder);

    return files;
  }
}