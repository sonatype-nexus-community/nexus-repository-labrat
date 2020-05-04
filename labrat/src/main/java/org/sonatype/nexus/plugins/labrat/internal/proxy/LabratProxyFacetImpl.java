/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2020-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.plugins.labrat.internal.proxy;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.plugins.labrat.internal.AssetKind;
import org.sonatype.nexus.plugins.labrat.internal.util.LabratDataAccess;
import org.sonatype.nexus.plugins.labrat.internal.util.LabratPathUtils;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.proxy.ProxyFacetSupport;
import org.sonatype.nexus.repository.storage.Asset;
import org.sonatype.nexus.repository.storage.Bucket;
import org.sonatype.nexus.repository.storage.Component;
import org.sonatype.nexus.repository.storage.StorageFacet;
import org.sonatype.nexus.repository.storage.StorageTx;
import org.sonatype.nexus.repository.storage.TempBlob;
import org.sonatype.nexus.repository.transaction.TransactionalStoreBlob;
import org.sonatype.nexus.repository.transaction.TransactionalTouchBlob;
import org.sonatype.nexus.repository.transaction.TransactionalTouchMetadata;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.transaction.UnitOfWork;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.plugins.labrat.internal.util.LabratPathUtils.ASSET_FILENAME;
import static org.sonatype.nexus.plugins.labrat.internal.util.LabratPathUtils.PACKAGE_FILENAME;
import static org.sonatype.nexus.repository.storage.AssetEntityAdapter.P_ASSET_KIND;

/**
 * Labrat {@link ProxyFacet} implementation.
 *
 * @since 0.0.1
 */
@Named
public class LabratProxyFacetImpl
    extends ProxyFacetSupport
    implements LabratProxyFacet
{
  private LabratPathUtils labratPathUtils;

  private LabratDataAccess labratDataAccess;

  @Inject
  public LabratProxyFacetImpl(final LabratPathUtils labratPathUtils,
                             final LabratDataAccess labratDataAccess)
  {
    this.labratPathUtils = checkNotNull(labratPathUtils);
    this.labratDataAccess = checkNotNull(labratDataAccess);
  }

  // HACK: Workaround for known CGLIB issue, forces an Import-Package for org.sonatype.nexus.repository.config
  @Override
  protected void doValidate(final Configuration configuration) throws Exception {
    super.doValidate(configuration);
  }

  @Nullable
  @Override
  protected Content getCachedContent(final Context context) {
    AssetKind assetKind = context.getAttributes().require(AssetKind.class);
    TokenMatcher.State matcherState = labratPathUtils.matcherState(context);
    switch (assetKind) {
      case PACKAGES:
        return getAsset(labratPathUtils.buildAssetPath(matcherState, PACKAGE_FILENAME));
      case ARCHIVE:
        return getAsset(labratPathUtils.buildAssetPath(matcherState, ASSET_FILENAME));
      default:
        throw new IllegalStateException("Received an invalid AssetKind of type: " + assetKind.name());
    }
  }

  @TransactionalTouchBlob
  public Content getAsset(final String assetPath) {
    StorageTx tx = UnitOfWork.currentTx();

    Asset asset = labratDataAccess.findAsset(tx, tx.findBucket(getRepository()), assetPath);
    if (asset == null) {
      return null;
    }
    return labratDataAccess.toContent(asset, tx.requireBlob(asset.requireBlobRef()));
  }

  @Override
  protected Content store(final Context context, final Content content) throws IOException {
    AssetKind assetKind = context.getAttributes().require(AssetKind.class);
    TokenMatcher.State matcherState = labratPathUtils.matcherState(context);
    switch (assetKind) {
      case PACKAGES:
        return putMetadata(content,
            assetKind,
            labratPathUtils.buildAssetPath(matcherState, PACKAGE_FILENAME));
      case ARCHIVE:
        return putLabratPackage(content,
            assetKind,
            labratPathUtils.buildAssetPath(matcherState, ASSET_FILENAME));
      default:
        throw new IllegalStateException("Received an invalid AssetKind of type: " + assetKind.name());
    }
  }

  private Content putLabratPackage(final Content content,
                                  final AssetKind assetKind,
                                  final String assetPath)
      throws IOException
  {
    StorageFacet storageFacet = facet(StorageFacet.class);

    try (TempBlob tempBlob = storageFacet.createTempBlob(content.openInputStream(), LabratDataAccess.HASH_ALGORITHMS)) {
      Component component = findOrCreateComponent(assetPath);

      return findOrCreateAsset(tempBlob, content, assetKind, assetPath, component);
    }
  }

  @TransactionalStoreBlob
  protected Component findOrCreateComponent(final String assetPath) {
    StorageTx tx = UnitOfWork.currentTx();
    Bucket bucket = tx.findBucket(getRepository());

    Component component = labratDataAccess.findComponent(tx,
        getRepository(),
        assetPath);

    if (component == null) {
      component = tx.createComponent(bucket, getRepository().getFormat())
          .name(assetPath);
    }
    tx.saveComponent(component);

    return component;
  }

  private Content putMetadata(final Content content,
                              final AssetKind assetKind,
                              final String assetPath) throws IOException
  {
    StorageFacet storageFacet = facet(StorageFacet.class);

    try (TempBlob tempBlob = storageFacet.createTempBlob(content.openInputStream(), LabratDataAccess.HASH_ALGORITHMS)) {
      return findOrCreateAsset(tempBlob, content, assetKind, assetPath, null);
    }
  }

  @TransactionalStoreBlob
  protected Content findOrCreateAsset(final TempBlob tempBlob,
                                      final Content content,
                                      final AssetKind assetKind,
                                      final String assetPath,
                                      final Component component) throws IOException
  {
    StorageTx tx = UnitOfWork.currentTx();
    Bucket bucket = tx.findBucket(getRepository());

    Asset asset = labratDataAccess.findAsset(tx, bucket, assetPath);

    if (assetKind.equals(AssetKind.ARCHIVE)) {
      if (asset == null) {
        asset = tx.createAsset(bucket, component);
        asset.name(assetPath);
        asset.formatAttributes().set(P_ASSET_KIND, assetKind.name());
      }
    } else {
      if (asset == null) {
        asset = tx.createAsset(bucket, getRepository().getFormat());
        asset.name(assetPath);
        asset.formatAttributes().set(P_ASSET_KIND, assetKind.name());
      }
    }

    return labratDataAccess.saveAsset(tx, asset, tempBlob, content);
  }

  @Override
  protected void indicateVerified(final Context context, final Content content, final CacheInfo cacheInfo)
      throws IOException
  {
    setCacheInfo(content, cacheInfo);
  }

  @TransactionalTouchMetadata
  public void setCacheInfo(final Content content, final CacheInfo cacheInfo) throws IOException {
    StorageTx tx = UnitOfWork.currentTx();
    Asset asset = Content.findAsset(tx, tx.findBucket(getRepository()), content);
    if (asset == null) {
      log.debug(
          "Attempting to set cache info for non-existent Labrat asset {}", content.getAttributes().require(Asset.class)
      );
      return;
    }
    log.debug("Updating cacheInfo of {} to {}", asset, cacheInfo);
    CacheInfo.applyToAsset(asset, cacheInfo);
    tx.saveAsset(asset);
  }

  @Override
  protected String getUrl(@Nonnull final Context context) {
    return context.getRequest().getPath().substring(1);
  }
}
