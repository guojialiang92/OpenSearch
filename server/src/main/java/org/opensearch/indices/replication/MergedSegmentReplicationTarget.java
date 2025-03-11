/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.opensearch.action.StepListener;
import org.opensearch.common.UUIDs;
import org.opensearch.common.util.CancellableThreads;
import org.opensearch.common.util.set.Sets;
import org.opensearch.core.action.ActionListener;
import org.opensearch.index.shard.IndexShard;
import org.opensearch.index.store.StoreFileMetadata;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;
import org.opensearch.indices.replication.common.ReplicationListener;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MergedSegmentReplicationTarget extends SegmentReplicationTarget {
    public final static String MERGE_REPLICATION_PREFIX = "merge.";
    private final String preCopyUUID;

    public MergedSegmentReplicationTarget(
        String preCopyUUID,
        IndexShard indexShard,
        ReplicationCheckpoint checkpoint,
        SegmentReplicationSource source,
        ReplicationListener listener
    ) {
        super(indexShard, checkpoint, source, listener);
        this.preCopyUUID = preCopyUUID;
    }

    @Override
    protected String getPrefix() {
        return MERGE_REPLICATION_PREFIX + UUIDs.randomBase64UUID() + ".";
    }

    @Override
    public String description() {
        return String.format(
            Locale.ROOT,
            "Id:[%d] preCopyUUID[%s] Checkpoint [%s] Shard:[%s] Source:[%s]",
            getId(),
            preCopyUUID,
            getCheckpoint(),
            shardId(),
            source.getDescription()
        );
    }

    public void startReplication(ActionListener<Void> listener) {
        state.setStage(SegmentReplicationState.Stage.REPLICATING);
        cancellableThreads.setOnCancel((reason, beforeCancelEx) -> {
            throw new CancellableThreads.ExecutionCancelledException("merge replication was canceled reason [" + reason + "]");
        });

        final StepListener<GetSegmentFilesResponse> getFilesListener = new StepListener<>();

        logger.trace(new ParameterizedMessage("Starting Merge Replication Target: {}", description()));

        state.setStage(SegmentReplicationState.Stage.GET_CHECKPOINT_INFO);
        List<StoreFileMetadata> filesToFetch;
        try {
            filesToFetch = getFiles(checkpoint.getMetadataMap(), checkpoint);
        } catch (Exception e) {
            listener.onFailure(e);
            return;
        }
        state.setStage(SegmentReplicationState.Stage.GET_FILES);
        cancellableThreads.checkForCancel();
        source.getMergedSegmentFiles(getId(), checkpoint, filesToFetch, indexShard, this::updateFileRecoveryBytes, getFilesListener);
        getFilesListener.whenComplete(response -> {
            state.setStage(SegmentReplicationState.Stage.FINALIZE_REPLICATION);
            cancellableThreads.checkForCancel();
            Set<String> pendingMergeFiles = Sets.newHashSet(multiFileWriter.getTempFileNames().values());
            multiFileWriter.renameAllTempFiles();
            indexShard.getPendingMergeFiles().addAll(pendingMergeFiles);
            listener.onResponse(null);
        }, listener::onFailure);
    }

    public String getPreCopyUUID() {
        return preCopyUUID;
    }
}
