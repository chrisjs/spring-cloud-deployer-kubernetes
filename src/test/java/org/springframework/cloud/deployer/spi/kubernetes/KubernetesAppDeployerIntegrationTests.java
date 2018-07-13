/*
 * Copyright 2016-2018 the original author or authors.
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

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HostPathVolumeSource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpec;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceAccount;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.StorageClass;
import io.fabric8.kubernetes.api.model.StorageClassBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.extensions.StatefulSet;
import io.fabric8.kubernetes.api.model.extensions.StatefulSetSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.assertj.core.api.Assertions;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.junit.ClassRule;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.spi.app.AppDeployer;
import org.springframework.cloud.deployer.spi.app.AppStatus;
import org.springframework.cloud.deployer.spi.core.AppDefinition;
import org.springframework.cloud.deployer.spi.core.AppDeploymentRequest;
import org.springframework.cloud.deployer.spi.test.AbstractAppDeployerIntegrationTests;
import org.springframework.cloud.deployer.spi.test.Timeout;
import org.springframework.core.io.Resource;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.entry;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.deployed;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.failed;
import static org.springframework.cloud.deployer.spi.app.DeploymentState.unknown;
import static org.springframework.cloud.deployer.spi.kubernetes.AbstractKubernetesDeployer.SPRING_APP_KEY;
import static org.springframework.cloud.deployer.spi.test.EventuallyMatcher.eventually;

/**
 * Integration tests for {@link KubernetesAppDeployer}.
 *
 * @author Thomas Risberg
 * @author Donovan Muller
 * @Author David Turanski
 * @author Chris Schaefer
 */
@SpringBootTest(classes = {KubernetesAutoConfiguration.class}, properties = {
	"logging.level.org.springframework.cloud.deployer.spi=INFO"
})
public class KubernetesAppDeployerIntegrationTests extends AbstractAppDeployerIntegrationTests {

	@ClassRule
	public static KubernetesTestSupport kubernetesAvailable = new KubernetesTestSupport();

	@Autowired
	private AppDeployer appDeployer;

	@Autowired
	private KubernetesClient kubernetesClient;

	@Autowired
	private KubernetesDeployerProperties originalProperties;

	@Override
	protected AppDeployer provideAppDeployer() {
		return appDeployer;
	}

