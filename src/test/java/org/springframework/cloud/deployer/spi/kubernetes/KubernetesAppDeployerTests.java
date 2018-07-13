/*
 * Copyright 2015-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.deployer.spi.kubernetes;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import org.junit.Test;
import org.springframework.boot.bind.YamlConfigurationFactory;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.Assert.assertNotNull;

/**
 * Unit tests for {@link KubernetesAppDeployer}
 *
 * @author Donovan Muller
 * @author David Turanski
 * @author Ilayaperumal Gopinathan
 * @author Chris Schaefer
 */
public class KubernetesAppDeployerTests {

	private KubernetesAppDeployer deployer;

	@Test
	public void deployWithVolumesOnly() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(),
			new HashMap<>());

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getVolumes()).isEmpty();
	}

	@Test
	public void deployWithVolumesAndVolumeMounts() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.volumeMounts", "[" + "{name: 'testpvc', mountPath: '/test/pvc'}, "
			+ "{name: 'testnfs', mountPath: '/test/nfs', readOnly: 'true'}" + "]");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getVolumes()).containsOnly(
			// volume 'testhostpath' defined in dataflow-server.yml should not be added
			// as there is no corresponding volume mount
			new VolumeBuilder().withName("testpvc").withNewPersistentVolumeClaim("testClaim", true).build(),
			new VolumeBuilder().withName("testnfs").withNewNfs("/test/nfs", null, "10.0.0.1:111").build());

		props.clear();
		props.put("spring.cloud.deployer.kubernetes.volumes",
			"[" + "{name: testhostpath, hostPath: { path: '/test/override/hostPath' }},"
				+ "{name: 'testnfs', nfs: { server: '192.168.1.1:111', path: '/test/override/nfs' }} " + "]");
		props.put("spring.cloud.deployer.kubernetes.volumeMounts",
			"[" + "{name: 'testhostpath', mountPath: '/test/hostPath'}, "
				+ "{name: 'testpvc', mountPath: '/test/pvc'}, "
				+ "{name: 'testnfs', mountPath: '/test/nfs', readOnly: 'true'}" + "]");
		appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getVolumes()).containsOnly(
			new VolumeBuilder().withName("testhostpath").withNewHostPath("/test/override/hostPath").build(),
			new VolumeBuilder().withName("testpvc").withNewPersistentVolumeClaim("testClaim", true).build(),
			new VolumeBuilder().withName("testnfs").withNewNfs("/test/override/nfs", null, "192.168.1.1:111").build());
	}

	@Test
	public void deployWithNodeSelector() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.deployment.nodeSelector", "disktype:ssd, os: linux");
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getNodeSelector()).containsOnly(entry("disktype", "ssd"), entry("os", "linux"));
	}

	@Test
	public void deployWithEnvironmentWithCommaDelimitedValue() throws Exception {
		AppDefinition definition = new AppDefinition("app-test", null);
		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.environmentVariables",
			"foo='bar,baz',car=caz,boo='zoo,gnu',doo=dar");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(bindDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getContainers().get(0).getEnv())
			.contains(
				new EnvVar("foo", "bar,baz", null),
				new EnvVar("car", "caz", null),
				new EnvVar("boo", "zoo,gnu", null),
				new EnvVar("doo", "dar", null));
	}

	@Test
	public void deployWithImagePullSecretDeploymentProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.imagePullSecret", "regcred");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getImagePullSecrets().size()).isEqualTo(1);
		assertThat(podSpec.getImagePullSecrets().get(0).getName()).isEqualTo("regcred");
	}

	@Test
	public void deployWithImagePullSecretDeployerProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setImagePullSecret("regcred");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec("1", appDeploymentRequest, 8080, false);

		assertThat(podSpec.getImagePullSecrets().size()).isEqualTo(1);
		assertThat(podSpec.getImagePullSecrets().get(0).getName()).isEqualTo("regcred");
	}

	@Test
	public void deployWithDeploymentServiceAccountNameDeploymentProperties() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.deploymentServiceAccountName", "myserviceaccount");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), null);
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("myserviceaccount"));
	}

	@Test
	public void deployWithDeploymentServiceAccountNameDeployerProperty() {
		AppDefinition definition = new AppDefinition("app-test", null);

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), null);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setDeploymentServiceAccountName("myserviceaccount");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("myserviceaccount"));
	}

	@Test
	public void deployWithDeploymentServiceAccountNameDeploymentPropertyOverride() {
		AppDefinition definition = new AppDefinition("app-test", null);

		Map<String, String> props = new HashMap<>();
		props.put("spring.cloud.deployer.kubernetes.deploymentServiceAccountName", "overridesan");

		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, getResource(), props);

		KubernetesDeployerProperties kubernetesDeployerProperties = new KubernetesDeployerProperties();
		kubernetesDeployerProperties.setDeploymentServiceAccountName("defaultsan");

		deployer = new KubernetesAppDeployer(kubernetesDeployerProperties, null);
		PodSpec podSpec = deployer.createPodSpec("app-test", appDeploymentRequest, null, false);

		assertNotNull(podSpec.getServiceAccountName());
		assertThat(podSpec.getServiceAccountName().equals("overridesan"));
	}

	private Resource getResource() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private KubernetesDeployerProperties bindDeployerProperties() throws Exception {
		YamlConfigurationFactory<KubernetesDeployerProperties> yamlConfigurationFactory = new YamlConfigurationFactory<>(
			KubernetesDeployerProperties.class);
		yamlConfigurationFactory.setResource(new ClassPathResource("dataflow-server.yml"));
		yamlConfigurationFactory.afterPropertiesSet();
		return yamlConfigurationFactory.getObject();
	}
}
