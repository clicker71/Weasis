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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.table.TableModel;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;
import org.weasis.dicom.aiw.UpsExplorerState.WorkitemView;
import org.weasis.dicom.web.ProcedureStepState;

/**
 * The rendering of the worklist drill-down: the row model of the listing and the output listing,
 * both without the dialog and without the network, so the display rule is asserted where it is
 * decided.
 */
@DisplayNameGeneration(ReplaceUnderscores.class)
class UpsWorklistDialogTest {

  @Test
  void an_empty_output_list_says_so_rather_than_showing_nothing() {
    assertAll(
        () ->
            assertTrue(
                UpsWorklistDialog.render(Map.of()).contains("0040,4033"),
                "the empty rendering names the sequence it read"),
        () -> assertTrue(UpsWorklistDialog.render(null).contains("0040,4033")));
  }

  @Test
  void the_output_listing_names_the_series_and_each_referenced_instance() {
    Map<String, List<Attributes>> outputs = new LinkedHashMap<>();
    outputs.put("1.2.3.4", List.of(sop("1.2.840.10008.5.1.4.1.1.481.3", "1.2.3.4.5")));

    String text = UpsWorklistDialog.render(outputs);

    assertAll(
        () -> assertTrue(text.contains("Series 1.2.3.4"), text),
        () -> assertTrue(text.contains("1.2.3.4.5"), text),
        () -> assertTrue(text.contains("1.2.840.10008.5.1.4.1.1.481.3"), text),
        () -> assertFalse(text.contains("0040,4033"), text));
  }

  @Test
  void the_row_model_carries_the_state_the_label_and_the_results_flag() {
    WorkitemView scheduled =
        new WorkitemView("1.2.3.a", ProcedureStepState.SCHEDULED, "CXR-CLAHE^99CLARUS", false);
    WorkitemView completed =
        new WorkitemView("1.2.3.b", ProcedureStepState.COMPLETED, "CXR-UPSCALE^99CLARUS", true);
    TableModel model = new UpsWorklistDialog.RowTableModel(List.of(scheduled, completed));

    assertAll(
        () -> assertEquals(2, model.getRowCount()),
        () -> assertEquals(3, model.getColumnCount()),
        () -> assertEquals("State", model.getColumnName(0)),
        () -> assertEquals("SCHEDULED", model.getValueAt(0, 0)),
        () -> assertEquals("CXR-CLAHE^99CLARUS", model.getValueAt(0, 1)),
        () -> assertEquals("", model.getValueAt(0, 2)),
        () -> assertEquals("COMPLETED", model.getValueAt(1, 0)),
        () -> assertEquals("ready", model.getValueAt(1, 2)));
  }

  @Test
  void a_row_without_a_state_or_a_label_still_renders() {
    WorkitemView broken = new WorkitemView("1.2.3.c", null, null, false);
    TableModel model = new UpsWorklistDialog.RowTableModel(List.of(broken));

    assertAll(
        () -> assertEquals("", model.getValueAt(0, 0)),
        // Without a label the row falls back to its identity, so the drill-down stays usable.
        () -> assertEquals("1.2.3.c", model.getValueAt(0, 1)));
  }

  @Test
  void an_empty_listing_has_no_row() {
    assertEquals(0, new UpsWorklistDialog.RowTableModel(List.of()).getRowCount());
  }

  @Test
  void the_label_of_a_row_is_never_null() {
    assertAll(
        () ->
            assertEquals(
                "1.2.3.d", UpsWorklistDialog.label(new WorkitemView("1.2.3.d", null, null, false))),
        () ->
            assertEquals(
                "CXR-CLAHE^99CLARUS",
                UpsWorklistDialog.label(
                    new WorkitemView(
                        "1.2.3.e", ProcedureStepState.SCHEDULED, "CXR-CLAHE^99CLARUS", false))));
  }

  private static Attributes sop(String sopClass, String sopInstance) {
    Attributes sop = new Attributes();
    sop.setString(Tag.ReferencedSOPClassUID, VR.UI, sopClass);
    sop.setString(Tag.ReferencedSOPInstanceUID, VR.UI, sopInstance);
    return sop;
  }
}
