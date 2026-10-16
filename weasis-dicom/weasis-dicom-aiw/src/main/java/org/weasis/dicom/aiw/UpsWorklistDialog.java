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

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.gui.util.GuiExecutor;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.IdentityMask;
import org.weasis.core.api.media.data.TagW;
import org.weasis.dicom.aiw.UpsExplorerState.StudyState;
import org.weasis.dicom.aiw.UpsExplorerState.WorkitemView;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomWebOrigin;
import org.weasis.dicom.explorer.main.DicomExplorer;
import org.weasis.dicom.explorer.main.DicomTaskManager;

/**
 * The v1 drill-down of the AI workflow: the cached workitems of a study and, for the selected one,
 * the results its Output Information Sequence (0040,4033) names (PS3.18 §11.5, RAD-83).
 *
 * <p>The listing comes from {@link UpsExplorerState}'s cache; loading the outputs - the one place
 * with real UPS I/O - runs on a background task of the explorer, so the EDT only builds and paints.
 * A workitem label carries patient identity, so it is rendered through the display mask in force,
 * the same path the rest of the explorer uses, never as raw text.
 */
public final class UpsWorklistDialog extends JDialog {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpsWorklistDialog.class);

  private final String studyUID;
  private final List<WorkitemView> workitems;
  private final JTextArea outputs = new JTextArea();
  private final JLabel status = new JLabel(" ");

  private UpsWorklistDialog(String studyUID, List<WorkitemView> workitems) {
    super(GuiUtils.getUICore().getApplicationWindow(), "AI tasks", ModalityType.MODELESS);
    this.studyUID = studyUID;
    this.workitems = List.copyOf(workitems);
    build();
  }

  /** Opens the dialog for a study, on the EDT. */
  public static void showFor(String studyUID, List<WorkitemView> workitems) {
    GuiUtils.showCenterScreen(new UpsWorklistDialog(studyUID, workitems));
  }

  private void build() {
    setLayout(new BorderLayout(6, 6));
    JTable table = new JTable(new RowTableModel(workitems));
    table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    table
        .getSelectionModel()
        .addListSelectionListener(
            event -> {
              if (!event.getValueIsAdjusting()) {
                loadOutputs(table.getSelectedRow());
              }
            });

    outputs.setEditable(false);
    outputs.setLineWrap(true);
    outputs.setWrapStyleWord(true);

    JSplitPane split =
        new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(outputs));
    split.setResizeWeight(0.5);
    add(split, BorderLayout.CENTER);

    JPanel south = new JPanel(new BorderLayout());
    south.add(status, BorderLayout.CENTER);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    JButton close = new JButton("Close");
    close.addActionListener(event -> dispose());
    buttons.add(close);
    south.add(buttons, BorderLayout.EAST);
    add(south, BorderLayout.SOUTH);

    setPreferredSize(new Dimension(720, 420));
    pack();
  }

  /** Loads the outputs of the selected row off the EDT and shows the result UIDs. */
  private void loadOutputs(int row) {
    if (row < 0 || row >= workitems.size()) {
      outputs.setText("");
      return;
    }
    WorkitemView workitem = workitems.get(row);
    DicomTaskManager manager = taskManager();
    if (manager == null) {
      // No explorer task manager (a headless run): say so rather than reading on the EDT.
      status.setText("No background task manager: cannot load the results");
      return;
    }
    status.setText("Loading the results of " + label(workitem) + " ...");
    manager.executeGeneralTask("UPS-RS outputs " + workitem.workitemUid(), () -> load(row));
  }

  private void load(int row) {
    String text = loadText(workitems.get(row));
    GuiExecutor.execute(
        () -> {
          outputs.setText(text);
          status.setText(" ");
        });
  }

  private String loadText(WorkitemView workitem) {
    DicomWebOrigin origin = origin();
    if (origin == null) {
      return "The study has no DICOMweb origin.";
    }
    try {
      return render(UpsExplorerState.service(origin).loadOutputs(workitem.workitemUid()));
    } catch (IOException e) {
      LOGGER.warn("UPS-RS outputs failed on {}", workitem.workitemUid(), e);
      return "Could not load the results: " + e.getMessage();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "Interrupted while loading the results.";
    }
  }

  private DicomWebOrigin origin() {
    StudyState study = UpsExplorerState.getInstance().studyFor(studyUID).orElse(null);
    return study != null ? study.origin() : UpsExplorerState.originOf(studyUID);
  }

  /**
   * Renders the outputs of a workitem, one block per series: the Series Instance UID and, under it,
   * the referenced SOP Class and SOP Instance UIDs (PS3.18 §11.5). A performer that names no series
   * (PS3.3 Table C.30.3-1 does not require it here) groups under the empty key, which is shown as
   * "unnamed" rather than as a missing UID.
   */
  static String render(Map<String, List<Attributes>> outputs) {
    if (outputs == null || outputs.isEmpty()) {
      return "No results named by this workitem (0040,4033 is empty).";
    }
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, List<Attributes>> entry : outputs.entrySet()) {
      String series =
          entry.getKey().isEmpty() ? "(series not named by the performer)" : entry.getKey();
      builder.append("Series ").append(series).append('\n');
      for (Attributes sop : entry.getValue()) {
        builder
            .append("  SOP ")
            .append(sop.getString(Tag.ReferencedSOPInstanceUID))
            .append("  class ")
            .append(sop.getString(Tag.ReferencedSOPClassUID))
            .append('\n');
      }
    }
    return builder.toString();
  }

  /**
   * The label of a row through the display mask in force, so identifying text is never rendered raw
   * here while the rest of the explorer masks it. The mask key is the workitem identity tag, which
   * an unclassifying profile leaves untouched.
   */
  static String label(WorkitemView workitem) {
    String text = workitem.label() == null ? workitem.workitemUid() : workitem.label();
    try {
      return IdentityMask.maskText(TagD.get(Tag.ScheduledProcedureStepID), text);
    } catch (RuntimeException e) {
      return text;
    }
  }

  private static DicomTaskManager taskManager() {
    DataExplorerView view = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
    return view instanceof DicomExplorer explorer ? explorer.getTaskManager() : null;
  }

  /** The row model of the listing: state, task label and whether the workitem names results. */
  static final class RowTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {"State", "Task", "Results"};
    private static final long serialVersionUID = 1L;

    private final transient List<WorkitemView> rows = new ArrayList<>();

    RowTableModel(List<WorkitemView> workitems) {
      rows.addAll(workitems);
    }

    @Override
    public int getRowCount() {
      return rows.size();
    }

    @Override
    public int getColumnCount() {
      return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
      return COLUMNS[column];
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
      WorkitemView workitem = rows.get(rowIndex);
      return switch (columnIndex) {
        case 0 -> workitem.state() == null ? "" : workitem.state().code();
        case 1 -> label(workitem);
        default -> workitem.hasOutputs() ? "ready" : "";
      };
    }
  }

  /** The tag whose classification the label follows; kept named so the display rule is explicit. */
  static TagW labelTag() {
    return TagD.get(Tag.ScheduledProcedureStepID);
  }
}
