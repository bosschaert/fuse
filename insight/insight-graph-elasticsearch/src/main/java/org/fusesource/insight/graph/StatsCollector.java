/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.insight.graph;


import org.codehaus.jackson.annotate.JsonProperty;
import org.elasticsearch.action.index.IndexRequest;
import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.FabricService;
import org.fusesource.fabric.api.Profile;
import org.fusesource.fabric.groups.ClusteredSingleton;
import org.fusesource.fabric.groups.Group;
import org.fusesource.fabric.groups.NodeState;
import org.fusesource.fabric.groups.ZooKeeperGroupFactory;
import org.fusesource.fabric.zookeeper.IZKClient;
import org.fusesource.insight.elasticsearch.ElasticSender;
import org.fusesource.insight.graph.model.MBeanAttrs;
import org.fusesource.insight.graph.model.MBeanOpers;
import org.fusesource.insight.graph.model.Query;
import org.fusesource.insight.graph.model.QueryResult;
import org.fusesource.insight.graph.model.Request;
import org.fusesource.insight.graph.model.Server;
import org.fusesource.insight.graph.support.JSONReader;
import org.fusesource.insight.graph.support.JmxUtils;
import org.fusesource.insight.graph.support.Renderer;
import org.fusesource.insight.graph.support.ScriptUtils;
import org.osgi.framework.BundleContext;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Collects all the charting statistics defined against its profiles
 */
public class StatsCollector {

    public static final String GRAPH_JSON = "org.fusesource.insight.graph.json";

    public static final String QUERIES = "queries";
    public static final String NAME = "name";
    public static final String URL = "url";
    public static final String TEMPLATE = "template";
    public static final String LOCK = "lock";
    public static final String PERIOD = "period";
    public static final String MIN_PERIOD = "minPeriod";
    public static final String REQUESTS = "requests";
    public static final String OBJ = "obj";
    public static final String ATTRS = "attrs";
    public static final String OPER = "oper";
    public static final String ARGS = "args";
    public static final String SIG = "sig";
    public static final String DEFAULT = "default";
    public static final String LOCK_GLOBAL = "global";
    public static final String LOCK_HOST = "host";

    private static final transient Logger LOG = LoggerFactory.getLogger(StatsCollector.class);

    private BundleContext bundleContext;
    private FabricService fabricService;
    private IZKClient zookeeper;

    private ScheduledThreadPoolExecutor executor;
    private Map<Query, QueryState> queries = new HashMap<Query, QueryState>();
    private Renderer renderer = new Renderer();

    private ServiceTracker<MBeanServer, MBeanServer> mbeanServer;
    private ServiceTracker<ElasticSender, ElasticSender> sender;

    // Locks management
    private Group globalGroup;
    private Group hostGroup;

    private int defaultDelay = 60;
    private int threadPoolSize = 5;
    private String index;
    private String type;

    static class QueryState {
        ScheduledFuture<?> future;
        Server server;
        Query query;
        QueryResult lastResult;
        boolean lastResultSent;
        long lastSent;
        ClusteredSingleton<QueryNodeState> lock;

        public void close() {
            future.cancel(false);
            if (lock != null) {
                lock.leave();
            }
        }
    }

    static class QueryNodeState implements NodeState {
        @JsonProperty
        String id;
        @JsonProperty
        String container;

        QueryNodeState() {
        }

        QueryNodeState(String id, String container) {
            this.id = id;
            this.container = container;
        }

