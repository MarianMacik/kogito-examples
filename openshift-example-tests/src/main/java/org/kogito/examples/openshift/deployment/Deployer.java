/*
 * Copyright 2019 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kogito.examples.openshift.deployment;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import cz.xtf.builder.builders.BuildConfigBuilder;
import cz.xtf.builder.builders.ImageStreamBuilder;
import cz.xtf.core.http.Https;
import cz.xtf.core.openshift.OpenShiftBinary;
import cz.xtf.core.openshift.OpenShifts;
import cz.xtf.core.waiting.SimpleWaiter;
import io.fabric8.openshift.api.model.ImageSourceBuilder;
import io.fabric8.openshift.api.model.ImageSourcePath;
import io.fabric8.openshift.api.model.ImageStream;
import org.kogito.examples.openshift.Project;
import org.kogito.examples.openshift.TestConfig;

public class Deployer {

    /**
     * Deploy KaaS application into the project using S2I and wait until application starts.
     *
     * @param project Project where the application will be deployed to.
     * @param assetsUrl URL pointing to the GIT repo containing Kie assets.
     * @param s2iBuildImageTag Image tag pointing to image used to build KaaS application from source code.
     * @param s2iRuntimeImageTag Image tag pointing to image used as a base for KaaS runtime application.
     * @return Deployment object containing reference to the deployed application URL.
     */
    public static HttpDeployment deployKaasUsingS2iAndWait(Project project, URL assetsUrl, String s2iBuildImageTag, String s2iRuntimeImageTag) {
        return deployKaasUsingS2iAndWait(project, assetsUrl, null, s2iBuildImageTag, s2iRuntimeImageTag);
    }

    public static HttpDeployment deployKaasUsingS2iAndWait(Project project, URL assetsUrl, String gitContextDir, String s2iBuildImageTag, String s2iRuntimeImageTag) {
        return deployKaasUsingS2iAndWait(project, "kaas", assetsUrl, gitContextDir, s2iBuildImageTag, s2iRuntimeImageTag, new HashMap<>(), new HashMap<>());
    }

    /**
     * Deploy KaaS application into the project using S2I and wait until application starts.
     *
     * @param project Project where the application will be deployed to.
     * @param applicationName Name of the deployed application.
     * @param assetsUrl URL pointing to the GIT repo containing Kie assets.
     * @param gitContextDir Context directory of GIT repo containing Kie assets.
     * @param s2iBuildImageTag Image tag pointing to image used to build KaaS application from source code.
     * @param s2iRuntimeImageTag Image tag pointing to image used as a base for KaaS runtime application.
     * @param envVariables Environment variables for running application.
     * @param serviceLabels Service labels applied to deployed service.
     * @return Deployment object containing reference to the deployed application URL.
     */
    public static HttpDeployment deployKaasUsingS2iAndWait(Project project, String applicationName, URL assetsUrl, String gitContextDir, String s2iBuildImageTag, String s2iRuntimeImageTag, Map<String, String> envVariables, Map<String, String> serviceLabels) {
        OpenShiftBinary masterBinary = OpenShifts.masterBinary(project.getName());

        String s2iResultImageStreamName = buildKaasS2iApplication(project, applicationName, assetsUrl, gitContextDir, s2iBuildImageTag);
        String runtimeImageBuildName = buildKaasS2iRuntimeImage(project, applicationName, s2iResultImageStreamName, s2iRuntimeImageTag);

        List<String> newAppCommand = new ArrayList<>(Arrays.asList("new-app", runtimeImageBuildName + ":latest"));
        if (!envVariables.isEmpty()) {
            newAppCommand.add("-e");
            newAppCommand.add(getParameterKeyValueString(envVariables));
        }
        if (!serviceLabels.isEmpty()) {
            newAppCommand.add("-l");
            newAppCommand.add(getParameterKeyValueString(serviceLabels));
        }
        masterBinary.execute(newAppCommand.toArray(new String[0]));
        project.getMaster().waiters().areExactlyNPodsRunning(1, runtimeImageBuildName).timeout(TimeUnit.MINUTES, 1L).waitFor();

        masterBinary.execute("expose", "svc/" + runtimeImageBuildName);

        // Temporary implementation, service name is equal to runtimeImageBuildName
        HttpDeployment kaasDeployment = new HttpDeployment(project, runtimeImageBuildName);

        // Wait until route is available and working
        new SimpleWaiter(() -> Https.getCode(kaasDeployment.getRouteUrl().toExternalForm()) != 503).reason("Waiting for deployment route to connect to pod.").timeout(TimeUnit.SECONDS, 30L).waitFor();

        return kaasDeployment;
    }

    /**
     * Build the KaaS application image and push it to an image stream.
     *
     * @param project
     * @param applicationName Name of the application to be built.
     * @param assetsUrl URL pointing to the GIT repo containing Kie assets.
     * @param gitContextDir Context directory of GIT repo containing Kie assets.
     * @param s2iBuildImageTag Image tag pointing to image used to build KaaS application from source code.
     * @return Name of the image stream containing application image.
     */
    private static String buildKaasS2iApplication(Project project, String applicationName, URL assetsUrl, String gitContextDir, String s2iBuildImageTag) {
        String s2iImageStreamName = applicationName + "-builder-s2i-image";
        String s2iImageStreamTag = "1.0";
        String buildName = applicationName + "-s2i-build";
        String resultImageStreamName = applicationName + "-s2i";

        createInsecureImageStream(project, s2iImageStreamName, s2iImageStreamTag, s2iBuildImageTag);
        createEmptyImageStream(project, resultImageStreamName);

        BuildConfigBuilder s2iConfigBuilder = new BuildConfigBuilder(buildName);
        s2iConfigBuilder.setOutput(resultImageStreamName);
        s2iConfigBuilder.gitSource(assetsUrl.toExternalForm());
        s2iConfigBuilder.sti().fromImageStream(project.getName(), s2iImageStreamName, s2iImageStreamTag);
        s2iConfigBuilder.addMemoryResource().setRequests("6Gi").setLimits("6Gi");
        s2iConfigBuilder.addCPUResource().setRequests("2").setLimits("2");

        if (gitContextDir != null && !gitContextDir.isEmpty()) {
            s2iConfigBuilder.gitContextDir(gitContextDir);
        }

        TestConfig.getMavenMirrorUrl().ifPresent(mavenMirrorUrl -> s2iConfigBuilder.sti().addEnvVariable("MAVEN_MIRROR_URL", mavenMirrorUrl));

        project.getMaster().createBuildConfig(s2iConfigBuilder.build());
        project.getMaster().startBuild(buildName);
        project.getMaster().waiters().hasBuildCompleted(buildName).timeout(TimeUnit.MINUTES, 20L).waitFor();
        return resultImageStreamName;
    }

    /**
     * Build runtime image of the KaaS application and push it to an image stream.
     *
     * @param project
     * @param applicationName Name of the application to be built.
     * @param s2iResultImageStreamName Image stream name containing KaaS application created by S2I build.
     * @param s2iRuntimeImageTag Image tag pointing to image used as a base for KaaS runtime application.
     * @return Name of the image stream containing runtime image.
     */
    private static String buildKaasS2iRuntimeImage(Project project, String applicationName, String s2iResultImageStreamName, String s2iRuntimeImageTag) {
        String finalImageStreamName = applicationName + "-builder-image";
        String finalImageStreamTag = "1.0";
        String buildName = applicationName + "-runtime-build";
        String resultImageStreamName = applicationName;

        createInsecureImageStream(project, finalImageStreamName, finalImageStreamTag, s2iRuntimeImageTag);
        createEmptyImageStream(project, resultImageStreamName);

        // XTF has a bug, cannot create the build without GIT or binary source. Using Fabric8 to create the build.
        io.fabric8.openshift.api.model.ImageSource imageSource = new ImageSourceBuilder().withNewFrom()
                                                                                             .withKind("ImageStreamTag")
                                                                                             .withName(s2iResultImageStreamName + ":latest")
                                                                                             .withNamespace(project.getName())
                                                                                         .endFrom()
                                                                                         .withPaths(new ImageSourcePath(".", "/home/kogito/bin"))
                                                                                         .build();

        io.fabric8.openshift.api.model.BuildConfig runtimeConfig = new io.fabric8.openshift.api.model.BuildConfigBuilder().withNewMetadata()
                                                                                                                              .withName(buildName)
                                                                                                                          .endMetadata()
                                                                                                                          .withNewSpec()
                                                                                                                              .withNewOutput()
                                                                                                                                  .withNewTo()
                                                                                                                                      .withKind("ImageStreamTag")
                                                                                                                                      .withName(resultImageStreamName + ":latest")
                                                                                                                                  .endTo()
                                                                                                                              .endOutput()
                                                                                                                              .withNewSource()
                                                                                                                                  .withType("Image")
                                                                                                                                  .withImages(imageSource)
                                                                                                                              .endSource()
                                                                                                                              .withNewStrategy()
                                                                                                                                  .withType("Source")
                                                                                                                                  .withNewSourceStrategy()
                                                                                                                                      .withNewFrom()
                                                                                                                                          .withKind("ImageStreamTag")
                                                                                                                                          .withName(finalImageStreamName + ":" + finalImageStreamTag)
                                                                                                                                          .withNamespace(project.getName())
                                                                                                                                      .endFrom()
                                                                                                                                  .endSourceStrategy()
                                                                                                                              .endStrategy()
                                                                                                                          .endSpec()
                                                                                                                          .build();

        project.getMaster().createBuildConfig(runtimeConfig);
        project.getMaster().startBuild(buildName);
        project.getMaster().waiters().hasBuildCompleted(buildName).timeout(TimeUnit.MINUTES, 5L).waitFor();
        return resultImageStreamName;
    }

    // Helper methods

    /**
     * Create image stream pointing to external image.
     *
     * @param project
     * @param name Image stream name
     * @param tag Image stream tag
     * @param externalImage External image URL.
     */
    private static void createInsecureImageStream(Project project, String name, String tag, String externalImage) {
        ImageStream s2iImageStream = new ImageStreamBuilder(name).addTag(tag, externalImage, true).build();
        project.getMaster().createImageStream(s2iImageStream);
        new SimpleWaiter(() -> isImageStreamTagAvailable(project, name, tag)).reason("Waiting for image to be loaded by OpenShift.").timeout(TimeUnit.MINUTES, 5).waitFor();
    }

    private static boolean isImageStreamTagAvailable(Project project, String imageStreamName, String imageStreamTag) {
        return project.getMaster().getImageStream(imageStreamName).getStatus().getTags().stream()
                                                                                        .anyMatch(s -> s.getTag().equals(imageStreamTag));
    }

    /**
     * Create empty image stream. Can be used as a target image stream for S2I build.
     *
     * @param project
     * @param name Image stream name
     */
    private static void createEmptyImageStream(Project project, String name) {
        ImageStream imageStream = new ImageStreamBuilder(name).build();
        project.getMaster().createImageStream(imageStream);
    }

    /**
     * Returns String containing parameters prepared for usage with OC client, in for of parameter1=value1,parameter=value2...
     *
     * @param parameters
     * @return
     */
    private static String getParameterKeyValueString(Map<String, String> parameters) {
        return parameters.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
    }
}
