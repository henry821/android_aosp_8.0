/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.packageinstaller;

import static android.content.pm.PackageInstaller.SessionParams.UID_UNKNOWN;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Button;
import android.widget.ProgressBar;

import com.android.internal.content.PackageHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Send package to the package manager and handle results from package manager. Once the
 * installation succeeds, start {@link InstallSuccess} or {@link InstallFailed}.
 * <p>This has two phases: First send the data to the package manager, then wait until the package
 * manager processed the result.</p>
 *
 * 向包管理器发送包的信息并处理包管理器的回调。一旦安装完成，启动 InstallSuccess 或者 InstallFailed 页面
 * 这里有两个步骤：
 * 1. 向包管理器发送数据
 * 2. 在包管理器处理完返回结果前一直等待
 */
public class InstallInstalling extends Activity {
    private static final String LOG_TAG = InstallInstalling.class.getSimpleName();

    private static final String SESSION_ID = "com.android.packageinstaller.SESSION_ID";
    private static final String INSTALL_ID = "com.android.packageinstaller.INSTALL_ID";

    private static final String BROADCAST_ACTION =
            "com.android.packageinstaller.ACTION_INSTALL_COMMIT";

    /** Listens to changed to the session and updates progress bar */
    private PackageInstaller.SessionCallback mSessionCallback;

    /** Task that sends the package to the package installer */
    private InstallingAsyncTask mInstallingTask;

    /** Id of the session to install the package */
    private int mSessionId;

    /** Id of the install event we wait for */
    private int mInstallId;

    /** URI of package to install */
    private Uri mPackageURI;

