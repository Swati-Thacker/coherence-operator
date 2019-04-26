/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */

package helm;

import com.oracle.bedrock.deferred.options.InitialDelay;
import com.oracle.bedrock.deferred.options.MaximumRetryDelay;
import com.oracle.bedrock.deferred.options.RetryFrequency;
import com.oracle.bedrock.options.Timeout;
import com.oracle.bedrock.runtime.Application;
import com.oracle.bedrock.runtime.console.CapturingApplicationConsole;
import com.oracle.bedrock.runtime.console.SystemApplicationConsole;
import com.oracle.bedrock.runtime.k8s.K8sCluster;
import com.oracle.bedrock.runtime.options.Arguments;
import com.oracle.bedrock.runtime.options.Console;
import com.oracle.bedrock.testsupport.deferred.Eventually;
import com.tangosol.util.Resources;
import org.hamcrest.Matcher;
import org.junit.*;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.oracle.bedrock.deferred.DeferredHelper.invoking;
import static helm.HelmUtils.HELM_TIMEOUT;
import static helm.HelmUtils.getPods;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test ELK log aspects of the Helm chart values file.
 * <p>
 * This test depends on certain artifacts produced by the Maven build
 * so although tPersistenceSnapshotHelmChartIThis test can be run from an IDE it requires that at least
 * a Maven build with at least the package phase being run first.
 *
 * @author sc
 */