	@Test
	public void testFailedDeploymentWithLoadBalancer() {
		log.info("Testing {}...", "FailedDeploymentWithLoadBalancer");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setLivenessProbePeriod(10);
		deployProperties.setMaxTerminatedErrorRestarts(1);
		deployProperties.setMaxCrashLoopBackOffRestarts(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		Map<String, String> props = new HashMap<>();
		// setting to small memory value will cause app to fail to be deployed
		props.put("spring.cloud.deployer.kubernetes.memory", "8Mi");
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(failed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testGoodDeploymentWithLoadBalancer() {
		log.info("Testing {}...", "GoodDeploymentWithLoadBalancer");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setMinutesToWaitForLoadBalancer(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentWithLoadBalancerHasUrlAndAnnotation() {
		log.info("Testing {}...", "DeploymentWithLoadBalancerShowsUrl");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setMinutesToWaitForLoadBalancer(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.serviceAnnotations","foo:bar,fab:baz"));

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Checking instance attributes of {}...", request.getDefinition().getName());
		AppStatus status = lbAppDeployer.status(deploymentId);
		for (String inst : status.getInstances().keySet()) {
			assertThat(deploymentId, eventually(hasInstanceAttribute(Matchers.hasKey("url"), lbAppDeployer, inst),
					timeout.maxAttempts, timeout.pause));
		}
		log.info("Checking service annotations of {}...", request.getDefinition().getName());
		Map<String, String> annotations = kubernetesClient.services().withName(request.getDefinition().getName()).get()
				.getMetadata().getAnnotations();
		assertThat(annotations, is(notNullValue()));
		assertThat(annotations.size(), is(2));
		assertTrue(annotations.containsKey("foo"));
		assertThat(annotations.get("foo"), is("bar"));
		assertTrue(annotations.containsKey("fab"));
		assertThat(annotations.get("fab"), is("baz"));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentWithPodAnnotation() {
		log.info("Testing {}...", "DeploymentWithPodAnnotation");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource,
				Collections.singletonMap("spring.cloud.deployer.kubernetes.podAnnotations",
						"iam.amazonaws.com/role:role-arn,foo:bar"));

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = appDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Checking pod spec annotations of {}...", request.getDefinition().getName());

		List<Pod> pods = kubernetesClient.pods().withLabel("spring-deployment-id", request.getDefinition()
				.getName()).list().getItems();

		assertThat(pods, is(notNullValue()));
		assertThat(pods.size(), is(1));

		Pod pod = pods.get(0);
		Map<String, String> annotations = pod.getMetadata().getAnnotations();
		log.info("Number of annotations found" + annotations.size());
		for (Map.Entry<String, String> annotationsEntry: annotations.entrySet()) {
			log.info("Annotation key: " + annotationsEntry.getKey());
		}
		assertTrue(annotations.containsKey("iam.amazonaws.com/role"));
		assertTrue(annotations.get("iam.amazonaws.com/role").equals("role-arn"));
		assertTrue(annotations.containsKey("foo"));
		assertTrue(annotations.get("foo").equals("bar"));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentWithMountedHostPathVolume() throws IOException {
		log.info("Testing {}...", "DeploymentWithMountedVolume");
		String hostPath = "/tmp/" + randomName() + '/';
		String containerPath = "/tmp/";
		String subPath = randomName();
		String mountName = "mount";
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setVolumes(Collections.singletonList(new VolumeBuilder()
				.withHostPath(new HostPathVolumeSource(hostPath))
				.withName(mountName)
				.build()));
		deployProperties.setVolumeMounts(Collections.singletonList(new VolumeMount(hostPath, mountName, false, null)));
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer lbAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient, containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), Collections.singletonMap("logging.file", containerPath + subPath));
		Resource resource = testApplication();
		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = lbAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);
		PodSpec spec = kubernetesClient.pods().withLabels(selector).list().getItems().get(0).getSpec();
		assertThat(spec.getVolumes(), is(notNullValue()));
		Volume volume = spec.getVolumes().stream()
				.filter(v -> mountName.equals(v.getName()))
				.findAny()
				.orElseThrow(() -> new AssertionError("Volume not mounted"));
		assertThat(volume.getHostPath(), is(notNullValue()));
		assertThat(hostPath, is(volume.getHostPath().getPath()));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		lbAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}


	private void verifyAppEnv(String appId) {
		String ip="";
		int port = 0;

		KubernetesDeployerProperties properties = new KubernetesDeployerProperties();
		boolean success = false;

		Service svc = kubernetesClient.services().withName(appId).get();

		if (svc != null && "LoadBalancer".equals(svc.getSpec().getType())) {
			int tries = 0;
			int maxWait = properties.getMinutesToWaitForLoadBalancer() * 6; // we check 6 times per minute
			while (tries++ < maxWait && !success) {
				if (svc.getStatus() != null && svc.getStatus().getLoadBalancer() != null &&
					svc.getStatus().getLoadBalancer().getIngress() != null &&
					!(svc.getStatus().getLoadBalancer().getIngress().isEmpty())) {
					ip = svc.getStatus().getLoadBalancer().getIngress().get(0).getIp();
					port = svc.getSpec().getPorts().get(0).getPort();
					success = true;
				}
				else {
					try {
						Thread.sleep(5000L);
					}
					catch (InterruptedException e) {
					}
					svc = kubernetesClient.services().withName(appId).get();
				}
			}
			log.debug(String.format("LoadBalancer Ingress: %s",
				svc.getStatus().getLoadBalancer().getIngress().toString()));
		}

		if (!success) {
			fail("cannot get service information for " + appId);
		}



		String url = String.format("http://%s:%d/env",ip,port);

		log.debug("getting app environment from " + url);
		RestTemplate restTemplate = new RestTemplate();
		HashMap<String,Object> env = restTemplate.getForObject(url, HashMap.class);

		Map<String,String> systemEnvironment = (Map)env.get("systemEnvironment");
		Map<String,String> applicationConfig = (Map)env.get(
			"applicationConfig: [file:./config/application.properties]");

		String hostName = systemEnvironment.get("HOSTNAME");
		String expectedIndex =hostName.substring(hostName.lastIndexOf("-")+1);
		String actualIndex = applicationConfig.get("INSTANCE_INDEX");
		assertEquals(actualIndex,expectedIndex);
	}

	@Test
	public void testDeploymentWithGroupAndIndex() throws IOException {
		log.info("Testing {}...", "DeploymentWithWithGroupAndIndex");
		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setCreateLoadBalancer(true);
		deployProperties.setMinutesToWaitForLoadBalancer(1);
		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer testAppDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient, containerFactory);

		Map<String,String> appProperties = new  HashMap<>();
		appProperties.put("security.basic.enabled","false");
		appProperties.put("management.security.enabled","false");
		AppDefinition definition = new AppDefinition(randomName(), appProperties);
		Resource resource = testApplication();
		Map<String, String> props = new HashMap<>();
		props.put(AppDeployer.GROUP_PROPERTY_KEY, "foo");
		props.put(AppDeployer.INDEXED_PROPERTY_KEY, "true");

		AppDeploymentRequest request = new AppDeploymentRequest(definition, resource, props);

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = testAppDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);
		PodSpec spec = kubernetesClient.pods().withLabels(selector).list().getItems().get(0).getSpec();

		Map<String, String> envVars = new HashMap<>();
		for (EnvVar e : spec.getContainers().get(0).getEnv()) {
			envVars.put(e.getName(), e.getValue());
		}
		assertThat(envVars.get("SPRING_CLOUD_APPLICATION_GROUP"), is("foo"));
		verifyAppEnv(deploymentId);

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		testAppDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void testDeploymentServiceAccountName() {
		log.info("Testing {}...", "DeploymentServiceAccountName");

		ServiceAccount deploymentServiceAccount =
				new ServiceAccountBuilder().withNewMetadata().withName("appsa").endMetadata().build();

		kubernetesClient.serviceAccounts().create(deploymentServiceAccount);

		String serviceAccountName = deploymentServiceAccount.getMetadata().getName();

		KubernetesDeployerProperties deployProperties = new KubernetesDeployerProperties();
		deployProperties.setDeploymentServiceAccountName(serviceAccountName);

		ContainerFactory containerFactory = new DefaultContainerFactory(deployProperties);
		KubernetesAppDeployer appDeployer = new KubernetesAppDeployer(deployProperties, kubernetesClient,
				containerFactory);

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest request = new AppDeploymentRequest(definition, testApplication());

		log.info("Deploying {}...", request.getDefinition().getName());
		String deploymentId = appDeployer.deploy(request);
		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.serviceAccounts().delete(deploymentServiceAccount);
	}

	@Test
	public void createStatefulSet() throws Exception {
		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());
		String deploymentId = deployer.deploy(appDeploymentRequest);
		Map<String, String> idMap = deployer.createIdMap(deploymentId, appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		StatefulSet statefulSet = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems().get(0);
		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		Assertions.assertThat(statefulSetSpec.getPodManagementPolicy()).isEqualTo("Parallel");
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(3);
		Assertions.assertThat(statefulSetSpec.getServiceName()).isEqualTo(deploymentId);

		Assertions.assertThat(statefulSet.getMetadata().getName()).isEqualTo(deploymentId);

		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.containsAllEntriesOf(deployer.createIdMap(deploymentId, appDeploymentRequest));
		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);
		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Container container = statefulSetSpec.getTemplate().getSpec().getContainers().get(0);

		Assertions.assertThat(container.getName()).isEqualTo(deploymentId);
		Assertions.assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(8080);
		Assertions.assertThat(container.getImage()).isEqualTo(testApplication().getURI().getSchemeSpecificPart());

		PersistentVolumeClaim pvc = statefulSetSpec.getVolumeClaimTemplates().get(0);
		Assertions.assertThat(pvc.getMetadata().getName()).isEqualTo(deploymentId);

		PersistentVolumeClaimSpec pvcSpec = pvc.getSpec();
		Assertions.assertThat(pvcSpec.getAccessModes()).containsOnly("ReadWriteOnce");
		Assertions.assertThat(pvcSpec.getStorageClassName()).isNull();
		Assertions.assertThat(pvcSpec.getResources().getLimits().get("storage").getAmount()).isEqualTo("10Mi");
		Assertions.assertThat(pvcSpec.getResources().getRequests().get("storage").getAmount()).isEqualTo("10Mi");

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));
	}

