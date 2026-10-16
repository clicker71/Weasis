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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JPopupMenu;
import org.dcm4che3.data.Code;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;

/**
 * The catalogue and the study/instance mapping of the menu, the two parts a right click depends on
 * without a network: the catalogue is site configuration (a code is identified by value and
 * designator, PS3.3 Table 8.8-1) and the mapping turns the explorer model into the dcm4che datasets
 * a Create names.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class UpsMenuContributorTest {

  @AfterEach
  void clearState() {
    UpsExplorerState.clear();
  }

  @Test
  void the_catalogue_parses_a_scheme_code_and_meaning() {
    UpsMenuContributor.CatalogTask task =
        UpsMenuContributor.parseTask("99CLARUS:CXR-CLAHE:CLAHE filter");
    Code code = task.code();
    assertAll(
        () -> assertEquals("CXR-CLAHE", code.getCodeValue()),
        () -> assertEquals("99CLARUS", code.getCodingSchemeDesignator()),
        () -> assertEquals("CLAHE filter", code.getCodeMeaning()),
        () -> assertTrue(task.bodyPartGlob().isEmpty(), "no glob means any study"));
  }

  @Test
  void a_catalogue_entry_carries_an_optional_body_part_glob() {
    UpsMenuContributor.CatalogTask task =
        UpsMenuContributor.parseTask("99CLARUS:CXR-CLAHE:CLAHE filter:*lung*");
    assertAll(
        () -> assertEquals("CXR-CLAHE", task.code().getCodeValue()),
        () -> assertEquals("*lung*", task.bodyPartGlob()));
  }

  @Test
  void a_catalogue_entry_without_a_meaning_falls_back_to_the_code_value() {
    UpsMenuContributor.CatalogTask task = UpsMenuContributor.parseTask("99CLARUS:CXR-UPSCALE");
    assertAll(
        () -> assertEquals("CXR-UPSCALE", task.code().getCodeValue()),
        () -> assertEquals("CXR-UPSCALE", task.code().getCodeMeaning()));
  }

  @Test
  void a_malformed_catalogue_entry_is_skipped() {
    assertAll(
        () -> assertNull(UpsMenuContributor.parseTask("99CLARUS")),
        () -> assertNull(UpsMenuContributor.parseTask("")),
        () -> assertNull(UpsMenuContributor.parseTask("99CLARUS:")));
  }

  @Test
  void the_default_catalogue_is_the_published_demo_pack_vocabulary() {
    // A site that sets nothing gets the demo pack vocabulary, so the menu offers the two codes the
    // pack mints, both tied to the chest. The parse is asserted on the raw value to keep the test
    // headless.
    List<UpsMenuContributor.CatalogTask> catalog = UpsMenuContributor.catalog(null);
    assertAll(
        () -> assertEquals(2, catalog.size(), catalog.toString()),
        () -> assertTrue(hasCode(catalog, "CXR-CLAHE", "99CLARUS")),
        () -> assertTrue(hasCode(catalog, "CXR-UPSCALE", "99CLARUS")),
        () ->
            assertTrue(
                catalog.stream().allMatch(task -> "*lung*".equals(task.bodyPartGlob())),
                catalog.toString()));
  }

  @Test
  void a_site_catalogue_replaces_the_default_wholesale() {
    List<UpsMenuContributor.CatalogTask> catalog =
        UpsMenuContributor.catalog("99SITE:MY-CODE:My task");
    assertAll(
        () -> assertEquals(1, catalog.size(), catalog.toString()),
        () -> assertTrue(hasCode(catalog, "MY-CODE", "99SITE")),
        () -> assertFalse(hasCode(catalog, "CXR-CLAHE", "99CLARUS")));
  }

  @Test
  void a_malformed_entry_in_a_site_catalogue_is_skipped_and_the_rest_kept() {
    List<UpsMenuContributor.CatalogTask> catalog =
        UpsMenuContributor.catalog("broken,99SITE:MY-CODE:My task");
    assertAll(
        () -> assertEquals(1, catalog.size(), catalog.toString()),
        () -> assertTrue(hasCode(catalog, "MY-CODE", "99SITE")));
  }

  @Test
  void a_body_part_glob_applies_only_to_a_matching_study() {
    assertAll(
        () ->
            assertTrue(
                UpsMenuContributor.appliesTo(java.util.Set.of("LUNG"), "*lung*"),
                "LUNG matches *lung*"),
        () ->
            assertFalse(
                UpsMenuContributor.appliesTo(java.util.Set.of("KNEE"), "*lung*"),
                "KNEE does not match *lung*"),
        () ->
            assertFalse(
                UpsMenuContributor.appliesTo(java.util.Set.of(), "*lung*"),
                "an empty body-part set matches no glob"));
  }

  @Test
  void a_task_without_a_glob_applies_to_any_study() {
    assertAll(
        () -> assertTrue(UpsMenuContributor.appliesTo(java.util.Set.of("LUNG"), "")),
        () -> assertTrue(UpsMenuContributor.appliesTo(java.util.Set.of(), "")),
        () -> assertTrue(UpsMenuContributor.appliesTo(null, "")));
  }

  @Test
  void the_glob_is_case_insensitive() {
    assertAll(
        () -> assertTrue(UpsMenuContributor.glob("*lung*", "lung")),
        () -> assertTrue(UpsMenuContributor.glob("*LUNG*", "lung")),
        () -> assertTrue(UpsMenuContributor.glob("lung", "LUNG")),
        () -> assertFalse(UpsMenuContributor.glob("*lung*", "knee")));
  }

  @Test
  void the_demo_catalogue_offers_nothing_on_a_study_with_no_body_part() {
    // The knee: the default catalogue's two tasks both name "*lung*", so neither applies to a study
    // that names no body part, and the menu (which runs this same loop) offers no Run entry - which
    // is also why the whole "AI tasks" submenu disappears when no workitem is cached.
    long applicable =
        UpsMenuContributor.catalog(null).stream()
            .filter(task -> UpsMenuContributor.appliesTo(java.util.Set.of(), task.bodyPartGlob()))
            .count();
    assertEquals(0, applicable, "no demo task applies to an empty body-part set");
  }

  @Test
  void the_demo_catalogue_offers_both_tasks_on_a_lung_study() {
    long applicable =
        UpsMenuContributor.catalog(null).stream()
            .filter(
                task -> UpsMenuContributor.appliesTo(java.util.Set.of("LUNG"), task.bodyPartGlob()))
            .count();
    assertEquals(2, applicable, "both demo tasks apply to a lung study");
  }

  private static boolean hasCode(
      List<UpsMenuContributor.CatalogTask> catalog, String value, String scheme) {
    return catalog.stream()
        .anyMatch(
            task ->
                value.equals(task.code().getCodeValue())
                    && scheme.equals(task.code().getCodingSchemeDesignator()));
  }

  @Test
  void an_empty_cache_contributes_no_menu_item_beyond_the_entry_point() {
    // The contributor is asked on the EDT for a study it has never seen; nothing to add means no
    // submenu, so the built-in menu is left untouched.
    JPopupMenu menu = new JPopupMenu();
    new UpsMenuContributor().addMenuItems(menu, null, null, List.of());
    assertEquals(0, menu.getComponentCount());
  }
}
