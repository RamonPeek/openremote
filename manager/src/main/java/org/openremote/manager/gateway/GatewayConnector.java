/*
 * Copyright 2020, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.gateway;

import org.openremote.container.util.UniqueIdentifierGenerator;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.manager.concurrent.ManagerExecutorService;
import org.openremote.model.asset.*;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.value.Values;

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openremote.model.query.AssetQuery.Select.selectExcludeAll;

/**
 * Handles all communication between a gateway representation in the local manager and the actual gateway
 */
public class GatewayConnector {

    private static final Logger LOG = Logger.getLogger(GatewayConnector.class.getName());
    public static long SYNC_TIMEOUT_MILLIS = 10000; // How long to wait for a response before resending request
    public static long ASSET_CRUD_TIMEOUT_MILLIS = 10000; // How long to wait for a response when merging an asset before throwing an exception
    public static int MAX_SYNC_RETRIES = 5;
    public static int SYNC_ASSET_BATCH_SIZE = 20;
    public static final String ASSET_READ_EVENT_NAME_INITIAL = "INITIAL";
    public static final String ASSET_READ_EVENT_NAME_BATCH = "BATCH";
    protected final String realm;
    protected final String gatewayId;
    protected final AssetStorageService assetStorageService;
    protected final ManagerExecutorService executorService;
    protected final AssetProcessingService assetProcessingService;
    protected final Map<String, Asset> pendingAssetMerges = new HashMap<>();
    protected final AtomicReference<DeleteAssetsRequestEvent> pendingAssetDelete = new AtomicReference<>();
    protected List<AssetEvent> cachedAssetEvents;
    protected List<AttributeEvent> cachedAttributeEvents;
    protected Consumer<Object> gatewayMessageConsumer;
    protected Runnable disconnectRunnable;
    protected boolean disabled;
    protected boolean initialSyncInProgress;
    protected ScheduledFuture<?> syncProcessorFuture;
    Set<String> syncAssetIds;
    int syncIndex;
    int syncErrors;
    String expectedSyncResponseName;

    public GatewayConnector(
        AssetStorageService assetStorageService,
        AssetProcessingService assetProcessingService,
        ManagerExecutorService executorService,
        Asset gateway) {

        this.assetStorageService = assetStorageService;
        this.assetProcessingService = assetProcessingService;
        this.executorService = executorService;
        boolean disabled = gateway.getAttribute("disabled").flatMap(AssetAttribute::getValueAsBoolean).orElse(false);
        this.realm = gateway.getRealm();
        this.gatewayId = gateway.getId();
        this.disabled = disabled;
    }

    public void sendMessageToGateway(Object message) {
        try {
            if (gatewayMessageConsumer != null) {
                gatewayMessageConsumer.accept(message);
            }
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to send message to gateway: " + gatewayId, e);
        }
    }

    /**
     * Start the connector and initiate synchronisation of assets
     */
    synchronized public void connect(Consumer<Object> gatewayMessageConsumer, Runnable disconnectRunnable) {
        if (this.gatewayMessageConsumer != null) {
            return;
        }
        this.gatewayMessageConsumer = gatewayMessageConsumer;
        this.disconnectRunnable = disconnectRunnable;
        initialSyncInProgress = true;

        LOG.info("Gateway connector starting: Gateway ID=" + gatewayId);
        assetProcessingService.sendAttributeEvent(new AttributeEvent(gatewayId, "status", Values.create(ConnectionStatus.CONNECTING.name())), AttributeEvent.Source.GATEWAY);

        // Reinitialise state
        syncProcessorFuture = null;
        cachedAssetEvents = new ArrayList<>();
        cachedAttributeEvents = new ArrayList<>();
        syncAssetIds = null;
        syncIndex = 0;
        syncErrors = 0;

        startSync();
    }

    /**
     * Stop the connector and prevent further communication with the gateway
     */
    synchronized public void disconnect() {
        if (this.gatewayMessageConsumer == null) {
            return;
        }

        LOG.info("Gateway connector disconnected: Gateway ID=" + gatewayId);

        this.gatewayMessageConsumer = null;
        Runnable disconnectRunnable = this.disconnectRunnable;
        this.disconnectRunnable = null;
        initialSyncInProgress = false;
        pendingAssetMerges.clear();
        pendingAssetDelete.set(null);

        if (syncProcessorFuture != null) {
            syncProcessorFuture.cancel(true);
        }

        disconnectRunnable.run();
        assetProcessingService.sendAttributeEvent(new AttributeEvent(gatewayId, "status", Values.create(ConnectionStatus.DISCONNECTED.name())), AttributeEvent.Source.GATEWAY);
    }

