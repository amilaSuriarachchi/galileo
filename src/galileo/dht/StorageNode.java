/*
Copyright (c) 2013, Colorado State University
All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this
   list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

This software is provided by the copyright holders and contributors "as is" and
any express or implied warranties, including, but not limited to, the implied
warranties of merchantability and fitness for a particular purpose are
disclaimed. In no event shall the copyright holder or contributors be liable for
any direct, indirect, incidental, special, exemplary, or consequential damages
(including, but not limited to, procurement of substitute goods or services;
loss of use, data, or profits; or business interruption) however caused and on
any theory of liability, whether in contract, strict liability, or tort
(including negligence or otherwise) arising in any way out of the use of this
software, even if advised of the possibility of such damage.
*/

package galileo.dht;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import galileo.comm.Query;
import galileo.comm.QueryPreamble;
import galileo.comm.QueryRequest;
import galileo.comm.QueryResponse;
import galileo.comm.StorageEvent;
import galileo.comm.StorageRequest;

import galileo.config.SystemConfig;

import galileo.dataset.BlockMetadata;
import galileo.dataset.FileBlock;
import galileo.dataset.MetaArray;

import galileo.dht.hash.HashException;
import galileo.dht.hash.HashTopologyException;

import galileo.event.EventContainer;
import galileo.event.EventType;

import galileo.fs.FileSystem;
import galileo.fs.FileSystemException;

import galileo.logging.GalileoFormatter;

import galileo.net.ClientConnectionPool;
import galileo.net.GalileoMessage;
import galileo.net.HostIdentifier;
import galileo.net.MessageListener;
import galileo.net.PortTester;
import galileo.net.ServerMessageRouter;

import galileo.serialization.Serializer;

import galileo.util.StatusLine;
import galileo.util.Version;

/**
 * Primary communication component in the Galileo DHT.  StorageNodes service
 * client requests and communication from other StorageNodes to disseminate
 * state information throughout the DHT.
 *
 * @author malensek
 */
public class StorageNode implements MessageListener {

    private static final Logger logger = Logger.getLogger("galileo");
    private StatusLine nodeStatus;

    private int port;
    private int threads = 1;

    private NetworkInfo network;

    private ServerMessageRouter messageRouter;
    private ClientConnectionPool connectionPool;
    private Scheduler scheduler;
    private FileSystem fs;

    private Partitioner<BlockMetadata> partitioner;

    private ConcurrentHashMap<String, QueryTracker> queryTrackers
        = new ConcurrentHashMap<>();

    private String sessionId;

    public StorageNode(int port) {
        this.port = port;
        this.sessionId = HostIdentifier.getSessionId(port);
        nodeStatus = new StatusLine(SystemConfig.getRootDir() + "/status.txt");
    }

    /**
     * Begins Server execution.  This method attempts to fail fast to provide
     * immediate feedback to wrapper scripts or other user interface tools.
     * Only once all the prerequisite components are initialized and in a sane
     * state will the StorageNode begin accepting connections.
     */
    public void start()
    throws Exception {
        Version.printSplash();

        /* First, make sure the port we're binding to is available. */
        nodeStatus.set("Attempting to bind to port");
        if (PortTester.portAvailable(port) == false) {
            nodeStatus.set("Could not bind to port " + port + ".");
            throw new IOException("Could not bind to port " + port);
        }

        /* Read the network configuration; if this is invalid, there is no need
         * to execute the rest of this method. */
        nodeStatus.set("Reading network configuration");
        network = NetworkConfig.readNetworkDescription(
                SystemConfig.getNetworkConfDir());

        /* Set up the FileSystem. */
        nodeStatus.set("Initializing file system");
        try {
            fs = new FileSystem(SystemConfig.getRootDir());
            fs.recoverMetadata();
        } catch (FileSystemException e) {
            nodeStatus.set("File system initialization failure");
            logger.log(Level.SEVERE,
                    "Could not initialize the Galileo File System!", e);
            return;
        }

        nodeStatus.set("Initializing communications");

        /* Set up our Shutdown hook */
        Runtime.getRuntime().addShutdownHook(new ShutdownHandler());

        /* Pre-scheduler setup tasks */
        connectionPool = new ClientConnectionPool();
        connectionPool.addListener(this);
        configurePartitioner();

        /* Initialize the Scheduler */
        scheduler = new QueueScheduler(threads);

        /* Start listening for incoming messages. */
        messageRouter = new ServerMessageRouter(port);
        messageRouter.addListener(this);
        messageRouter.listen();
        nodeStatus.set("Online");
    }

    private void configurePartitioner()
    throws HashException, HashTopologyException, PartitionException {
        String[] geohashes = { "c2", "c8", "cb", "f0", "f2",
                               "9r", "9x", "9z", "dp", "dr",
                               "9q", "9w", "9y", "dn", "dq",
                               "9m", "9t", "9v", "dj" };

        partitioner = new SpatialHierarchyPartitioner(this, network, geohashes);
    }

