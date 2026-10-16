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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Code;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaElement;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.dicom.aiw.UpsExplorerState.WorkitemView;
import org.weasis.dicom.codec.DicomImageElement;
import org.weasis.dicom.codec.DicomSeries;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.DicomWebOrigin;
import org.weasis.dicom.explorer.ExplorerMenuContributor;
import org.weasis.dicom.explorer.main.DicomExplorer;
import org.weasis.dicom.explorer.main.DicomTaskManager;
import org.weasis.dicom.web.ProcedureStepState;

/**
 * The "AI tasks" submenu of the series context menu: the Task Requestor half of the plugin. It
 * offers the deployment's task codes (the site catalogue) as Run entries, and the cached workitems
 * of the study as Cancel and view entries.
 *
 * <p>The menu is rebuilt on every right click on the EDT, so this class reads the cache and the
 * catalogue only and never waits on the network: Run, Cancel and Refresh each hand their work to
 * the explorer's {@link DicomTaskManager}, which is off the EDT. It is a plain class with no OSGi
 * import; the single wiring class registers it as a service, and in Weasis 5 only that class
 * changes.
 *
 * <p>The catalogue is site configuration, not something read off the study: a task code is the
 * deployment's own vocabulary (PS3.3 Table 8.8-1), so it comes from the {@code weasis.ups.catalog}
 * preference as {@code scheme:code:meaning[:bodypart-glob]} entries, comma separated, and defaults
 * to the codes the published demo pack mints. The optional 4th component ties the task to the body
 * part it targets (a knee study gets no chest task), and an absent one means the task applies to
 * any study.
 */
