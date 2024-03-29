/*
Copyright (c) 2014, Colorado State University
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

/**
 * A "dual" MessageRouter instance that can act as both a server and a client.
 * This implementation is made up of a {@link ClientMessageRouter} and a
 * {@link ServerMessageRouter} instance, meaning outgoing and incoming messages
 * are processed by separate threads.
 *
 * @author malensek
 */
public class DualMessageRouter {

    private ServerMessageRouter serverRouter;
    private ClientMessageRouter clientRouter;

    public DualMessageRouter(int port) {

    }

    public DualMessageRouter(int port,
            int readBufferSize, int maxWriteQueueSize) {

    }

    public void listen()
    throws IOException {
        serverRouter.listen();
    }

    public void sendMessage(NetworkDestination destination,
            GalileoMessage message)
    throws IOException {
        clientRouter.sendMessage(destination, message);
    }

    public void shutdown()
    throws IOException {
        serverRouter.shutdown();
        clientRouter.shutdown();
    }

    public void addListener(MessageListener listener) {
        serverRouter.addListener(listener);
        clientRouter.addListener(listener);
    }
}
