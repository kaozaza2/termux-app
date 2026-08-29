package com.termux.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.errors.Errno;
import com.termux.shared.logger.Logger;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.plugins.TermuxPluginUtils;

/**
 * An internal non-exported activity that shows a confirmation dialog to the user before an external
 * app is allowed to start an execution command in Termux context via the
 * {@link RUN_COMMAND_SERVICE#ACTION_RUN_COMMAND} intent.
 *
 * The activity is started by {@link RunCommandService} with the execution command intent that should
 * be started if the user allows the command to run. If the user denies the command, then an error
 * result is sent back to the caller instead.
 *
 * The user can optionally choose to remember the decision for the calling app so that it is not asked
 * again for the same app.
 */
public class RunCommandConfirmationActivity extends Activity {

    private static final String LOG_TAG = "RunCommandConfirmationActivity";

    /** Name of the {@link SharedPreferences} file used to remember decisions for each calling app. */
    private static final String CONFIRMATION_PREFS_FILE = "termux_run_command_confirmation";
    /** {@link SharedPreferences} key prefix for remembering the decision for a calling app. */
    private static final String PREF_ALLOWED_KEY_PREFIX = "allowed_";

    private Intent mExecIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.logDebug(LOG_TAG, "onCreate");

        Intent intent = getIntent();
        mExecIntent = intent.getParcelableExtra(RUN_COMMAND_SERVICE.EXTRA_EXEC_INTENT);

        // Cancel the fallback confirmation notification if one was shown
        int notificationId = intent.getIntExtra(RUN_COMMAND_SERVICE.EXTRA_CONFIRMATION_NOTIFICATION_ID, -1);
        if (notificationId != -1) {
            final NotificationManager notificationManager = NotificationUtils.getNotificationManager(this);
            if (notificationManager != null)
                notificationManager.cancel(notificationId);
        }

        if (mExecIntent == null) {
            Logger.logWarn(LOG_TAG, "The exec intent is null, finishing " + LOG_TAG);
            finish();
            return;
        }