	@Test
	public void createStatefulSetWithOverridingRequest() throws Exception {
		String storageClassName = randomName();

		Map<String, String> props = new HashMap<>();
		props.put(KubernetesAppDeployer.COUNT_PROPERTY_KEY, "3");
		props.put(KubernetesAppDeployer.INDEXED_PROPERTY_KEY, "true");
		props.put("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storageClassName", storageClassName);
		props.put("spring.cloud.deployer.kubernetes.statefulSet.volumeClaimTemplate.storage", "1g");

		ObjectMeta metadata = new ObjectMeta();
		metadata.setName(storageClassName);

		StorageClass storageClass = new StorageClassBuilder()
				.withApiVersion("storage.k8s.io/v1")
				.withKind("StorageClass")
				.withProvisioner("kubernetes.io/gce-pd")
				.withMetadata(metadata)
				.build();

		kubernetesClient.storageClasses().create(storageClass);

		AppDefinition definition = new AppDefinition(randomName(), null);
		AppDeploymentRequest appDeploymentRequest = new AppDeploymentRequest(definition, testApplication(), props);

		KubernetesAppDeployer deployer = new KubernetesAppDeployer(new KubernetesDeployerProperties(), kubernetesClient);

		log.info("Deploying {}...", appDeploymentRequest.getDefinition().getName());
		String deploymentId = deployer.deploy(appDeploymentRequest);
		Map<String, String> idMap = deployer.createIdMap(deploymentId, appDeploymentRequest);

		Timeout timeout = deploymentTimeout();
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(deployed))), timeout.maxAttempts, timeout.pause));

		Map<String, String> selector = Collections.singletonMap(SPRING_APP_KEY, deploymentId);

		StatefulSet statefulSet = kubernetesClient.apps().statefulSets().withLabels(selector).list().getItems().get(0);
		StatefulSetSpec statefulSetSpec = statefulSet.getSpec();

		Assertions.assertThat(statefulSetSpec.getPodManagementPolicy()).isEqualTo("Parallel");
		Assertions.assertThat(statefulSetSpec.getReplicas()).isEqualTo(3);
		Assertions.assertThat(statefulSetSpec.getServiceName()).isEqualTo(deploymentId);
		Assertions.assertThat(statefulSet.getMetadata().getName()).isEqualTo(deploymentId);

		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.containsAllEntriesOf(deployer.createIdMap(deploymentId, appDeploymentRequest));
		Assertions.assertThat(statefulSetSpec.getSelector().getMatchLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels()).containsAllEntriesOf(idMap);
		Assertions.assertThat(statefulSetSpec.getTemplate().getMetadata().getLabels())
				.contains(entry(KubernetesAppDeployer.SPRING_MARKER_KEY, KubernetesAppDeployer.SPRING_MARKER_VALUE));

		Container container = statefulSetSpec.getTemplate().getSpec().getContainers().get(0);

		Assertions.assertThat(container.getName()).isEqualTo(deploymentId);
		Assertions.assertThat(container.getPorts().get(0).getContainerPort()).isEqualTo(8080);
		Assertions.assertThat(container.getImage()).isEqualTo(testApplication().getURI().getSchemeSpecificPart());

		PersistentVolumeClaim pvc = statefulSetSpec.getVolumeClaimTemplates().get(0);
		Assertions.assertThat(pvc.getMetadata().getName()).isEqualTo(deploymentId);

		PersistentVolumeClaimSpec pvcSpec = pvc.getSpec();
		Assertions.assertThat(pvcSpec.getAccessModes()).containsOnly("ReadWriteOnce");
		Assertions.assertThat(pvcSpec.getStorageClassName()).isEqualTo(storageClassName);
		Assertions.assertThat(pvcSpec.getResources().getLimits().get("storage").getAmount()).isEqualTo("1Gi");
		Assertions.assertThat(pvcSpec.getResources().getRequests().get("storage").getAmount()).isEqualTo("1Gi");

		log.info("Undeploying {}...", deploymentId);
		timeout = undeploymentTimeout();
		appDeployer.undeploy(deploymentId);
		assertThat(deploymentId, eventually(hasStatusThat(
				Matchers.hasProperty("state", is(unknown))), timeout.maxAttempts, timeout.pause));

		kubernetesClient.storageClasses().delete(storageClass);
	}

	@Override
	protected String randomName() {
		// Kubernetest service names must start with a letter and can only be 24 characters long
		return "app-" + UUID.randomUUID().toString().substring(0, 18);
	}

	@Override
	protected Timeout deploymentTimeout() {
		return new Timeout(300, 2000);
	}

	@Override
	protected Resource testApplication() {
		return new DockerResource("springcloud/spring-cloud-deployer-spi-test-app:latest");
	}

	private Matcher<String> hasInstanceAttribute(final Matcher<Map<? extends String, ?>> mapMatcher,
	                                               final KubernetesAppDeployer appDeployer,
	                                               final String inst) {
		return new BaseMatcher<String>() {
			private Map<String, String> instanceAttributes;

			public boolean matches(Object item) {
				this.instanceAttributes = appDeployer.status(item.toString()).getInstances().get(inst).getAttributes();
				return mapMatcher.matches(this.instanceAttributes);
			}

			public void describeMismatch(Object item, Description mismatchDescription) {
				mismatchDescription.appendText("attributes of instance " + inst + " of ").appendValue(item)
						.appendText(" ");
				mapMatcher.describeMismatch(this.instanceAttributes, mismatchDescription);
			}

			public void describeTo(Description description) {
				mapMatcher.describeTo(description);
			}
		};
	}
}
