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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.dicom.aiw.UpsExplorerState.WorkitemView;
import org.weasis.dicom.web.ProcedureStepState;

/**
 * The state-to-badge rule of the AI workflow, as a pure function of the cached rows. It is what a
 * thumbnail paints, so the cases are the ones the paint path can meet: a pending task, a finished
 * one with results, a finished one without, a cancelled one, and an unknown series.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class UpsExplorerStateTest {

  @AfterEach
  void clearState() {
    UpsExplorerState.clear();
  }

  private static WorkitemView row(ProcedureStepState state, boolean hasOutputs) {
    return new WorkitemView("1.2.3." + state + hasOutputs, state, "CXR-CLAHE^99CLARUS", hasOutputs);
  }

  @Test
  void a_scheduled_workitem_shows_the_task_badge() {
    assertEquals(
        java.util.Set.of(OtherIcon.AI_TASK),
        UpsExplorerState.badgesOf(List.of(row(ProcedureStepState.SCHEDULED, false)), false));
  }

  @Test
  void an_in_progress_workitem_shows_the_clock_badge() {
    assertEquals(
        java.util.Set.of(OtherIcon.AI_PROGRESS),
        UpsExplorerState.badgesOf(List.of(row(ProcedureStepState.IN_PROGRESS, false)), false));
  }

  @Test
  void an_available_study_with_no_workitem_shows_the_task_badge() {
    // The gap this task closes: a fresh eligible study shows the robot before any run, because AI
    // is available here even though no workitem exists yet.
    assertEquals(java.util.Set.of(OtherIcon.AI_TASK), UpsExplorerState.badgesOf(List.of(), true));
  }

  @Test
  void a_study_that_does_not_offer_ai_shows_no_badge() {
    // Not loaded through DICOMweb, or an empty catalogue: nothing to advertise.
    assertTrue(UpsExplorerState.badgesOf(List.of(), false).isEmpty());
  }

  @Test
  void a_completed_workitem_with_outputs_shows_the_result_badge() {
    assertEquals(
        java.util.Set.of(OtherIcon.AI_RESULT),
        UpsExplorerState.badgesOf(List.of(row(ProcedureStepState.COMPLETED, true)), false));
  }

  @Test
  void a_completed_workitem_without_outputs_shows_no_badge() {
    // COMPLETED without outputs names nothing to open, so there is no result to advertise.
    assertTrue(
        UpsExplorerState.badgesOf(List.of(row(ProcedureStepState.COMPLETED, false)), false)
            .isEmpty());
  }

  @Test
  void a_cancelled_workitem_shows_no_badge() {
    // CANCELED is terminal (PS3.4 Table CC.1.1-1): nothing pending, nothing to open.
    assertTrue(
        UpsExplorerState.badgesOf(List.of(row(ProcedureStepState.CANCELED, true)), false)
            .isEmpty());
  }

  @Test
  void a_result_outranks_a_running_or_queued_task() {
    // The outcome stays visible even while another task runs or is queued.
    assertEquals(
        java.util.Set.of(OtherIcon.AI_RESULT),
        UpsExplorerState.badgesOf(
            List.of(
                row(ProcedureStepState.IN_PROGRESS, false),
                row(ProcedureStepState.SCHEDULED, false),
                row(ProcedureStepState.COMPLETED, true)),
            true));
  }

  @Test
  void a_running_task_outranks_a_queued_one() {
    assertEquals(
        java.util.Set.of(OtherIcon.AI_PROGRESS),
        UpsExplorerState.badgesOf(
            List.of(
                row(ProcedureStepState.SCHEDULED, false),
                row(ProcedureStepState.IN_PROGRESS, false)),
            false));
  }

  @Test
  void a_queued_task_outranks_a_bare_available_study() {
    assertEquals(
        java.util.Set.of(OtherIcon.AI_TASK),
        UpsExplorerState.badgesOf(List.of(row(ProcedureStepState.SCHEDULED, false)), true));
  }

  @Test
  void an_unknown_state_is_not_pending_and_not_a_result() {
    assertTrue(UpsExplorerState.badgesOf(List.of(row(null, false)), false).isEmpty());
  }

  @Test
  void no_workitem_and_no_entry_shows_no_badge() {
    assertAll(
        () -> assertTrue(UpsExplorerState.badgesOf(null, false).isEmpty()),
        () -> assertTrue(UpsExplorerState.badgesOf(List.of(), false).isEmpty()));
  }

  @Test
  void a_lung_study_is_available_and_a_study_without_a_body_part_is_not() {
    // The body-part half of the indicator: the demo catalogue targets the chest, so a LUNG study
    // offers AI (the robot shows before any run) and a knee, which names no body part, does not.
    UpsExplorerState.StudyState lung =
        new UpsExplorerState.StudyState("1.2.3", ORIGIN, List.of(), java.util.Set.of("LUNG"));
    UpsExplorerState.StudyState knee =
        new UpsExplorerState.StudyState("1.2.3", ORIGIN, List.of(), java.util.Set.of());
    assertAll(
        () -> assertTrue(UpsExplorerState.available(lung), "LUNG is targeted by the catalogue"),
        () -> assertFalse(UpsExplorerState.available(knee), "no body part matches *lung*"),
        () ->
            assertFalse(
                UpsExplorerState.available(
                    new UpsExplorerState.StudyState(
                        "1.2.3", null, List.of(), java.util.Set.of("LUNG"))),
                "no DICOMweb origin means no AI"));
  }

  /** A DICOMweb origin for the availability tests; the base URL is never contacted. */
  private static final org.weasis.dicom.explorer.DicomWebOrigin ORIGIN =
      new org.weasis.dicom.explorer.DicomWebOrigin("http://example.invalid/dicomweb", null, null);

  @Test
  void an_unknown_series_has_no_badge_and_no_workitems() {
    UpsExplorerState state = UpsExplorerState.getInstance();
    assertAll(
        () -> assertTrue(state.badgesFor("1.2.3.unknown").isEmpty()),
        () -> assertTrue(state.workitemsFor("1.2.3.unknown").isEmpty()),
        () -> assertTrue(state.badgesFor(null).isEmpty()),
        () -> assertTrue(state.studyFor("1.2.3.unknown").isEmpty()));
  }

  /**
   * The AE Title the plugin sends is never blank, so the wire client's {@code encodeAet} cannot
   * throw and the refresh always reaches a subscription attempt (which then either succeeds or
   * routes to the poll). This is the regression guard for the phase-2 blocker: a blank AE made both
   * the Subscribe and the channel throw before any read ran.
   */
  @Test
  void the_requester_ae_title_is_never_blank() {
    String ae = UpsExplorerState.requesterAe();
    assertAll(
        () -> assertTrue(ae != null && !ae.isBlank(), "the AE Title is non-blank: [" + ae + "]"),
        // The AE Title field is 16 characters (PS3.3 Table 10-1).
        () -> assertTrue(ae.length() <= 16, "the AE Title fits the field: " + ae));
  }

  @Test
  void the_rows_of_a_listing_carry_the_state_the_label_and_the_outputs_flag() {
    Attributes scheduled = workitem("SCHEDULED", "CXR-CLAHE", "99CLARUS", false);
    Attributes completed = workitem("COMPLETED", "CXR-UPSCALE", "99CLARUS", true);

    List<WorkitemView> rows = UpsExplorerState.rows(List.of(scheduled, completed));

    assertAll(
        () -> assertEquals(2, rows.size()),
        () -> assertEquals(ProcedureStepState.SCHEDULED, rows.get(0).state()),
        () -> assertEquals("CXR-CLAHE^99CLARUS", rows.get(0).label()),
        () -> assertEquals(false, rows.get(0).hasOutputs()),
        () -> assertEquals(ProcedureStepState.COMPLETED, rows.get(1).state()),
        () -> assertEquals("CXR-UPSCALE^99CLARUS", rows.get(1).label()),
        () -> assertEquals(true, rows.get(1).hasOutputs()));
  }

  @Test
  void a_workitem_without_a_sop_instance_uid_is_skipped() {
    Attributes broken = new Attributes();
    broken.setString(Tag.ProcedureStepState, VR.CS, "SCHEDULED");
    assertTrue(UpsExplorerState.rows(List.of(broken)).isEmpty());
  }

  @Test
  void the_label_joins_the_code_value_and_the_scheme() {
    Attributes workitem = workitem("SCHEDULED", "CXR-CLAHE", "99CLARUS", false);
    assertEquals("CXR-CLAHE^99CLARUS", UpsExplorerState.label(workitem));
  }

  @Test
  void a_workitem_without_a_task_code_has_no_label() {
    Attributes workitem = new Attributes();
    workitem.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3");
    assertEquals(null, UpsExplorerState.label(workitem));
  }

  private static Attributes workitem(
      String state, String codeValue, String scheme, boolean withOutputs) {
    Attributes workitem = new Attributes();
    workitem.setString(Tag.SOPInstanceUID, VR.UI, "1.2.3." + codeValue);
    workitem.setString(Tag.StudyInstanceUID, VR.UI, "1.2.3.4");
    workitem.setString(Tag.ProcedureStepState, VR.CS, state);
    Sequence codes = workitem.newSequence(Tag.ScheduledWorkitemCodeSequence, 1);
    Attributes code = new Attributes();
    code.setString(Tag.CodeValue, VR.SH, codeValue);
    code.setString(Tag.CodingSchemeDesignator, VR.SH, scheme);
    code.setString(Tag.CodeMeaning, VR.LO, codeValue);
    codes.add(code);
    if (withOutputs) {
      // The outputs hang off the single (0074,1216) performed item (PS3.3 Table C.30.3-1).
      Attributes item = new Attributes();
      item.newSequence(Tag.OutputInformationSequence, 1).add(new Attributes());
      workitem.newSequence(Tag.UnifiedProcedureStepPerformedProcedureSequence, 1).add(item);
    }
    return workitem;
  }
}
