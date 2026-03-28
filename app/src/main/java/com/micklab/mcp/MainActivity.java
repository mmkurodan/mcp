package com.micklab.mcp;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.micklab.mcp.api.CustomHttpApiDefinition;
import com.micklab.mcp.api.CustomHttpApiToolHandler;
import com.micklab.mcp.runtime.McpRuntimeBootstrap;
import com.micklab.mcp.service.McpForegroundService;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final List<CustomHttpApiDefinition> customApis = new ArrayList<>();

    private TextView statusView;
    private EditText toolNameInput;
    private EditText descriptionInput;
    private Spinner methodSpinner;
    private EditText urlInput;
    private EditText timeoutInput;
    private EditText headersInput;
    private EditText bodyInput;
    private EditText testOverridesInput;
    private TextView editorStatusView;
    private LinearLayout apiListContainer;
    private TextView testResultView;
    private TextView manualView;

    private String editingApiId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestNotificationPermissionIfNeeded();
        setContentView(R.layout.activity_main);
        bindViews();
        configureMethodSpinner();
        configureButtons();
        clearForm();
        loadManual();
        loadCustomApis();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCustomApis();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }

    private void bindViews() {
        statusView = findViewById(R.id.statusView);
        toolNameInput = findViewById(R.id.toolNameInput);
        descriptionInput = findViewById(R.id.descriptionInput);
        methodSpinner = findViewById(R.id.methodSpinner);
        urlInput = findViewById(R.id.urlInput);
        timeoutInput = findViewById(R.id.timeoutInput);
        headersInput = findViewById(R.id.headersInput);
        bodyInput = findViewById(R.id.bodyInput);
        testOverridesInput = findViewById(R.id.testOverridesInput);
        editorStatusView = findViewById(R.id.editorStatusView);
        apiListContainer = findViewById(R.id.apiListContainer);
        testResultView = findViewById(R.id.testResultView);
        manualView = findViewById(R.id.manualView);
    }

    private void configureMethodSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                CustomHttpApiDefinition.supportedMethods()
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        methodSpinner.setAdapter(adapter);
    }

    private void configureButtons() {
        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(view -> {
            Intent intent = new Intent(this, McpForegroundService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            refreshStatus("Starting embedded MCP service...");
            statusView.postDelayed(this::refreshStatus, 750L);
        });

        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(view -> {
            stopService(new Intent(this, McpForegroundService.class));
            refreshStatus("Stopping embedded MCP service...");
            statusView.postDelayed(this::refreshStatus, 500L);
        });

        Button saveApiButton = findViewById(R.id.saveApiButton);
        saveApiButton.setOnClickListener(view -> saveCurrentApi());

        Button clearFormButton = findViewById(R.id.clearFormButton);
        clearFormButton.setOnClickListener(view -> {
            clearForm();
            editorStatusView.setText("API editor cleared.");
        });

        Button testEditorButton = findViewById(R.id.testEditorButton);
        testEditorButton.setOnClickListener(view -> {
            try {
                CustomHttpApiDefinition definition = buildDefinitionFromForm();
                runLocalTest(definition, false);
            } catch (Exception exception) {
                editorStatusView.setText(buildErrorMessage("Unable to start local test", exception));
            }
        });
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_POST_NOTIFICATIONS
                );
            }
        }
    }

    private void loadManual() {
        try {
            manualView.setText(readAssetText("manual/user_manual.txt"));
        } catch (Exception exception) {
            manualView.setText(buildErrorMessage("Unable to load manual", exception));
        }
    }

    private void loadCustomApis() {
        try {
            customApis.clear();
            customApis.addAll(McpRuntimeBootstrap.getInstance(getApplicationContext()).loadCustomApis());
            if (editingApiId != null && findApiById(editingApiId) == null) {
                clearForm();
            }
            renderApiList();
        } catch (Exception exception) {
            editorStatusView.setText(buildErrorMessage("Unable to load saved APIs", exception));
        }
    }

    private void renderApiList() {
        apiListContainer.removeAllViews();
        if (customApis.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText("No custom APIs have been saved yet.");
            apiListContainer.addView(emptyView);
            return;
        }
        for (CustomHttpApiDefinition definition : customApis) {
            apiListContainer.addView(createApiCard(definition));
        }
    }

    private LinearLayout createApiCard(CustomHttpApiDefinition definition) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardLayoutParams.topMargin = dpToPx(12);
        card.setLayoutParams(cardLayoutParams);
        card.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        card.setBackgroundColor(0xFFF1F1F1);

        TextView summaryView = new TextView(this);
        summaryView.setText(
                definition.getToolName()
                        + "\n"
                        + definition.getMethod() + " " + definition.getUrl()
                        + "\n"
                        + definition.getDescription()
        );
        summaryView.setTextIsSelectable(true);
        card.addView(summaryView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsLayoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        actionsLayoutParams.topMargin = dpToPx(8);
        actions.setLayoutParams(actionsLayoutParams);

        Button editButton = new Button(this);
        editButton.setText("Edit");
        editButton.setOnClickListener(view -> {
            populateForm(definition);
            editorStatusView.setText("Loaded " + definition.getToolName() + " into the editor.");
        });
        actions.addView(editButton, buildWeightedButtonLayoutParams(false));

        Button testButton = new Button(this);
        testButton.setText("Test");
        testButton.setOnClickListener(view -> runLocalTest(definition, true));
        actions.addView(testButton, buildWeightedButtonLayoutParams(true));

        Button deleteButton = new Button(this);
        deleteButton.setText("Delete");
        deleteButton.setOnClickListener(view -> deleteApi(definition));
        actions.addView(deleteButton, buildWeightedButtonLayoutParams(true));

        card.addView(actions);
        return card;
    }

    private LinearLayout.LayoutParams buildWeightedButtonLayoutParams(boolean addStartMargin) {
        LinearLayout.LayoutParams layoutParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        if (addStartMargin) {
            layoutParams.setMarginStart(dpToPx(8));
        }
        return layoutParams;
    }

    private void saveCurrentApi() {
        try {
            CustomHttpApiDefinition definition = buildDefinitionFromForm();
            List<CustomHttpApiDefinition> updatedApis = new ArrayList<>(customApis);
            int existingIndex = findApiIndex(definition.getId());
            if (existingIndex >= 0) {
                updatedApis.set(existingIndex, definition);
            } else {
                updatedApis.add(definition);
            }
            McpRuntimeBootstrap.getInstance(getApplicationContext()).saveCustomApis(updatedApis);
            customApis.clear();
            customApis.addAll(updatedApis);
            populateForm(definition);
            renderApiList();
            editorStatusView.setText("Saved API " + definition.getToolName() + ".");
            refreshStatus("Saved API " + definition.getToolName() + ".");
        } catch (Exception exception) {
            editorStatusView.setText(buildErrorMessage("Unable to save API", exception));
        }
    }

    private void deleteApi(CustomHttpApiDefinition definition) {
        try {
            List<CustomHttpApiDefinition> updatedApis = new ArrayList<>(customApis);
            int existingIndex = findApiIndex(definition.getId());
            if (existingIndex < 0) {
                throw new IllegalStateException("The API is no longer present in local storage.");
            }
            updatedApis.remove(existingIndex);
            McpRuntimeBootstrap.getInstance(getApplicationContext()).saveCustomApis(updatedApis);
            customApis.clear();
            customApis.addAll(updatedApis);
            if (definition.getId().equals(editingApiId)) {
                clearForm();
            }
            renderApiList();
            editorStatusView.setText("Deleted API " + definition.getToolName() + ".");
            refreshStatus("Deleted API " + definition.getToolName() + ".");
        } catch (Exception exception) {
            editorStatusView.setText(buildErrorMessage("Unable to delete API", exception));
        }
    }

    private void runLocalTest(CustomHttpApiDefinition definition, boolean fromSavedApi) {
        JSONObject overrides;
        try {
            overrides = parseTestOverrides();
        } catch (Exception exception) {
            editorStatusView.setText(buildErrorMessage("Invalid local test overrides", exception));
            return;
        }
        editorStatusView.setText("Running local test for " + definition.getToolName() + "...");
        testResultView.setText("");
        backgroundExecutor.execute(() -> {
            try {
                JSONObject result = new CustomHttpApiToolHandler(definition).handle(overrides);
                String renderedResult = result.toString(2);
                runOnUiThread(() -> {
                    testResultView.setText(renderedResult);
                    editorStatusView.setText(
                            (fromSavedApi ? "Saved" : "Draft")
                                    + " API local test succeeded for "
                                    + definition.getToolName()
                                    + "."
                    );
                    refreshStatus("Local test completed for " + definition.getToolName() + ".");
                });
            } catch (Exception exception) {
                String errorText = buildErrorMessage("Local test failed", exception);
                runOnUiThread(() -> {
                    testResultView.setText(errorText);
                    editorStatusView.setText(errorText);
                });
            }
        });
    }

    private JSONObject parseTestOverrides() throws Exception {
        String rawOverrides = testOverridesInput.getText().toString().trim();
        if (rawOverrides.isEmpty()) {
            return new JSONObject();
        }
        return new JSONObject(rawOverrides);
    }

    private CustomHttpApiDefinition buildDefinitionFromForm() throws Exception {
        String id = editingApiId != null ? editingApiId : UUID.randomUUID().toString();
        return CustomHttpApiDefinition.create(
                id,
                toolNameInput.getText().toString(),
                descriptionInput.getText().toString(),
                String.valueOf(methodSpinner.getSelectedItem()),
                urlInput.getText().toString(),
                parseTimeoutMs(timeoutInput.getText().toString()),
                headersInput.getText().toString(),
                bodyInput.getText().toString()
        );
    }

    private void populateForm(CustomHttpApiDefinition definition) {
        editingApiId = definition.getId();
        toolNameInput.setText(definition.getToolName());
        descriptionInput.setText(definition.getDescription());
        methodSpinner.setSelection(resolveMethodIndex(definition.getMethod()));
        urlInput.setText(definition.getUrl());
        timeoutInput.setText(Integer.toString(definition.getTimeoutMs()));
        headersInput.setText(definition.getDefaultHeadersText());
        bodyInput.setText(definition.getDefaultBodyText());
    }

    private void clearForm() {
        editingApiId = null;
        toolNameInput.setText("");
        descriptionInput.setText("");
        methodSpinner.setSelection(0);
        urlInput.setText("");
        timeoutInput.setText("3000");
        headersInput.setText("");
        bodyInput.setText("");
        testOverridesInput.setText("");
    }

    private int resolveMethodIndex(String method) {
        String[] methods = CustomHttpApiDefinition.supportedMethods();
        for (int index = 0; index < methods.length; index++) {
            if (methods[index].equals(method)) {
                return index;
            }
        }
        return 0;
    }

    private int parseTimeoutMs(String rawTimeoutMs) {
        String trimmed = rawTimeoutMs == null ? "" : rawTimeoutMs.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Timeout must be a number in milliseconds.", exception);
        }
    }

    private CustomHttpApiDefinition findApiById(String id) {
        int index = findApiIndex(id);
        return index >= 0 ? customApis.get(index) : null;
    }

    private int findApiIndex(String id) {
        for (int index = 0; index < customApis.size(); index++) {
            if (customApis.get(index).getId().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void refreshStatus() {
        refreshStatus(null);
    }

    private void refreshStatus(String prefix) {
        McpRuntimeBootstrap bootstrap = McpRuntimeBootstrap.getInstance(getApplicationContext());
        StringBuilder builder = new StringBuilder();
        if (prefix != null && !prefix.isEmpty()) {
            builder.append(prefix).append("\n\n");
        }
        builder.append("Port: 127.0.0.1:").append(bootstrap.getMcpPort()).append("\n\n");
        builder.append("Saved custom APIs: ").append(customApis.size()).append("\n\n");
        builder.append("Runtime state:\n").append(bootstrap.describeState()).append("\n\n");
        builder.append("Bundled layout:\n").append(bootstrap.describeLayout()).append("\n\n");
        builder.append("JSON-RPC endpoint:\nPOST http://127.0.0.1:")
                .append(bootstrap.getMcpPort())
                .append("/rpc");
        statusView.setText(builder.toString());
    }

    private String readAssetText(String assetPath) throws Exception {
        try (InputStream inputStream = getAssets().open(assetPath);
             Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
             StringWriter writer = new StringWriter()) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, read);
            }
            return writer.toString();
        }
    }

    private String buildErrorMessage(String prefix, Exception exception) {
        return prefix + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }

    private int dpToPx(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
