/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionRequestValidationException;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;
import org.opensearch.indices.replication.checkpoint.ReplicationCheckpoint;

import java.io.IOException;
import java.util.Objects;

public class PublishMergedCheckpointRequest extends ActionRequest {
    private final String preCopyUUID;
    private final ReplicationCheckpoint checkpoint;

    public PublishMergedCheckpointRequest(String preCopyUUID, ReplicationCheckpoint checkpoint) {
        this.preCopyUUID = preCopyUUID;
        this.checkpoint = checkpoint;
    }

    public PublishMergedCheckpointRequest(StreamInput in) throws IOException {
        super(in);
        preCopyUUID = in.readString();
        this.checkpoint = new ReplicationCheckpoint(in);
    }

    @Override
    public ActionRequestValidationException validate() {
        return null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(preCopyUUID);
        checkpoint.writeTo(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PublishMergedCheckpointRequest that)) return false;
        return Objects.equals(preCopyUUID, that.preCopyUUID) && Objects.equals(checkpoint, that.checkpoint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(preCopyUUID, checkpoint);
    }

    @Override
    public String toString() {
        return "MergeFilesMetaRequest{" + "preCopyUUID='" + preCopyUUID + '\'' + ", checkpoint=" + checkpoint + '}';
    }

    public String getPreCopyUUID() {
        return preCopyUUID;
    }

    public ReplicationCheckpoint getCheckpoint() {
        return checkpoint;
    }
}
