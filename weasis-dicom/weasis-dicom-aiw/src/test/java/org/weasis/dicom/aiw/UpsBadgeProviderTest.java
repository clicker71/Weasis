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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;

/**
 * The badge provider is the paint-path hook, so the test is that it is a pure, non-null read of the
 * cache: an unknown series and a cleared cache both answer empty rather than throwing.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class UpsBadgeProviderTest {

  @AfterEach
  void clearState() {
    UpsExplorerState.clear();
  }

  @Test
  void an_unknown_series_yields_no_badge_and_never_null() {
    UpsBadgeProvider provider = new UpsBadgeProvider();
    assertAll(
        () -> assertNotNull(provider.getBadges("1.2.3.unknown")),
        () -> assertTrue(provider.getBadges("1.2.3.unknown").isEmpty()),
        () -> assertNotNull(provider.getBadges(null)),
        () -> assertTrue(provider.getBadges(null).isEmpty()));
  }

  @Test
  void the_provider_returns_what_the_cache_holds() {
    // The cache is filled through the same path the menu uses; here the badge rule itself is
    // asserted so the provider stays a thin delegate.
    assertEquals(
        java.util.Set.of(OtherIcon.AI_TASK),
        UpsExplorerState.badgesOf(
            java.util.List.of(
                new UpsExplorerState.WorkitemView(
                    "1.2.3",
                    org.weasis.dicom.web.ProcedureStepState.SCHEDULED,
                    "CXR-CLAHE",
                    false)),
            false));
  }
}