        String callerPackageName = intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_CALLER_PACKAGE_NAME);
        String callerAppLabel = intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_CALLER_APP_LABEL);

        showConfirmationDialog(callerPackageName, callerAppLabel);
    }

    private void showConfirmationDialog(@Nullable final String callerPackageName, @Nullable final String callerAppLabel) {
        final CheckBox rememberCheckBox = new CheckBox(this);
        if (callerPackageName != null) {
            rememberCheckBox.setText(getString(R.string.run_command_confirm_remember,
                getCallerLabelForDisplay(callerPackageName, callerAppLabel)));
            rememberCheckBox.setChecked(false);
        } else {
            // Do not offer to remember the decision for an unknown caller since the caller
            // identity cannot be verified
            rememberCheckBox.setVisibility(View.GONE);
        }

        TextView commandDetailsView = new TextView(this);
        commandDetailsView.setText(getCommandDetailsText(callerPackageName, callerAppLabel));
        commandDetailsView.setTextIsSelectable(true);

        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout contentLayout = new LinearLayout(this);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(padding, padding, padding, padding);
        contentLayout.addView(commandDetailsView);
        contentLayout.addView(rememberCheckBox, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 0));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(contentLayout);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.run_command_confirm_title));
        builder.setView(scrollView);
        builder.setCancelable(true);
        builder.setOnCancelListener(dialog -> deny(callerPackageName, false));
        builder.setPositiveButton(getString(R.string.run_command_confirm_allow),
            (dialog, which) -> allow(callerPackageName, rememberCheckBox.isChecked()));
        builder.setNegativeButton(getString(R.string.run_command_confirm_deny),
            (dialog, which) -> deny(callerPackageName, rememberCheckBox.isChecked()));

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private String getCommandDetailsText(@Nullable String callerPackageName, @Nullable String callerAppLabel) {
        String callerLabel = getCallerLabelForDisplay(callerPackageName, callerAppLabel);
        StringBuilder details = new StringBuilder(getString(R.string.run_command_confirm_caller_label, callerLabel));
        if (callerPackageName != null)
            details.append(" (").append(callerPackageName).append(")");
        details.append("\n\n");

        String executable = UriUtils.getUriFilePathWithFragment(mExecIntent.getData());
        details.append(getString(R.string.run_command_confirm_command_label,
            DataUtils.getDefaultIfNull(executable, "-")));

        String[] arguments = IntentUtils.getStringArrayExtraIfSet(mExecIntent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
        if (arguments != null && arguments.length != 0) {
            StringBuilder argumentsString = new StringBuilder();
            for (int i = 0; i < arguments.length; i++) {
                if (i != 0) argumentsString.append(", ");
                argumentsString.append(DataUtils.getTruncatedCommandOutput(arguments[i], 100, true, false, true));
            }
            details.append("\n").append(getString(R.string.run_command_confirm_arguments_label, argumentsString));
        }

        String workingDirectory = IntentUtils.getStringExtraIfSet(mExecIntent, TERMUX_SERVICE.EXTRA_WORKDIR, null);
        if (workingDirectory != null)
            details.append("\n").append(getString(R.string.run_command_confirm_workdir_label, workingDirectory));

        return details.toString();
    }

    private String getCallerLabelForDisplay(@Nullable String callerPackageName, @Nullable String callerAppLabel) {
        if (callerAppLabel != null && !callerAppLabel.isEmpty()) return callerAppLabel;
        if (callerPackageName != null) return callerPackageName;
        return getString(R.string.run_command_confirm_caller_unknown);
    }

    private void allow(@Nullable String callerPackageName, boolean remember) {
        if (remember && callerPackageName != null)
            setRememberedDecision(this, callerPackageName, true);
        startServiceExecuteIntent();
        finish();
    }

    private void deny(@Nullable String callerPackageName, boolean remember) {
        if (remember && callerPackageName != null)
            setRememberedDecision(this, callerPackageName, false);
        sendDenialError();
        finish();
    }

    private void startServiceExecuteIntent() {
        Intent execIntent = new Intent(mExecIntent);
        // TERMUX_SERVICE is a foreground service, so it must be started with startForegroundService()
        // on Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(execIntent);
        else
            startService(execIntent);
    }

    private void sendDenialError() {
        ExecutionCommand executionCommand = buildExecutionCommandForResult(mExecIntent);
        if (executionCommand == null) return;

        String errmsg = getString(R.string.error_run_command_service_denied_by_user);
        executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
        TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
    }

    /** Build an {@link ExecutionCommand} from the exec intent so that an error result can be sent
     * back to the command caller. */
    private static ExecutionCommand buildExecutionCommandForResult(Intent execIntent) {
        if (execIntent == null) return null;

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.executableUri = execIntent.getData();
        executionCommand.executable = UriUtils.getUriFilePathWithFragment(execIntent.getData());
        executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
        executionCommand.stdin = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_STDIN, null);
        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_WORKDIR, null);
        executionCommand.runner = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_RUNNER, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "RUN_COMMAND Execution Command");
        executionCommand.isPluginExecutionCommand = true;
        executionCommand.resultConfig.resultPendingIntent = execIntent.getParcelableExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT);
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = execIntent.getBooleanExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }

        return executionCommand;
    }

    /**
     * Get the decision previously remembered for the calling app, or {@code null} if no decision
     * was remembered yet or if the caller identity is unknown.
     *
     * @param context The {@link Context} for operations.
     * @param callerPackageName The package name of the calling app.
     * @return Returns {@link Boolean#TRUE} if the app was allowed, {@link Boolean#FALSE} if the app
     * was denied, or {@code null} if no decision was remembered.
     */
    @Nullable
    public static Boolean getRememberedDecision(Context context, String callerPackageName) {
        if (context == null || DataUtils.isNullOrEmpty(callerPackageName)) return null;

        SharedPreferences prefs = context.getSharedPreferences(CONFIRMATION_PREFS_FILE, Context.MODE_PRIVATE);
        String key = PREF_ALLOWED_KEY_PREFIX + callerPackageName;
        if (!prefs.contains(key)) return null;
        return prefs.getBoolean(key, false);
    }

    /**
     * Remember the decision for the calling app so that the user is not asked again.
     *
     * @param context The {@link Context} for operations.
     * @param callerPackageName The package name of the calling app.
     * @param allowed If {@code true}, the app is allowed to run commands without asking, otherwise
     *                it is denied the ability to run commands without asking.
     */
    public static void setRememberedDecision(Context context, String callerPackageName, boolean allowed) {
        if (context == null || DataUtils.isNullOrEmpty(callerPackageName)) return;

        context.getSharedPreferences(CONFIRMATION_PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_ALLOWED_KEY_PREFIX + callerPackageName, allowed).apply();
    }

}