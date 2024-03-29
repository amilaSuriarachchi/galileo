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

package galileo.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides client-side message routing capabilities. This includes connecting
 * to a remote server, and transmitting messages in non-blocking mode.
 *
 * @author malensek
 */
public class ClientMessageRouter extends MessageRouter {

    protected static final Logger logger = Logger.getLogger("galileo");

    protected Map<NetworkDestination, SocketChannel> destinationToSocket
        = new HashMap<>();
    protected Map<SocketChannel, NetworkDestination> socketToDestination
        = new HashMap<>();
    protected Map<SocketChannel, TransmissionTracker> socketToTracker
        = new HashMap<>();

    protected Queue<SocketChannel> pendingRegistrations
        = new ConcurrentLinkedQueue<>();

    public ClientMessageRouter()
    throws IOException {
        super();
        initializeSelector();
    }

    public ClientMessageRouter(int readBufferSize, int maxWriteQueueSize)
    throws IOException {
        super(readBufferSize, maxWriteQueueSize);
        initializeSelector();
    }

    private void initializeSelector()
    throws IOException {
        this.selector = Selector.open();
        this.online = true;
        Thread selectorThread = new Thread(this);
        selectorThread.start();
    }

    @Override
    public void run() {
        while (online) {
            try {
                processPendingRegistrations();
                updateInterestOps();
                processSelectionKeys();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Handles pending registration operations on the Selector thread.
     */
    private void processPendingRegistrations()
    throws ClosedChannelException {
        Iterator<SocketChannel> it = pendingRegistrations.iterator();
        while (it.hasNext() == true) {
            SocketChannel channel = it.next();
            it.remove();

            TransmissionTracker tracker = socketToTracker.get(channel);
            channel.register(selector, SelectionKey.OP_CONNECT, tracker);
        }
    }

    /**
     * Ensures that a particular {@link NetworkDestination} has been connected
     * to, and retrieves its relevant {@link TransmissionTracker} instance.
     *
     * @param destination The NetworkDestination to ensure this MessageRouter is
     * connected to
     *
     * @return The TransmissionTracker associated with the NetworkDestination.
     */
    private TransmissionTracker ensureConnected(NetworkDestination destination)
    throws IOException {
        SocketChannel channel = destinationToSocket.get(destination);
        if (channel != null) {
            return socketToTracker.get(channel);
        }

        channel = SocketChannel.open();
        channel.configureBlocking(false);
        InetSocketAddress address = new InetSocketAddress(
                destination.getHostname(), destination.getPort());
        channel.connect(address);

        /* Update data structures for mapping between sockets/keys/trackers */
        destinationToSocket.put(destination, channel);
        socketToDestination.put(channel, destination);
        TransmissionTracker tracker = new TransmissionTracker(writeQueueSize);
        socketToTracker.put(channel, tracker);

        /* Finally, put this registration in the pending queue */
        pendingRegistrations.add(channel);

        return tracker;
    }

    /**
     * Sends a message to multiple network destinations.
     */
    public void broadcastMessage(Iterable<NetworkDestination> destinations,
            GalileoMessage message)
    throws IOException {
        for (NetworkDestination destination : destinations) {
            sendMessage(destination, message);
        }
    }

    /**
     * Sends a message to the specified network destination.  Connections are
     * completed during the first send operation.
     */
    public void sendMessage(NetworkDestination destination,
            GalileoMessage message)
    throws IOException {

        /* Make sure this destination has been connected.  If not, this kicks
         * off the connection process. */
        TransmissionTracker tracker = ensureConnected(destination);

        /* Queue the data to be written */
        ByteBuffer payload = wrapWithPrefix(message);
        try {
            tracker.getPendingWriteQueue().put(payload);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        /* Request for interestOps change. */
        SocketChannel channel = destinationToSocket.get(destination);
        if (channel.isRegistered() && channel.isConnected()) {
            changeInterest.put(channel.keyFor(this.selector),
                    SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }

        selector.wakeup();
    }

    @Override
    protected void disconnect(SelectionKey key) {
        /* Update our ClientMessageRouter-specific data structures when
         * disconnected. */
        SocketChannel channel = (SocketChannel) key.channel();
        NetworkDestination destination = socketToDestination.get(channel);
        destinationToSocket.remove(destination);
        socketToDestination.remove(channel);
        socketToTracker.remove(channel);
        super.disconnect(key);
    }

    /**
     * Forcibly shuts down the message processor and disconnects from any
     * connected server(s).  If pending writes have been queued, they will be
     * discarded.
     */
    public void forceShutdown() {
        shutdown(true);
    }

    /**
     * Shuts down the message processor and disconnects from the server(s).  If
     * pending writes have been queued, this method will block until the queue
     * is empty.
     */
    public void shutdown() {
        shutdown(false);
    }

    /**
     * Shuts down the message processor and disconnects from the server(s).
     *
     * @param forcible Whether or not to forcibly shut down (discard queued
     * messages).
     */
    private void shutdown(boolean forcible) {
        for (SocketChannel channel : destinationToSocket.values()) {
            SelectionKey key = channel.keyFor(this.selector);

            /* If this is not a forcible shutdown, then we need to check each
             * TransmissionTracker's pending write queue, and make sure the
             * items in the queues get sent before shutdown happens. */
            if (forcible == false) {
                safeShutdown(key);
            }

            if (key != null) {
                disconnect(key);
            }
        }
        this.online = false;
        selector.wakeup();
    }

    /**
     * This method checks a given SelectionKey's write queue for pending
     * writes, and then does a series of sleep-checks until the queue is empty.
     *
     * @param key SelectionKey to monitor for pending writes.
     */
    private void safeShutdown(SelectionKey key) {
        final int initialWait = 1000;
        final int longestWait = 5000;

        TransmissionTracker tracker = TransmissionTracker.fromKey(key);
        BlockingQueue<ByteBuffer> pendingWrites
            = tracker.getPendingWriteQueue();

        int wait = initialWait;
        int size = pendingWrites.size();
        if (pendingWrites.isEmpty() == false) {
            do {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    logger.log(Level.WARNING, "Received interrupt "
                            + "during safe shutdown.", e);
                }

                if (pendingWrites.isEmpty() == false) {
                    /* Still not empty; we'll log something now. */
                    logger.info("Waiting to shut down; " + pendingWrites.size()
                            + " items remaining in write queue.");

                    /* If the queue didn't get any smaller, increase the amount
                     * of time we'll wait before polling. */
                    if (pendingWrites.size() >= size && wait < longestWait) {
                        wait += initialWait;
                    }
                    size = pendingWrites.size();

                    /* Make sure the SelectionKey is still valid. */
                    if (key.isValid() == false) {
                        logger.severe("Connection terminated while emptying "
                                + "send buffer!");
                        return;
                    }
                }

            } while (pendingWrites.isEmpty() == false);
        }
    }
}
