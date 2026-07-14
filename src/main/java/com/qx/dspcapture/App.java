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
    private static final int BUF_SIZE  = 1024;
    private static final int POLL_MS   = 10;
    private static final String HOST   = "localhost";
    private static final int PORT      = 4333;
    // Adjust to your proxy path
    private static final String PROXY_PATH =
            "C:/Users/95412/Desktop/work/ide_code/ide_source_code/bundles/QXTOOLS/qxtools/toolchain/3slot_320f/bin/or_debug_proxy.exe";
    private static final String CHIP_ARG = "--chip37xd";  // 137 project

    private static final int CHART_DOWNSAMPLE = 2;  // plot every Nth point

    private CaptureEngine engine;
    private ProxyLauncher proxyLauncher;
    private final List<CaptureEngine.Chunk> allChunks = new ArrayList<>();
    private Label statusLabel;
    private Button startBtn, stopBtn, clearBtn, exportBtn;
    private TableView<SampleRow> table;
    private WaveformCanvas waveform;

    // ---- Table data model ----
    public static class SampleRow {
        private final int index;
        private final float value;
        private final int raw;
        private final String hex;
        public SampleRow(int index, float value, int raw) {
            this.index = index;
            this.value = value;
            this.raw = raw;
            this.hex = "0x" + Integer.toHexString(raw).toUpperCase();
        }
        public int getIndex() { return index; }
        public float getValue() { return value; }
        public int getRaw() { return raw; }
        public String getHex() { return hex; }
    }

    private final ObservableList<SampleRow> tableData = FXCollections.observableArrayList();

    @Override
    public void start(Stage stage) {
        proxyLauncher = new ProxyLauncher(PROXY_PATH, HOST, PORT, CHIP_ARG);
        engine = new CaptureEngine()
                .host(HOST).port(PORT)
                .wrAddr(WR_ADDR).bufferAddr(BUF_ADDR)
                .bufferSize(BUF_SIZE).pollIntervalMs(POLL_MS)
                .outputDir(Paths.get(System.getProperty("user.dir"), "captures"))
                .saveToDisk(true);

        engine.addListener(chunk -> {
            allChunks.add(chunk);
            int[] data = chunk.data;
            int wr = chunk.wrValue;
            int n = data.length;
            int step = CHART_DOWNSAMPLE;
            int newCount = chunk.newCount;   // how many words are NEW this chunk

            // Build rows for table (full buffer snapshot) + waveform samples
            SampleRow[] rows = new SampleRow[n];
            float[] wfSamples = new float[n / step];
            for (int i = 0; i < n; i++) {
                int bufIdx = (wr + i) % n;
                int raw = data[bufIdx];
                rows[i] = new SampleRow(bufIdx, Float.intBitsToFloat(raw), raw);
                if (i % step == 0) wfSamples[i / step] = rows[i].getValue();
            }

            // Only push NEW samples to waveform (not the full 512 each time).
            // New data is always at the END of the time-ordered sequence.
            int newWfCount = Math.min(newCount / step, wfSamples.length);
            final float[] pushSamples;
            if (newWfCount >= wfSamples.length || newWfCount <= 0) {
                pushSamples = wfSamples;           // first chunk: push all
            } else {
                pushSamples = Arrays.copyOfRange(wfSamples,
                        wfSamples.length - newWfCount, wfSamples.length);
            }

            Platform.runLater(() -> {
                tableData.setAll(rows);
                waveform.pushChunk(pushSamples);
                statusLabel.setText(String.format(
                        "Chunks: %d | WR: %d | New: %d words | Running: %s",
                        engine.getChunkCount(), wr, newCount, engine.isRunning()));
            });
        });

        engine.addListener(new CaptureEngine.CaptureListener() {
            @Override public void onChunk(CaptureEngine.Chunk chunk) {}
            @Override public void onError(Exception e) {
                Platform.runLater(() ->
                    statusLabel.setText("Error: " + e.getMessage()));
            }
        });

        // ---- Build UI ----
        stage.setTitle("DSP Capture Viewer");

        // ── Header ──
        Label titleLabel = new Label("◆  DSP Capture Viewer");
        titleLabel.getStyleClass().add("header-title");

        Label subtitleLabel = new Label("Real-time DSP buffer monitor  |  " + CHIP_ARG);
        subtitleLabel.getStyleClass().add("header-subtitle");

        VBox headerBox = new VBox(2, titleLabel, subtitleLabel);
        headerBox.getStyleClass().add("header-bar");

        // ── Status bar (bottom) ──
        Label statusDot = new Label("●");
        statusDot.getStyleClass().addAll("status-dot", "idle");

        statusLabel = new Label("Ready");
        statusLabel.getStyleClass().add("status-label");

        HBox statusBar = new HBox(8, statusDot, statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("status-bar");

        // ── Table ──
        table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setAccessibleText("DSP sample data table");
        table.setPlaceholder(new Label("No data captured yet — click Start to begin"));

        TableColumn<SampleRow, Integer> colIdx = new TableColumn<>("#");
        colIdx.setCellValueFactory(new PropertyValueFactory<>("index"));
        colIdx.setPrefWidth(50);

        TableColumn<SampleRow, Float> colVal = new TableColumn<>("Value (float)");
        colVal.setCellValueFactory(new PropertyValueFactory<>("value"));
        colVal.setPrefWidth(120);

        TableColumn<SampleRow, Integer> colRaw = new TableColumn<>("Raw (int)");
        colRaw.setCellValueFactory(new PropertyValueFactory<>("raw"));
        colRaw.setPrefWidth(100);

        TableColumn<SampleRow, String> colHex = new TableColumn<>("Hex");
        colHex.setCellValueFactory(new PropertyValueFactory<>("hex"));
        colHex.setPrefWidth(80);

        table.getColumns().addAll(colIdx, colVal, colRaw, colHex);
        table.setItems(tableData);
        VBox.setVgrow(table, Priority.ALWAYS);

        // ── Waveform Canvas (4 chunks × 512 samples = 2048 total) ──
        waveform = new WaveformCanvas(600, 400, 2048, 4);
        waveform.getStyleClass().add("waveform-canvas");
        waveform.setAccessibleText("Real-time DSP signal waveform");

        // Canvas is not a Region — wrap so SplitPane can size it
        StackPane waveformBox = new StackPane(waveform);
        waveformBox.setMinSize(100, 100);
        waveformBox.widthProperty().addListener((obs, ov, nv) -> {
            waveform.setWidth(nv.doubleValue());
            waveform.draw();
        });
        waveformBox.heightProperty().addListener((obs, ov, nv) -> {
            waveform.setHeight(nv.doubleValue());
            waveform.draw();
        });
        VBox.setVgrow(waveformBox, Priority.ALWAYS);

        // ── SplitPane (table : waveform ≈ 1 : 2.5) ──
        SplitPane splitPane = new SplitPane(table, waveformBox);
        splitPane.setDividerPositions(0.28);
        splitPane.getStyleClass().add("split-pane");

        // ── Buttons ──
        startBtn = new Button("▶  Start");
        startBtn.getStyleClass().add("button-start");
        startBtn.setTooltip(new Tooltip("Connect to DSP proxy and start capturing data"));
        startBtn.setAccessibleText("Start capture");
        startBtn.setMnemonicParsing(true);

        stopBtn  = new Button("■  Stop");
        stopBtn.getStyleClass().add("button-stop");
        stopBtn.setTooltip(new Tooltip("Stop capturing data"));
        stopBtn.setAccessibleText("Stop capture");
        stopBtn.setMnemonicParsing(true);

        clearBtn = new Button("✖  Clear");
        clearBtn.getStyleClass().add("button-clear");
        clearBtn.setTooltip(new Tooltip("Clear all captured data from table and chart"));
        clearBtn.setAccessibleText("Clear data");
        clearBtn.setMnemonicParsing(true);

        exportBtn= new Button("⇩  Export CSV");
        exportBtn.getStyleClass().add("button-export");
        exportBtn.setTooltip(new Tooltip("Export captured data to a CSV file"));
        exportBtn.setAccessibleText("Export CSV");
        exportBtn.setMnemonicParsing(true);

        stopBtn.setDisable(true);
        clearBtn.setDisable(true);
        exportBtn.setDisable(true);

        HBox buttonBar = new HBox(10, startBtn, stopBtn, clearBtn, exportBtn);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(12, 0, 0, 0));

        // ── Center content ──
        VBox centerBox = new VBox(0, splitPane, buttonBar);
        centerBox.setPadding(new Insets(0, 16, 8, 16));
        centerBox.getStyleClass().add("content-area");
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // ── Root layout ──
        BorderPane root = new BorderPane();
        root.setTop(headerBox);
        root.setCenter(centerBox);
        root.setBottom(statusBar);

        // ── Listeners to update status dot color ──
        engine.addListener(new CaptureEngine.CaptureListener() {
            @Override public void onChunk(CaptureEngine.Chunk chunk) {
                Platform.runLater(() ->
                    statusDot.getStyleClass().setAll("status-dot", "running"));
            }
            @Override public void onError(Exception e) {
                Platform.runLater(() ->
                    statusDot.getStyleClass().setAll("status-dot", "error"));
            }
        });

        startBtn.setOnAction(e -> {
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
        });

        stopBtn.setOnAction(e -> {
            engine.stop();
            startBtn.setDisable(false);
            stopBtn.setDisable(true);
            clearBtn.setDisable(tableData.isEmpty());
            exportBtn.setDisable(tableData.isEmpty());
        });

        clearBtn.setOnAction(e -> {
            tableData.clear();
            waveform.clearData();
            startBtn.setDisable(false);
            stopBtn.setDisable(true);
            clearBtn.setDisable(true);
            exportBtn.setDisable(true);
            statusLabel.setText("Cleared");
        });

        exportBtn.setOnAction(e -> {
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
        });

        // ── Scene ──
        Scene scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(
                getClass().getResource("/styles.css").toExternalForm());
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.show();
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