        @Override
        public String id() {
            return id;
        }
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public void setDefaultDelay(int defaultDelay) {
        this.defaultDelay = defaultDelay;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public void setIndex(String index) {
        this.index = index;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setFabricService(FabricService fabricService) {
        this.fabricService = fabricService;
    }

    public void setZookeeper(IZKClient zookeeper) {
        this.zookeeper = zookeeper;
    }

    public void start() throws IOException {
        this.executor = new ScheduledThreadPoolExecutor(threadPoolSize);
        this.executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);

        this.mbeanServer = new ServiceTracker<MBeanServer, MBeanServer>(bundleContext, MBeanServer.class, null);
        this.sender = new ServiceTracker<ElasticSender, ElasticSender>(bundleContext, ElasticSender.class, null);

        this.mbeanServer.open();
        this.sender.open();

        this.executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                process();
            }
        }, 1, defaultDelay, TimeUnit.SECONDS);
    }

    public void stop() throws Exception {
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
        this.executor.shutdownNow();
        try {
            this.executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // Ignore
        }
        this.mbeanServer.close();
        this.sender.close();
        if (globalGroup != null) {
            globalGroup.close();
        }
        if (hostGroup != null) {
            hostGroup.close();
        }
    }


    public void process() {
        Container container = fabricService.getCurrentContainer();
        if (container != null) {
            Set<Query> newQueries = new HashSet<Query>();
            Profile[] profiles = container.getProfiles();
            if (profiles != null) {
                for (Profile profile : profiles) {
                    loadProfile(profile, newQueries);
                }
            }
            for (Query q : queries.keySet()) {
                if (!newQueries.remove(q)) {
                    queries.remove(q).close();
                }
            }
            Server server = new Server(container.getId());
            for (Query q : newQueries) {
                QueryState state = new QueryState();
                state.server = server;
                state.query = q;

                // Clustered stats ?
                if (q.getLock() != null) {
                    state.lock = new ClusteredSingleton<QueryNodeState>(QueryNodeState.class);
                    state.lock.start(startGroup(q.getLock()));
                    state.lock.join(new QueryNodeState(q.getName(), container.getId()));
                }

                long delay = q.getPeriod() > 0 ? q.getPeriod() : defaultDelay;
                state.future = this.executor.scheduleAtFixedRate(
                        new Task(state),
                        Math.round(Math.random() * 1000) + 1,
                        delay * 1000,
                        TimeUnit.MILLISECONDS);
                queries.put(q, state);
            }
        }
    }

    protected synchronized Group startGroup(String lock) {
        if (LOCK_GLOBAL.equals(lock)) {
            if (globalGroup == null) {
                globalGroup = ZooKeeperGroupFactory.create(zookeeper,
                                "/fabric/registry/clusters/insight-graph/global");
            }
            return globalGroup;
        } else if (LOCK_HOST.equals(lock)) {
            if (hostGroup == null) {
                String host;
                try {
                    host = InetAddress.getLocalHost().getHostName();
                } catch (UnknownHostException e) {
                    throw new IllegalStateException("Unable to retrieve host name", e);
                }
                hostGroup = ZooKeeperGroupFactory.create(zookeeper,
                                "/fabric/registry/clusters/insight-graph/host-" + host);
            }
            return hostGroup;
        } else {
            throw new IllegalArgumentException("Unknown lock type: " + lock);
        }
    }

    protected void loadProfile(Profile profile, Set<Query> queries) {
        Map<String, byte[]> fileConfigurations = profile.getFileConfigurations();
        byte[] bytes = fileConfigurations.get(GRAPH_JSON);
        if (bytes != null && bytes.length > 0) {
            try {
                Map object = (Map) new JSONReader().read(new String(bytes));
                for (Map q : (List<Map>) object.get(QUERIES)) {
                    String name = (String) q.get(NAME);
                    String url = (String) q.get(URL);
                    String template = (String) q.get(TEMPLATE);
                    String lock = (String) q.get(LOCK);
                    int period = DEFAULT.equals(q.get(PERIOD)) ? defaultDelay : q.get(PERIOD) != null ? ((Number) q.get(PERIOD)).intValue() : defaultDelay;
                    int minPeriod = DEFAULT.equals(q.get(MIN_PERIOD)) ? defaultDelay : q.get(MIN_PERIOD) != null ? ((Number) q.get(MIN_PERIOD)).intValue() : period;
                    Set<Request> requests = new HashSet<Request>();
                    for (Map mb : (List<Map>) q.get(REQUESTS)) {
                        if (mb.containsKey(ATTRS)) {
                            String mname = (String) mb.get(NAME);
                            String mobj = (String) mb.get(OBJ);
                            List<String> mattrs = (List<String>) mb.get(ATTRS);
                            requests.add(new MBeanAttrs(mname, mobj, mattrs));
                        } else if (mb.containsKey(OPER)) {
                            String mname = (String) mb.get(NAME);
                            String mobj = (String) mb.get(OBJ);
                            String moper = (String) mb.get(OPER);
                            List<Object> margs = (List<Object>) mb.get(ARGS);
                            List<String> msig = (List<String>) mb.get(SIG);
                            requests.add(new MBeanOpers(mname, mobj, moper, margs, msig));
                        } else {
                            throw new IllegalArgumentException("Unknown request " + ScriptUtils.toJson(mb));
                        }
                    }
                    queries.add(new Query(name, requests, url, template, lock, period, minPeriod));
                }
            } catch (Throwable t) {
                LOG.warn("Unable to load queries from profile " + profile.getId(), t);
            }
        }
        for (Profile p : profile.getParents()) {
            loadProfile(p, queries);
        }
    }

    class Task implements Runnable {

        private final QueryState query;

        public Task(QueryState query) {
            this.query = query;
        }

        @Override
        public void run() {
            try {
                MBeanServer mbs = mbeanServer.getService();
                ElasticSender snd = sender.getService();
                // Abort if required services aren't available
                if (mbs == null || snd == null) {
                    return;
                }
                // If there's a lock, check we are the master
                if (query.lock != null && !query.lock.isMaster()) {
                    return;
                }
                QueryResult qrs = JmxUtils.execute(query.server, query.query, mbs);
                boolean forceSend = query.query.getMinPeriod() == query.query.getPeriod() ||
                        qrs.getTimestamp().getTime() - query.lastSent >= TimeUnit.SECONDS.toMillis(query.query.getMinPeriod());
                if (!forceSend && query.lastResult != null) {
                    if (qrs.getResults().equals(query.lastResult.getResults())) {
                        query.lastResult = qrs;
                        query.lastResultSent = false;
                        return;
                    }
                    if (!query.lastResultSent) {
                        renderAndSend(snd, query.lastResult);
                    }
                }
                query.lastResult = qrs;
                query.lastResultSent = true;
                query.lastSent = qrs.getTimestamp().getTime();
                renderAndSend(snd, qrs);
            } catch (Exception e) {
                LOG.debug("Error sending stats", e);
            }
        }

        private void renderAndSend(ElasticSender snd, QueryResult qrs) throws Exception {
            String output = renderer.render(qrs);
            IndexRequest request = new IndexRequest()
                    .index(getIndex(index, qrs))
                    .type(getType(type, qrs))
                    .source(output)
                    .create(true);
            snd.push(request);
        }

    }

    private static final SimpleDateFormat indexFormat = new SimpleDateFormat("yyyy.MM.dd");

    public static String getIndex(String index, QueryResult qrs) {
        return index + "-" + indexFormat.format(qrs.getTimestamp());
    }

    public static String getType(String type, QueryResult qrs) {
        return type + "-" + qrs.getQuery().getName();
    }
}