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

import java.util.Set;
import org.weasis.core.api.media.data.SeriesBadgeProvider;
import org.weasis.core.api.util.ResourceUtil.ResourceIconPath;

/**
 * The AI workflow badge of a series thumbnail: the task icon while a workitem of the study is
 * pending, the result icon once its results are ready to open.
 *
 * <p>This is a plain class on purpose. It is consulted on every paint of a thumbnail (via {@code
 * UICore.getSeriesBadgeProviders()}), so it answers from {@link UpsExplorerState}'s cache alone and
 * never touches the network or blocks; the single OSGi wiring class registers it as a service, and
 * in Weasis 5 only that class changes.
 */
public class UpsBadgeProvider implements SeriesBadgeProvider {

  @Override
  public Set<ResourceIconPath> getBadges(String seriesUID) {
    // The menu is what normally fills the cache, so a study the doctor has not right-clicked shows
    // no badge. resolveIfUnknown records the series once (it only hands the network to the task
    // manager, documented EDT-safe), which is what lets a fresh eligible study show the robot on
    // load; after that first pass badgesFor is a pure cache read and the paint path never blocks.
    UpsExplorerState.getInstance().resolveIfUnknown(seriesUID);
    // badgesFor is a pure cache read and returns an empty set for an unknown series, so the paint
    // path never has to guard against null.
    return UpsExplorerState.getInstance().badgesFor(seriesUID);
  }
}
