package com.qx.dspcapture;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

public class App extends Application {

    private static final int BUF_ADDR  = 0x9000;
    private static final int WR_ADDR   = 0xA000;
    private static final int BUF_SIZE  = 512;   // per-channel buffer size (DSP side)
    private static final int POLL_MS   = 10;
    private static final String HOST   = "localhost";
    private static final int PORT      = 4333;
    private static final String PROXY_PATH =
            "C:/Users/95412/Desktop/work/ide_code/ide_source_code/bundles/QXTOOLS/qxtools/toolchain/3slot_320f/bin/or_debug_proxy.exe";
    private static final String CHIP_ARG = "--chip37xd";

    private static final int CHART_DOWNSAMPLE = 2;
    private static final int MAX_CHUNKS = 5000;

    private CaptureEngine engine;
    private ProxyLauncher proxyLauncher;
    private final List<CaptureEngine.Chunk> allChunks = new ArrayList<>(MAX_CHUNKS);
    private int numChannels = 1;
    private int captureGen;
    private long startTime;
    private final long[] chunkTimes = new long[64];
    private final int[] chunkWords = new int[64];
    private int chunkIdx;
    private long lastStatusUpdate;

    private Label statusLabel;
    private Button startBtn, stopBtn, clearBtn, exportBtn;
    private ComboBox<Integer> channelSelector;
    private Label statusDot;
    private TableView<StatRow> table;
    private WaveformCanvas waveform;

    // ---- Table data model — per-channel statistics ----
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
        proxyLauncher = new ProxyLauncher(PROXY_PATH, HOST, PORT, CHIP_ARG);
        configureEngine();

        // ── Build UI ──
        stage.setTitle("DSP Capture Viewer");

        // Header
        Label titleLabel = new Label("◆  DSP Capture Viewer");
        titleLabel.getStyleClass().add("header-title");
        Label subtitleLabel = new Label("Real-time DSP buffer monitor  |  " + CHIP_ARG);
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

        VBox headerLeftBox = new VBox(2, titleLabel, subtitleLabel);
        HBox headerRightBox = new HBox(10, new Label("Channels:"), channelSelector);
        headerRightBox.setAlignment(Pos.CENTER_RIGHT);
        BorderPane headerPane = new BorderPane();
        headerPane.setLeft(headerLeftBox);
        headerPane.setRight(headerRightBox);
        headerPane.getStyleClass().add("header-bar");
        headerPane.setPadding(new Insets(12, 20, 12, 20));

        // Status bar
        statusDot = new Label("●");
        statusDot.getStyleClass().addAll("status-dot", "idle");
        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-label");
        HBox statusBar = new HBox(8, statusDot, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        // ── Waveform (top) ──
        waveform = new WaveformCanvas(numChannels, 2048, 4);
        StackPane wfBox = new StackPane(waveform);
        wfBox.setMinSize(100, 150);
        wfBox.widthProperty().addListener((obs, ov, nv) -> {
            waveform.setWidth(nv.doubleValue());
            waveform.draw();
        });
        wfBox.heightProperty().addListener((obs, ov, nv) -> {
            waveform.setHeight(nv.doubleValue());
            waveform.draw();
        });

        // ── Table (bottom) — per-channel statistics ──
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

        // ── BorderPane: waveform center (grows), table bottom (fixed) ──
        BorderPane vizPane = new BorderPane();
        vizPane.setCenter(wfBox);
        vizPane.setBottom(table);
        VBox.setVgrow(vizPane, Priority.ALWAYS);

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

        VBox centerBox = new VBox(0, vizPane, buttonBar);
        centerBox.setPadding(new Insets(0, 16, 8, 16));
        centerBox.getStyleClass().add("content-area");

        BorderPane root = new BorderPane();
        root.setTop(headerPane);
        root.setCenter(centerBox);
        root.setBottom(statusBar);

        // Button actions
        startBtn.setOnAction(e -> startCapture());
        stopBtn.setOnAction(e -> stopCapture());
        clearBtn.setOnAction(e -> clearAllData());
        exportBtn.setOnAction(e -> exportCSV(stage));

        Scene scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();
    }

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
                // Cap history
                if (allChunks.size() >= MAX_CHUNKS)
                    allChunks.subList(0, allChunks.size() - MAX_CHUNKS + 1).clear();
                allChunks.add(chunk);

                float[] samples = buildWfSamples(chunk);

                Platform.runLater(() -> {
                    if (gen != captureGen) return;
                    waveform.pushChunk(chunk.channel, samples);
                    updateStats();
                    statusDot.getStyleClass().setAll("status-dot", "running");
                    // Rate (sliding window, tracks actual new words)
                    long now = System.currentTimeMillis();
                    int i = chunkIdx++ & 63;
                    chunkTimes[i] = now;
                    chunkWords[i] = chunk.newCount;
                    int n = Math.min(chunkIdx, 64);
                    long oldest = chunkTimes[Math.max(0, chunkIdx - n) & 63];
                    float elapsedSec = Math.max(0.001f, (now - oldest) / 1000f);
                    int totalWords = 0;
                    for (int j = 0; j < n; j++) totalWords += chunkWords[(chunkIdx - 1 - j) & 63];
                    float kBps = totalWords * 4f / 1024f / elapsedSec;  // kB/s
                    long elapsed = now - startTime;
                    if (now - lastStatusUpdate > 500) {
                        lastStatusUpdate = now;
                        statusLabel.setText(String.format(
                                "Running  %02d:%02d  |  %.1f kB/s  |  %d chunks",
                                elapsed / 60000, (elapsed / 1000) % 60, kBps, engine.getChunkCount()));
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

    /** Recompute per-channel stats from the waveform ring buffers. */
    private void updateStats() {
        for (int ch = 0; ch < numChannels; ch++) {
            float[] s = waveform.getChannelStats(ch);
            if (s == null) continue;
            StatRow row = statRows[ch];
            if (row == null) {
                row = new StatRow(ch);
                statRows[ch] = row;
                tableData.add(row);
            }
            row.update(s[0], s[1], s[2], s[3], s[4]);
        }
        table.refresh();
    }

    private void rebuildWaveform() {
        waveform.setNumChannels(numChannels);  // setNumChannels calls clearData+draw
        table.setPrefHeight((int)table.getFixedCellSize() * (numChannels + 1) + 2);
    }

    private void startCapture() {
        statusLabel.setText("Starting proxy...");
        startBtn.setDisable(true);
        new Thread(() -> {
            try {
                if (!proxyLauncher.startIfNeeded()) {
                    Platform.runLater(() -> {
                        startBtn.setDisable(false);
                        statusLabel.setText("Proxy failed to start");
                    });
                    return;
                }
                Platform.runLater(() -> statusLabel.setText("Connecting..."));
                configureEngine();
                startTime = System.currentTimeMillis();
                lastStatusUpdate = 0;
                chunkIdx = 0;
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
            }
        }).start();
    }

    private void stopCapture() {
        engine.stop();
        startBtn.setDisable(false);
        stopBtn.setDisable(true);
        clearBtn.setDisable(false);
        exportBtn.setDisable(false);
    }

    private void clearAllData() {
        allChunks.clear();
        tableData.clear();
        for (int i = 0; i < 4; i++) statRows[i] = null;
        waveform.clearData();
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
                CaptureExporter.exportCSV(new ArrayList<>(allChunks),
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
        if (proxyLauncher != null) proxyLauncher.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
