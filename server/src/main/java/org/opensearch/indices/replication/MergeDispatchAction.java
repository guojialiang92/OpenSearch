/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.indices.replication;

import org.opensearch.action.ActionType;

public class MergeDispatchAction extends ActionType<PublishMergedCheckpointResponse> {
    public static final String NAME = "internal:index/shard/replication/merge_dispatch";
    public static final MergeDispatchAction INSTANCE = new MergeDispatchAction();

    protected MergeDispatchAction() {
        super(NAME, PublishMergedCheckpointResponse::new);
    }
}
