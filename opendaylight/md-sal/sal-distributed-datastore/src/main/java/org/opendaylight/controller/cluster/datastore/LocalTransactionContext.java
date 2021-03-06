/*
 * Copyright (c) 2015 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.datastore;

import akka.actor.ActorSelection;
import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.util.Optional;
import java.util.SortedSet;
import org.opendaylight.controller.cluster.access.concepts.TransactionIdentifier;
import org.opendaylight.controller.cluster.datastore.messages.AbstractRead;
import org.opendaylight.controller.cluster.datastore.modification.AbstractModification;
import org.opendaylight.mdsal.common.api.ReadFailedException;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreReadTransaction;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreTransaction;
import org.opendaylight.mdsal.dom.spi.store.DOMStoreWriteTransaction;
import scala.concurrent.Future;

/**
 * Processes front-end transaction operations locally before being committed to the destination shard.
 * Instances of this class are used when the destination shard is local to the caller.
 *
 * @author Thomas Pantelis
 */
abstract class LocalTransactionContext extends AbstractTransactionContext {
    private final DOMStoreTransaction txDelegate;
    private final LocalTransactionReadySupport readySupport;
    private Exception operationError;

    LocalTransactionContext(final DOMStoreTransaction txDelegate, final TransactionIdentifier identifier,
            final LocalTransactionReadySupport readySupport) {
        super(identifier);
        this.txDelegate = Preconditions.checkNotNull(txDelegate);
        this.readySupport = readySupport;
    }

    protected abstract DOMStoreWriteTransaction getWriteDelegate();

    protected abstract DOMStoreReadTransaction getReadDelegate();

    @Override
    @SuppressWarnings("checkstyle:IllegalCatch")
    public void executeModification(final AbstractModification modification, final Boolean havePermit) {
        incrementModificationCount();
        if (operationError == null) {
            try {
                modification.apply(getWriteDelegate());
            } catch (Exception e) {
                operationError = e;
            }
        }
    }

    @Override
    public <T> void executeRead(final AbstractRead<T> readCmd, final SettableFuture<T> proxyFuture,
            final Boolean havePermit) {
        Futures.addCallback(readCmd.apply(getReadDelegate()), new FutureCallback<T>() {
            @Override
            public void onSuccess(final T result) {
                proxyFuture.set(result);
            }

            @Override
            public void onFailure(final Throwable failure) {
                proxyFuture.setException(failure instanceof Exception
                        ? ReadFailedException.MAPPER.apply((Exception) failure) : failure);
            }
        }, MoreExecutors.directExecutor());
    }

    private LocalThreePhaseCommitCohort ready() {
        logModificationCount();
        return readySupport.onTransactionReady(getWriteDelegate(), operationError);
    }

    @Override
    public Future<ActorSelection> readyTransaction(final Boolean havePermit,
            final Optional<SortedSet<String>> participatingShardNames) {
        final LocalThreePhaseCommitCohort cohort = ready();
        return cohort.initiateCoordinatedCommit(participatingShardNames);
    }

    @Override
    public Future<Object> directCommit(final Boolean havePermit) {
        final LocalThreePhaseCommitCohort cohort = ready();
        return cohort.initiateDirectCommit();
    }

    @Override
    public void closeTransaction() {
        txDelegate.close();
    }
}