    public boolean isConnected() {
        return gatewayMessageConsumer != null;
    }

    public boolean isInitialSyncInProgress() {
        return initialSyncInProgress;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public String getRealm() {
        return realm;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        if (this.disabled == disabled) {
            return;
        }

        this.disabled = disabled;

        if (disabled) {
            if (isConnected()) {
                disconnect();
            }
            LOG.info("Gateway connector disabled: Gateway ID=" + gatewayId);
            assetProcessingService.sendAttributeEvent(new AttributeEvent(gatewayId, "status", Values.create(ConnectionStatus.DISABLED.name())), AttributeEvent.Source.GATEWAY);
        } else {
            LOG.info("Gateway connector enabled: Gateway ID=" + gatewayId);
            assetProcessingService.sendAttributeEvent(new AttributeEvent(gatewayId, "status", Values.create(ConnectionStatus.DISCONNECTED.name())), AttributeEvent.Source.GATEWAY);
        }
    }

    synchronized protected void onGatewayEvent(SharedEvent e) {
        if (!isConnected()) {
            return;
        }

        if (initialSyncInProgress) {
            if (e instanceof AssetsEvent) {
                onSyncAssetsResponse((AssetsEvent) e);
            } else if (e instanceof AttributeEvent) {
                cachedAttributeEvents.add((AttributeEvent) e);
            } else if (e instanceof AssetEvent) {
                cachedAssetEvents.add((AssetEvent) e);
            }
        } else {
            if (e instanceof AssetEvent) {
                onAssetEvent((AssetEvent) e);
            } else if (e instanceof AttributeEvent) {
                onAttributeEvent((AttributeEvent) e);
            } else if (e instanceof DeleteAssetsResponseEvent) {
                onAssetDeleteResponseEvent((DeleteAssetsResponseEvent) e);
            }
        }
    }

    /**
     * Get list of gateway assets (get basic details and then batch load them to minimise load)
     */
    synchronized protected void startSync() {

        if (syncAborted()) {
            return;
        }

        expectedSyncResponseName = ASSET_READ_EVENT_NAME_INITIAL;
        sendMessageToGateway(new ReadAssetsEvent(
            ASSET_READ_EVENT_NAME_INITIAL, new AssetQuery().select(selectExcludeAll()).recursive(true)
        ));
        syncProcessorFuture = executorService.schedule(this::onSyncAssetsTimeout, SYNC_TIMEOUT_MILLIS);
    }

    /**
     * Called if a response isn't received from the gateway within {@link #SYNC_TIMEOUT_MILLIS}
     */
    synchronized protected void onSyncAssetsTimeout() {
        if (!isConnected()) {
            return;
        }

        LOG.info("Gateway sync timeout occurred: Gateway ID=" + gatewayId);
        syncErrors++;

        if (syncAborted()) {
            return;
        }

        if (syncAssetIds == null) {
            // Haven't received initial list of assets so retry
            startSync();
        } else {
            requestAssets();
        }
    }

    protected boolean syncAborted() {
        if (syncErrors == MAX_SYNC_RETRIES) {
            LOG.warning("Gateway sync max retries reached so disabling the gateway: Gateway ID=" + gatewayId);
            setDisabled(true);
            return true;
        }

        return false;
    }

    /**
     * Request assets in batches of {@link #SYNC_ASSET_BATCH_SIZE} to avoid overloading the gateway
     */
    protected void requestAssets() {

        if (syncAborted()) {
            return;
        }

        String[] requestAssetIds = syncAssetIds.stream().skip(syncIndex).limit(SYNC_ASSET_BATCH_SIZE).toArray(String[]::new);
        expectedSyncResponseName = ASSET_READ_EVENT_NAME_BATCH + syncIndex;

        LOG.fine("Synchronising gateway assets " + syncIndex+1 + "-" + syncIndex + requestAssetIds.length + " of " + syncAssetIds.size());

        sendMessageToGateway(
            new ReadAssetsEvent(
                expectedSyncResponseName,
                new AssetQuery()
                    .select(new AssetQuery.Select().excludeParentInfo(true).excludePath(true))
                    .ids(requestAssetIds)
            )
        );
        syncProcessorFuture = executorService.schedule(this::requestAssets, SYNC_TIMEOUT_MILLIS);
    }

    synchronized protected void onSyncAssetsResponse(AssetsEvent e) {
        if (!isConnected()) {
            return;
        }

        if (!expectedSyncResponseName.equalsIgnoreCase(e.getName())) {
            LOG.info("Unexpected response from gateway so ignoring (expected=" + expectedSyncResponseName + ", actual =" + e.getName() + "): " + e);
            return;
        }

        syncProcessorFuture.cancel(true);
        syncProcessorFuture = null;
        boolean isInitialResponse = ASSET_READ_EVENT_NAME_INITIAL.equalsIgnoreCase(e.getName());

        if (isInitialResponse) {

            Map<String, String> gatewayAssetIdParentIdMap = e.getAssets().stream()
                .collect(HashMap::new, (m,v)->m.put(v.getId(), v.getParentId()), HashMap::putAll);

            ToIntFunction<Asset> assetLevelExtractor = asset -> {
                int level = 0;
                String parentId = asset.getParentId();
                while (parentId != null) {
                    level++;
                    parentId = gatewayAssetIdParentIdMap.get(parentId);
                }
                return level;
            };

            syncAssetIds = e.getAssets()
                .stream()
                .sorted(Comparator.comparingInt(assetLevelExtractor))
                .map(Asset::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

            if (syncAssetIds.isEmpty()) {
                deleteObsoleteLocalAssets();
                onInitialSyncComplete();
                return;
            }

            requestAssets();

        } else {

            List<String> requestedAssetIds = syncAssetIds.stream().skip(syncIndex).limit(SYNC_ASSET_BATCH_SIZE).collect(Collectors.toList());
            List<Asset> returnedAssets = e.getAssets();

            // Remove any assets that have been deleted since requested
            cachedAssetEvents.removeIf(
                assetEvent -> {
                    boolean remove = requestedAssetIds.stream().anyMatch(id -> id.equals(assetEvent.getEntityId()) && assetEvent.getCause() == AssetEvent.Cause.DELETE);
                    if (remove) {
                        syncAssetIds.remove(assetEvent.getEntityId());
                        requestedAssetIds.remove(assetEvent.getEntityId());
                    }
                    return remove;
                });

            if (returnedAssets.size() != requestedAssetIds.size() || !returnedAssets.stream().allMatch(asset -> requestedAssetIds.contains(asset.getId()))) {
                LOG.warning("Retrieved gateway asset batch count or ID mismatch, attempting to re-send the request");
                syncErrors++;
                requestAssets();
                return;
            }

            // Merge returned assets ensuring the latest version of each is merged
            returnedAssets.stream()
                .map(returnedAsset -> {
                    final AtomicReference<Asset> latestAssetVersion = new AtomicReference<>(returnedAsset);
                    cachedAssetEvents.removeIf(
                        assetEvent -> {
                            boolean remove = assetEvent.getEntityId().equals(returnedAsset.getId()) && (assetEvent.getCause() == AssetEvent.Cause.UPDATE || assetEvent.getCause() == AssetEvent.Cause.READ);
                            if (remove && assetEvent.getAsset().getVersion() > latestAssetVersion.get().getVersion()) {
                                latestAssetVersion.set(assetEvent.getAsset());
                            }
                            return remove;
                        });
                    return latestAssetVersion.get();
                }).forEach(this::saveAssetLocally);


            // Request next batch or move on
            syncIndex += requestedAssetIds.size();
            if (syncIndex >= syncAssetIds.size()) {
                LOG.info("All requested gateway assets retrieved");

                Set<String> refreshAssets = new HashSet<>();

                cachedAssetEvents.forEach(
                    assetEvent -> {
                        if (assetEvent.getCause() == AssetEvent.Cause.DELETE) {
                            syncAssetIds.remove(assetEvent.getEntityId());
                        } else if (assetEvent.getCause() == AssetEvent.Cause.CREATE) {
                            syncAssetIds.add(assetEvent.getEntityId());
                            try {
                                saveAssetLocally(assetEvent.getAsset());
                            } catch (Exception ex) {
                                LOG.log(Level.SEVERE, "Failed to add new gateway asset (Gateway ID=" + gatewayId + ", Asset=" + assetEvent.getAsset(), ex);
                            }
                        } else {
                            refreshAssets.add(assetEvent.getEntityId());
                        }
                    }
                );

                deleteObsoleteLocalAssets();
                onInitialSyncComplete();

                // Refresh attributes that have changed
                cachedAttributeEvents.stream().filter(attributeEvent -> syncAssetIds.contains(attributeEvent.getEntityId())).collect(Collectors.groupingBy(AttributeEvent::getEntityId)).forEach(
                    (assetId, attributeEvents) -> {
                        LOG.info("1 or more gateway asset attribute values have changed so requesting latest values (Gateway ID=" + gatewayId + ", Asset ID=" + assetId);
                        sendMessageToGateway(new ReadAssetAttributesEvent(assetId, attributeEvents.stream().map(AttributeEvent::getAttributeName).toArray(String[]::new)));
                    }
                );

                // Refresh assets that have changed
                refreshAssets.forEach(id -> sendMessageToGateway(new ReadAssetEvent(id)));
            } else {
                requestAssets();
            }
        }
    }

    protected void deleteObsoleteLocalAssets() {

        // Find obsolete local assets
        List<Asset> localAssets = assetStorageService.findAll(
            new AssetQuery()
                .select(selectExcludeAll())
                .recursive(true)
                .parents(gatewayId)
        );

        // Delete obsolete assets
        List<String> obsoleteLocalAssetIds = localAssets.stream()
            .filter(localAsset -> !syncAssetIds.contains(localAsset.getId()))
            .map(Asset::getId).collect(Collectors.toList());

        if (!obsoleteLocalAssetIds.isEmpty()) {
            boolean deleted = deleteAssetsLocally(obsoleteLocalAssetIds);
            if (!deleted) {
                LOG.warning("Failed to delete obsolete local gateway assets; assets are not correctly synced");
            }
        }
    }

    protected void onInitialSyncComplete() {
        initialSyncInProgress = false;
        cachedAssetEvents.clear();
        cachedAttributeEvents.clear();
        assetProcessingService.sendAttributeEvent(new AttributeEvent(gatewayId, "status", Values.create(ConnectionStatus.CONNECTED.name())), AttributeEvent.Source.GATEWAY);
    }

    protected Asset mergeGatewayAsset(Asset asset, boolean isUpdate) {

        if (!isConnected() || isInitialSyncInProgress()) {
            String msg = "Gateway is not connected or initial sync in progress so cannot merge asset: Gateway ID=" + gatewayId + ", Asset ID=" + asset.getId();
            LOG.info(msg);
            throw new IllegalStateException(msg);
        }

        final String id = asset.getId();
        final String parentId = asset.getParentId();

        synchronized (pendingAssetMerges) {

            if (id != null && pendingAssetMerges.containsKey(id)) {
                String msg = "Gateway asset merge already pending for this asset: Gateway ID=" + gatewayId + ", Asset ID=" + id;
                LOG.info(msg);
                throw new IllegalStateException(msg);
            }


            if (id == null) {
                // Generate an ID to allow tracking the asset when it is returned from the gateway
                asset.setId(UniqueIdentifierGenerator.generateId());
            }

            if (gatewayId.equals(parentId)) {
                asset.setParentId(null);
            }

            sendMessageToGateway(new AssetEvent(isUpdate ? AssetEvent.Cause.UPDATE : AssetEvent.Cause.CREATE, asset, null));
            pendingAssetMerges.put(asset.getId(), null);
        }

        synchronized (asset.getId()) {
            try {
                asset.getId().wait(ASSET_CRUD_TIMEOUT_MILLIS);

                Asset mergedAsset;
                synchronized (pendingAssetMerges) {
                    mergedAsset = pendingAssetMerges.remove(asset.getId());
                }

                if (mergedAsset == null) {
                    throw new IllegalStateException("Gateway asset merge failed: Gateway ID=" + gatewayId + ", Asset ID=" + asset.getId());
                }

                return mergedAsset;

            } catch (InterruptedException e) {
                String msg = "Gateway asset merge interrupted: Gateway ID=" + gatewayId + ", Asset ID=" + id;
                LOG.info(msg);
                throw new IllegalStateException(msg);
            } finally {
                synchronized (pendingAssetMerges) {
                    pendingAssetMerges.remove(asset.getId());
                }
                asset.setId(id);
                asset.setParentId(parentId);
            }
        }
    }

    protected boolean deleteGatewayAssets(List<String> assetIds) {

        if (!isConnected() || isInitialSyncInProgress()) {
            String msg = "Gateway is not connected or initial sync in progress so cannot delete asset(s): Gateway ID=" + gatewayId + ", Asset IDs=" + Arrays.toString(assetIds.toArray());
            LOG.info(msg);
            throw new IllegalStateException(msg);
        }

        synchronized (pendingAssetDelete) {

            if (pendingAssetDelete.get() != null) {
                String msg = "Gateway asset delete already pending: Gateway ID=" + gatewayId + ", Asset IDs=" + Arrays.toString(assetIds.toArray());
                LOG.info(msg);
                throw new IllegalStateException(msg);
            }

            pendingAssetDelete.set(new DeleteAssetsRequestEvent(UniqueIdentifierGenerator.generateId(), new ArrayList<>(assetIds)));

            try {
                sendMessageToGateway(pendingAssetDelete.get());
                pendingAssetDelete.wait(ASSET_CRUD_TIMEOUT_MILLIS);
                if (pendingAssetDelete.get() != null) {
                    throw new IllegalStateException("Gateway asset delete failed: Gateway ID=" + gatewayId + ", Asset IDs=" + Arrays.toString(assetIds.toArray()));
                }
                return true;
            } catch (InterruptedException e) {
                String msg = "Gateway asset delete interrupted: Gateway ID=" + gatewayId + ", Asset IDs=" + Arrays.toString(assetIds.toArray());
                LOG.info(msg);
                throw new IllegalStateException(msg);
            } finally {
                pendingAssetDelete.set(null);
            }
        }
    }

    protected void onAssetDeleteResponseEvent(DeleteAssetsResponseEvent e) {

        synchronized (pendingAssetDelete) {
            if (pendingAssetDelete.get() == null) {
                return;
            }

            if (!pendingAssetDelete.get().getName().equals(e.getName())) {
                LOG.info("Gateway asset delete response name does not match request so ignoring");
                return;
            }

            if (e.isDeleted()) {
                pendingAssetDelete.set(null);
            }

            pendingAssetDelete.notify();
        }
    }

    synchronized protected void onAssetEvent(AssetEvent e) {

        switch (e.getCause()) {

            case CREATE:
            case READ:
            case UPDATE:
                Asset mergedAsset = saveAssetLocally(e.getAsset());

                synchronized (pendingAssetMerges) {
                    if (pendingAssetMerges.containsKey(e.getEntityId())) {
                        @SuppressWarnings("OptionalGetWithoutIsPresent")
                        Map.Entry<String, Asset> pendingAssetMergeEntry = pendingAssetMerges.entrySet().stream().filter(entry -> entry.getKey().equals(e.getEntityId())).findFirst().get();
                        String id = pendingAssetMergeEntry.getKey();
                        pendingAssetMergeEntry.setValue(mergedAsset);

                        //noinspection SynchronizationOnLocalVariableOrMethodParameter
                        synchronized (id) {
                            // Notify the waiting merge thread
                            id.notify();
                        }
                    }
                }
                break;
            case DELETE:
                try {
                    deleteAssetsLocally(Collections.singletonList(e.getEntityId()));
                } catch (Exception ex) {
                    LOG.log(Level.SEVERE, "Removing obsolete asset failed: " + e.getEntityId(), ex);
                }
                break;
        }
    }

    protected void onAttributeEvent(AttributeEvent e) {
        // Just push the event through the processing chain
        assetProcessingService.sendAttributeEvent(e, AttributeEvent.Source.GATEWAY);
    }

    protected Asset saveAssetLocally(Asset asset) {
        asset.setParentId(asset.getParentId() != null ? asset.getParentId() : gatewayId);
        asset.setRealm(realm);
        LOG.fine("Creating/updating gateway asset: Gateway ID=" + gatewayId + ", Asset ID=" + asset.getId());
        return assetStorageService.merge(asset, true, true, null);
    }

    protected boolean deleteAssetsLocally(List<String> assetIds) {
        LOG.fine("Removing gateway asset: Gateway ID=" + gatewayId + ", Asset IDs=" + Arrays.toString(assetIds.toArray()));
        return assetStorageService.delete(assetIds, true);
    }
}