public class UpsMenuContributor implements ExplorerMenuContributor {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpsMenuContributor.class);

  /** Preference holding the catalogue, e.g. {@code 99CLARUS:CXR-CLAHE:CLAHE filter:*lung*, ...}. */
  static final String CATALOG_PREFERENCE = "weasis.ups.catalog"; // NON-NLS

  private static final String DEFAULT_CATALOG =
      "99CLARUS:CXR-CLAHE:CLAHE filter:*lung*,99CLARUS:CXR-UPSCALE:Upscaled x2:*lung*"; // NON-NLS

  /**
   * One catalogue entry: the task code and the body-part glob it applies to.
   *
   * @param code the Scheduled Workitem Code (PS3.3 Table 8.8-1) the Run entry creates
   * @param bodyPartGlob the {@code BodyPartExamined} pattern the task targets, blank when the task
   *     applies to any study (including one that names no body part)
   */
  record CatalogTask(Code code, String bodyPartGlob) {}

  @Override
  public void addMenuItems(
      JPopupMenu menu,
      DicomModel model,
      MediaSeries<? extends MediaElement> clicked,
      List<MediaSeries<? extends MediaElement>> selection) {
    if (menu == null || model == null || clicked == null) {
      return;
    }
    MediaSeriesGroup study = model.getParent(clicked, DicomModel.study);
    if (study == null) {
      return;
    }
    String studyUID = string(study, Tag.StudyInstanceUID);
    if (studyUID == null) {
      return;
    }
    UpsExplorerState state = UpsExplorerState.getInstance();
    // A study that was closed keeps no watch: the menu is the only place that knows the model, so
    // it prunes the cache here rather than a listener the module would have to install. The first
    // right click on a study's series records it and starts its refresh; neither call waits on the
    // origin.
    state.prune(model);
    state.observe(model, study, UpsExplorerState.seriesUidsOf(model, study));
    Set<String> bodyParts = UpsExplorerState.bodyPartsOf(model, study);
    String seriesUID = string(clicked, Tag.SeriesInstanceUID);
    // Every series of a study carries the same rows, so the clicked one is enough: a selection
    // groups by study by construction, and the submenu is about that study's tasks. On the first
    // click the rows are still empty and the refresh fills them.
    List<WorkitemView> workitems = seriesUID == null ? List.of() : state.workitemsFor(seriesUID);

    List<JMenuItem> items = new ArrayList<>();
    // Only the tasks the study's body parts qualify for are offered: a study with no body part
    // gets no Run entry for a task that names a glob (the demo tasks both name "*lung*", so both
    // disappear on a knee).
    for (CatalogTask task : catalog()) {
      if (!appliesTo(bodyParts, task.bodyPartGlob())) {
        continue;
      }
      JMenuItem run = runItem(model, study, clicked, selection, task.code(), state);
      if (run != null) {
        items.add(run);
      }
    }
    for (WorkitemView workitem : workitems) {
      if (isNonTerminal(workitem)) {
        JMenuItem cancel = cancelItem(model, study, studyUID, workitem);
        if (cancel != null) {
          items.add(cancel);
        }
      }
    }
    if (!workitems.isEmpty()) {
      items.add(viewItem(state, studyUID, workitems));
    }
    if (items.isEmpty()) {
      // Nothing to offer: no applicable Run entry and no cached workitem, so contribute nothing.
      // A non-eligible study therefore shows neither the robot nor an "AI tasks" entry, and Refresh
      // is not added here because there is no submenu for it to sit in.
      return;
    }
    items.add(refreshItem(state));
    JMenu submenu = new JMenu("AI tasks");
    for (JMenuItem item : items) {
      submenu.add(item);
    }
    menu.addSeparator();
    menu.add(submenu);
  }

  /**
   * A Run entry for a catalogue code. A Task Requestor never re-addresses a workitem that exists
   * (PS3.18 §11.4), so the entry is disabled while the study already has a non-terminal workitem of
   * that code, and the click creates one otherwise.
   */
  private JMenuItem runItem(
      DicomModel model,
      MediaSeriesGroup study,
      MediaSeries<? extends MediaElement> clicked,
      List<MediaSeries<? extends MediaElement>> selection,
      Code code,
      UpsExplorerState state) {
    String studyUID = string(study, Tag.StudyInstanceUID);
    if (studyUID == null) {
      return null;
    }
    JMenuItem item = new JMenuItem("Run " + code.getCodeMeaning());
    if (state.hasPending(studyUID, code)) {
      item.setEnabled(false);
      item.setToolTipText("A task of this code is already pending on the study");
      return item;
    }
    item.addActionListener(
        event -> {
          DicomTaskManager manager = taskManager();
          if (manager == null) {
            LOGGER.warn("UPS-RS: no explorer task manager, cannot create the task");
            return;
          }
          // The dcm4che study and instances are built off the EDT from the explorer model, so the
          // EDT only schedules.
          manager.executeGeneralTask(
              "UPS-RS create " + code.getCodeValue(),
              () -> createTask(model, study, clicked, selection, code));
        });
    return item;
  }

  private void createTask(
      DicomModel model,
      MediaSeriesGroup study,
      MediaSeries<? extends MediaElement> clicked,
      List<MediaSeries<? extends MediaElement>> selection,
      Code code) {
    DicomWebOrigin origin = DicomWebOrigin.of(model, study);
    if (origin == null) {
      LOGGER.warn("UPS-RS: the study was not loaded through DICOMweb, cannot create a task");
      return;
    }
    try {
      String studyUID = string(study, Tag.StudyInstanceUID);
      UpsService service = UpsExplorerState.service(origin);
      String workitem =
          service.run(code, study(study), instances(studyUID, series(clicked, selection)));
      LOGGER.info("UPS-RS: created workitem {} for {} on {}", workitem, code, studyUID);
      // The cache still shows the old study state; refresh it so the badge flips without waiting
      // for the poll interval.
      UpsExplorerState.getInstance().refreshNow();
    } catch (IOException e) {
      LOGGER.warn("UPS-RS create failed on {}", code, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** A Cancel entry, i.e. Request Cancellation (PS3.18 §11.8), off the EDT. */
  private JMenuItem cancelItem(
      DicomModel model, MediaSeriesGroup study, String studyUID, WorkitemView workitem) {
    JMenuItem item = new JMenuItem("Cancel " + labelOf(workitem));
    item.addActionListener(
        event -> {
          DicomTaskManager manager = taskManager();
          if (manager == null) {
            return;
          }
          manager.executeGeneralTask(
              "UPS-RS cancel " + workitem.workitemUid(),
              () -> cancel(model, study, workitem.workitemUid()));
        });
    return item;
  }

  private void cancel(DicomModel model, MediaSeriesGroup study, String workitemUid) {
    DicomWebOrigin origin = DicomWebOrigin.of(model, study);
    if (origin == null) {
      return;
    }
    try {
      UpsExplorerState.service(origin).cancel(workitemUid, null);
      UpsExplorerState.getInstance().refreshNow();
    } catch (IOException e) {
      LOGGER.warn("UPS-RS cancellation failed on {}", workitemUid, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private JMenuItem viewItem(
      UpsExplorerState state, String studyUID, List<WorkitemView> workitems) {
    JMenuItem item = new JMenuItem("Show tasks (" + workitems.size() + ")");
    item.addActionListener(event -> UpsWorklistDialog.showFor(studyUID, workitems));
    return item;
  }

  /**
   * A Refresh entry: re-reads the open studies on a background task and returns, so the EDT never
   * waits on the wire.
   */
  private JMenuItem refreshItem(UpsExplorerState state) {
    JMenuItem item = new JMenuItem("Refresh");
    item.addActionListener(event -> state.refreshNow());
    return item;
  }

  private static boolean isNonTerminal(WorkitemView workitem) {
    return workitem.state() == ProcedureStepState.SCHEDULED
        || workitem.state() == ProcedureStepState.IN_PROGRESS;
  }

  private static String labelOf(WorkitemView workitem) {
    return workitem.label() == null ? workitem.workitemUid() : workitem.label();
  }

  /** The catalogue of tasks, parsed from the site preference. Never null. */
  static List<CatalogTask> catalog() {
    return catalog(siteCatalog());
  }

  /** The catalogue of a raw preference value, comma separated; null and blank fall back. */
  static List<CatalogTask> catalog(String rawCatalog) {
    String value = rawCatalog == null || rawCatalog.isBlank() ? DEFAULT_CATALOG : rawCatalog;
    List<CatalogTask> tasks = new ArrayList<>();
    for (String entry : value.split(",")) {
      CatalogTask task = parseTask(entry);
      if (task != null) {
        tasks.add(task);
      }
    }
    return tasks;
  }

  /**
   * The site catalogue, from the {@code weasis.ups.catalog} preference. The read is guarded as a
   * whole because a headless run has no UI core to ask: the value a site sets is looked up in the
   * preferences first and in the system properties next, and the demo pack vocabulary is the
   * default so a deployment that sets nothing still offers its two codes.
   */
  static String siteCatalog() {
    try {
      String value = GuiUtils.getUICore().getSystemPreferences().getProperty(CATALOG_PREFERENCE);
      if (value != null && !value.isBlank()) {
        return value;
      }
    } catch (RuntimeException | LinkageError e) {
      LOGGER.debug("UPS-RS: no UI preferences, using the configured catalogue");
    }
    String property = System.getProperty(CATALOG_PREFERENCE);
    return property == null || property.isBlank() ? DEFAULT_CATALOG : property;
  }

  /**
   * One {@code scheme:code:meaning[:bodypart-glob]} entry, null when the entry is malformed.
   *
   * <p>The 4th component is OPTIONAL: absent (or blank) means the task applies to any study,
   * including one that names no body part. The meaning is the 3rd component and falls back to the
   * code value when blank, so a two-component entry stays valid.
   */
  static CatalogTask parseTask(String entry) {
    if (entry == null) {
      return null;
    }
    String[] parts = entry.trim().split(":");
    if (parts.length < 2 || parts[1].isBlank()) {
      return null;
    }
    String scheme = parts[0].trim();
    String value = parts[1].trim();
    String meaning = parts.length > 2 && !parts[2].isBlank() ? parts[2].trim() : value;
    String glob = parts.length > 3 ? parts[3].trim() : "";
    return new CatalogTask(new Code(value, scheme, null, meaning), glob);
  }

  /**
   * Whether a catalogue task applies to a study: true when the task names no body-part glob, or any
   * of the study's {@code BodyPartExamined} values glob-matches it. Body part is CS ({@code LUNG}),
   * so both sides are lower-cased before comparing.
   */
  static boolean appliesTo(Collection<String> studyBodyParts, String bodyPartGlob) {
    if (bodyPartGlob == null || bodyPartGlob.isBlank()) {
      return true;
    }
    if (studyBodyParts == null) {
      return false;
    }
    for (String bodyPart : studyBodyParts) {
      if (bodyPart != null && glob(bodyPartGlob, bodyPart)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A case-insensitive wildcard match: {@code *} stands for any run of characters, every other
   * character is literal. This is the small subset clinfer's rule matcher uses, mirrored so a
   * catalogue glob means the same thing on both sides of the wire.
   */
  static boolean glob(String pattern, String value) {
    if (pattern == null || value == null) {
      return false;
    }
    return globMatch(pattern.toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT), 0, 0);
  }

  private static boolean globMatch(String pattern, String value, int p, int v) {
    int pi = p;
    int vi = v;
    while (pi < pattern.length()) {
      char c = pattern.charAt(pi);
      if (c == '*') {
        // A trailing `*` matches the rest; otherwise try every split point.
        if (pi + 1 == pattern.length()) {
          return true;
        }
        for (int k = vi; k <= value.length(); k++) {
          if (globMatch(pattern, value, pi + 1, k)) {
            return true;
          }
        }
        return false;
      }
      if (vi >= value.length() || value.charAt(vi) != c) {
        return false;
      }
      pi++;
      vi++;
    }
    return vi == value.length();
  }

  /** The dcm4che study a Create names: Study Instance UID, Patient ID and Accession Number. */
  static Attributes study(MediaSeriesGroup study) {
    Attributes attrs = new Attributes();
    String studyUID = string(study, Tag.StudyInstanceUID);
    if (studyUID != null) {
      attrs.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
    }
    String patientId = string(study, Tag.PatientID);
    if (patientId != null) {
      attrs.setString(Tag.PatientID, VR.LO, patientId);
    }
    String accession = string(study, Tag.AccessionNumber);
    if (accession != null) {
      attrs.setString(Tag.AccessionNumber, VR.SH, accession);
    }
    return attrs;
  }

  /**
   * The input instances of a task: one {@code SERIES_UID/SOP_CLASS_UID/SOP_INSTANCE_UID} triple per
   * image, as the Input Information Sequence names them (PS3.4 Table CC.2.5-2b).
   */
  static List<Attributes> instances(
      String studyUID, List<MediaSeries<? extends MediaElement>> series) {
    List<Attributes> result = new ArrayList<>();
    for (MediaSeries<? extends MediaElement> s : series) {
      if (!(s instanceof DicomSeries dicomSeries)) {
        continue;
      }
      String seriesUid = string(dicomSeries, Tag.SeriesInstanceUID);
      if (seriesUid == null) {
        continue;
      }
      for (DicomImageElement image : dicomSeries.getMedias(null, null)) {
        String sopUid = string(image, Tag.SOPInstanceUID);
        String sopClass = string(image, Tag.SOPClassUID);
        if (sopUid == null || sopClass == null) {
          continue;
        }
        Attributes instance = new Attributes();
        instance.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
        instance.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
        instance.setString(Tag.SOPClassUID, VR.UI, sopClass);
        instance.setString(Tag.SOPInstanceUID, VR.UI, sopUid);
        result.add(instance);
      }
    }
    return result;
  }

  /** The clicked series followed by the rest of the selection, each once. */
  private static List<MediaSeries<? extends MediaElement>> series(
      MediaSeries<? extends MediaElement> clicked,
      List<MediaSeries<? extends MediaElement>> selection) {
    List<MediaSeries<? extends MediaElement>> series = new ArrayList<>();
    series.add(clicked);
    if (selection != null) {
      for (MediaSeries<? extends MediaElement> s : selection) {
        if (s != null && !series.contains(s)) {
          series.add(s);
        }
      }
    }
    series.removeIf(Objects::isNull);
    return series;
  }

  private static DicomTaskManager taskManager() {
    DataExplorerView view = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
    return view instanceof DicomExplorer explorer ? explorer.getTaskManager() : null;
  }

  private static String string(org.weasis.core.api.media.data.TagReadable readable, int tag) {
    Object value = readable == null ? null : readable.getTagValue(TagD.get(tag));
    return value == null ? null : value.toString();
  }
}
