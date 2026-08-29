package com.termux.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.termux.R;
import com.termux.shared.data.DataUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.net.uri.UriUtils;
import com.termux.shared.termux.plugins.TermuxPluginUtils;
import com.termux.shared.termux.file.TermuxFileUtils;
import com.termux.shared.file.filesystem.FileType;
import com.termux.shared.errors.Errno;
import com.termux.shared.errors.Error;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.notification.NotificationUtils;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A service that receives {@link RUN_COMMAND_SERVICE#ACTION_RUN_COMMAND} intent from third party apps and
 * plugins that contains info on command execution and forwards the extras to {@link TermuxService}
 * for the actual execution.
 *
 * Check https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent for more info.
 */
public class RunCommandService extends Service {

    private static final String LOG_TAG = "RunCommandService";

    private static final AtomicInteger sConfirmationNotificationCounter = new AtomicInteger(0);

    class LocalBinder extends Binder {
        public final RunCommandService service = RunCommandService.this;
    }

    private final IBinder mBinder = new RunCommandService.LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        Logger.logVerbose(LOG_TAG, "onCreate");
        runStartForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Logger.logDebug(LOG_TAG, "onStartCommand");

        if (intent == null) return Service.START_NOT_STICKY;

        // Run again in case service is already started and onCreate() is not called
        runStartForeground();

        Logger.logVerboseExtended(LOG_TAG, "Intent Received:\n" + IntentUtils.getIntentString(intent));

        ExecutionCommand executionCommand = new ExecutionCommand();
        executionCommand.pluginAPIHelp = this.getString(R.string.error_run_command_service_api_help, RUN_COMMAND_SERVICE.RUN_COMMAND_API_HELP_URL);

        Error error;
        String errmsg;

        // If invalid action passed, then just return
        if (!RUN_COMMAND_SERVICE.ACTION_RUN_COMMAND.equals(intent.getAction())) {
            errmsg = this.getString(R.string.error_run_command_service_invalid_intent_action, intent.getAction());
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }

        String executableExtra = executionCommand.executable = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH, null);
        executionCommand.arguments = IntentUtils.getStringArrayExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_ARGUMENTS, null);

        /*
        * If intent was sent with `am` command, then normal comma characters may have been replaced
        * with alternate characters if a normal comma existed in an argument itself to prevent it
        * splitting into multiple arguments by `am` command.
        * If `tudo` or `sudo` are used, then simply using their `-r` and `--comma-alternative` command
        * options can be used without passing the below extras, but native supports is helpful if
        * they are not being used.
        * https://github.com/agnostic-apollo/tudo#passing-arguments-using-run_command-intent
        * https://android.googlesource.com/platform/frameworks/base/+/21bdaf1/cmds/am/src/com/android/commands/am/Am.java#572
        */
        boolean replaceCommaAlternativeCharsInArguments = intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_REPLACE_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS, false);
        if (replaceCommaAlternativeCharsInArguments) {
            String commaAlternativeCharsInArguments = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMA_ALTERNATIVE_CHARS_IN_ARGUMENTS, null);
            if (commaAlternativeCharsInArguments == null)
                commaAlternativeCharsInArguments = TermuxConstants.COMMA_ALTERNATIVE;
            // Replace any commaAlternativeCharsInArguments characters with normal commas
            DataUtils.replaceSubStringsInStringArrayItems(executionCommand.arguments, commaAlternativeCharsInArguments, TermuxConstants.COMMA_NORMAL);
        }

        executionCommand.stdin = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_STDIN, null);
        executionCommand.workingDirectory = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_WORKDIR, null);

        // If EXTRA_RUNNER is passed, use that, otherwise check EXTRA_BACKGROUND and default to Runner.TERMINAL_SESSION
        executionCommand.runner = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RUNNER,
            (intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_BACKGROUND, false) ? Runner.APP_SHELL.getName() : Runner.TERMINAL_SESSION.getName()));
        if (Runner.runnerOf(executionCommand.runner) == null) {
            errmsg = this.getString(R.string.error_run_command_service_invalid_execution_command_runner, executionCommand.runner);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }

        executionCommand.backgroundCustomLogLevel = IntentUtils.getIntegerExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, null);
        executionCommand.sessionAction = intent.getStringExtra(RUN_COMMAND_SERVICE.EXTRA_SESSION_ACTION);
        executionCommand.shellName = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_SHELL_NAME, null);
        executionCommand.shellCreateMode = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_SHELL_CREATE_MODE, null);
        executionCommand.commandLabel = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_LABEL, "RUN_COMMAND Execution Intent Command");
        executionCommand.commandDescription = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_DESCRIPTION, null);
        executionCommand.commandHelp = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_COMMAND_HELP, null);
        executionCommand.isPluginExecutionCommand = true;
        executionCommand.resultConfig.resultPendingIntent = intent.getParcelableExtra(RUN_COMMAND_SERVICE.EXTRA_PENDING_INTENT);

        // If "allow-external-apps" property to not set to "true", then just return
        // We enable force notifications if "allow-external-apps" policy is violated so that the
        // user knows someone tried to run a command in termux context, since it may be malicious
        // app or imported (tasker) plugin project and not the user himself. If a pending intent is
        // also sent, then its creator is also logged and shown.
        errmsg = TermuxPluginUtils.checkIfAllowExternalAppsPolicyIsViolated(this, LOG_TAG);
        if (errmsg != null) {
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, true);
            return stopService();
        }

        // Do not send result back to any file based result config before "allow-external-app"
        // property has been ensured to be "true", otherwise clients can overwrite files inside
        // Termux home or prefix, or external storage (if Termux has been granted permission) using
        // `ResultConfig.resultFileErrorFormat` as `ResultSender.sendCommandResultDataToDirectory()`
        // uses client controlled format passed to `String.format()` that is used to set the error
        // file content, which can even be used to overwrite `termux.properties` file to set
        // `allow-external-app=true` in it or any other shell rc file. The client would still need
        // to have the `RUN_COMMAND` permission before it could do this.
        // Note that clients waiting for file based result will hang if "allow-external-app" property
        // is not "true" if we do not send the result, but a notification will still be shown for
        // clients to know. A result is still sent if pending intent is used as that does not have
        // this security issue.
        executionCommand.resultConfig.resultDirectoryPath = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_DIRECTORY, null);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            executionCommand.resultConfig.resultSingleFile = intent.getBooleanExtra(RUN_COMMAND_SERVICE.EXTRA_RESULT_SINGLE_FILE, false);
            executionCommand.resultConfig.resultFileBasename = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_BASENAME, null);
            executionCommand.resultConfig.resultFileOutputFormat = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, null);
            executionCommand.resultConfig.resultFileErrorFormat = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, null);
            executionCommand.resultConfig.resultFilesSuffix = IntentUtils.getStringExtraIfSet(intent, RUN_COMMAND_SERVICE.EXTRA_RESULT_FILES_SUFFIX, null);
        }



        // If executable is null or empty, then exit here instead of getting canonical path which would expand to "/"
        if (executionCommand.executable == null || executionCommand.executable.isEmpty()) {
            errmsg  = this.getString(R.string.error_run_command_service_mandatory_extra_missing, RUN_COMMAND_SERVICE.EXTRA_COMMAND_PATH);
            executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }

        // Get canonical path of executable
        executionCommand.executable = TermuxFileUtils.getCanonicalPath(executionCommand.executable, null, true);

        // If executable is not a regular file, or is not readable or executable, then just return
        // Setting of missing read and execute permissions is not done
        error = FileUtils.validateRegularFileExistenceAndPermissions("executable", executionCommand.executable, null,
            FileUtils.APP_EXECUTABLE_FILE_PERMISSIONS, true, true,
            false);
        if (error != null) {
            executionCommand.setStateFailed(error);
            TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
            return stopService();
        }



        // If workingDirectory is not null or empty
        if (executionCommand.workingDirectory != null && !executionCommand.workingDirectory.isEmpty()) {
            // Get canonical path of workingDirectory
            executionCommand.workingDirectory = TermuxFileUtils.getCanonicalPath(executionCommand.workingDirectory, null, true);

            // If workingDirectory is not a directory, or is not readable or writable, then just return
            // Creation of missing directory and setting of read, write and execute permissions are only done if workingDirectory is
            // under allowed termux working directory paths.
            // We try to set execute permissions, but ignore if they are missing, since only read and write permissions are required
            // for working directories.
            error = TermuxFileUtils.validateDirectoryFileExistenceAndPermissions("working", executionCommand.workingDirectory,
                true, true, true,
                false, true);
            if (error != null) {
                executionCommand.setStateFailed(error);
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
                return stopService();
            }
        }

        // If the executable passed as the extra was an applet for coreutils/busybox, then we must
        // use it instead of the canonical path above since otherwise arguments would be passed to
        // coreutils/busybox instead and command would fail. Broken symlinks would already have been
        // validated so it should be fine to use it.
        executableExtra = TermuxFileUtils.getExpandedTermuxPath(executableExtra);
        if (FileUtils.getFileType(executableExtra, false) == FileType.SYMLINK) {
            Logger.logVerbose(LOG_TAG, "The executableExtra path \"" + executableExtra + "\" is a symlink so using it instead of the canonical path \"" + executionCommand.executable + "\"");
            executionCommand.executable = executableExtra;
        }

        executionCommand.executableUri = new Uri.Builder().scheme(TERMUX_SERVICE.URI_SCHEME_SERVICE_EXECUTE).path(executionCommand.executable).build();

        Logger.logVerboseExtended(LOG_TAG, executionCommand.toString());

        // Create execution intent with the action TERMUX_SERVICE#ACTION_SERVICE_EXECUTE to be sent to the TERMUX_SERVICE
        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, executionCommand.executableUri);
        execIntent.setClass(this, TermuxService.class);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, executionCommand.arguments);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_STDIN, executionCommand.stdin);
        if (executionCommand.workingDirectory != null && !executionCommand.workingDirectory.isEmpty()) execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, executionCommand.workingDirectory);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_RUNNER, executionCommand.runner);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_BACKGROUND_CUSTOM_LOG_LEVEL, DataUtils.getStringFromInteger(executionCommand.backgroundCustomLogLevel, null));
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION, executionCommand.sessionAction);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, executionCommand.shellName);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_SHELL_CREATE_MODE, executionCommand.shellCreateMode);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, executionCommand.commandLabel);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_DESCRIPTION, executionCommand.commandDescription);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_HELP, executionCommand.commandHelp);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_PLUGIN_API_HELP, executionCommand.pluginAPIHelp);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_PENDING_INTENT, executionCommand.resultConfig.resultPendingIntent);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_DIRECTORY, executionCommand.resultConfig.resultDirectoryPath);
        if (executionCommand.resultConfig.resultDirectoryPath != null) {
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_SINGLE_FILE, executionCommand.resultConfig.resultSingleFile);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILE_BASENAME, executionCommand.resultConfig.resultFileBasename);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILE_OUTPUT_FORMAT, executionCommand.resultConfig.resultFileOutputFormat);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILE_ERROR_FORMAT, executionCommand.resultConfig.resultFileErrorFormat);
            execIntent.putExtra(TERMUX_SERVICE.EXTRA_RESULT_FILES_SUFFIX, executionCommand.resultConfig.resultFilesSuffix);
        }

        // Start the execution command only after the user has been asked to confirm it, since the
        // RUN_COMMAND permission can be requested and granted to any app
        startServiceExecuteIntentForPluginCaller(execIntent, executionCommand);

        return stopService();
    }

    private int stopService() {
        runStopForeground();
        return Service.START_NOT_STICKY;
    }

    /**
     * Start the {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} exec intent after checking whether the
     * user needs to confirm the RUN_COMMAND execution first.
     *
     * If a decision has previously been remembered for the calling app, then that decision is used,
     * otherwise the user is asked for confirmation via a dialog. A fallback notification, which opens
     * the confirmation dialog when tapped, is also shown since a service cannot always start an
     * activity when the app is in the background.
     *
     * @param execIntent The {@link TERMUX_SERVICE#ACTION_SERVICE_EXECUTE} intent to start if the
     *                   command is allowed.
     * @param executionCommand The {@link ExecutionCommand} matching the exec intent, used to send a
     *                         result back if the command is denied.
     */
    private void startServiceExecuteIntentForPluginCaller(Intent execIntent, ExecutionCommand executionCommand) {
        // A started service cannot determine the uid of the app which started it, so the caller
        // package name is derived from the creator of the pending intent (if one was sent) since
        // that is recorded by the system and cannot be spoofed.
        String callerPackageName = null;
        if (executionCommand.resultConfig.resultPendingIntent != null)
            callerPackageName = executionCommand.resultConfig.resultPendingIntent.getCreatorPackage();

        if (callerPackageName != null) {
            Boolean rememberedDecision = RunCommandConfirmationActivity.getRememberedDecision(this, callerPackageName);
            if (Boolean.TRUE.equals(rememberedDecision)) {
                // User had previously allowed this app to run commands
                startServiceExecuteIntent(execIntent);
                return;
            } else if (Boolean.FALSE.equals(rememberedDecision)) {
                // User had previously denied command execution to this app
                String errmsg = this.getString(R.string.error_run_command_service_denied_by_user);
                executionCommand.setStateFailed(Errno.ERRNO_FAILED.getCode(), errmsg);
                TermuxPluginUtils.processPluginExecutionCommandError(this, LOG_TAG, executionCommand, false);
                return;
            }
        }

        int notificationId = TermuxConstants.TERMUX_RUN_COMMAND_CONFIRMATION_NOTIFICATION_ID + sConfirmationNotificationCounter.incrementAndGet();

        Intent confirmationIntent = new Intent(this, RunCommandConfirmationActivity.class);
        confirmationIntent.putExtra(RUN_COMMAND_SERVICE.EXTRA_EXEC_INTENT, execIntent);
        confirmationIntent.putExtra(RUN_COMMAND_SERVICE.EXTRA_CALLER_PACKAGE_NAME, callerPackageName);
        confirmationIntent.putExtra(RUN_COMMAND_SERVICE.EXTRA_CALLER_APP_LABEL, getCallerLabel(callerPackageName));
        confirmationIntent.putExtra(RUN_COMMAND_SERVICE.EXTRA_CONFIRMATION_NOTIFICATION_ID, notificationId);
        confirmationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        showRunCommandConfirmationNotification(confirmationIntent, notificationId, callerPackageName, getCommandSummaryForDisplay(execIntent));

        try {
            startActivity(confirmationIntent);
        } catch (RuntimeException e) {
            // Starting an activity from the background may be restricted by Android. The confirmation
            // notification shown above still allows the user to review and approve or deny the command.
            Logger.logError(LOG_TAG, "Failed to start RunCommandConfirmationActivity: " + e.getMessage());
        }
    }

    /** Start {@link TERMUX_SERVICE} and pass it the exec intent. */
    private void startServiceExecuteIntent(Intent execIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            this.startForegroundService(execIntent);
        } else {
            this.startService(execIntent);
        }
    }

    /** Get the display label of the caller app, falling back to the package name. */
    private String getCallerLabel(String callerPackageName) {
        if (callerPackageName == null) return null;
        try {
            PackageManager packageManager = getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(callerPackageName, 0);
            return packageManager.getApplicationLabel(applicationInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return callerPackageName;
        }
    }

    /** Get a short command summary (executable and first argument) for the confirmation notification. */
    private String getCommandSummaryForDisplay(Intent execIntent) {
        String executable = UriUtils.getUriFilePathWithFragment(execIntent.getData());
        String commandSummary = DataUtils.getDefaultIfNull(executable, "-");

        String[] arguments = IntentUtils.getStringArrayExtraIfSet(execIntent, TERMUX_SERVICE.EXTRA_ARGUMENTS, null);
        if (arguments != null && arguments.length != 0)
            commandSummary += " " + DataUtils.getTruncatedCommandOutput(arguments[0], 100, true, false, true);

        return commandSummary;
    }

    /** Show a fallback notification for the RUN_COMMAND confirmation prompt. */
    private void showRunCommandConfirmationNotification(Intent confirmationIntent, int notificationId, String callerPackageName, String commandSummary) {
        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_RUN_COMMAND_CONFIRMATION_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_RUN_COMMAND_CONFIRMATION_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);

        int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, notificationId, confirmationIntent, pendingIntentFlags);

        String callerLabel = callerPackageName != null ? getCallerLabel(callerPackageName) :
            getString(R.string.run_command_confirm_caller_unknown);

        Notification.Builder builder = NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_RUN_COMMAND_CONFIRMATION_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_HIGH,
            getString(R.string.run_command_confirm_title),
            getString(R.string.run_command_confirm_notification_text, callerLabel, commandSummary),
            null,
            contentIntent,
            null,
            NotificationUtils.NOTIFICATION_MODE_SOUND);
        if (builder == null) return;

        builder.setSmallIcon(R.drawable.ic_service_notification);
        builder.setColor(0xFF607D8B);
        builder.setOnlyAlertOnce(true);
        builder.setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null)
            notificationManager.notify(notificationId, builder.build());
    }

    private void runStartForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotificationChannel();
            startForeground(TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_ID, buildNotification());
        }
    }

    private void runStopForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopForeground(true);
        }
    }

    private Notification buildNotification() {
        // Build the notification
        Notification.Builder builder =  NotificationUtils.geNotificationBuilder(this,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID, Notification.PRIORITY_LOW,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME, null, null,
            null, null, NotificationUtils.NOTIFICATION_MODE_SILENT);
        if (builder == null)  return null;

        // No need to show a timestamp:
        builder.setShowWhen(false);

        // Set notification icon
        builder.setSmallIcon(R.drawable.ic_service_notification);

        // Set background color for small notification icon
        builder.setColor(0xFF607D8B);

        return builder.build();
    }

    private void setupNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationUtils.setupNotificationChannel(this, TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_ID,
            TermuxConstants.TERMUX_RUN_COMMAND_NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
    }

}
