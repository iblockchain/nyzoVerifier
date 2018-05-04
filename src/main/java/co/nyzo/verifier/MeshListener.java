package co.nyzo.verifier;

import co.nyzo.verifier.messages.*;
import co.nyzo.verifier.util.IpUtil;
import co.nyzo.verifier.util.UpdateUtil;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

public class MeshListener {

    public static void main(String[] args) {
        start();
    }

    private static final AtomicBoolean alive = new AtomicBoolean(false);

    public static boolean isAlive() {
        return alive.get();
    }

    public static final int standardPort = 9444;

    private static ServerSocket serverSocket = null;
    private static int port;

    public static int getPort() {
        return port;
    }

    public static void start() {

        final long startTimestamp = System.currentTimeMillis();
        if (!alive.getAndSet(true)) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        serverSocket = new ServerSocket(standardPort);
                        port = serverSocket.getLocalPort();

                        long timeToStartPort = System.currentTimeMillis() - startTimestamp;
                        while (!UpdateUtil.shouldTerminate()) {
                            Socket clientSocket = serverSocket.accept();

                            new Thread(new Runnable() {
                                @Override
                                public void run() {

                                    try {
                                        Message message = Message.readFromStream(clientSocket.getInputStream(),
                                                IpUtil.addressFromString(clientSocket.getRemoteSocketAddress() + ""));
                                        if (message != null) {
                                            Message response = response(message);
                                            if (response != null) {
                                                clientSocket.getOutputStream().write(response
                                                        .getBytesForTransmission());
                                            }
                                        }

                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }

                                    try {
                                        clientSocket.close();
                                    } catch (Exception ignored) { }
                                }
                            }, "MeshListener-clientSocket").start();
                        }

                        closeServerSocket();

                    } catch (Exception ignored) { }

                    alive.set(false);
                }
            }, "MeshListener-serverSocket").start();
        }
    }

    public static void closeServerSocket() {

        try {
            serverSocket.close();
        } catch (Exception ignored) { }
        serverSocket = null;
    }

    public static Message response(Message message) {

        // This is the single point of dispatch for responding to all received messages.

        Message response = null;
        try {
            if (message != null && message.isValid()) {

                Verifier.registerMessage();

                MessageType messageType = message.getType();

                if (messageType == MessageType.BootstrapRequest1) {

                    // Update the node with the node manager so it will appear in the node list that it receives.
                    BootstrapRequest requestMessage = (BootstrapRequest) message.getContent();
                    NodeManager.updateNode(message.getSourceNodeIdentifier(), message.getSourceIpAddress(),
                            requestMessage.getPort(), requestMessage.isFullNode());

                    response = new Message(MessageType.BootstrapResponse2, new BootstrapResponse());

                } else if (messageType == MessageType.NodeJoin3) {

                    System.out.println("received node-join message");
                    NodeJoinMessage nodeJoinMessage = (NodeJoinMessage) message.getContent();
                    NodeManager.updateNode(message.getSourceNodeIdentifier(), message.getSourceIpAddress(),
                            nodeJoinMessage.getPort(), nodeJoinMessage.isFullNode());

                } else if (messageType == MessageType.Transaction5) {

                    response = new Message(MessageType.TransactionResponse6,
                            new TransactionResponse((Transaction) message.getContent()));

                    Message.forward(message);

                } else if (messageType == MessageType.PreviousHashRequest7) {

                    response = new Message(MessageType.PreviousHashResponse8, new PreviousHashResponse());

                } else if (messageType == MessageType.NewBlock9) {

                    boolean shouldForwardBlock = ChainOptionManager.registerBlock((Block) message.getContent());

                    response = new Message(MessageType.NewBlockResponse10, null);
                    if (shouldForwardBlock) {
                        Message.forward(message);
                    }

                } else if (messageType == MessageType.BlockRequest11) {

                    BlockRequest request = (BlockRequest) message.getContent();
                    response = new Message(MessageType.BlockResponse12, new BlockResponse(request.getStartHeight(),
                            request.getEndHeight(), request.includeBalanceList()));

                } else if (messageType == MessageType.TransactionPoolRequest13) {

                    response = new Message(MessageType.TransactionPoolResponse14,
                            new TransactionPoolResponse(TransactionPool.allTransactions()));

                } else if (messageType == MessageType.Ping200) {

                    response = new Message(MessageType.PingResponse201, new PingResponse("hello, " +
                            IpUtil.addressAsString(message.getSourceIpAddress()) + "!"));

                } else if (messageType == MessageType.UpdateRequest300) {

                    response = new Message(MessageType.UpdateResponse301, new UpdateResponse(message));

                } else if (messageType == MessageType.GenesisBlock500) {

                    Block genesisBlock = (Block) message.getContent();
                    response = new Message(MessageType.GenesisBlockResponse501,
                            new GenesisBlockAcknowledgement(genesisBlock));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            String errorMessage = e.getMessage();
            if (errorMessage == null) {
                errorMessage = "Message from exception is null.";
            }

            response = new Message(MessageType.Error65534, new ErrorMessage(errorMessage));
        }

        return response;
    }

}
