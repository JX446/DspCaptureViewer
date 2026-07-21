package com.qx.dspcapture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public class App extends Application {

    private static final int BUF_ADDR  = 0x9000;
    private static final int WR_ADDR   = 0xA000;
    private static final int BUF_SIZE  = 512;
    private static final int POLL_MS   = 10;
    private static final String HOST   = "localhost";
    private static final int PORT      = 4333;
    private static final int CHART_DOWNSAMPLE = 2;
    private static final int RINGBUF_CAPACITY = 2_000_000;  // ~400 s @ 200µs

    private CaptureEngine engine;
    private SampleRingBuffer ringBuffer;
    private Viewport viewport;
    private int numChannels = 1;
    private int captureGen;
    private long startTime;
    private long totalWords, lastStatusUpdate;
    private final long[] chWords = new long[4];

    // ── UI ──
    private Label statusLabel;
    private Button startBtn, stopBtn, clearBtn, exportBtn;
    private ComboBox<Integer> channelSelector;
    private Label statusDot;
    private TableView<StatRow> table;
    private WaveformCanvas waveform;
    private Image appIcon;  // cached for dialogs

    // ── Playback controls ──
    private Slider scrollSlider;
    private ToggleButton liveToggle;
    private Button playToggle;
    private Button zoomInBtn, zoomOutBtn;
    private Timeline playTimeline;
    private boolean sliderChanging;

    // ── Stall detection ──
    private static final long STALL_TIMEOUT_MS = 2_000;  // 2 s without data = stalled
    private long lastChunkTime;
    private boolean stalled;
    private Timeline watchdog;

    // ── Table data model ──
    public static class StatRow {
        private final int channel;
        private float current, min, max, avg, pkpk;
        public StatRow(int channel) { this.channel = channel; }
        public void update(float cur, float min, float max, float avg, float pkpk) {
            this.current = cur; this.min = min; this.max = max;
            this.avg = avg; this.pkpk = pkpk;
        }
        public int   getChannel() { return channel; }
        public float getCurrent() { return current; }
        public float getMin()     { return min; }
        public float getMax()     { return max; }
        public float getAvg()     { return avg; }
        public float getPkpk()    { return pkpk; }
    }

    private final StatRow[] statRows = new StatRow[4];
    private final ObservableList<StatRow> tableData = FXCollections.observableArrayList();

    @Override
    public void start(Stage stage) {
        // ── Create the data pipeline ──
        ringBuffer = new SampleRingBuffer(4, RINGBUF_CAPACITY);
        viewport = new Viewport();
        configureEngine();

        stage.setTitle("DSP Capture Viewer");

        // ── Header ──
        Label titleLabel = new Label("◆  DSP Capture Viewer");
        titleLabel.getStyleClass().add("header-title");
        Label subtitleLabel = new Label("Real-time DSP buffer monitor");
        subtitleLabel.getStyleClass().add("header-subtitle");

        channelSelector = new ComboBox<>(
                FXCollections.observableArrayList(1, 2, 3, 4));
        channelSelector.setValue(1);
        channelSelector.setTooltip(new Tooltip("Number of channels to monitor"));
        channelSelector.setOnAction(e -> {
            if (engine.isRunning()) {
                engine.stop();
                statusLabel.setText("Stopped — channel count changed");
            }
            numChannels = channelSelector.getValue();
            configureEngine();
            rebuildWaveform();
            clearAllData();
        });

        ComboBox<Integer> fpsSelector = new ComboBox<>(
                FXCollections.observableArrayList(30, 60));
        fpsSelector.setValue(60);
        fpsSelector.setTooltip(new Tooltip("Display refresh rate"));
        fpsSelector.setOnAction(e -> waveform.setTargetFps(fpsSelector.getValue()));

        VBox headerLeftBox = new VBox(2, titleLabel, subtitleLabel);
        HBox headerRightBox = new HBox(10,
                new Label("FPS:"), fpsSelector,
                new Label("Channels:"), channelSelector);
        headerRightBox.setAlignment(Pos.CENTER_RIGHT);
        BorderPane headerPane = new BorderPane();
        headerPane.setLeft(headerLeftBox);
        headerPane.setRight(headerRightBox);
        headerPane.getStyleClass().add("header-bar");
        headerPane.setPadding(new Insets(12, 20, 12, 20));

        // ── Status bar ──
        statusDot = new Label("●");
        statusDot.getStyleClass().addAll("status-dot", "idle");
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-label");
        HBox statusBar = new HBox(8, statusDot, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        // ── Waveform ──
        waveform = new WaveformCanvas();
        waveform.setDataProvider(ringBuffer);
        waveform.setViewport(viewport);
        waveform.setNumChannels(numChannels);  // override DataProvider's 4 → App's actual
        waveform.setOnViewportChanged(() -> {
            syncToggleToViewport();
            syncSliderToViewport();
            updateStats();
        });
        StackPane wfBox = new StackPane(waveform);
        wfBox.setMinSize(100, 150);
        // Scroll on wrapper → zoom waveform (reliable, avoids Canvas event quirks)
        wfBox.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            waveform.scrollZoom(e.getX(), e.getDeltaY());
            e.consume();
        });
        wfBox.widthProperty().addListener((obs, ov, nv) -> {
            waveform.setWidth(nv.doubleValue());
            waveform.draw();
        });
        wfBox.heightProperty().addListener((obs, ov, nv) -> {
            waveform.setHeight(nv.doubleValue());
            waveform.draw();
        });

        // ── Playback control bar ──
        HBox playbackBar = buildPlaybackBar();

        // ── Stats table ──
        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("No data captured yet — click Start to begin"));

        TableColumn<StatRow, Boolean> colVis = new TableColumn<>("Show");
        colVis.setPrefWidth(60); colVis.setMinWidth(60); colVis.setMaxWidth(60);
        colVis.setResizable(false); colVis.setSortable(false);
        colVis.setStyle("-fx-alignment: CENTER;");
        colVis.setCellFactory(col -> {
            TableCell<StatRow, Boolean> cell = new TableCell<>() {
                private final Label mark = new Label();
                { mark.setStyle("-fx-font-size: 13px; -fx-text-fill: #00cc66; -fx-font-weight: bold; "
                        + "-fx-alignment: center; -fx-min-width: 30px;"); }
                @Override protected void updateItem(Boolean v, boolean empty) {
                    super.updateItem(v, empty);
                    if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                        setGraphic(null);
                    } else {
                        int ch = getTableRow().getItem().getChannel();
                        mark.setText(waveform.isChannelVisible(ch) ? "✔" : "");
                        mark.setStyle(waveform.isChannelVisible(ch)
                                ? "-fx-font-size: 13px; -fx-text-fill: #00cc66; -fx-font-weight: bold; "
                                  + "-fx-alignment: center; -fx-min-width: 30px;"
                                : "-fx-font-size: 13px; -fx-text-fill: #444455; "
                                  + "-fx-alignment: center; -fx-min-width: 30px;");
                        setGraphic(mark);
                    }
                }
            };
            cell.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                StatRow row = cell.getTableRow() != null ? cell.getTableRow().getItem() : null;
                if (row != null) {
                    int ch = row.getChannel();
                    waveform.setChannelVisible(ch, !waveform.isChannelVisible(ch));
                    table.refresh();
                    e.consume();
                }
            });
            return cell;
        });

        TableColumn<StatRow, Integer> colCh = new TableColumn<>("Ch");
        colCh.setCellValueFactory(new PropertyValueFactory<>("channel"));
        colCh.setPrefWidth(60); colCh.setMinWidth(60); colCh.setMaxWidth(60);
        colCh.setResizable(false); colCh.setSortable(false);
        colCh.setStyle("-fx-alignment: CENTER;");
        colCh.setCellFactory(col -> new TableCell<>() {
            private final javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(5);
            @Override protected void updateItem(Integer ch, boolean empty) {
                super.updateItem(ch, empty);
                if (empty || ch == null) {
                    setGraphic(null); setText(null);
                } else {
                    dot.setFill(WaveformCanvas.CHANNEL_COLORS[ch % 4]);
                    setGraphic(dot);
                    setText(" " + ch);
                }
            }
        });

        TableColumn<StatRow, Float> colCur = new TableColumn<>("Current");
        colCur.setCellValueFactory(new PropertyValueFactory<>("current"));
        colCur.setMinWidth(60); colCur.setSortable(false);

        TableColumn<StatRow, Float> colMin = new TableColumn<>("Min");
        colMin.setCellValueFactory(new PropertyValueFactory<>("min"));
        colMin.setMinWidth(60); colMin.setSortable(false);

        TableColumn<StatRow, Float> colMax = new TableColumn<>("Max");
        colMax.setCellValueFactory(new PropertyValueFactory<>("max"));
        colMax.setMinWidth(60); colMax.setSortable(false);

        TableColumn<StatRow, Float> colAvg = new TableColumn<>("Avg");
        colAvg.setCellValueFactory(new PropertyValueFactory<>("avg"));
        colAvg.setMinWidth(60); colAvg.setSortable(false);

        TableColumn<StatRow, Float> colPP = new TableColumn<>("Peak-to-Peak");
        colPP.setCellValueFactory(new PropertyValueFactory<>("pkpk"));
        colPP.setMinWidth(60); colPP.setSortable(false);

        table.getColumns().addAll(colVis, colCh, colCur, colMin, colMax, colAvg, colPP);
        table.setItems(tableData);
        table.setFixedCellSize(26);
        table.setPrefHeight(26 * (numChannels + 1) + 2);

        // ── Buttons ──
        startBtn = new Button("▶  Start");
        startBtn.getStyleClass().add("button-start");
        stopBtn  = new Button("■  Stop");
        stopBtn.getStyleClass().add("button-stop");
        clearBtn = new Button("✖  Clear");
        clearBtn.getStyleClass().add("button-clear");
        exportBtn= new Button("⇩  Export CSV");
        exportBtn.getStyleClass().add("button-export");
        stopBtn.setDisable(true);
        clearBtn.setDisable(true);
        exportBtn.setDisable(true);

        HBox buttonBar = new HBox(10, startBtn, stopBtn, clearBtn, exportBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(12, 0, 0, 0));

        // ── Layout: waveform (grows) | playback bar | table (fixed) | buttons ──
        VBox centerBox = new VBox(0, wfBox, playbackBar, table, buttonBar);
        centerBox.setPadding(new Insets(0, 16, 8, 16));
        centerBox.getStyleClass().add("content-area");
        VBox.setVgrow(wfBox, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(headerPane);
        root.setCenter(centerBox);
        root.setBottom(statusBar);

        // ── Actions ──
        startBtn.setOnAction(e -> startCapture());
        stopBtn.setOnAction(e -> stopCapture());
        clearBtn.setOnAction(e -> {
    if (ringBuffer != null && ringBuffer.getTotalSamples() > 0) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "This will discard all captured data.", ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Clear All Data");
        confirm.setHeaderText("Clear all captured data?");
        confirm.initOwner(stage);
        confirm.setOnShown(ev -> ((Stage) confirm.getDialogPane()
                .getScene().getWindow()).getIcons().add(appIcon));
        confirm.showAndWait().ifPresent(r -> {
            if (r == ButtonType.OK) clearAllData();
        });
    } else {
        clearAllData();
    }
});
        exportBtn.setOnAction(e -> exportCSV(stage));

        // Watchdog: detect DSP stall every 1 s
        watchdog = new Timeline(new KeyFrame(Duration.seconds(1), e -> checkStall()));
        watchdog.setCycleCount(Timeline.INDEFINITE);
        watchdog.play();

        Scene scene = new Scene(root, 1100, 780);
        scene.getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setMaximized(true);
        appIcon = new Image(getClass().getResourceAsStream("/ICON.png"));
        stage.getIcons().add(appIcon);
        stage.show();
    }

    // ── Playback control bar ──────────────────────────────────────────

    private HBox buildPlaybackBar() {
        // Live toggle — pressed = following, unpressed = frozen
        liveToggle = new ToggleButton("● LIVE");
        liveToggle.setTooltip(new Tooltip("Toggle: follow latest data / freeze for review"));
        liveToggle.setAccessibleText("Live mode toggle — currently live");
        liveToggle.setSelected(true);
        liveToggle.getStyleClass().add("toggle-live");
        liveToggle.setOnAction(e -> {
            if (liveToggle.isSelected()) {
                enterLiveMode();
            } else {
                freezeView();
            }
        });

        // Play/Pause toggle — single button, changes text
        playToggle = new Button("▶ Play");
        playToggle.setTooltip(new Tooltip("Auto-play through history"));
        playToggle.setDisable(true);  // disabled in live mode
        playToggle.setOnAction(e -> {
            if (playTimeline != null) {
                stopPlayback();
            } else {
                startPlayback();
            }
        });

        scrollSlider = new Slider(0, 1, 1);
        scrollSlider.setTooltip(new Tooltip("Drag to scroll history (auto-freezes)"));
        scrollSlider.setAccessibleText("History scroll — 0% oldest, 100% newest");
        scrollSlider.valueProperty().addListener((obs, ov, nv) -> {
            if (sliderChanging || ringBuffer.getTotalSamples() == 0) return;
            // Dragging slider → exit live & stop play
            if (viewport.isLive()) {
                viewport.setLive(false);
                liveToggle.setSelected(false);
                stopPlayback();
            }
            sliderChanging = true;
            viewport.setSliderPosition(
                    nv.doubleValue(),
                    ringBuffer.getValidStart(),
                    ringBuffer.getValidEnd());
            updateStats();
            waveform.draw();
            sliderChanging = false;
        });
        HBox.setHgrow(scrollSlider, Priority.ALWAYS);

        zoomOutBtn = new Button("−");
        zoomOutBtn.setTooltip(new Tooltip("Zoom out (x2)"));
        zoomOutBtn.setAccessibleText("Zoom out — double visible range");
        zoomOutBtn.setOnAction(e -> {
            viewport.zoom(2.0);
            syncSliderToViewport();
        });

        zoomInBtn = new Button("+");
        zoomInBtn.setTooltip(new Tooltip("Zoom in (/2)"));
        zoomInBtn.setAccessibleText("Zoom in — halve visible range");
        zoomInBtn.setOnAction(e -> {
            viewport.zoom(0.5);
            syncSliderToViewport();
        });

        HBox bar = new HBox(8,
                liveToggle, playToggle,
                new Label("│"), scrollSlider, new Label("│"),
                zoomOutBtn, zoomInBtn);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 0, 4, 0));
        return bar;
    }

    /** Switch to live-following mode. */
    private void enterLiveMode() {
        stopPlayback();
        viewport.setLive(true);
        sliderChanging = true;
        scrollSlider.setValue(1.0);
        sliderChanging = false;
        liveToggle.setSelected(true);
        liveToggle.setText("● LIVE");
        liveToggle.setAccessibleText("Live mode toggle — currently live");
        playToggle.setDisable(true);
    }

    /** Freeze the view at the current position for review. */
    private void freezeView() {
        stopPlayback();
        viewport.setLive(false);
        liveToggle.setSelected(false);
        liveToggle.setText("○ LIVE");
        liveToggle.setAccessibleText("Live mode toggle — currently frozen");
        playToggle.setDisable(false);
    }

    /** Start auto-advancing through history. */
    private void startPlayback() {
        if (ringBuffer.getTotalSamples() == 0) return;
        viewport.setLive(false);
        liveToggle.setSelected(false);
        liveToggle.setText("○ LIVE");
        stopPlayback();

        playTimeline = new Timeline(new KeyFrame(Duration.millis(33), e -> {
            long span = ringBuffer.getValidEnd() - ringBuffer.getValidStart();
            if (span <= viewport.getViewCount()) {
                stopPlayback();
                return;
            }
            viewport.scroll(0.05,
                    ringBuffer.getValidStart(), ringBuffer.getValidEnd());
            syncSliderToViewport();
            updateStats();
        }));
        playTimeline.setCycleCount(Timeline.INDEFINITE);
        playTimeline.play();

        playToggle.setText("⏸ Pause");
    }

    /** Stop auto-playback. */
    private void stopPlayback() {
        if (playTimeline != null) {
            playTimeline.stop();
            playTimeline = null;
        }
        playToggle.setText("▶ Play");
    }

    /** Sync live-toggle button to match viewport state. */
    private void syncToggleToViewport() {
        if (viewport.isLive()) {
            liveToggle.setSelected(true);
            liveToggle.setText("● LIVE");
            liveToggle.setAccessibleText("Live mode toggle — currently live");
            playToggle.setDisable(true);
        } else {
            liveToggle.setSelected(false);
            liveToggle.setText("○ LIVE");
            liveToggle.setAccessibleText("Live mode toggle — currently frozen");
            playToggle.setDisable(false);
        }
    }

    private void syncSliderToViewport() {
        sliderChanging = true;
        if (viewport.isLive()) {
            scrollSlider.setValue(1.0);  // live mode: always at rightmost
        } else {
            long validStart = ringBuffer.getValidStart();
            long validEnd   = ringBuffer.getValidEnd();
            long span = validEnd - validStart;
            if (span > viewport.getViewCount()) {
                scrollSlider.setValue(
                        (double)(viewport.getViewStart() - validStart)
                        / (span - viewport.getViewCount()));
            }
        }
        sliderChanging = false;
    }

    // ── Stall detection ───────────────────────────────────────────────

    /** Called every 1 s by watchdog. Detects if DSP has stopped sending data. */
    private void checkStall() {
        if (engine == null || !engine.isRunning()) return;
        if (lastChunkTime == 0) return;  // no data yet
        long now = System.currentTimeMillis();
        if (!stalled && (now - lastChunkTime) > STALL_TIMEOUT_MS) {
            stalled = true;
            Platform.runLater(() -> {
                statusDot.getStyleClass().setAll("status-dot", "stalled");
                long secs = (now - lastChunkTime) / 1000;
                statusLabel.setText(String.format(
                        "DSP stalled — no data for %d s  |  last %d ks buffered",
                        secs, ringBuffer.getTotalSamples() / 1000));
                // Auto-freeze so user can inspect the last waveform
                if (viewport.isLive()) {
                    viewport.setLive(false);
                    liveToggle.setSelected(false);
                    liveToggle.setText("○ LIVE");
                    liveToggle.setAccessibleText("Live mode toggle — auto-frozen (DSP stalled)");
                    playToggle.setDisable(false);
                }
            });
        }
    }

    private void recoverFromStall() {
        stalled = false;
        statusDot.getStyleClass().setAll("status-dot", "running");
        statusLabel.setText("DSP recovered — capturing…");
    }

    // ── Engine & capture ──────────────────────────────────────────────

    private void configureEngine() {
        if (engine != null) engine.stop();
        final int gen = ++captureGen;
        engine = new CaptureEngine()
                .host(HOST).port(PORT)
                .wrAddr(WR_ADDR).bufferAddr(BUF_ADDR)
                .bufferSize(BUF_SIZE).pollIntervalMs(POLL_MS)
                .outputDir(Paths.get(System.getProperty("user.dir"), "captures"))
                .saveToDisk(false)
                .numChannels(numChannels);

        engine.addListener(new CaptureEngine.CaptureListener() {
            @Override public void onChunk(CaptureEngine.Chunk chunk) {
                if (chunk.channel >= numChannels) return;

                float[] samples = buildWfSamples(chunk);
                ringBuffer.push(chunk.channel, samples, samples.length);

                // Advance totalWritten after the last channel in each cycle
                if (chunk.channel == numChannels - 1) {
                    ringBuffer.advanceTotalWritten(samples.length);
                }

                Platform.runLater(() -> {
                    if (gen != captureGen) return;
                    lastChunkTime = System.currentTimeMillis();
                    if (stalled) recoverFromStall();
                    updateStats();
                    if (viewport.isLive()) syncSliderToViewport();  // only in live mode
                    statusDot.getStyleClass().setAll("status-dot", "running");
                    long now = System.currentTimeMillis();
                    if (startTime == 0) startTime = now;
                    totalWords += chunk.newCount;
                    chWords[chunk.channel] += chunk.newCount;
                    float kBps = totalWords * 4f / 1024f / Math.max(0.001f, (now - startTime) / 1000f);
                    long elapsed = now - startTime;
                    if (now - lastStatusUpdate > 500) {
                        lastStatusUpdate = now;
                        statusLabel.setText(String.format(
                                "Running  %02d:%02d  |  %.0f kB/s  |  Ch %d/%d/%d/%d kw  |  %d ks total",
                                elapsed / 60000, (elapsed / 1000) % 60, kBps,
                                chWords[0]/1000, chWords[1]/1000,
                                chWords[2]/1000, chWords[3]/1000,
                                ringBuffer.getTotalSamples() / 1000));
                    }
                });
            }
            @Override public void onError(Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Error: " + e.getMessage());
                    statusDot.getStyleClass().setAll("status-dot", "error");
                });
            }
        });
    }

    /** Convert a raw Chunk to downsampled float values (new portion only). */
    private float[] buildWfSamples(CaptureEngine.Chunk chunk) {
        int[] data = chunk.data;
        int wr = chunk.wrValue;
        int n = data.length;
        int step = CHART_DOWNSAMPLE;
        float[] all = new float[n / step];
        for (int i = 0; i < n; i++) {
            if (i % step == 0)
                all[i / step] = Float.intBitsToFloat(data[(wr + i) % n]);
        }
        int newCnt = Math.min(chunk.newCount / step, all.length);
        if (newCnt <= 0 || newCnt >= all.length) return all;
        return Arrays.copyOfRange(all, all.length - newCnt, all.length);
    }

    // ── Stats ──────────────────────────────────────────────────────────

    private void updateStats() {
        long total = ringBuffer.getTotalSamples();
        if (total == 0) return;

        long vs = viewport.getViewStart();
        int vc = viewport.getViewCount();
        // Clamp to valid range
        long validStart = ringBuffer.getValidStart();
        if (vs < validStart) vs = validStart;
        if (vs + vc > total) vc = (int)(total - vs);
        if (vc <= 0) return;

        for (int ch = 0; ch < numChannels; ch++) {
            float[] s = ringBuffer.getSamples(ch, vs, vc);
            if (s == null || s.length == 0) continue;
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            double sum = 0;
            for (float v : s) {
                if (v < min) min = v;
                if (v > max) max = v;
                sum += v;
            }
            float avg = (float)(sum / s.length);
            float cur = s[s.length - 1];
            float pkpk = max - min;

            StatRow row = statRows[ch];
            if (row == null) {
                row = new StatRow(ch);
                statRows[ch] = row;
                tableData.add(row);
            }
            row.update(cur, min, max, avg, pkpk);
        }
        table.refresh();
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    private void rebuildWaveform() {
        waveform.setDataProvider(ringBuffer);
        waveform.setViewport(viewport);
        waveform.setNumChannels(numChannels);
        double targetH = table.getFixedCellSize() * (numChannels + 1) + 2;
        double current = table.getPrefHeight();
        if (Math.abs(current - targetH) > 1 && current > 0) {
            Timeline t = new Timeline(new KeyFrame(Duration.millis(200),
                    new KeyValue(table.prefHeightProperty(), targetH, Interpolator.EASE_BOTH)));
            t.play();
        } else {
            table.setPrefHeight(targetH);
        }
    }

    private void startCapture() {
        statusLabel.setText("Connecting…");
        startBtn.setDisable(true);
        new Thread(() -> {
            try {
                configureEngine();
                startTime = 0;
                lastStatusUpdate = 0;
                totalWords = 0;
                for (int i = 0; i < 4; i++) chWords[i] = 0;
                engine.start();
                Platform.runLater(() -> {
                    startBtn.setDisable(true);
                    stopBtn.setDisable(false);
                    clearBtn.setDisable(true);
                    exportBtn.setDisable(true);
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    startBtn.setDisable(false);
                    statusLabel.setText("Connect failed: " + ex.getMessage());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    startBtn.setDisable(false);
                    statusLabel.setText("Error: " + ex.getClass().getSimpleName()
                            + " - " + ex.getMessage());
                });
                ex.printStackTrace();
            }
        }).start();
    }

    private void stopCapture() {
        engine.stop();
        stopPlayback();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        clearBtn.setDisable(false);
        exportBtn.setDisable(false);
    }

    private void clearAllData() {
        stopPlayback();
        ringBuffer.clear();
        viewport = new Viewport();
        waveform.setViewport(viewport);
        tableData.clear();
        for (int i = 0; i < 4; i++) statRows[i] = null;
        waveform.draw();
        scrollSlider.setValue(1.0);
        statusDot.getStyleClass().setAll("status-dot", "idle");
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        clearBtn.setDisable(true);
        exportBtn.setDisable(true);
        statusLabel.setText("Cleared");
    }

    private void exportCSV(Stage stage) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select directory to save CSV");
        File dir = dc.showDialog(stage);
        if (dir != null) {
            try {
                CaptureExporter.exportCSV(ringBuffer,
                        Paths.get(dir.getAbsolutePath(),
                        "dsp_capture_" + System.currentTimeMillis() + ".csv"));
                statusLabel.setText("Exported to " + dir.getAbsolutePath());
            } catch (IOException ex) {
                statusLabel.setText("Export failed: " + ex.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        if (engine != null) engine.stop();
        stopPlayback();
        if (watchdog != null) watchdog.stop();
        if (waveform != null) waveform.dispose();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
