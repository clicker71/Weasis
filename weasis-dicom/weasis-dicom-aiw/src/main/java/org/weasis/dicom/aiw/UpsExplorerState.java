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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Code;
import org.dcm4che3.data.Sequence;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weasis.core.api.explorer.DataExplorerView;
import org.weasis.core.api.gui.util.GuiUtils;
import org.weasis.core.api.media.data.MediaSeries;
import org.weasis.core.api.media.data.MediaSeriesGroup;
import org.weasis.core.api.util.ResourceUtil.OtherIcon;
import org.weasis.core.api.util.ResourceUtil.ResourceIconPath;
import org.weasis.dicom.codec.TagD;
import org.weasis.dicom.explorer.DicomModel;
import org.weasis.dicom.explorer.DicomWebOrigin;
import org.weasis.dicom.explorer.main.DicomExplorer;
import org.weasis.dicom.explorer.main.DicomTaskManager;
import org.weasis.dicom.web.ProcedureStepState;
import org.weasis.dicom.web.UpsConfig;

/**
 * The UPS-RS state the explorer shows: for every series of an open study, the workitems of that
 * study, so the context menu and the thumbnail badges read one cached answer instead of asking the
 * origin.
 *
 * <p>The refresh is the one the maintainer asked for: a Filtered Global Subscription (PS3.18
 * §11.10) drives the WebSocket event reports of §11.13, and every report re-reads the study. An
 * origin that does not service the channel - dcm4chee-arc answers 403 to Subscribe and 404 to the
 * handshake - falls back to re-reading the worklist with Search (§11.9), the only pull transaction
 * a Watcher has. That poll is a task of the explorer's {@link DicomTaskManager}, never of a
 * scheduler of this class's own, and it stops when the study is closed.
 */
public final class UpsExplorerState {

  private static final Logger LOGGER = LoggerFactory.getLogger(UpsExplorerState.class);

  /** Poll interval of the fallback, in seconds; the default {@code ups:watch} also uses. */
  private static final int POLL_INTERVAL_SECONDS = 2;

  /** Preference holding the requester/subscriber AE Title of this plugin. */
  static final String AE_PREFERENCE = "weasis.ups.ae"; // NON-NLS

  /** The AE Title used when a site sets none; vendor-neutral, and valid (PS3.3 Table 10-1). */
  static final String DEFAULT_AE = "WEASIS"; // NON-NLS

  private static final UpsExplorerState INSTANCE = new UpsExplorerState();

  /** seriesUID -> the state of the study that series belongs to. */
  private final ConcurrentHashMap<String, StudyState> seriesState = new ConcurrentHashMap<>();

  /**
   * seriesUIDs whose lazy resolution already ran, so the paint path records a series once rather
   * than on every repaint while the network answer is still in flight.
   */
  private final Set<String> resolved = ConcurrentHashMap.newKeySet();

  /** studyUID -> the token of the refresh in progress, so closing the study stops it. */
  private final ConcurrentHashMap<String, StudyWatch> watches = new ConcurrentHashMap<>();

  private UpsExplorerState() {}

  public static UpsExplorerState getInstance() {
    return INSTANCE;
  }

  /**
   * The state of one study, as the hooks render it.
   *
   * @param studyUID the Study Instance UID the workitems belong to
   * @param origin the DICOMweb server to ask, null when the study was not loaded through DICOMweb
   * @param workitems the rows of the study, in the order the origin listed them
   * @param bodyParts the study's {@code BodyPartExamined} values, empty when it names none. A
   *     series-level attribute (PS3.3 C.7.3.1), collected from the open series when the study is
   *     observed, so the badge and the menu can apply the catalogue's body-part constraint without
   *     a network read.
   */
  public record StudyState(
      String studyUID, DicomWebOrigin origin, List<WorkitemView> workitems, Set<String> bodyParts) {

    public StudyState {
      workitems = List.copyOf(workitems);
      bodyParts = Set.copyOf(bodyParts);
    }
  }

  /** One row of the listing: identity, state, task label and whether it names results. */
  public record WorkitemView(
      String workitemUid, ProcedureStepState state, String label, boolean hasOutputs) {}