    @Override
    public void onMessage(GalileoMessage message) {
        try {
            EventContainer container = Serializer.deserialize(
                    EventContainer.class, message.getPayload());

            EventHandler handler = getHandler(container);
            if (handler == null) {
                EventType type = container.getEventType();
                logger.log(Level.WARNING,
                        "No handler found for event type " + type.toInt());
                return;
            }

            handler.message = message;
            handler.eventContainer = container;
            handler.router = messageRouter;
            handler.connectionPool = connectionPool;

            scheduler.schedule(handler);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to process incoming message", e);
        }
    }

    /**
     * Provides a mapping between events (implementations of
     * {@link GalileoEvent}) and their respective {@link EventHandler}s.
     */
    private EventHandler getHandler(EventContainer container) {
        EventType type = container.getEventType();

        logger.log(Level.INFO, "Processing event type: {0}", type);

        switch (type) {
            case STORAGE: return new storageHandler();
            case STORAGE_REQUEST: return new storageRequestHandler();
            case QUERY: return new queryHandler();
            case QUERY_REQUEST: return new queryRequestHandler();
            case QUERY_RESPONSE: return new queryResponseHandler();
            default: return null;
        }
    }

    /**
     * Handles a storage request from a client.  This involves determining where
     * the data belongs via a {@link Partitioner} implementation and then
     * forwarding the data on to its destination.
     */
    private class storageRequestHandler extends EventHandler {
        @Override
        public void handleEvent() throws Exception {
            StorageRequest request = deserializeEvent(StorageRequest.class);

            /* Determine where this block goes. */
            FileBlock file = request.getBlock();
            BlockMetadata metadata = file.getMetadata();
            NodeInfo node = partitioner.locateData(metadata);

            logger.log(Level.INFO, "Storage destination: {0}", node);
            StorageEvent store = new StorageEvent(file);
            publishEvent(store, node);
        }
    }

    private class storageHandler extends EventHandler {
        @Override
        public void handleEvent() throws Exception {
            StorageEvent store = deserializeEvent(StorageEvent.class);

            logger.log(Level.INFO, "Storing block: {0}", store.getBlock());
            fs.storeBlock(store.getBlock());
        }
    }

    /**
     * Handles a query request from a client.  Query requests result in a number
     * of subqueries being performed across the Galileo network.
     */
    private class queryRequestHandler extends EventHandler {
        @Override
        public void handleEvent() throws Exception {
            QueryRequest request = deserializeEvent(QueryRequest.class);
            String queryString = request.getQueryString();
            logger.log(Level.INFO, "Query request: {0}", queryString);

            /* Determine StorageNodes that contain relevant data. */
            //featureGraph.query(
            NodeArray queryNodes = new NodeArray();
            queryNodes.addAll(network.getAllNodes());

            /* Set up QueryTracker for this request */
            QueryTracker tracker = new QueryTracker(message.getSelectionKey());
            String clientId = tracker.getIdString(sessionId);
            queryTrackers.put(clientId, tracker);

            /* Send a Query Preamble to the client */
            QueryPreamble preamble = new QueryPreamble(
                    clientId, queryString, queryNodes);
            publishResponse(preamble);

            /* Optionally write out where this query is going */
            if (logger.getLevel() == Level.INFO) {
                StringBuilder sb = new StringBuilder();
                sb.append("Forwarding Query to nodes: ");
                for (NodeInfo node : queryNodes) {
                    sb.append(node.toString() + " ");
                }
                logger.info(sb.toString());
            }

            Query query = new Query(tracker.getIdString(sessionId),
                    request.getQueryString());
            for (NodeInfo node : queryNodes) {
                publishEvent(query, node);
            }
        }
    }

    /**
     * Handles an internal Query request (from another StorageNode)
     */
    private class queryHandler extends EventHandler {
        @Override
        public void handleEvent() throws Exception {
            Query query = deserializeEvent(Query.class);

            MetaArray results = fs.query(query.getQueryString());
            logger.info("Got " + results.size() + "results");
            QueryResponse response
                = new QueryResponse(query.getQueryId(), results);
            publishResponse(response);
        }
    }

    private class queryResponseHandler extends EventHandler {
        @Override
        public void handleEvent() throws Exception {
            QueryResponse response = deserializeEvent(QueryResponse.class);
            QueryTracker tracker = queryTrackers.get(response.getId());
            if (tracker == null) {
                logger.log(Level.WARNING,
                        "Unknown query response received: {0}",
                        response.getId());
                return;
            }
            sendMessage(message, tracker.getSelectionKey());
        }
    }

    /**
     * Handles cleaning up the system for a graceful shutdown.
     */
    private class ShutdownHandler extends Thread {
        @Override
        public void run() {
            /* The logging subsystem may have already shut down, so we revert to
             * stdout for our final messages */
            System.out.println("Initiated shutdown.");
            System.out.println("Goodbye!");
        }
    }

    /**
     * Executable entrypoint for a Galileo DHT Storage Node
     */
    public static void main(String[] args) {
        int port = NetworkConfig.DEFAULT_PORT;
        StorageNode node = new StorageNode(port);
        try {
            node.start();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not start StorageNode.", e);
        }
    }
}
