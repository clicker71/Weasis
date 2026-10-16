/*
 * Copyright (c) 2026 Weasis Team and other contributors.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License 2.0 which is available at https://www.eclipse.org/legal/epl-2.0, or the Apache
 * License, Version 2.0 which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */
package org.weasis.dicom.aiw;

import org.osgi.annotation.bundle.Header;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.media.data.SeriesBadgeProvider;
import org.weasis.dicom.explorer.ExplorerMenuContributor;

/**
 * The single OSGi wiring class of the module. Everything else it uses - {@link UpsService}, {@link
 * UpsExplorerState}, {@link UpsBadgeProvider}, {@link UpsMenuContributor}, {@link
 * UpsWorklistDialog} - is free of OSGi imports, so the port to the lighter plugin system the
 * maintainer announced replaces this class only.
 *
 * <p>It registers the two extension points as services: a {@link SeriesBadgeProvider} the core
 * activator tracks into {@code UICore.getSeriesBadgeProviders()}, and an {@link
 * ExplorerMenuContributor} the explorer activator tracks into {@code ExplorerMenuContributors}. The
 * {@code ups:} command service is self-registered by its own {@code @Component} declaration, so it
 * is not touched here.
 */
@Header(name = Constants.BUNDLE_ACTIVATOR, value = "${@class}") // NON-NLS
public class UpsAiwActivator implements BundleActivator {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpsAiwActivator.class);

  private UpsBadgeProvider badgeProvider;
  private UpsMenuContributor menuContributor;

  @Override
  public void start(BundleContext context) {
    badgeProvider = new UpsBadgeProvider();
    menuContributor = new UpsMenuContributor();
    context.registerService(SeriesBadgeProvider.class, badgeProvider, null);
    context.registerService(ExplorerMenuContributor.class, menuContributor, null);
    LOGGER.info("UPS-RS AI workflow registered (badge and menu contributors)");
  }

  @Override
  public void stop(BundleContext context) {
    // Dropping the service objects is the whole teardown: the registrations are released by the
    // framework, and the two registries drop the entries through their service listeners. The
    // cache is dropped with the plugin so a restart does not show a stale task.
    UpsExplorerState.clear();
    badgeProvider = null;
    menuContributor = null;
  }
}