    /** The button that can cancel this dialog */
    private Button mCancelButton;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.install_installing);

        ApplicationInfo appInfo = getIntent()
                .getParcelableExtra(PackageUtil.INTENT_ATTR_APPLICATION_INFO);
        mPackageURI = getIntent().getData();

        if ("package".equals(mPackageURI.getScheme())) {
            try {
                getPackageManager().installExistingPackage(appInfo.packageName);
                launchSuccess();
            } catch (PackageManager.NameNotFoundException e) {
                launchFailure(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null);
            }
        } else {
        	// add by whw: 根据mPackageURI创建一个对应的文件
            final File sourceFile = new File(mPackageURI.getPath());
            PackageUtil.initSnippetForNewApp(this, PackageUtil.getAppSnippet(this, appInfo,
                    sourceFile), R.id.app_snippet);

			// add by whw: 如果savedInstanceState不为null，获取此前保存的mSessionId和mInstallId
			// add by whw: mSessionId是安装包的会话id
			// add by whw: mInstallId是等待的安装事件id
            if (savedInstanceState != null) {
                mSessionId = savedInstanceState.getInt(SESSION_ID);
                mInstallId = savedInstanceState.getInt(INSTALL_ID);

                // Reregister for result; might instantly call back if result was delivered while
                // activity was destroyed
                // add by whw: 向InstallEventReceiver注册一个观察者
                // add by whw: launchFinishBaseOnResult会接收到安装事件的回调，无论安装成功或者失败都会关闭当前Activity
                try {
                    InstallEventReceiver.addObserver(this, mInstallId,
                            this::launchFinishBasedOnResult);
                } catch (EventResultPersister.OutOfIdsException e) {
                    // Does not happen
                }
            } else {
            	// add by whw: 创建SessionParams，它用来代表安装会话的参数
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.referrerUri = getIntent().getParcelableExtra(Intent.EXTRA_REFERRER);
                params.originatingUri = getIntent()
                        .getParcelableExtra(Intent.EXTRA_ORIGINATING_URI);
                params.originatingUid = getIntent().getIntExtra(Intent.EXTRA_ORIGINATING_UID,
                        UID_UNKNOWN);

                File file = new File(mPackageURI.getPath());
                try {
					// add by whw：根据mPackageURI对包(需要安装的APK)进行轻量级的解析，并将解析的参数赋值给SessionParams
                    PackageParser.PackageLite pkg = PackageParser.parsePackageLite(file, 0);
                    params.setAppPackageName(pkg.packageName);
                    params.setInstallLocation(pkg.installLocation);
                    params.setSize(
                            PackageHelper.calculateInstalledSize(pkg, false, params.abiOverride));
                } catch (PackageParser.PackageParserException e) {
                    Log.e(LOG_TAG, "Cannot parse package " + file + ". Assuming defaults.");
                    Log.e(LOG_TAG,
                            "Cannot calculate installed size " + file + ". Try only apk size.");
                    params.setSize(file.length());
                } catch (IOException e) {
                    Log.e(LOG_TAG,
                            "Cannot calculate installed size " + file + ". Try only apk size.");
                    params.setSize(file.length());
                }

                try {
					// add by whw: 向InstallEventReceiver注册一个观察者，并返回一个新的mInstallId
                    mInstallId = InstallEventReceiver
                            .addObserver(this, EventResultPersister.GENERATE_NEW_ID,
                                    this::launchFinishBasedOnResult);
                } catch (EventResultPersister.OutOfIdsException e) {
                    launchFailure(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null);
                }

                try {
					// add by whw: PackageInstaller的createSession方法内部会通过IPackageInstaller与PackageInstallerService进行进程间通信
					// add by whw: 最终调用的是PackageInstallerService的createSession方法来创建并返回mSessionId
                    mSessionId = getPackageManager().getPackageInstaller().createSession(params);
                } catch (IOException e) {
                    launchFailure(PackageManager.INSTALL_FAILED_INTERNAL_ERROR, null);
                }
            }

            mCancelButton = (Button) findViewById(R.id.cancel_button);

            mCancelButton.setOnClickListener(view -> {
                if (mInstallingTask != null) {
                    mInstallingTask.cancel(true);
                }

                if (mSessionId > 0) {
                    getPackageManager().getPackageInstaller().abandonSession(mSessionId);
                    mSessionId = 0;
                }

                setResult(RESULT_CANCELED);
                finish();
            });

            mSessionCallback = new InstallSessionCallback();
        }
    }

    /**
     * Launch the "success" version of the final package installer dialog
     */
    private void launchSuccess() {
        Intent successIntent = new Intent(getIntent());
        successIntent.setClass(this, InstallSuccess.class);
        successIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);

        startActivity(successIntent);
        finish();
    }

    /**
     * Launch the "failure" version of the final package installer dialog
     *
     * @param legacyStatus  The status as used internally in the package manager.
     * @param statusMessage The status description.
     */
    private void launchFailure(int legacyStatus, String statusMessage) {
        Intent failureIntent = new Intent(getIntent());
        failureIntent.setClass(this, InstallFailed.class);
        failureIntent.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT);
        failureIntent.putExtra(PackageInstaller.EXTRA_LEGACY_STATUS, legacyStatus);
        failureIntent.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, statusMessage);

        startActivity(failureIntent);
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();

        getPackageManager().getPackageInstaller().registerSessionCallback(mSessionCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // This is the first onResume in a single life of the activity
        if (mInstallingTask == null) {
			// add by whw: 根据mSessionId得到SessionInfo，SessionInfo代表安装会话的详细信息
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            PackageInstaller.SessionInfo sessionInfo = installer.getSessionInfo(mSessionId);

			// add by whw: 如果sessionInfo不为null并且是不活动的，就创建并执行InstallingAsyncTask
			// add by whw: InstallingAsyncTask的doInBackGround方法中会根据包(APK)的Uri，将APK的信息通过IO流的形式写入到PackageInstaller.Session中
            if (sessionInfo != null && !sessionInfo.isActive()) {
                mInstallingTask = new InstallingAsyncTask();
                mInstallingTask.execute();
            } else {
                // we will receive a broadcast when the install is finished
                mCancelButton.setEnabled(false);
                setFinishOnTouchOutside(false);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(SESSION_ID, mSessionId);
        outState.putInt(INSTALL_ID, mInstallId);
    }

    @Override
    public void onBackPressed() {
        if (mCancelButton.isEnabled()) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        getPackageManager().getPackageInstaller().unregisterSessionCallback(mSessionCallback);
    }

    @Override
    protected void onDestroy() {
        if (mInstallingTask != null) {
            mInstallingTask.cancel(true);
            synchronized (mInstallingTask) {
                while (!mInstallingTask.isDone) {
                    try {
                        mInstallingTask.wait();
                    } catch (InterruptedException e) {
                        Log.i(LOG_TAG, "Interrupted while waiting for installing task to cancel",
                                e);
                    }
                }
            }
        }

        InstallEventReceiver.removeObserver(this, mInstallId);

        super.onDestroy();
    }

    /**
     * Launch the appropriate finish activity (success or failed) for the installation result.
     *
     * @param statusCode    The installation result.
     * @param legacyStatus  The installation as used internally in the package manager.
     * @param statusMessage The detailed installation result.
     */
    private void launchFinishBasedOnResult(int statusCode, int legacyStatus, String statusMessage) {
        if (statusCode == PackageInstaller.STATUS_SUCCESS) {
            launchSuccess();
        } else {
            launchFailure(legacyStatus, statusMessage);
        }
    }


    private class InstallSessionCallback extends PackageInstaller.SessionCallback {
        @Override
        public void onCreated(int sessionId) {
            // empty
        }

        @Override
        public void onBadgingChanged(int sessionId) {
            // empty
        }

        @Override
        public void onActiveChanged(int sessionId, boolean active) {
            // empty
        }

        @Override
        public void onProgressChanged(int sessionId, float progress) {
            if (sessionId == mSessionId) {
                ProgressBar progressBar = (ProgressBar)findViewById(R.id.progress_bar);
                progressBar.setMax(Integer.MAX_VALUE);
                progressBar.setProgress((int) (Integer.MAX_VALUE * progress));
            }
        }

        @Override
        public void onFinished(int sessionId, boolean success) {
            // empty, finish is handled by InstallResultReceiver
        }
    }

    /**
     * Send the package to the package installer and then register a event result observer that
     * will call {@link #launchFinishBasedOnResult(int, int, String)}
     */
    private final class InstallingAsyncTask extends AsyncTask<Void, Void,
            PackageInstaller.Session> {
        volatile boolean isDone;

        @Override
        protected PackageInstaller.Session doInBackground(Void... params) {
            PackageInstaller.Session session;
            try {
                session = getPackageManager().getPackageInstaller().openSession(mSessionId);
            } catch (IOException e) {
                return null;
            }

            session.setStagingProgress(0);

            try {
                File file = new File(mPackageURI.getPath());

                try (InputStream in = new FileInputStream(file)) {
                    long sizeBytes = file.length();
                    try (OutputStream out = session
                            .openWrite("PackageInstaller", 0, sizeBytes)) {
                        byte[] buffer = new byte[4096];
                        while (true) {
                            int numRead = in.read(buffer);

                            if (numRead == -1) {
                                session.fsync(out);
                                break;
                            }

                            if (isCancelled()) {
                                session.close();
                                break;
                            }

                            out.write(buffer, 0, numRead);
                            if (sizeBytes > 0) {
                                float fraction = ((float) numRead / (float) sizeBytes);
                                session.addProgress(fraction);
                            }
                        }
                    }
                }

                return session;
            } catch (IOException | SecurityException e) {
                Log.e(LOG_TAG, "Could not write package", e);

                session.close();

                return null;
            } finally {
                synchronized (this) {
                    isDone = true;
                    notifyAll();
                }
            }
        }

        @Override
        protected void onPostExecute(PackageInstaller.Session session) {
            if (session != null) {
                Intent broadcastIntent = new Intent(BROADCAST_ACTION);
                broadcastIntent.setPackage(
                        getPackageManager().getPermissionControllerPackageName());
                broadcastIntent.putExtra(EventResultPersister.EXTRA_ID, mInstallId);

				// add by whw: 创建了一个PendingIntent，并将该PendingIntent的IntentSender通过
				// add by whw: PackageInstaller.Session的commit方法发送出去
                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        InstallInstalling.this,
                        mInstallId,
                        broadcastIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);

                session.commit(pendingIntent.getIntentSender());
                mCancelButton.setEnabled(false);
                setFinishOnTouchOutside(false);
            } else {
                getPackageManager().getPackageInstaller().abandonSession(mSessionId);

                if (!isCancelled()) {
                    launchFailure(PackageManager.INSTALL_FAILED_INVALID_APK, null);
                }
            }
        }
    }
}
