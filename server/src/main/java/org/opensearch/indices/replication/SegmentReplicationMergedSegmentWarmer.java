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
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentReader;
import org.opensearch.common.UUIDs;
import org.opensearch.core.index.shard.ShardId;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.engine.EngineConfig;
import org.opensearch.index.store.Store;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.transport.client.Client;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SegmentReplicationMergedSegmentWarmer implements IndexWriter.IndexReaderWarmer {
    private static final Logger logger = LogManager.getLogger(SegmentReplicationMergedSegmentWarmer.class);
    private final Client client;
    private final Store store;
    private final ShardId shardId;
    private final long primaryTerm;
    private final EngineConfig engineConfig;
    private final IndexSettings indexSettings;

    public SegmentReplicationMergedSegmentWarmer(Client client, Store store, EngineConfig engineConfig) {
        this.client = client;
        this.store = store;
        this.shardId = engineConfig.getShardId();
        this.primaryTerm = engineConfig.getPrimaryTermSupplier().getAsLong();
        this.engineConfig = engineConfig;
        this.indexSettings = engineConfig.getIndexSettings();
    }

    @Override
    public void warm(LeafReader reader) throws IOException {
        if (indexSettings.isMergedSegmentPreCopyEnable() == false) {
            return;
        }
        final SegmentCommitInfo info = ((SegmentReader) reader).getSegmentInfo();
        Map<String, StoreFileMetadata> segmentMetadataMap = store.getSegmentMetadataMap(info);
        long totalSize = segmentMetadataMap.values().stream().mapToLong(StoreFileMetadata::length).sum();
        if (totalSize < indexSettings.getMergeSegmentPreCopySizeThreshold().getBytes()) {
            logger.trace(
                "skip pre copy merged segment: infoName {}, size {}, pre-copy merge segment size threshold {}",
                info.info.name,
                totalSize,
                indexSettings.getMergeSegmentPreCopySizeThreshold().getBytes()
            );
            return;
        }
        ReplicationCheckpoint replicationCheckpoint = new ReplicationCheckpoint(
            shardId,
            primaryTerm,
            0,
            0,
            totalSize,
            engineConfig.getCodec().getName(),
            segmentMetadataMap
        );
        final String preCopyUUID = UUIDs.randomBase64UUID();
        try {
            PublishMergedCheckpointRequest publishMergedCheckpointRequest = new PublishMergedCheckpointRequest(
                preCopyUUID,
                replicationCheckpoint
            );
            assert client != null : "client must not be null";
            long startTime = System.currentTimeMillis();
            PublishMergedCheckpointResponse response = client.execute(MergeDispatchAction.INSTANCE, publishMergedCheckpointRequest)
                .get(indexSettings.getMergeSegmentPreCopyTimeout().millis(), TimeUnit.MILLISECONDS);
            logger.info(
                () -> new ParameterizedMessage(
                    "pre copy merged segment: infoName [{}] preCopyUUID [{}] cost[{}ms] total replica count [{}] successful count [{}] failure count [{}]",
                    info.info.name,
                    preCopyUUID,
                    System.currentTimeMillis() - startTime,
                    response.getTotal(),
                    response.getSuccess(),
                    response.getFailure()
                )
            );
        } catch (Exception e) {
            logger.warn("pre copy merged segment generate exception", e);
        }
    }
}
