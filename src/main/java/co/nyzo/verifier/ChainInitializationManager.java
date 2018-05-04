package co.nyzo.verifier;

import co.nyzo.verifier.messages.BlockRequest;
import co.nyzo.verifier.messages.BlockResponse;
import co.nyzo.verifier.messages.BootstrapResponse;
import co.nyzo.verifier.util.UpdateUtil;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class ChainInitializationManager {

    private static final Map<Long, FrozenBlockVoteTally> hashVotes = new HashMap<>();

    public static synchronized void processBootstrapResponseMessage(Message message) {

        System.out.println("processing node-join response");
        BootstrapResponse response = (BootstrapResponse) message.getContent();

        // Accumulate votes for the hashes.
        int numberOfHashes = response.getFrozenBlockHashes().size();
        for (int i = 0; i < numberOfHashes; i++) {
            long blockHeight = response.getFirstHashHeight() + i;
            FrozenBlockVoteTally voteTally = hashVotes.get(blockHeight);
            if (voteTally == null) {
                voteTally = new FrozenBlockVoteTally(blockHeight);
                hashVotes.put(blockHeight, voteTally);
            }

            byte[] hash = response.getFrozenBlockHashes().get(i);
            long determinationHeight = response.getDiscontinuityDeterminationHeights().get(i);
            voteTally.vote(message.getSourceNodeIdentifier(), hash, determinationHeight);
        }
    }

    public static synchronized long frozenEdgeHeight(byte[] frozenEdgeHash, AtomicLong determinationHeight) {

        // Determine the maximum number of votes we have at any level. This determines our consensus threshold.
        int maximumVotesAtAnyLevel = 0;
        for (FrozenBlockVoteTally tally : hashVotes.values()) {
            maximumVotesAtAnyLevel = Math.max(maximumVotesAtAnyLevel, tally.totalVotes());
        }

        // Determine the highest level at which consensus has been reached.
        long maximumConsensusHeight = -1;
        if (maximumVotesAtAnyLevel > 0) {
            for (long height : hashVotes.keySet()) {
                if (height > maximumConsensusHeight) {
                    FrozenBlockVoteTally tally = hashVotes.get(height);
                    if (tally.votesForWinner(frozenEdgeHash, determinationHeight) > maximumVotesAtAnyLevel / 2) {
                        maximumConsensusHeight = height;
                    }
                }
            }
        }

        return maximumConsensusHeight;
    }

    public static void fetchChainSection(long startHeight, long endHeight, byte[] endBlockHash) {

        // Only fetch the balance list if the section does not connect to previously frozen blocks.
        boolean fetchBalanceList = startHeight > BlockManager.highestBlockFrozen() + 1;

        Map<Long, Block> blocksToSave = new HashMap<>();
        int numberOfBlocksRequired = (int) (endHeight - startHeight + 1L);
        while (blocksToSave.size() < numberOfBlocksRequired && !UpdateUtil.shouldTerminate()) {

            // The chain is built from the end to the beginning so hashes can be confirmed.
            long minimumHeightAlreadyFetched = endHeight + 1;
            for (Long height : blocksToSave.keySet()) {
                minimumHeightAlreadyFetched = Math.min(minimumHeightAlreadyFetched, height);
            }
            final long requestEndHeight = Math.min(endHeight, minimumHeightAlreadyFetched - 1);

            Message message = new Message(MessageType.BlockRequest11, new BlockRequest(startHeight, requestEndHeight,
                    fetchBalanceList));
            Message.fetch(message, false, new MessageCallback() {
                @Override
                public void responseReceived(Message message) {

                    BlockResponse response = (BlockResponse) message.getContent();
                    Map<Long, Block> responseBlocks = blockMap(response.getBlocks());
                    long height = requestEndHeight;
                    boolean addedAllAvailable = false;
                    while (!addedAllAvailable) {
                        Block block = responseBlocks.get(height);
                        byte[] requiredHash = height == endHeight ? endBlockHash :
                                blocksToSave.get(height + 1).getPreviousBlockHash();
                        if (!ByteUtil.arraysAreEqual(block.getHash(), requiredHash)) {
                            block = null;
                            System.out.println("discarded block at height " + height + " due to incorrect hash");
                        }

                        if (block != null && height == startHeight && response.getInitialBalanceList() != null) {
                            block.setBalanceList(response.getInitialBalanceList());
                            if (block.getBalanceList() == null) {
                                System.err.println("discarded start block because balance list was not available");
                                block = null;
                            }
                        }

                        if (block == null) {
                            addedAllAvailable = true;
                        } else {
                            blocksToSave.put(height, block);
                            height--;
                        }
                    }
                }
            });

            if (blocksToSave.size() < numberOfBlocksRequired && !UpdateUtil.shouldTerminate()) {
                try {
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                }
            }
        }

        // Save the blocks.
        for (long height = startHeight; height <= endHeight; height++) {
            Block block = blocksToSave.get(height);
            if (height == startHeight) {
                BlockManager.freezeBlock(block, block.getPreviousBlockHash());
            } else {
                BlockManager.freezeBlock(block);
            }
        }
    }

    private static Map<Long, Block> blockMap(List<Block> blocks) {

        Map<Long, Block> map = new HashMap<>();
        for (Block block : blocks) {
            map.put(block.getBlockHeight(), block);
        }

        return map;
    }
}