  /**
   * A study being refreshed: the channel handle once the origin has one, and the flag that tells
   * the poll loop to stop. Closing a study flips the flag and cancels the channel.
   */
  private static final class StudyWatch {
    private final String studyUID;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CompletableFuture<?> channel;

    StudyWatch(String studyUID, CompletableFuture<?> channel) {
      this.studyUID = studyUID;
      this.channel = channel;
    }

    void channel(CompletableFuture<?> future) {
      channel = future;
      if (closed.get() && future != null && !future.isDone()) {
        // The study was closed while the handshake was in flight: the subscription is ours to drop.
        future.cancel(true);
      }
    }

    void close() {
      closed.set(true);
      CompletableFuture<?> future = channel;
      if (future != null && !future.isDone()) {
        future.cancel(true);
      }
    }

    boolean isClosed() {
      return closed.get();
    }
  }

  /**
   * The badges of a series, read from the cache only: the paint path calls this, so it never waits
   * on the network and never throws.
   *
   * @return the icons to draw, empty when nothing is known about the series
   */
  public Set<ResourceIconPath> badgesFor(String seriesUID) {
    if (seriesUID == null) {
      return Set.of();
    }
    StudyState state = seriesState.get(seriesUID);
    if (state == null) {
      return Set.of();
    }
    return badgesOf(state.workitems(), available(state));
  }

