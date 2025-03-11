/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.opensearch.core.action.ActionResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.common.io.stream.StreamOutput;

import java.io.IOException;
import java.util.Objects;

public class PublishMergedCheckpointResponse extends ActionResponse {
    private final int total;
    private final int success;
    private final int failure;

    public PublishMergedCheckpointResponse(int total, int success, int failure) {
        this.total = total;
        this.success = success;
        this.failure = failure;
    }

    public PublishMergedCheckpointResponse(StreamInput in) throws IOException {
        super(in);
        total = in.readInt();
        success = in.readInt();
        failure = in.readInt();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeInt(total);
        out.writeInt(success);
        out.writeInt(failure);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PublishMergedCheckpointResponse that)) return false;
        return total == that.total && success == that.success && failure == that.failure;
    }

    @Override
    public int hashCode() {
        return Objects.hash(total, success, failure);
    }

    @Override
    public String toString() {
        return "PublishMergedCheckpointResponse{" + "total=" + total + ", success=" + success + ", failure=" + failure + '}';
    }

    public int getTotal() {
        return total;
    }

    public int getSuccess() {
        return success;
    }

    public int getFailure() {
        return failure;
    }
}
