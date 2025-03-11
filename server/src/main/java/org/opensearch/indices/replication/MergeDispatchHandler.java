/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.action.ActionListenerResponseHandler;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.IndexService;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.shard.IndexShardClosedException;
import org.opensearch.indices.IndicesService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportService;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MergeDispatchHandler {
    private static final Logger logger = LogManager.getLogger(MergeDispatchHandler.class);
    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private final TransportService transportService;

    public MergeDispatchHandler(ClusterService clusterService, IndicesService indicesService, TransportService transportService) {
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.transportService = transportService;
    }

    public void messageReceived(PublishMergedCheckpointRequest request, ActionListener<PublishMergedCheckpointResponse> publishListener) {
        final ShardId shardId = request.getCheckpoint().getShardId();
        try {
            IndexService indexService = indicesService.indexService(shardId.getIndex());
            if (indexService == null) {
                publishListener.onFailure(new IndexNotFoundException(shardId.getIndexName()));
                return;
            }
            IndexShard indexShard = indexService.getShardOrNull(shardId.id());
            if (indexShard == null || indexShard.routingEntry().primary() == false) {
                publishListener.onFailure(new IndexShardClosedException(shardId));
                return;
            }
            DiscoveryNodes nodes = clusterService.state().nodes();
            List<ShardRouting> replicaShards = indexShard.getReplicationGroup().getRoutingTable().replicaShards();
            List<DiscoveryNode> activeReplicaNodes = replicaShards.stream()
                .filter(ShardRouting::active)
                .map(s -> nodes.get(s.currentNodeId()))
                .toList();

            int inActiveReplicaCount = replicaShards.size() - activeReplicaNodes.size();

            if (activeReplicaNodes.isEmpty()) {
                publishListener.onResponse(new PublishMergedCheckpointResponse(replicaShards.size(), 0, inActiveReplicaCount));
                return;
            }

            AtomicInteger replicaCount = new AtomicInteger(activeReplicaNodes.size());
            AtomicInteger successfulCount = new AtomicInteger(0);
            AtomicInteger failureCount = new AtomicInteger(0);
            for (DiscoveryNode replicaNode : activeReplicaNodes) {
                logger.trace("pre copy merged segment to replica, request[{}] replica[{}]", request, replicaNode.getId());
                ActionListener<PublishMergedCheckpointResponse> listener = ActionListener.wrap(r -> {
                    successfulCount.incrementAndGet();
                    if (replicaCount.decrementAndGet() == 0) {
                        publishListener.onResponse(
                            new PublishMergedCheckpointResponse(
                                replicaShards.size(),
                                successfulCount.get(),
                                failureCount.get() + inActiveReplicaCount
                            )
                        );
                    }

                }, e -> {
                    failureCount.incrementAndGet();
                    if (replicaCount.decrementAndGet() == 0) {
                        publishListener.onResponse(
                            new PublishMergedCheckpointResponse(
                                replicaShards.size(),
                                successfulCount.get(),
                                failureCount.get() + inActiveReplicaCount
                            )
                        );
                    }
                });
                transportService.sendRequest(
                    replicaNode,
                    SegmentReplicationTargetService.Actions.PUBLISH_MERGE_FILES,
                    request,
                    new ActionListenerResponseHandler<>(listener, PublishMergedCheckpointResponse::new, ThreadPool.Names.GENERIC)
                );
            }
        } catch (Exception e) {
            publishListener.onFailure(e);
        }
    }
}