  /**
   * Whether AI is offered for a study at all: it was loaded through DICOMweb (there is a server to
   * address, PS3.18 §11) and at least one catalogue task applies to its body parts. This is the
   * "available, not yet run" half of the indicator, so a fresh eligible study shows the robot
   * before any workitem exists - and a study the catalogue does not target (a knee against a
   * chest-only catalogue) shows nothing.
   */
  static boolean available(StudyState state) {
    if (state == null || state.origin() == null) {
      return false;
    }
    for (UpsMenuContributor.CatalogTask task : UpsMenuContributor.catalog()) {
      if (UpsMenuContributor.appliesTo(state.bodyParts(), task.bodyPartGlob())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The badge rule, a pure function of the cached rows: a task is queued while any workitem is
   * SCHEDULED (or AI is available but nothing ran yet), running while any is IN PROGRESS, and its
   * results are ready once a COMPLETED one names outputs.
   *
   * <p>The priority is result, then running, then queued: a study that already shows an outcome
   * keeps advertising it, and a running task outranks a merely queued one so the clock replaces the
   * robot. Terminal means COMPLETED or CANCELED (PS3.4 Table CC.1.1-1): a cancelled task leaves no
   * badge, and a COMPLETED one without outputs names nothing to open, so neither shows.
   *
   * @param available whether the study offers AI at all, see {@link #available(StudyState)}
   */
  static Set<ResourceIconPath> badgesOf(List<WorkitemView> workitems, boolean available) {
    boolean scheduled = false;
    boolean running = false;
    boolean result = false;
    if (workitems != null) {
      for (WorkitemView workitem : workitems) {
        ProcedureStepState state = workitem.state();
        if (state == ProcedureStepState.IN_PROGRESS) {
          running = true;
        } else if (state == ProcedureStepState.SCHEDULED) {
          scheduled = true;
        } else if (state == ProcedureStepState.COMPLETED && workitem.hasOutputs()) {
          result = true;
        }
      }
    }
    if (result) {
      return Set.of(OtherIcon.AI_RESULT);
    }
    if (running) {
      return Set.of(OtherIcon.AI_PROGRESS);
    }
    return scheduled || available ? Set.of(OtherIcon.AI_TASK) : Set.of();
  }

  /** The cached rows of a series, empty when nothing is known about it. */
  public List<WorkitemView> workitemsFor(String seriesUID) {
    if (seriesUID == null) {
      return List.of();
    }
    StudyState state = seriesState.get(seriesUID);
    return state == null ? List.of() : state.workitems();
  }

  /** The cached state of a series' study, empty when nothing is known about it. */
  public Optional<StudyState> studyFor(String seriesUID) {
    return seriesUID == null ? Optional.empty() : Optional.ofNullable(seriesState.get(seriesUID));
  }

  /**
   * Records the series the explorer shows for a study and starts its refresh the first time the
   * study is seen. Called from the menu, so it only touches the cache maps.
   */
  public void observe(DicomModel model, MediaSeriesGroup study, List<String> seriesUids) {
    if (study == null || seriesUids == null || seriesUids.isEmpty()) {
      return;
    }
    String studyUID = uid(study, Tag.StudyInstanceUID);
    if (studyUID == null) {
      return;
    }
    DicomWebOrigin origin = DicomWebOrigin.of(model, study);
    Set<String> bodyParts = bodyPartsOf(model, study);
    for (String seriesUid : seriesUids) {
      StudyState current = seriesState.get(seriesUid);
      if (current == null || !studyUID.equals(current.studyUID())) {
        seriesState.put(seriesUid, new StudyState(studyUID, origin, List.of(), bodyParts));
      }
    }
    // observe runs on the EDT (the menu): it only records the series. Establishing the subscription
    // or the channel is network I/O, so it is handed to the background task manager.
    if (!watches.containsKey(studyUID)) {
      StudyWatch watch = new StudyWatch(studyUID, null);
      if (watches.putIfAbsent(studyUID, watch) == null) {
        readNow(watch);
        startLater(watch, origin);
      }
    }
  }

  /**
   * Records a series the paint path met but the cache does not know yet: the menu is what normally
   * fills the cache, so a study the doctor has not right-clicked shows no badge until this runs.
   * The whole lookup is guarded so a headless paint (no explorer) is a no-op, and it is remembered
   * per series so the network hand-off happens once, never on every repaint.
   *
   * <p>{@link #observe} only records the series and hands the network to the task manager, so this
   * stays cheap on the EDT.
   */
  public void resolveIfUnknown(String seriesUID) {
    if (seriesUID == null || seriesState.containsKey(seriesUID)) {
      return;
    }
    if (!resolved.add(seriesUID)) {
      return;
    }
    DicomModel model = model();
    if (model == null) {
      return;
    }
    MediaSeriesGroup series = model.getSeriesNode(seriesUID);
    MediaSeriesGroup study = series == null ? null : model.getParent(series, DicomModel.study);
    if (study != null) {
      observe(model, study, seriesUidsOf(model, study));
    }
  }

  /** Drops the rows of a study and stops its refresh, e.g. when the study is closed. */
  public void forget(String studyUID) {
    if (studyUID == null) {
      return;
    }
    seriesState.values().removeIf(state -> studyUID.equals(state.studyUID()));
    StudyWatch watch = watches.remove(studyUID);
    if (watch != null) {
      watch.close();
    }
  }

  /**
   * Re-reads every open study now, for the menu's Refresh entry. The reads run on a background task
   * of the explorer, so the caller may be the EDT.
   */
  public void refreshNow() {
    for (StudyWatch watch : List.copyOf(watches.values())) {
      if (!watch.isClosed()) {
        readNow(watch);
      }
    }
  }

  /**
   * Stops the refresh of the studies the explorer no longer shows, the "stop when the tab closes"
   * half of the rule. The menu cannot know a study was closed, so this is asked on every right
   * click: a study whose node is gone from the live model loses its rows and its watch.
   */
  public void prune(DicomModel model) {
    if (model == null) {
      return;
    }
    for (String studyUID : List.copyOf(watches.keySet())) {
      if (model.getStudyNode(studyUID) == null) {
        LOGGER.debug("UPS-RS: study {} is closed, stopping its refresh", studyUID);
        forget(studyUID);
      }
    }
  }

  /** The series UIDs of a study the live model shows; the menu uses it to scope its entries. */
  public static List<String> seriesUidsOf(DicomModel model, MediaSeriesGroup study) {
    List<String> uids = new ArrayList<>();
    if (model == null || study == null) {
      return uids;
    }
    for (MediaSeriesGroup series : model.getChildren(study)) {
      if (series instanceof MediaSeries<?>) {
        String uid = uid(series, Tag.SeriesInstanceUID);
        if (uid != null) {
          uids.add(uid);
        }
      }
    }
    return uids;
  }

  /**
   * The {@code BodyPartExamined} values of a study's series, upper-cased as they are on the wire
   * (CS, e.g. {@code LUNG}). BodyPartExamined is a series-level attribute (PS3.3 General Series
   * Module C.7.3.1), so it is read from each open series; a study whose series name none yields an
   * empty set, which no body-part glob matches.
   */
  public static Set<String> bodyPartsOf(DicomModel model, MediaSeriesGroup study) {
    Set<String> parts = new LinkedHashSet<>();
    if (model == null || study == null) {
      return parts;
    }
    for (MediaSeriesGroup series : model.getChildren(study)) {
      Object value = series == null ? null : series.getTagValue(TagD.get(Tag.BodyPartExamined));
      if (value != null) {
        String text = value.toString().trim();
        if (!text.isEmpty()) {
          parts.add(text);
        }
      }
    }
    return parts;
  }

  /** The explorer's task manager, i.e. the scheduler nroduit asked for; null outside the UI. */
  private static DicomTaskManager taskManager() {
    DataExplorerView view = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
    return view instanceof DicomExplorer explorer ? explorer.getTaskManager() : null;
  }

  /**
   * The live explorer model, null outside the UI (a headless run or a unit test). Guarded as a
   * whole because the paint path calls {@link #resolveIfUnknown}: a missing UI core must degrade to
   * "no badge" rather than throw from a repaint.
   */
  private static DicomModel model() {
    try {
      DataExplorerView view = GuiUtils.getUICore().getExplorerPlugin(DicomExplorer.NAME);
      return view instanceof DicomExplorer explorer ? explorer.getDataExplorerModel() : null;
    } catch (RuntimeException | LinkageError e) {
      return null;
    }
  }

  /**
   * Establishes the subscription and the channel on a background task, and starts the poll when the
   * origin has no channel. Never runs on the EDT: every step here is network I/O.
   */
  private static void startLater(StudyWatch watch, DicomWebOrigin origin) {
    DicomTaskManager manager = taskManager();
    Runnable task = () -> start(watch, origin);
    if (manager == null) {
      // A headless run reads once; there is no UI to freeze and no channel to keep open.
      read(watch);
    } else {
      manager.executeGeneralTask("UPS-RS subscribe " + watch.studyUID, task);
    }
  }

  private static void start(StudyWatch watch, DicomWebOrigin origin) {
    if (watch.isClosed()) {
      return;
    }
    if (origin == null) {
      // Not loaded through DICOMweb: there is no server to ask, so the study keeps its empty rows
      // rather than the plugin guessing an origin from a series URL.
      LOGGER.debug("UPS-RS: study {} has no DICOMweb origin, not watching", watch.studyUID);
      return;
    }
    CompletableFuture<?> channel = openChannel(watch.studyUID, origin);
    watch.channel(channel);
    read(watch);
    if (channel == null) {
      poll(watch, origin);
    } else {
      channel.whenComplete(
          (socket, error) -> {
            if (error != null && !watch.isClosed()) {
              LOGGER.debug(
                  "UPS-RS: no notification channel for {}, polling", watch.studyUID, error);
              poll(watch, origin);
            }
          });
    }
  }

  /**
   * Subscribes to the study and opens the channel, or null when either step fails, which is the
   * signal to poll. The Subscribe of a Watcher is a Filtered Global Subscription: it observes the
   * study, it does not own the workitems (PS3.18 §11.10.1.2).
   */
  private static CompletableFuture<?> openChannel(String studyUID, DicomWebOrigin origin) {
    UpsService service;
    try {
      service = service(origin);
      Attributes filter = new Attributes();
      filter.setString(Tag.StudyInstanceUID, VR.UI, studyUID);
      service.subscribe(filter, false);
    } catch (IOException e) {
      LOGGER.debug("UPS-RS Subscribe refused on {}, falling back to polling", studyUID, e);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } catch (IllegalArgumentException e) {
      // The wire client throws this client-side when it has no AE Title to encode. requesterAe()
      // is meant to make that impossible, but a channel is never worth losing the whole refresh
      // over: an unusable subscription routes to the poll, exactly like a refused one.
      LOGGER.debug("UPS-RS Subscribe rejected on {}, falling back to polling", studyUID, e);
      return null;
    }
    // The report names the workitem that changed, not its new value (PS3.18 §11.13): it is the
    // trigger to re-read the study, so the change lands in the cache the hooks render.
    try {
      return service
          .watch(report -> refreshReport(studyUID, report))
          .whenComplete(
              (socket, error) -> {
                if (error != null) {
                  LOGGER.debug("UPS-RS channel refused on {}", studyUID, error);
                }
              });
    } catch (IllegalArgumentException e) {
      // Same client-side guard as the subscription: the channel URL encodes the AE Title too, so
      // an unusable one must route to the poll rather than abort the refresh.
      LOGGER.debug("UPS-RS channel rejected on {}, falling back to polling", studyUID, e);
      return null;
    }
  }

  private static void refreshReport(String studyUID, Attributes report) {
    LOGGER.debug("UPS-RS event on study {}: {}", studyUID, report.getString(Tag.EventTypeID));
    StudyWatch watch = INSTANCE.watches.get(studyUID);
    if (watch != null && !watch.isClosed()) {
      readNow(watch);
    }
  }

  /** Re-reads a study once and replaces its rows, on the explorer's task manager. */
  private static void readNow(StudyWatch watch) {
    DicomTaskManager manager = taskManager();
    Runnable task = () -> read(watch);
    if (manager == null) {
      // No explorer (a unit test or a headless run): read on the calling thread rather than
      // dropping the refresh silently.
      task.run();
    } else {
      manager.executeGeneralTask("UPS-RS refresh " + watch.studyUID, task);
    }
  }

  /**
   * The conservative poll: re-read the worklist every {@link #POLL_INTERVAL_SECONDS} seconds on the
   * explorer's scheduler while the study stays open. A channel report supersedes the rows and ends
   * it; without a channel this loop is the only refresher.
   */
  private static void poll(StudyWatch watch, DicomWebOrigin origin) {
    DicomTaskManager manager = taskManager();
    if (manager == null) {
      read(watch);
      return;
    }
    manager.executeGeneralTask(
        "UPS-RS poll " + watch.studyUID,
        () -> {
          while (!watch.isClosed()) {
            try {
              Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }
            read(watch);
          }
        });
  }

  /** Reads a study's worklist and replaces the rows of its series. */
  private static void read(StudyWatch watch) {
    DicomWebOrigin origin = originOf(watch.studyUID);
    if (origin == null) {
      return;
    }
    try {
      applyRows(watch.studyUID, origin, service(origin).search(watch.studyUID, null, null));
    } catch (IOException e) {
      LOGGER.debug("UPS-RS refresh failed on {}", watch.studyUID, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** The origin of the study that owns a series, null when the study is unknown. */
  static DicomWebOrigin originOf(String studyUID) {
    for (StudyState state : INSTANCE.seriesState.values()) {
      if (studyUID.equals(state.studyUID())) {
        return state.origin();
      }
    }
    return null;
  }

  /** Replaces the rows of every series of a study with what the origin just listed. */
  private static void applyRows(
      String studyUID, DicomWebOrigin origin, List<Attributes> workitems) {
    List<WorkitemView> rows = rows(workitems);
    for (Map.Entry<String, StudyState> entry : INSTANCE.seriesState.entrySet()) {
      if (studyUID.equals(entry.getValue().studyUID())) {
        // The body parts came from the model when the study was observed; this refresh only
        // replaces the rows, so they are carried over rather than re-read.
        Set<String> bodyParts = entry.getValue().bodyParts();
        INSTANCE.seriesState.put(entry.getKey(), new StudyState(studyUID, origin, rows, bodyParts));
      }
    }
  }

  static List<WorkitemView> rows(List<Attributes> workitems) {
    List<WorkitemView> rows = new ArrayList<>();
    for (Attributes workitem : workitems) {
      String uid = workitem.getString(Tag.SOPInstanceUID);
      if (uid == null) {
        continue;
      }
      rows.add(
          new WorkitemView(
              uid, UpsService.stateOf(workitem), label(workitem), UpsService.hasOutputs(workitem)));
    }
    return rows;
  }

  /** The task code of a workitem as {@code value^scheme}: how a Run entry tells it is pending. */
  static String label(Attributes workitem) {
    Sequence codes = workitem.getSequence(Tag.ScheduledWorkitemCodeSequence);
    if (codes == null || codes.isEmpty()) {
      return null;
    }
    Attributes code = codes.get(0);
    String value = code.getString(Tag.CodeValue);
    if (value == null) {
      return null;
    }
    String scheme = code.getString(Tag.CodingSchemeDesignator);
    return value + "^" + (scheme == null ? "" : scheme);
  }

  /** Whether the cached rows of a study have a non-terminal workitem carrying {@code code}. */
  public boolean hasPending(String studyUID, Code code) {
    if (studyUID == null || code == null) {
      return false;
    }
    String expected = code.getCodeValue() + "^" + code.getCodingSchemeDesignator();
    for (WorkitemView workitem : rowsOf(studyUID)) {
      if (expected.equals(workitem.label())
          && (workitem.state() == ProcedureStepState.SCHEDULED
              || workitem.state() == ProcedureStepState.IN_PROGRESS)) {
        return true;
      }
    }
    return false;
  }

  /** The cached rows of a study, from whichever series is known first. */
  private List<WorkitemView> rowsOf(String studyUID) {
    for (StudyState state : seriesState.values()) {
      if (studyUID.equals(state.studyUID())) {
        return state.workitems();
      }
    }
    return List.of();
  }

  static UpsService service(DicomWebOrigin origin) {
    UpsConfig.Builder builder = UpsConfig.builder().baseUrl(origin.baseUrl());
    String ae = requesterAe();
    builder.requesterAet(ae);
    origin.authMethod().ifPresent(method -> builder.authorization(UpsCommands.bearer(method)));
    return new UpsService(builder.build(), ae);
  }

  /**
   * The AE Title this plugin sends as requester and subscriber. A UPS request carries one (the
   * `--ae` option of the command line plays this role in phase 1), and the wire client refuses an
   * empty one client-side, so a blank here would abort the whole refresh. It comes from the {@code
   * weasis.ups.ae} preference, or the system property of the same name, and falls back to a
   * vendor-neutral constant: a deployment that sets nothing still gets a usable requester rather
   * than a channel that cannot be opened.
   *
   * <p>The read is an in-memory preference; it never touches the network, so it is safe on the EDT.
   */
  static String requesterAe() {
    String value = null;
    try {
      value = GuiUtils.getUICore().getSystemPreferences().getProperty(AE_PREFERENCE);
    } catch (RuntimeException | LinkageError e) {
      LOGGER.debug("UPS-RS: no UI preferences, using the configured AE Title");
    }
    if (value == null || value.isBlank()) {
      value = System.getProperty(AE_PREFERENCE);
    }
    if (value == null || value.isBlank()) {
      return DEFAULT_AE;
    }
    String trimmed = value.trim();
    // The AE Title field is 16 characters (PS3.3 Table 10-1); the wire client caps it too, but
    // capping here keeps the value the service is built with and the value it sends the same.
    return trimmed.length() > 16 ? trimmed.substring(0, 16) : trimmed;
  }

  /** Empties the cache and stops every refresh; the tests use it to start from a known state. */
  static void clear() {
    for (StudyWatch watch : INSTANCE.watches.values()) {
      watch.close();
    }
    INSTANCE.watches.clear();
    INSTANCE.seriesState.clear();
    INSTANCE.resolved.clear();
  }

  private static String uid(MediaSeriesGroup group, int tag) {
    Object value = group == null ? null : group.getTagValue(TagD.get(tag));
    return value == null ? null : value.toString();
  }
}