public class EFKHelmChartIT
        extends BaseHelmChartTest {

    // ----- test lifecycle --------------------------------------------------

    @BeforeClass
    public static void setup() throws Exception
        {
        assertPreconditions(s_k8sCluster);
        ensureNamespace(s_k8sCluster);
        ensureSecret(s_k8sCluster);

        String sOpNamespace = getK8sNamespace();

        if (sOpNamespace != null)
            {
            for (String sNamespace : getTargetNamespaces())
                {
                if (sNamespace != null && !sNamespace.equals(sOpNamespace))
                    {
                    ensureNamespace(s_k8sCluster, sNamespace);
                    }
                }
            }

        String sOperatorValuesFile = "values/helm-values-efk.yaml";
        System.err.println("Deploying " + OPERATOR_HELM_CHART_NAME + " with " + sOperatorValuesFile);

        String sNamespace = getK8sNamespace();

        s_sOperatorRelease = installChart(s_k8sCluster,
                                          OPERATOR_HELM_CHART_NAME,
                                          OPERATOR_HELM_CHART_URL,
                                          sNamespace,
                                          sOperatorValuesFile,
                                          getDefaultHelmSetValues());

        assertDeploymentReady(s_k8sCluster, sNamespace, getCoherenceOperatorSelector(s_sOperatorRelease), true);
        assertDeploymentReady(s_k8sCluster, sNamespace, getKibanaSelector(s_sOperatorRelease), true);

        String sElasticSearchSelector = getElasticSearchSelector(s_sOperatorRelease);

        assertDeploymentReady(s_k8sCluster, sNamespace, sElasticSearchSelector, true);

        List<String> listPods = getPods(s_k8sCluster, sNamespace, sElasticSearchSelector);

        assertThat(listPods.size(), is(1));

        s_sElasticsearchPod = listPods.get(0);

        // get the Kibana Pod
        String       sKibanaSelector = getKibanaSelector(s_sOperatorRelease);
        List<String> listPodsKibana  = getPods(s_k8sCluster, sNamespace, sKibanaSelector);
        assertThat(listPodsKibana.size(), is(1));

        s_sKibanaPod = listPodsKibana.get(0);

        Eventually.assertThat(invoking(STUB).isFluentdReady(),
                              is(true),
                              Timeout.after(HELM_TIMEOUT, TimeUnit.SECONDS),
                              InitialDelay.of(10, TimeUnit.SECONDS),
                              MaximumRetryDelay.of(RETRY_FREQUENCEY_SECONDS, TimeUnit.SECONDS),
                              RetryFrequency.every(RETRY_FREQUENCEY_SECONDS, TimeUnit.SECONDS));

        for (String sCohNamespace : getTargetNamespaces())
            {
            Eventually.assertThat(invoking(STUB).isNamespaceReady(s_k8sCluster, sCohNamespace),
                                  is(true),
                                  Timeout.after(HELM_TIMEOUT, TimeUnit.SECONDS),
                                  InitialDelay.of(2, TimeUnit.SECONDS));
            }
        }

    @AfterClass
    public static void cleanup()
        {
        if (s_sOperatorRelease != null)
            {
            try {
                String sSelector = getCoherenceOperatorSelector(s_sOperatorRelease);

                capturePodLogs(LogHelmChartIT.class, s_k8sCluster, sSelector, COHERENCE_K8S_OPERATOR);
                capturePodLogs(LogHelmChartIT.class, s_k8sCluster, sSelector, "fluentd");

                cleanupHelmReleases(s_sOperatorRelease);
                }
            catch(Throwable t)
                {
                System.err.println("Error in cleaning up Helm Releases: " + s_sOperatorRelease);
                }
            }

        cleanupPullSecrets(s_k8sCluster);
        cleanupNamespace(s_k8sCluster);

        for (String sNamespace : getTargetNamespaces())
            {
            cleanupNamespace(s_k8sCluster, sNamespace);
            }
        }

    @After
    public void cleanUpCoherence()
        {
        if (m_asReleases != null)
            {
            deleteCoherence(s_k8sCluster, getTargetNamespaces(), m_asReleases, PERSISTENCE);
            }
        }

    // ----- test methods ---------------------------------------------------

    /**
     * Test the log messages for jvm options and start.
     *
     * @throws Exception
     */
    @Test
    public void testCoherenceRoleClusterUid() throws Exception
        {
        String[] asCohNamespaces = getTargetNamespaces();
                    m_asReleases = installCoherence(s_k8sCluster, asCohNamespaces,
                            "values/helm-values-coh-efk-jvm.yaml");

        assertCoherence(s_k8sCluster, asCohNamespaces, m_asReleases);

        // verify the role, cluster and uid are set for each coherence
        for (int i = 0; i < m_asReleases.length; i++)
            {
            String sCoherenceSelector = getCoherencePodSelector(m_asReleases[i]);

            assertEFKData(m_asReleases[i], "log", new String[] {"Role=myrole", "Role=OracleCoherenceK8sCoherenceClusterProbe"});
            assertEFKData(m_asReleases[i], "log", "Started cluster Name=mycluster");

            assertThat(verifyEFKData(m_asReleases[i], "cluster", "mycluster"), is(true));
            assertThat(verifyEFKData(m_asReleases[i], "role", new String[] { "myrole", "OracleCoherenceK8sCoherenceClusterProbe" }),
                    is(true));
            assertEFKData(m_asReleases[i], "log", "Started DefaultCacheServer");

            List<String> listUids = getPodUids(s_k8sCluster, asCohNamespaces[i], sCoherenceSelector);
            assertThat(listUids.size() > 0, is(true));
            assertThat(verifyEFKData(m_asReleases[i], "pod-uid", listUids.get(0)), is(true));
            }

        // validate that the 2 index patterns exists. This ensures that the initContainer to
        // load the kibana-dashboard-data.json has been loaded
        validateIndexPatternExists(COHERENCE_CLUSTER_INDEX_PATTERN);
        validateIndexPatternExists(COHERENCE_OPERATOR_INDEX_PATTERN);
        }

    /**
     * Validate the configuration of application logging and including it in elastic search.
     *
     * @throws Exception  if the test fails
     */
    @Test
    public void testApplicationEnabledLogging() throws Exception
        {
        String sNamespace      = getK8sNamespace();
        String sValuesOriginal = "values/helm-values-coh-user-artifact-efk.yaml";

        // required to perform elastic search for application log events
        createCloudApplicationESIndex();

        String sRelease        = installCoherence(s_k8sCluster, sNamespace, sValuesOriginal,
             "clusterSize=2", "cluster=" + CLUSTER1,
            "fluentd.application.configFile=/conf/fluentd-cloud.conf",
            "fluentd.application.tag=cloud");

        m_asReleases = new String[] {sRelease};

        assertCoherence(s_k8sCluster, sNamespace, sRelease);

        assertCoherenceService(s_k8sCluster, sNamespace, sRelease);

        String       sCoherenceSelector = getCoherencePodSelector(sRelease);
        List<String> listPods           = getPods(s_k8sCluster, sNamespace, sCoherenceSelector);

        assertThat(listPods.size(), is(2));

        System.err.println("Waiting for Coherence Pods");

        for (String sPod : listPods)
            {
            System.err.println("Waiting for Coherence Pod " + sPod + "...");
            Eventually.assertThat(invoking(this).hasDefaultCacheServerStarted(s_k8sCluster, sNamespace, sPod),
                is(true), Timeout.after(HELM_TIMEOUT, TimeUnit.SECONDS));
            }

        System.err.println("Coherence Pods started");

        try
            {
            installClient(s_k8sCluster, CLIENT1, sNamespace, sRelease);
            installClient(s_k8sCluster, CLIENT2, sNamespace, sRelease);

            System.err.println("Waiting for Client-1 initial state ...");
            Eventually.assertThat(invoking(this).isRequiredClientStateReached(s_k8sCluster, sNamespace, CLIENT1),
                is(true),
                Eventually.within(TIMEOUT, TimeUnit.SECONDS));

            Eventually.assertThat("cloud-* index is not null", invoking(this).getCloudApplicationESIndex(),
                notNullValue(), RetryFrequency.every(10, TimeUnit.SECONDS), Timeout.after(3, TimeUnit.MINUTES));

            assertThat(verifyEFKApplicationData(m_asReleases[0], "cluster", CLUSTER1), is(true));
            assertThat(verifyEFKApplicationData(m_asReleases[0], "product", "Cloud 1.0"), is(true));
            assertThat(verifyEFKApplicationData(m_asReleases[0], "log", "AWS"), is(true));
            assertThat(verifyEFKApplicationData(m_asReleases[0], "log", "GCP"), is(true));
            }
        finally
            {
            dumpPodLog(s_k8sCluster, sNamespace, CLIENT1, null);
            dumpPodLog(s_k8sCluster, sNamespace, listPods.get(0), "fluentd");
            dumpPodLog(s_k8sCluster, sNamespace, listPods.get(0), "coherence");
            dumpPodLog(s_k8sCluster, sNamespace, listPods.get(1), "fluentd");
            dumpPodLog(s_k8sCluster, sNamespace, listPods.get(1), "coherence");

            deleteClients();
            }
        }

    // ----- helper methods -------------------------------------------------

    /**
     * Determine whether the Helm fluentd is ready (i.e all Pods are ready).
     *
     * @return  {@code true} if the fluentd is ready
     */
    // must be public - used in Eventually.assertThat call.
    public boolean isFluentdReady()
        {
        Queue<String> queueLines = processElasticsearchQuery("/_cat/indices");
        return queueLines.stream().anyMatch(s -> s.contains("coherence"));
        }

    // must be public - used in Eventually.assertThat call.
    public boolean isNamespaceReady(K8sCluster cluster, String sNamespace)
        {
        CapturingApplicationConsole console = new CapturingApplicationConsole();

        Arguments args = Arguments.of("get", "configmap", "coherence-internal-config");

        if (sNamespace != null && sNamespace.trim().length() > 0)
            {
            args = args.with("--namespace", sNamespace);
            }

        int nExitCode = cluster.kubectlAndWait(args, Console.of(console));

        HelmUtils.logConsoleOutput("get-configmap", console);

        return nExitCode == 0;
        }

    void assertEFKData(String sRelease, String sFieldName, String[] sKeyWord) throws IOException
        {
        Eventually.assertThat(invoking(this).verifyEFKData(sRelease, sFieldName, sKeyWord), is(true),
                Timeout.after(HELM_TIMEOUT, TimeUnit.SECONDS),
                MaximumRetryDelay.of(RETRY_FREQUENCEY_SECONDS, TimeUnit.SECONDS),
                RetryFrequency.every(RETRY_FREQUENCEY_SECONDS, TimeUnit.SECONDS));
        }

    void assertEFKData(String sRelease, String sFieldName, String sKeyWord) throws IOException
        {
        Eventually.assertThat(invoking(this).verifyEFKData(sRelease, sFieldName, sKeyWord), is(true),
                Timeout.after(HELM_TIMEOUT, TimeUnit.SECONDS),
                MaximumRetryDelay.of(RETRY_FREQUENCEY_SECONDS, TimeUnit.SECONDS),
                RetryFrequency.every(RETRY_FREQUENCEY_SECONDS, TimeUnit.SECONDS));
        }

    // must be public - used in Eventually.assertThat call.
    public boolean verifyEFKData(String sRelease, String sFieldName, String sKeyWord) throws IOException
        {
        return verifyEFKData(sRelease, sFieldName, new String[] {sKeyWord});
        }

    // must be public - used in Eventually.assertThat call.
    public boolean verifyEFKData(String sRelease, String sFieldName, String[] sKeyWord) throws IOException
        {
        String       sName   = sRelease + "-" + COHERENCE_CONTAINER_NAME;
        List<String> efkLogs = getEFKData(sName, sFieldName, sKeyWord);

        boolean fResult = false;

        assertTrue(sKeyWord.length > 0);
        if (sKeyWord.length == 1)
            {
            fResult = efkLogs.stream().anyMatch(l -> l.contains(sName) && l.contains(sKeyWord[0]));
            }
        else
            {
            fResult = efkLogs.stream().anyMatch(l -> l.contains(sName) && (l.contains(sKeyWord[0])
                    || l.contains(sKeyWord[1])));
            }

        System.err.printf("Verify release %s - %s: %b%n", sRelease, Arrays.toString(sKeyWord), fResult);

        return fResult;
        }

    List<String> getEFKData(String sHostPrefix, String sFieldName, String[] sKeyWords) throws IOException
        {
        String sIndexName = getESIndex();

        Queue<String> queueLogs = new ConcurrentLinkedQueue<>();
        for (String sKeyWord : sKeyWords)
            {
            queueLogs.addAll(processElasticsearchQuery(
                    "/" + sIndexName + "/_search?q=" +
                        sFieldName + "%3A" + sKeyWord.replace(" ", "%20") +
                        "%20AND%20" +
                        "host" + "%3A" + sHostPrefix));
            }

        Map<String, ?> map = HelmUtils.JSON_MAPPER.readValue(queueLogs.stream().collect(Collectors.joining()), Map.class);

        Map<String, List<Map<String, ?>>> mapHits = (Map<String, List<Map<String, ?>>>) map.get("hits");
        assertThat(mapHits, notNullValue());

        List<Map<String, ?>> list = mapHits.get("hits");
        assertThat(mapHits, notNullValue());

        return list.stream().map(m -> {
                Map<String, ?> mapSource = (Map<String, ?>) m.get("_source");
                return mapSource.get("host") + ">" + mapSource.get(sFieldName);
            }).collect(Collectors.toList());
        }

    boolean verifyEFKApplicationData(String sRelease, String sFieldName, String sKeyWord) throws IOException
        {
        return verifyEFKApplicationData(sRelease, sFieldName, new String[] {sKeyWord});
        }

    boolean verifyEFKApplicationData(String sRelease, String sFieldName, String[] sKeyWord) throws IOException
        {
        String       sName   = sRelease + "-" + COHERENCE_CONTAINER_NAME;
        List<String> efkLogs = getEFKApplicationData(sName, sFieldName, sKeyWord);

        boolean fResult = false;

        assertTrue(sKeyWord.length > 0);
        if (sKeyWord.length == 1)
            {
            fResult = efkLogs.stream().anyMatch(l -> l.contains(sName) && l.contains(sKeyWord[0]));
            }
        else
            {
            fResult = efkLogs.stream().anyMatch(l -> l.contains(sName) && (l.contains(sKeyWord[0])
                || l.contains(sKeyWord[1])));
            }

        System.err.printf("Verify release %s - %s: %b%n", sRelease, Arrays.toString(sKeyWord), fResult);

        return fResult;
        }

    List<String> getEFKApplicationData(String sHostPrefix, String sFieldName, String[] sKeyWords) throws IOException
        {
        String sIndexName = getCloudApplicationESIndex();

        Queue<String> queueLogs = new ConcurrentLinkedQueue<>();
        for (String sKeyWord : sKeyWords)
            {
            queueLogs.addAll(processElasticsearchQuery(
                "/" + sIndexName + "/_search?q=" +
                    sFieldName + "%3A" + sKeyWord.replace(" ", "%20") +
                    "%20AND%20" +
                    "member" + "%3A" + sHostPrefix));
            }

        Map<String, ?> map = HelmUtils.JSON_MAPPER.readValue(queueLogs.stream().collect(Collectors.joining()), Map.class);

        Map<String, List<Map<String, ?>>> mapHits = (Map<String, List<Map<String, ?>>>) map.get("hits");
        assertThat(mapHits, notNullValue());

        List<Map<String, ?>> list = mapHits.get("hits");
        assertThat(mapHits, notNullValue());

        return list.stream().map(m -> {
        Map<String, ?> mapSource = (Map<String, ?>) m.get("_source");
        return mapSource.get("member") + ">" + mapSource.get(sFieldName);
        }).collect(Collectors.toList());
        }

    void validateIndexPatternExists(String sIndexPattern) throws IOException
        {
        Queue<String> queueLogs = new ConcurrentLinkedQueue<>();

        queueLogs.addAll(processKibanaQuery("/api/saved_objects/index-pattern/" + sIndexPattern));

        Map<String, ?> map = HelmUtils.JSON_MAPPER.readValue(queueLogs.stream().collect(Collectors.joining()), Map.class);

        String sIndexPatternId = (String) map.get("id");
        assertThat(sIndexPatternId, notNullValue());
        assertThat(sIndexPatternId, is(sIndexPattern));
        }

    String getESIndex()
        {
        if (m_sElasticsearchIndex == null)
            {
            Queue<String>  queueIndices = processElasticsearchQuery("/_cat/indices");
            String         sIndexName   = queueIndices.stream().filter(s -> s.contains("coherence-cluster-"))
                    .map(s -> s.split(" ")[2]).findFirst().orElse(null);

            assertThat(sIndexName, notNullValue());

            m_sElasticsearchIndex = sIndexName;
            System.out.println("getESIndex: " + sIndexName);
            }

        return m_sElasticsearchIndex;
        }

    public String getCloudApplicationESIndex()
        {
        if (m_sCloudElasticsearchIndex == null)
            {
            Queue<String>  queueIndices = processElasticsearchQuery("/_cat/indices");
            String sIndexName = queueIndices.stream().filter(s -> s.contains("cloud"))
                .map(s -> s.split(" ")[2]).findFirst().orElse(null);

            assertThat("expected an index name for cloud-", sIndexName, notNullValue());

            m_sCloudElasticsearchIndex = sIndexName;
            }

        return m_sCloudElasticsearchIndex;
        }

    Queue<String> processElasticsearchQuery(String sPath)
        {
        return processHttpRequest(s_k8sCluster, s_sElasticsearchPod, "GET", "localhost", 9200, sPath);
        }

    Queue<String> processKibanaQuery(String sPath)
        {
        return processHttpRequest(s_k8sCluster, s_sKibanaPod, "GET", "localhost", 5601, sPath);
        }

    /**
     * Coherence-cluster-index is part of coherence operator.
     *
     * Here is a workaround for installing application index when application is in a side car.
     * Do not know how to get this picked up in a configmap for a side car.
     *
     * @return response for request to create an index pattern in kibana
     */
    String createCloudApplicationESIndex()
        throws Exception
        {
        HttpResponse<String> response = null;
        String sSelector = getKibanaSelector(s_sOperatorRelease);
        try (Application application = portForward(s_k8sCluster, getK8sNamespace(), sSelector, 5601))
            {
            String      sFilePath   = Resources.findFileOrResource(CLOUD_KIBANA_INDEX, null).getPath();
            String      sPath       = "/api/saved_objects/index-pattern/cloud-*";
            PortMapping portMapping = application.get(PortMapping.class);
            int         nPort       = portMapping.getPort().getActualPort();
            URI         uri     = URI.create("http://127.0.0.1:" + nPort + sPath);
            HttpClient  client  = HttpClient.newBuilder().proxy(ProxySelector.of(null)).version(Version.HTTP_1_1).build();
            HttpRequest request = HttpRequest.newBuilder().uri(uri).
                header("Content-Type", "application/json").
                header("kbn-xsrf", "true").
                POST(BodyPublishers.ofFile(Paths.get(sFilePath))).build();
            
            try
                {
                response = client.send(request, BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                }
            catch(Throwable t)
                {
                System.out.println("Handled unexpected exception " + t);
                t.printStackTrace();
                dumpPodLog(s_k8sCluster, getK8sNamespace(), s_sKibanaPod);
                }
            }

        return response == null ? "<no response>" : response.body();
        }

    List<String> getPodUids(K8sCluster cluster, String sNamespace, String sSelector)
        {
        CapturingApplicationConsole console = new CapturingApplicationConsole();

        Arguments args = Arguments.of("get", "pods");

        if (sNamespace != null && sNamespace.trim().length() > 0)
            {
            args = args.with("--namespace", sNamespace);
            }

        args = args.with("-o", "jsonpath=\"{.items[*].metadata.uid}\"", "-l", sSelector);

        int nExitCode = cluster.kubectlAndWait(args, Console.of(console));

        HelmUtils.logConsoleOutput("get-pods-uid", console);

        assertThat("kubectl returned non-zero exit code", nExitCode, is(0));

        String sList = console.getCapturedOutputLines().poll();

        // strip any leading quote
        if (sList.charAt(0) == '"')
            {
            sList = sList.substring(1);
            }

        // strip any trailing quote
        if (sList.endsWith("\""))
            {
            sList = sList.substring(0, sList.length() - 1);
            }

        return Arrays.asList(sList.split(" "));
        }

    /**
     * Check for required client state.
     *
     * @param cluster     the k8s cluster
     * @param sNamespace  the namespace name
     * @param sClientPod  the pod name
     *
     * @return {@code true} if required client state is reached.
     */
    public boolean isRequiredClientStateReached(K8sCluster cluster, String sNamespace, String sClientPod)
        {
        try
            {
            Queue<String> sLogs = getPodLog(cluster, sNamespace, sClientPod, null);

            return sLogs.stream().anyMatch(l -> l.contains("Cache Value Before Cloud EntryProcessor: AWS"))
                && sLogs.stream().anyMatch(l -> l.contains("Cache Value After Cloud EntryProcessor: GCP"));
            }
        catch (Exception ex)
            {
            return false;
            }
        }

    private void installClient(K8sCluster cluster, String name, String sNamespace, String sRelease) throws Exception
        {
        Arguments arguments = Arguments.of("apply");

        if (sNamespace != null)
            {
            arguments = arguments.with("--namespace", sNamespace);
            }

        arguments = arguments.with("-f", getClientYaml(name, sRelease, CLUSTER1));

        int nExitCode = cluster.kubectlAndWait(arguments, SystemApplicationConsole.builder());

        assertThat("kubectl create coherence client pod returned non-zero exit code", nExitCode, is(0));
        }

    /**
     * Determine whether the coherence start up log is ready. param cluster the k8s
     * cluster
     *
     * @param cluster     the k8s cluster
     * @param sNamespace  the namespace of coherence
     * @param sPod        the pod name of coherence
     *
     * @return {@code true} if coherence container is ready
     */
    public boolean hasDefaultCacheServerStarted(K8sCluster cluster, String sNamespace, String sPod)
        {
        try
            {
            Queue<String> sLogs = getPodLog(cluster, sNamespace, sPod, COHERENCE_CONTAINER_NAME);
            return sLogs.stream().anyMatch(l -> l.contains("Started DefaultCacheServer"));
            }
        catch (Exception ex)
            {
            return false;
            }
        }

    private void deleteClients()
        {
        deleteClient(CLIENT1);
        deleteClient(CLIENT2);
        }

    private void deleteClient(String sClient)
        {
        String sNamespace = getK8sNamespace();
        Arguments arguments = Arguments.of("delete", "pod", sClient);

        if (sNamespace != null)
            {
            arguments = arguments.with("-n", sNamespace);
            }

        s_k8sCluster.kubectlAndWait(arguments, SystemApplicationConsole.builder());
        }

    // ----- data members ---------------------------------------------------

    // index patterns to compare against. These values must match the id for both index patterns
    // in kibana-data/kibana-dashboard-data.json

    private final String COHERENCE_CLUSTER_INDEX_PATTERN  = "6abb1220-3feb-11e9-a9a3-4b1c09db6e6a";
    private final String COHERENCE_OPERATOR_INDEX_PATTERN = "42520a20-4151-11e9-b896-8f011e97d2d5";

    /**
     * The retry frequency in seconds.
     */
    private static final int RETRY_FREQUENCEY_SECONDS = 10;

    /**
     * The k8s cluster to use to install the charts.
     */
    private static K8sCluster s_k8sCluster = getDefaultCluster();

    /**
     * The name of the deployed Operator Helm release.
     */
    private static String s_sOperatorRelease = null;

    /**
     * The Elasticsearch pod.
     */
    private static String s_sElasticsearchPod = null;

    /**
     * The Kibana pod.
     */
    private static String s_sKibanaPod = null;

    /**
     * The boolean indicates whether coherence cache data is persisted.
     */
    private static boolean PERSISTENCE = false;

    /**
     * A stub {@link EFKHelmChartIT} to use to call methods via {@link Eventually#assertThat(String, Object, Matcher)}
     */
    private static final EFKHelmChartIT STUB = new EFKHelmChartIT();

    /**
     * The Elasticsearch index.
     */
    private String m_sElasticsearchIndex;

    /**
     * The Cloud application Elasticsearch index.
     */
    private String m_sCloudElasticsearchIndex;

/**
     * The name of the deployed Coherence Helm releases.
     */
    private String[] m_asReleases;

    /**
     * Time out value for checking the required condition.
    */
    private static final int      TIMEOUT      = 300;

    private static final String   CLIENT1  = "coh-client-1";

    private static final String   CLIENT2  = "coh-client-2";

    private static final String   CLUSTER1 = "ApplicationLoggingEnabledCluster";

    private static final String   CLOUD_KIBANA_INDEX = "json/cloud.index_pattern.json";
    }
