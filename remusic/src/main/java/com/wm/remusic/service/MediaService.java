/*
 * Copyright (C) 2012 Andrew Neal
 * Copyright (C) 2014 The CyanogenMod Project
 * Copyright (C) 2015 Naman Dwivedi
 *
 * Licensed under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law
 * or agreed to in writing, software distributed under the License is
 * distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */

package com.wm.remusic.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.audiofx.AudioEffect;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AlbumColumns;
import android.provider.MediaStore.Audio.AudioColumns;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.RemoteViews;

import com.facebook.common.executors.CallerThreadExecutor;
import com.facebook.common.references.CloseableReference;
import com.facebook.datasource.DataSource;
import com.facebook.drawee.backends.pipeline.Fresco;
import com.facebook.imagepipeline.core.ImagePipeline;
import com.facebook.imagepipeline.datasource.BaseBitmapDataSubscriber;
import com.facebook.imagepipeline.image.CloseableImage;
import com.facebook.imagepipeline.request.ImageRequest;
import com.facebook.imagepipeline.request.ImageRequestBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.wm.remusic.MediaAidlInterface;
import com.wm.remusic.R;
import com.wm.remusic.activity.PlayingActivity;
import com.wm.remusic.downmusic.Down;
import com.wm.remusic.info.MusicInfo;
import com.wm.remusic.json.MusicDetailInfo;
import com.wm.remusic.json.MusicDetailNet;
import com.wm.remusic.net.BMA;
import com.wm.remusic.net.HttpUtil;
import com.wm.remusic.permissions.Nammu;
import com.wm.remusic.provider.MusicPlaybackState;
import com.wm.remusic.provider.RecentStore;
import com.wm.remusic.receiver.MediaButtonIntentReceiver;
import com.wm.remusic.recent.QueueLoader;
import com.wm.remusic.recent.SongPlayCount;
import com.wm.remusic.recent.SortedCursor;
import com.wm.remusic.uitl.CommonUtils;
import com.wm.remusic.uitl.ImageUtils;
import com.wm.remusic.uitl.MusicUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import bolts.Task;

@SuppressLint("NewApi")
public class MediaService extends Service {
    public static final String PLAYSTATE_CHANGED = "com.wm.remusic.playstatechanged";
    public static final String POSITION_CHANGED = "com.wm.remusic.positionchanged";
    public static final String META_CHANGED = "com.wm.remusic.metachanged";
    public static final String PLAYLIST_ITEM_MOVED = "com.wm.remusic.mmoved";
    public static final String QUEUE_CHANGED = "com.wm.remusic.queuechanged";
    public static final String PLAYLIST_CHANGED = "com.wm.remusic.playlistchanged";
    public static final String REPEATMODE_CHANGED = "com.wm.remusic.repeatmodechanged";
    public static final String SHUFFLEMODE_CHANGED = "com.wm.remusic.shufflemodechanged";
    public static final String TRACK_ERROR = "com.wm.remusic.trackerror";
    public static final String TIMBER_PACKAGE_NAME = "com.wm.remusic";
    public static final String MUSIC_PACKAGE_NAME = "com.android.music";
    public static final String SERVICECMD = "com.wm.remusic.musicservicecommand";
    public static final String TOGGLEPAUSE_ACTION = "com.wm.remusic.togglepause";
    public static final String PAUSE_ACTION = "com.wm.remusic.pause";
    public static final String STOP_ACTION = "com.wm.remusic.stop";
    public static final String PREVIOUS_ACTION = "com.wm.remusic.previous";
    public static final String PREVIOUS_FORCE_ACTION = "com.wm.remusic.previous.force";
    public static final String NEXT_ACTION = "com.wm.remusic.next";
    public static final String REPEAT_ACTION = "com.wm.remusic.repeat";
    public static final String SHUFFLE_ACTION = "com.wm.remusic.shuffle";
    public static final String FROM_MEDIA_BUTTON = "frommediabutton";
    public static final String REFRESH = "com.wm.remusic.refresh";
    public static final String UPDATE_LOCKSCREEN = "com.wm.remusic.updatelockscreen";
    public static final String CMDNAME = "command";
    public static final String CMDTOGGLEPAUSE = "togglepause";
    public static final String CMDSTOP = "stop";
    public static final String CMDPAUSE = "pause";
    public static final String CMDPLAY = "play";
    public static final String CMDPREVIOUS = "previous";
    public static final String CMDNEXT = "next";
    public static final String CMDNOTIF = "buttonId";
    public static final int NEXT = 2;
    public static final int LAST = 3;
    public static final int SHUFFLE_NONE = 0;
    public static final int SHUFFLE_NORMAL = 1;
    public static final int SHUFFLE_AUTO = 2;
    public static final int REPEAT_NONE = 2;
    public static final int REPEAT_CURRENT = 1;
    public static final int REPEAT_ALL = 2;
    public static final int MAX_HISTORY_SIZE = 1000;
    private static final String TAG = "MusicPlaybackService";
    private static final boolean D = true;
    private static final String SHUTDOWN = "com.wm.remusic.shutdown";
    private static final int IDCOLIDX = 0;
    private static final int TRACK_ENDED = 1;
    private static final int TRACK_WENT_TO_NEXT = 2;
    private static final int RELEASE_WAKELOCK = 3;
    private static final int SERVER_DIED = 4;
    private static final int FOCUSCHANGE = 5;
    private static final int FADEDOWN = 6;
    private static final int FADEUP = 7;
    private static final int IDLE_DELAY = 5 * 60 * 1000;
    private static final long REWIND_INSTEAD_PREVIOUS_THRESHOLD = 3000;
    private static final String[] PROJECTION = new String[]{
            "audio._id AS _id", MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID
    };
    private static final String[] ALBUM_PROJECTION = new String[]{
            MediaStore.Audio.Albums.ALBUM, MediaStore.Audio.Albums.ARTIST,
            MediaStore.Audio.Albums.LAST_YEAR
    };
    private static final Shuffler mShuffler = new Shuffler();
    private static final int NOTIFY_MODE_NONE = 0;
    private static final int NOTIFY_MODE_FOREGROUND = 1;
    private static final int NOTIFY_MODE_BACKGROUND = 2;
    private static final String[] PROJECTION_MATRIX = new String[]{
            "_id", MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.ARTIST_ID
    };
    private static LinkedList<Integer> mHistory = new LinkedList<>();
    private final IBinder mBinder = new ServiceStub(this);
    private MultiPlayer mPlayer;
    private String mFileToPlay;
    private WakeLock mWakeLock;
    private AlarmManager mAlarmManager;
    private PendingIntent mShutdownIntent;
    private boolean mShutdownScheduled;
    private NotificationManager mNotificationManager;
    private Cursor mCursor;
    private Cursor mAlbumCursor;
    private AudioManager mAudioManager;
    private SharedPreferences mPreferences;
    private boolean mServiceInUse = false;
    private boolean mIsSupposedToBePlaying = false;
    private long mLastPlayedTime;
    private int mNotifyMode = NOTIFY_MODE_NONE;
    private long mNotificationPostTime = 0;
    private boolean mQueueIsSaveable = true;
    private boolean mPausedByTransientLossOfFocus = false;

    private MediaSession mSession;

    private ComponentName mMediaButtonReceiverComponent;

    private int mCardId;

    private int mPlayPos = -1;

    private int mNextPlayPos = -1;

    private int mOpenFailedCounter = 0;

    private int mMediaMountedCount = 0;

    private int mShuffleMode = SHUFFLE_NONE;

    private int mRepeatMode = REPEAT_ALL;

    private int mServiceStartId = -1;

    private ArrayList<MusicTrack> mPlaylist = new ArrayList<MusicTrack>(100);

    private HashMap<Long,MusicInfo> mPlaylistInfo = new HashMap<>();

    private long[] mAutoShuffleList = null;

    private MusicPlayerHandler mPlayerHandler;
    private final OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {

        @Override
        public void onAudioFocusChange(final int focusChange) {
            mPlayerHandler.obtainMessage(FOCUSCHANGE, focusChange, 0).sendToTarget();
        }
    };
    private HandlerThread mHandlerThread;
    private BroadcastReceiver mUnmountReceiver = null;
    private MusicPlaybackState mPlaybackStateStore;
    private boolean mShowAlbumArtOnLockscreen;
    private SongPlayCount mSongPlayCount;
    private RecentStore mRecentStore;
    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String command = intent.getStringExtra(CMDNAME);


            handleCommandIntent(intent);

        }
    };
    private ContentObserver mMediaStoreObserver;

    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private ExecutorService nextExecutor = Executors.newSingleThreadExecutor();
    private boolean isMiui;
    private boolean isFlyme;
    private MusicDetailInfo mInfo;
    boolean isNextLocal = true;
    Gson gson = new Gson();

    @Override
    public IBinder onBind(final Intent intent) {
        if (D) Log.d(TAG, "Service bound, intent = " + intent);
        cancelShutdown();
        mServiceInUse = true;
        return mBinder;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        if (D) Log.d(TAG, "Service unbound");
        mServiceInUse = false;
        saveQueue(true);

        if (mIsSupposedToBePlaying || mPausedByTransientLossOfFocus) {

            return true;

        } else if (mPlaylist.size() > 0 || mPlayerHandler.hasMessages(TRACK_ENDED)) {
            scheduleDelayedShutdown();
            return true;
        }
        stopSelf(mServiceStartId);

        return true;
    }

    @Override
    public void onRebind(final Intent intent) {
        cancelShutdown();
        mServiceInUse = true;
    }

    @Override
    public void onCreate() {
        if (D) Log.d(TAG, "Creating service");
        super.onCreate();

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // gets a pointer to the playback state store
        mPlaybackStateStore = MusicPlaybackState.getInstance(this);
        mSongPlayCount = SongPlayCount.getInstance(this);
        mRecentStore = RecentStore.getInstance(this);


        mHandlerThread = new HandlerThread("MusicPlayerHandler",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mHandlerThread.start();


        mPlayerHandler = new MusicPlayerHandler(this, mHandlerThread.getLooper());


        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mMediaButtonReceiverComponent = new ComponentName(getPackageName(),
                MediaButtonIntentReceiver.class.getName());
        mAudioManager.registerMediaButtonEventReceiver(mMediaButtonReceiverComponent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
            setUpMediaSession();
        }


        mPreferences = getSharedPreferences("Service", 0);
        mCardId = getCardId();

        registerExternalStorageListener();

        mPlayer = new MultiPlayer(this);
        mPlayer.setHandler(mPlayerHandler);

        // Initialize the intent filter and each action
        final IntentFilter filter = new IntentFilter();
        filter.addAction(SERVICECMD);
        filter.addAction(TOGGLEPAUSE_ACTION);
        filter.addAction(PAUSE_ACTION);
        filter.addAction(STOP_ACTION);
        filter.addAction(NEXT_ACTION);
        filter.addAction(PREVIOUS_ACTION);
        filter.addAction(PREVIOUS_FORCE_ACTION);
        filter.addAction(REPEAT_ACTION);
        filter.addAction(SHUFFLE_ACTION);
        // Attach the broadcast listener
        registerReceiver(mIntentReceiver, filter);

        mMediaStoreObserver = new MediaStoreObserver(mPlayerHandler);
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI, true, mMediaStoreObserver);
        getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, mMediaStoreObserver);

        // Initialize the wake lock
        final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());
        mWakeLock.setReferenceCounted(false);


        final Intent shutdownIntent = new Intent(this, MediaService.class);
        shutdownIntent.setAction(SHUTDOWN);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mShutdownIntent = PendingIntent.getService(this, 0, shutdownIntent, 0);

        scheduleDelayedShutdown();

        reloadQueueAfterPermissionCheck();
        notifyChange(QUEUE_CHANGED);
        notifyChange(META_CHANGED);

        isMiui = CommonUtils.isMIUI();
        isFlyme = CommonUtils.isFlyme();
    }

    private void setUpMediaSession() {
        mSession = new MediaSession(this, "Timber");
        mSession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPause() {
                pause();
                mPausedByTransientLossOfFocus = false;
            }

            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onSeekTo(long pos) {
                seek(pos);
            }

            @Override
            public void onSkipToNext() {
                gotoNext(true);
            }

            @Override
            public void onSkipToPrevious() {
                prev(false);
            }

            @Override
            public void onStop() {
                pause();
                mPausedByTransientLossOfFocus = false;
                seek(0);
                releaseServiceUiAndStop();
            }
        });
        mSession.setFlags(MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
    }


    public void exit() {

    }


    @Override
    public void onDestroy() {
        if (D) Log.d(TAG, "Destroying service");
        super.onDestroy();
        // Remove any sound effects
        final Intent audioEffectsIntent = new Intent(
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        audioEffectsIntent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(audioEffectsIntent);


        cancelNotification();


        mAlarmManager.cancel(mShutdownIntent);

        mPlayerHandler.removeCallbacksAndMessages(null);

        if (CommonUtils.isJellyBeanMR2())
            mHandlerThread.quitSafely();
        else mHandlerThread.quit();

        mPlayer.release();
        mPlayer = null;

        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            mSession.release();

        getContentResolver().unregisterContentObserver(mMediaStoreObserver);

        closeCursor();

        unregisterReceiver(mIntentReceiver);
        if (mUnmountReceiver != null) {
            unregisterReceiver(mUnmountReceiver);
            mUnmountReceiver = null;
        }

        mWakeLock.release();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        if (D) Log.d(TAG, "Got new intent " + intent + ", startId = " + startId);
        mServiceStartId = startId;

        if (intent != null) {
            final String action = intent.getAction();

            if (SHUTDOWN.equals(action)) {
                mShutdownScheduled = false;
                releaseServiceUiAndStop();
                return START_NOT_STICKY;
            }

            handleCommandIntent(intent);
        }

        scheduleDelayedShutdown();

        if (intent != null && intent.getBooleanExtra(FROM_MEDIA_BUTTON, false)) {
            MediaButtonIntentReceiver.completeWakefulIntent(intent);
        }

        return START_STICKY;
    }

    private void releaseServiceUiAndStop() {
        if (isPlaying()
                || mPausedByTransientLossOfFocus
                || mPlayerHandler.hasMessages(TRACK_ENDED)) {
            return;
        }

        if (D) Log.d(TAG, "Nothing is playing anymore, releasing notification");
        cancelNotification();
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            mSession.setActive(false);

        if (!mServiceInUse) {
            saveQueue(true);
            stopSelf(mServiceStartId);
        }
    }

    private void handleCommandIntent(Intent intent) {
        final String action = intent.getAction();
        final String command = SERVICECMD.equals(action) ? intent.getStringExtra(CMDNAME) : null;

        if (D) Log.d(TAG, "handleCommandIntent: action = " + action + ", command = " + command);

        if (CMDNEXT.equals(command) || NEXT_ACTION.equals(action)) {
            gotoNext(true);
        } else if (CMDPREVIOUS.equals(command) || PREVIOUS_ACTION.equals(action)
                || PREVIOUS_FORCE_ACTION.equals(action)) {
            prev(PREVIOUS_FORCE_ACTION.equals(action));
        } else if (CMDTOGGLEPAUSE.equals(command) || TOGGLEPAUSE_ACTION.equals(action)) {
            if (isPlaying()) {
                pause();
                mPausedByTransientLossOfFocus = false;
            } else {
                play();
            }
        } else if (CMDPAUSE.equals(command) || PAUSE_ACTION.equals(action)) {
            pause();
            mPausedByTransientLossOfFocus = false;
        } else if (CMDPLAY.equals(command)) {
            play();
        } else if (CMDSTOP.equals(command) || STOP_ACTION.equals(action)) {
            pause();
            mPausedByTransientLossOfFocus = false;
            seek(0);
            releaseServiceUiAndStop();
        } else if (REPEAT_ACTION.equals(action)) {
            cycleRepeat();
        } else if (SHUFFLE_ACTION.equals(action)) {
            cycleShuffle();
        }
    }

    private void updateNotification() {
        final int newNotifyMode;
        if (isPlaying()) {
            newNotifyMode = NOTIFY_MODE_FOREGROUND;
        } else if (recentlyPlayed()) {
            newNotifyMode = NOTIFY_MODE_BACKGROUND;
        } else {
            newNotifyMode = NOTIFY_MODE_NONE;
        }

        int notificationId = hashCode();
        if (mNotifyMode != newNotifyMode) {
            if (mNotifyMode == NOTIFY_MODE_FOREGROUND) {
                if (CommonUtils.isLollipop())
                    stopForeground(newNotifyMode == NOTIFY_MODE_NONE);
                else
                    stopForeground(newNotifyMode == NOTIFY_MODE_NONE || newNotifyMode == NOTIFY_MODE_BACKGROUND);
            } else if (newNotifyMode == NOTIFY_MODE_NONE) {
                mNotificationManager.cancel(notificationId);
                mNotificationPostTime = 0;
            }
        }

        if (newNotifyMode == NOTIFY_MODE_FOREGROUND) {
            startForeground(notificationId, getNotification());

        } else if (newNotifyMode == NOTIFY_MODE_BACKGROUND) {
            mNotificationManager.notify(notificationId, getNotification());
        }

        mNotifyMode = newNotifyMode;
    }

    private void cancelNotification() {
        stopForeground(true);
        mNotificationManager.cancel(hashCode());
        mNotificationPostTime = 0;
        mNotifyMode = NOTIFY_MODE_NONE;
    }

    private int getCardId() {
        if (CommonUtils.isMarshmallow()) {
            if (Nammu.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                return getmCardId();
            } else return 0;
        } else {
            return getmCardId();
        }
    }

    private int getmCardId() {
        final ContentResolver resolver = getContentResolver();
        Cursor cursor = resolver.query(Uri.parse("content://media/external/fs_id"), null, null,
                null, null);
        int mCardId = -1;
        if (cursor != null && cursor.moveToFirst()) {
            mCardId = cursor.getInt(0);
            cursor.close();
            cursor = null;
        }
        return mCardId;
    }

    public void closeExternalStorageFiles(final String storagePath) {
        stop(true);
        notifyChange(QUEUE_CHANGED);
        notifyChange(META_CHANGED);
    }

    public void registerExternalStorageListener() {
        if (mUnmountReceiver == null) {
            mUnmountReceiver = new BroadcastReceiver() {


                @Override
                public void onReceive(final Context context, final Intent intent) {
                    final String action = intent.getAction();
                    if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                        saveQueue(true);
                        mQueueIsSaveable = false;
                        closeExternalStorageFiles(intent.getData().getPath());
                    } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                        mMediaMountedCount++;
                        mCardId = getCardId();
                        reloadQueueAfterPermissionCheck();
                        mQueueIsSaveable = true;
                        notifyChange(QUEUE_CHANGED);
                        notifyChange(META_CHANGED);
                    }
                }
            };
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_MEDIA_EJECT);
            filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
            filter.addDataScheme("file");
            registerReceiver(mUnmountReceiver, filter);
        }
    }

    private void scheduleDelayedShutdown() {
        if (D) Log.v(TAG, "Scheduling shutdown in " + IDLE_DELAY + " ms");
        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + IDLE_DELAY, mShutdownIntent);
        mShutdownScheduled = true;
    }

    private void cancelShutdown() {
        if (D) Log.d(TAG, "Cancelling delayed shutdown, scheduled = " + mShutdownScheduled);
        if (mShutdownScheduled) {
            mAlarmManager.cancel(mShutdownIntent);
            mShutdownScheduled = false;
        }
    }

    private void stop(final boolean goToIdle) {
        if (D) Log.d(TAG, "Stopping playback, goToIdle = " + goToIdle);
        if (mPlayer.isInitialized()) {
            mPlayer.stop();
        }
        mFileToPlay = null;
        closeCursor();
        if (goToIdle) {
            setIsSupposedToBePlaying(false, false);
        } else {
            if (CommonUtils.isLollipop())
                stopForeground(false);
            //else stopForeground(true);
        }
    }

    private int removeTracksInternal(int first, int last) {
        synchronized (this) {
            if (last < first) {
                return 0;
            } else if (first < 0) {
                first = 0;
            } else if (last >= mPlaylist.size()) {
                last = mPlaylist.size() - 1;
            }

            boolean gotonext = false;
            if (first <= mPlayPos && mPlayPos <= last) {
                mPlayPos = first;
                gotonext = true;
            } else if (mPlayPos > last) {
                mPlayPos -= last - first + 1;
            }
            final int numToRemove = last - first + 1;

            if (first == 0 && last == mPlaylist.size() - 1) {
                mPlayPos = -1;
                mNextPlayPos = -1;
                mPlaylist.clear();
                mHistory.clear();
            } else {
                for (int i = 0; i < numToRemove; i++) {
                    mPlaylist.remove(first);
                }

                ListIterator<Integer> positionIterator = mHistory.listIterator();
                while (positionIterator.hasNext()) {
                    int pos = positionIterator.next();
                    if (pos >= first && pos <= last) {
                        positionIterator.remove();
                    } else if (pos > last) {
                        positionIterator.set(pos - numToRemove);
                    }
                }
            }
            if (gotonext) {
                if (mPlaylist.size() == 0) {
                    stop(true);
                    mPlayPos = -1;
                    closeCursor();
                } else {
                    if (mShuffleMode != SHUFFLE_NONE) {
                        mPlayPos = getNextPosition(true);
                    } else if (mPlayPos >= mPlaylist.size()) {
                        mPlayPos = 0;
                    }
                    final boolean wasPlaying = isPlaying();
                    stop(false);
                    openCurrentAndNext();
                    if (wasPlaying) {
                        play();
                    }
                }
                notifyChange(META_CHANGED);
            }
            return last - first + 1;
        }
    }

    private void addToPlayList(final long[] list, int position) {
        final int addlen = list.length;
        if (position < 0) {
            mPlaylist.clear();
            position = 0;
        }

        mPlaylist.ensureCapacity(mPlaylist.size() + addlen);
        if (position > mPlaylist.size()) {
            position = mPlaylist.size();
        }

        final ArrayList<MusicTrack> arrayList = new ArrayList<MusicTrack>(addlen);
        for (int i = 0; i < list.length; i++) {
            arrayList.add(new MusicTrack(list[i], i));
        }

        mPlaylist.addAll(position, arrayList);

        if (mPlaylist.size() == 0) {
            closeCursor();
            notifyChange(META_CHANGED);
        }
    }


    private void updateCursor(final long trackId) {
        updateCursor("_id=" + trackId, null);
    }

    private void updateCursor(final String selection, final String[] selectionArgs) {
        synchronized (this) {
            closeCursor();
            mCursor = openCursorAndGoToFirst(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION, selection, selectionArgs);
        }
        updateAlbumCursor();
    }

    private void updateCursor(final Uri uri) {
        synchronized (this) {
            closeCursor();
            mCursor = openCursorAndGoToFirst(uri, PROJECTION, null, null);
        }
        updateAlbumCursor();
    }


    private void updateAlbumCursor(String url){
        long albumId = getAlbumId();
        if (albumId >= 0) {
            mAlbumCursor = openCursorAndGoToFirst(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    ALBUM_PROJECTION, "_id=" + albumId, null);

            if(mAlbumCursor == null || mAlbumCursor.getCount() < 1){
            }
        } else {
            mAlbumCursor = null;
        }
    }

    private void updateAlbumCursor() {
        long albumId = getAlbumId();
        if (albumId >= 0) {
            mAlbumCursor = openCursorAndGoToFirst(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                    ALBUM_PROJECTION, "_id=" + albumId, null);

            if(mAlbumCursor == null || mAlbumCursor.getCount() < 1){


            }
        } else {
            mAlbumCursor = null;
        }
    }

    private Cursor openCursorAndGoToFirst(Uri uri, String[] projection,
                                          String selection, String[] selectionArgs) {
        Cursor c = getContentResolver().query(uri, projection,
                selection, selectionArgs, null);
        if (c == null) {
            return null;
        }
        if (!c.moveToFirst()) {
            c.close();
            return null;
        }
        return c;
    }

    private synchronized void closeCursor() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
        if (mAlbumCursor != null) {
            mAlbumCursor.close();
            mAlbumCursor = null;
        }
    }

    class GetPlayUrl implements Runnable{
        long id;
        public GetPlayUrl(long id) {
            this.id = id;
        }

        @Override
        public void run() {
            MusicDetailNet song = Down.getUrl(MediaService.this,id + "");
            if(song != null) {
                Log.e("current_url", song.getShow_link());
                mPlayer.setDataSource(song.getShow_link());
                play();
            }
        }
    }

    class GetPlayUrl1 implements Callable<Void>{
        long id;
        public GetPlayUrl1(long id) {
            this.id = id;
        }

        @Override
        public Void call() throws Exception {
            MusicDetailNet song = Down.getUrl(MediaService.this,id + "");
            mPlayer.setDataSource(song.getShow_link());
            play();

            return null;
        }

    }


    private void openCurrentAndNext() {
        openCurrentAndMaybeNext(true);
    }
    boolean openFile = true;
    int isNet;
    private void openCurrentAndMaybeNext(final boolean openNext) {
        openFile = true;
        synchronized (this) {
            closeCursor();

            if (mPlaylist.size() == 0) {
                return;
            }
            stop(false);

           boolean shutdown = false;


            final long id = mPlaylist.get(mPlayPos).mId ;
            if(mPlaylistInfo == null){
                return;
            }
            isNet = mPlaylistInfo.get(id).favorite;


            if(isNet == 1){

                MusicInfo info = mPlaylistInfo.get(id);
                if(mPlaylistInfo.get(id) != null){
                    MatrixCursor cursor = new MatrixCursor(PROJECTION);
                    cursor.addRow(new Object[]{info.songId,info.artist,info.albumName,info.musicName
                            ,info.data,info.albumData,info.albumId,info.artistId});
                    cursor.moveToFirst();
                    mCursor = cursor;
                    cursor.close();
                }

                executor.shutdownNow();
                executor = Executors.newSingleThreadExecutor();


                executor.submit(new GetPlayUrl(id));
                if (openNext) {
                                setNextTrack();
                            }
               // futureTask.cancel(true);

//                new AsyncTask<Void, Void, Void>() {
//
//                    MusicDetailNet song = null;
//                 //   MusicDetailInfo info = null;
//                    @Override
//                    protected Void doInBackground(Void... params) {
//                     //  noBit = HttpUtil.getBitmapStream(MediaService.this,getAlbumPath(),false);
//                        song = Down.getUrl(MediaService.this,id + "");
//                   //     info = Down.getInfo(id + "");
//                        return null;
//                    }
//
//                    @Override
//                    protected void onPostExecute(Void aVoid) {
//                        super.onPostExecute(aVoid);
//                        if(song != null){
//                            Log.e("current_url",song.getShow_link());
//                            mPlayer.setDataSource(song.getShow_link());
////                            MatrixCursor cursor = new MatrixCursor(PROJECTION);
////                            cursor.addRow(new Object[]{info.getSong_id(),info.getArtist_name(),info.getAlbum_title(),info.getTitle()
////                                    ,info.getLrclink(),info.getPic_small(),info.getAlbum_id(),info.getArtist_id()});
////                            cursor.moveToFirst();
////                            mCursor = cursor;
////                            cursor.close();
//                            play();
//
//                          if (openNext) {
//                                setNextTrack();
//                            }
//
//                        }
//                    }
//                }.execute();

                return;
            }else {
                updateCursor(mPlaylist.get(mPlayPos).mId);
                while (true) {
                    if (mCursor != null
                            && openFile(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "/"
                            + mCursor.getLong(IDCOLIDX))) {
                        break;
                    }
                    closeCursor();
                    if (mOpenFailedCounter++ < 10 && mPlaylist.size() > 1) {
                        final int pos = getNextPosition(false);
                        if (pos < 0) {
                            shutdown = true;
                            break;
                        }
                        mPlayPos = pos;
                        stop(false);
                        mPlayPos = pos;
                        updateCursor(mPlaylist.get(mPlayPos).mId);
                    } else {
                        mOpenFailedCounter = 0;
                        Log.w(TAG, "Failed to open file for playback");
                        shutdown = true;
                        break;
                    }

                    break;
                }
            }


            if (shutdown) {
                scheduleDelayedShutdown();
                if (mIsSupposedToBePlaying) {
                    mIsSupposedToBePlaying = false;
                    notifyChange(PLAYSTATE_CHANGED);
                }
            } else if (openNext) {
                setNextTrack();
            }

        }
    }

    private void sendErrorMessage(final String trackName) {
        final Intent i = new Intent(TRACK_ERROR);
        i.putExtra(TrackErrorExtra.TRACK_NAME, trackName);
        sendBroadcast(i);
    }

    private int getNextPosition(final boolean force) {
        if (mPlaylist == null || mPlaylist.isEmpty()) {
            return -1;
        }
        if (!force && mRepeatMode == REPEAT_CURRENT) {
            if (mPlayPos < 0) {
                return 0;
            }
            return mPlayPos;
        } else if (mShuffleMode == SHUFFLE_NORMAL) {
            final int numTracks = mPlaylist.size();


            final int[] trackNumPlays = new int[numTracks];
            for (int i = 0; i < numTracks; i++) {
                trackNumPlays[i] = 0;
            }


            final int numHistory = mHistory.size();
            for (int i = 0; i < numHistory; i++) {
                final int idx = mHistory.get(i).intValue();
                if (idx >= 0 && idx < numTracks) {
                    trackNumPlays[idx]++;
                }
            }

            if (mPlayPos >= 0 && mPlayPos < numTracks) {
                trackNumPlays[mPlayPos]++;
            }

            int minNumPlays = Integer.MAX_VALUE;
            int numTracksWithMinNumPlays = 0;
            for (int i = 0; i < trackNumPlays.length; i++) {
                if (trackNumPlays[i] < minNumPlays) {
                    minNumPlays = trackNumPlays[i];
                    numTracksWithMinNumPlays = 1;
                } else if (trackNumPlays[i] == minNumPlays) {
                    numTracksWithMinNumPlays++;
                }
            }


            if (minNumPlays > 0 && numTracksWithMinNumPlays == numTracks
                    && mRepeatMode != REPEAT_ALL && !force) {
                return -1;
            }


            int skip = mShuffler.nextInt(numTracksWithMinNumPlays);
            for (int i = 0; i < trackNumPlays.length; i++) {
                if (trackNumPlays[i] == minNumPlays) {
                    if (skip == 0) {
                        return i;
                    } else {
                        skip--;
                    }
                }
            }

            if (D)
                Log.e(TAG, "Getting the next position resulted did not get a result when it should have");
            return -1;
        } else if (mShuffleMode == SHUFFLE_AUTO) {
            doAutoShuffleUpdate();
            return mPlayPos + 1;
        } else {
            if (mPlayPos >= mPlaylist.size() - 1) {
                if (mRepeatMode == REPEAT_NONE && !force) {
                    return -1;
                } else if (mRepeatMode == REPEAT_ALL || force) {
                    return 0;
                }
                return -1;
            } else {
                return mPlayPos + 1;
            }
        }
    }

    private void setNextTrack() {
        setNextTrack(getNextPosition(false));
    }



    int isnextnet;
    private void setNextTrack(int position) {
        mNextPlayPos = position;
        if (D) Log.d(TAG, "setNextTrack: next play position = " + mNextPlayPos);
        if (mNextPlayPos >= 0 && mPlaylist != null && mNextPlayPos < mPlaylist.size()) {
            final long id = mPlaylist.get(mNextPlayPos).mId;
            isnextnet = mPlaylistInfo.get(id).getFavorite();
            if(isNet == 1){
                isNextLocal = false;
            }else {
                isNextLocal = true;
            }

            if( isNextLocal){
                mPlayer.setNextDataSource(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "/" + id);
            }else {

            Runnable next = new Runnable() {
                    @Override
                    public void run() {
                        MusicDetailNet song = Down.getUrl(MediaService.this,id + "");
                        if(song != null){
                            Log.e("next_url",song.getShow_link());
                            mPlayer.setNextDataSource(song.getShow_link());
                            //   mInfo = mPlaylistInfo.get(id);
                        }
                    }
                };
                nextExecutor.shutdownNow();
                if(!nextExecutor.isShutdown()){
                    nextExecutor.shutdownNow();
                }
                nextExecutor = Executors.newSingleThreadExecutor();
                nextExecutor.execute(next);

//                new AsyncTask<Void, Void, Void>() {
//                    MusicDetailNet song = null;
//                  //  MusicDetailInfo info = null;
//                    @Override
//                    protected Void doInBackground(Void... params) {
//                        song = Down.getUrl(MediaService.this,id + "");
//                  //      info = Down.getInfo(id + "");
//                        return null;
//                    }
//
//                    @Override
//                    protected void onPostExecute(Void aVoid) {
//                        super.onPostExecute(aVoid);
//                        if(song.getShow_link() != null){
//                            Log.e("next_url",song.getShow_link());
//                            mPlayer.setNextDataSource(song.getShow_link());
//                         //   mInfo = mPlaylistInfo.get(id);
//                        }
//
//                    }
//                }.execute();
            }

        } else {
            mPlayer.setNextDataSource(null);
        }
    }

    private boolean makeAutoShuffleList() {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Audio.Media._ID
                    }, MediaStore.Audio.Media.IS_MUSIC + "=1", null, null);
            if (cursor == null || cursor.getCount() == 0) {
                return false;
            }
            final int len = cursor.getCount();
            final long[] list = new long[len];
            for (int i = 0; i < len; i++) {
                cursor.moveToNext();
                list[i] = cursor.getLong(0);
            }
            mAutoShuffleList = list;
            return true;
        } catch (final RuntimeException e) {
        } finally {
            if (cursor != null) {
                cursor.close();
                cursor = null;
            }
        }
        return false;
    }

    private void doAutoShuffleUpdate() {
        boolean notify = false;
        if (mPlayPos > 10) {
            removeTracks(0, mPlayPos - 9);
            notify = true;
        }
        final int toAdd = 7 - (mPlaylist.size() - (mPlayPos < 0 ? -1 : mPlayPos));
        for (int i = 0; i < toAdd; i++) {
            int lookback = mHistory.size();
            int idx = -1;
            while (true) {
                idx = mShuffler.nextInt(mAutoShuffleList.length);
                if (!wasRecentlyUsed(idx, lookback)) {
                    break;
                }
                lookback /= 2;
            }
            mHistory.add(idx);
            if (mHistory.size() > MAX_HISTORY_SIZE) {
                mHistory.remove(0);
            }
            mPlaylist.add(new MusicTrack(mAutoShuffleList[idx], -1));
            notify = true;
        }
        if (notify) {
            notifyChange(QUEUE_CHANGED);
        }
    }

    private boolean wasRecentlyUsed(final int idx, int lookbacksize) {
        if (lookbacksize == 0) {
            return false;
        }
        final int histsize = mHistory.size();
        if (histsize < lookbacksize) {
            lookbacksize = histsize;
        }
        final int maxidx = histsize - 1;
        for (int i = 0; i < lookbacksize; i++) {
            final long entry = mHistory.get(maxidx - i);
            if (entry == idx) {
                return true;
            }
        }
        return false;
    }

    private void notifyChange(final String what) {
        if (D) Log.d(TAG, "notifyChange: what = " + what);

        // Update the lockscreen controls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            updateMediaSession(what);

        if (what.equals(POSITION_CHANGED)) {
            return;
        }

        final Intent intent = new Intent(what);
        intent.putExtra("id", getAudioId());
        intent.putExtra("artist", getArtistName());
        intent.putExtra("album", getAlbumName());
        intent.putExtra("track", getTrackName());
        intent.putExtra("playing", isPlaying());

        sendStickyBroadcast(intent);

        final Intent musicIntent = new Intent(intent);
        musicIntent.setAction(what.replace(TIMBER_PACKAGE_NAME, MUSIC_PACKAGE_NAME));
        sendStickyBroadcast(musicIntent);

        if (what.equals(META_CHANGED)) {

            mRecentStore.addSongId(getAudioId());
            mSongPlayCount.bumpSongCount(getAudioId());

        } else if (what.equals(QUEUE_CHANGED)) {
            saveQueue(true);
            if (isPlaying()) {

                if (mNextPlayPos >= 0 && mNextPlayPos < mPlaylist.size()
                        && getShuffleMode() != SHUFFLE_NONE) {
                    setNextTrack(mNextPlayPos);
                } else {
                    setNextTrack();
                }
            }
        } else {
            saveQueue(false);
        }

        if (what.equals(PLAYSTATE_CHANGED)) {
            updateNotification();
        }

    }

    private void updateMediaSession(final String what) {
        int playState = mIsSupposedToBePlaying
                ? PlaybackState.STATE_PLAYING
                : PlaybackState.STATE_PAUSED;

        if (what.equals(PLAYSTATE_CHANGED) || what.equals(POSITION_CHANGED)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mSession.setPlaybackState(new PlaybackState.Builder()
                        .setState(playState, position(), 1.0f)
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                                PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .build());
            }
        } else if (what.equals(META_CHANGED) || what.equals(QUEUE_CHANGED)) {
            //Bitmap albumArt = ImageLoader.getInstance().loadImageSync(CommonUtils.getAlbumArtUri(getAlbumId()).toString());
            Bitmap albumArt = null;
            if (albumArt != null) {

                Bitmap.Config config = albumArt.getConfig();
                if (config == null) {
                    config = Bitmap.Config.ARGB_8888;
                }
                albumArt = albumArt.copy(config, false);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mSession.setMetadata(new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, getArtistName())
                        .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, getAlbumArtistName())
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, getAlbumName())
                        .putString(MediaMetadata.METADATA_KEY_TITLE, getTrackName())
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, duration())
                        .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, getQueuePosition() + 1)
                        .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, getQueue().length)
                        .putString(MediaMetadata.METADATA_KEY_GENRE, getGenreName())
                        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,
                                mShowAlbumArtOnLockscreen ? albumArt : null)
                        .build());

                mSession.setPlaybackState(new PlaybackState.Builder()
                        .setState(playState, position(), 1.0f)
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                                PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .build());
            }
        }
    }

    RemoteViews remoteViews;
    Bitmap noBit;
    private Notification getNotification() {
        final int PAUSE_FLAG = 0x1;
        final int NEXT_FLAG = 0x2;
        final int STOP_FLAG = 0x3;
        final String albumName = getAlbumName();
        final String artistName = getArtistName();
        final boolean isPlaying = isPlaying();

      remoteViews = new RemoteViews(this.getPackageName(), R.layout.notification);

        String text = TextUtils.isEmpty(albumName) ? artistName : artistName + " - " + albumName;

        PendingIntent pendingIntent = PendingIntent.getActivity(this.getApplicationContext(), 0,
                new Intent(this.getApplicationContext(), PlayingActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);


        final Intent nowPlayingIntent = new Intent();
        nowPlayingIntent.setAction("com.wm.remusic.LAUNCH_NOW_PLAYING_ACTION");
       // nowPlayingIntent.setComponent(new ComponentName("com.wm.remusic","com.wm.remusic.activity.PlayingActivity"));

        PendingIntent clickIntent = PendingIntent.getBroadcast(this, 0, nowPlayingIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent intent = new Intent(getApplicationContext(),
                PlayingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
                0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

        final Bitmap bitmap = ImageUtils.getArtworkQuick(this, getAlbumId(), 160, 160);

        if (bitmap != null) {
            remoteViews.setImageViewBitmap(R.id.image, bitmap);
            // remoteViews.setImageViewUri(R.id.image, MusicUtils.getAlbumUri(this, getAudioId()));
            noBit = null;

        } else if(isNet == 1){
                if(noBit != null) {
                    remoteViews.setImageViewBitmap(R.id.image, noBit);
                    noBit = null;

                }else {

                    ImageRequest imageRequest = ImageRequestBuilder
                            .newBuilderWithSource( Uri.parse(getAlbumPath()))
                            .setProgressiveRenderingEnabled(true)
                            .build();

                    ImagePipeline imagePipeline = Fresco.getImagePipeline();
                    DataSource<CloseableReference<CloseableImage>>
                            dataSource = imagePipeline.fetchDecodedImage(imageRequest,MediaService.this);

                    dataSource.subscribe(new BaseBitmapDataSubscriber() {

                                             @Override
                                             public void onNewResultImpl(@Nullable Bitmap bitmap) {
                                                 // You can use the bitmap in only limited ways
                                                 // No need to do any cleanup.
                                                 if(bitmap != null){
                                                     noBit = bitmap;
                                                 };
                                                 updateNotification();
                                             }

                                             @Override
                                             public void onFailureImpl(DataSource dataSource) {
                                                 // No cleanup required here.
                                                 noBit = BitmapFactory.decodeResource(getResources(),R.drawable.placeholder_disk_210);
                                                 updateNotification();
                                             }
                                         },
                            CallerThreadExecutor.getInstance());



//                    new AsyncTask<Void,Void,Bitmap>(){
//                        @Override
//                        protected Bitmap doInBackground(Void... params) {
//
////                            Log.e("notification pic",url);
//                            return HttpUtil.getBitmapStream(MediaService.this,getAlbumPath(),false);
//                        }
//
//                        @Override
//                        protected void onPostExecute(Bitmap bitmap1) {
//                            if(bitmap1 != null){
//                                noBit = bitmap1;
//                                //remoteViews.setImageViewBitmap(R.id.image, BitmapFactory.decodeFile("/storage/emulated/0/c.jpg"));
//                            }
//                            else {
//                                noBit = BitmapFactory.decodeResource(getResources(),R.drawable.placeholder_disk_210);
//                            }
//                            updateNotification();
//                        }
//                    }.execute();
                }
            }else {
                remoteViews.setImageViewResource(R.id.image, R.drawable.placeholder_disk_210);
            }

        remoteViews.setTextViewText(R.id.title, getTrackName());
        remoteViews.setTextViewText(R.id.text, text);

        //此处action不能是一样的 如果一样的 接受的flag参数只是第一个设置的值
        Intent pauseIntent = new Intent(TOGGLEPAUSE_ACTION);
        pauseIntent.putExtra("FLAG", PAUSE_FLAG);
        PendingIntent pausePIntent = PendingIntent.getBroadcast(this, 0, pauseIntent, 0);
        remoteViews.setImageViewResource(R.id.iv_pause, isPlaying ? R.drawable.note_btn_pause : R.drawable.note_btn_play);
        remoteViews.setOnClickPendingIntent(R.id.iv_pause, pausePIntent);

        Intent nextIntent = new Intent(NEXT_ACTION);
        nextIntent.putExtra("FLAG", NEXT_FLAG);
        PendingIntent nextPIntent = PendingIntent.getBroadcast(this, 0, nextIntent, 0);
        remoteViews.setOnClickPendingIntent(R.id.iv_next, nextPIntent);

        Intent preIntent = new Intent(STOP_ACTION);
        preIntent.putExtra("FLAG", STOP_FLAG);
        PendingIntent prePIntent = PendingIntent.getBroadcast(this, 0, preIntent, 0);
        remoteViews.setOnClickPendingIntent(R.id.iv_stop, prePIntent);


        if (mNotificationPostTime == 0) {
            mNotificationPostTime = System.currentTimeMillis();
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this).setContent(remoteViews)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(clickIntent)
                .setWhen(mNotificationPostTime);

        if (CommonUtils.isJellyBeanMR1()) {
            builder.setShowWhen(false);
        }

        return builder.build();
    }


    private final PendingIntent retrievePlaybackAction(final String action) {
        final ComponentName serviceName = new ComponentName(this, MediaService.class);
        Intent intent = new Intent(action);
        intent.setComponent(serviceName);

        return PendingIntent.getService(this, 0, intent, 0);
    }

    private void saveQueue(final boolean full) {
        if (!mQueueIsSaveable) {
            return;
        }

        final SharedPreferences.Editor editor = mPreferences.edit();
        if (full) {

//            Log.e("playlist",getQueue()[1] + "");
          //  ArrayList<MusicInfo> playlist = QueueLoader.getQueueSongs(MediaService.this);
          //  Log.e("playlist",playlist.size() + "");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    mPlaybackStateStore.saveState(mPlaylist, mShuffleMode != SHUFFLE_NONE ? mHistory : null);

                    if(mPlaylistInfo.size() >  0 ){
                        String c  = gson.toJson(mPlaylistInfo);
                        try{
                            FileOutputStream fo = new FileOutputStream(new File("/storage/emulated/0/playlist"));
                            fo.write(c.getBytes());
                        }catch (Exception e){
                            e.printStackTrace();
                        }
                    }
                    editor.putInt("cardid", mCardId);
                }
            }).start();



        }
        editor.putInt("curpos", mPlayPos);
        if (mPlayer.isInitialized()) {
            editor.putLong("seekpos", mPlayer.position());
        }
        editor.putInt("repeatmode", mRepeatMode);
        editor.putInt("shufflemode", mShuffleMode);
        editor.apply();
    }

    private void reloadQueueAfterPermissionCheck() {
        if (CommonUtils.isMarshmallow()) {
            if (Nammu.checkPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                reloadQueue();
            }
        } else {
            reloadQueue();
        }
    }
    private String readTextFromSDcard(InputStream is) throws Exception {
        InputStreamReader reader = new InputStreamReader(is);
        BufferedReader bufferedReader = new BufferedReader(reader);
        StringBuffer buffer = new StringBuffer("");
        String str;
        while ((str = bufferedReader.readLine()) != null) {
            buffer.append(str);
            buffer.append("\n");
        }
        return buffer.toString();
    }
    private void reloadQueue() {
        int id = mCardId;
        if (mPreferences.contains("cardid")) {
            id = mPreferences.getInt("cardid", ~mCardId);
        }
        if (id == mCardId) {
            mPlaylist = mPlaybackStateStore.getQueue();

            try {
                FileInputStream fo = new FileInputStream(new File("/storage/emulated/0/playlist"));
                String c = readTextFromSDcard(fo);
                HashMap<Long,MusicInfo> play = gson.fromJson(c,new TypeToken<HashMap<Long,MusicInfo>>(){}.getType());
                if(play != null && play.size()> 0){
                    mPlaylistInfo = play;
                    Log.e("reload",mPlaylistInfo.keySet().toString());
                }

            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (mPlaylist.size() > 0) {
            final int pos = mPreferences.getInt("curpos", 0);
            if (pos < 0 || pos >= mPlaylist.size()) {
                mPlaylist.clear();
                return;
            }
            mPlayPos = pos;
            updateCursor(mPlaylist.get(mPlayPos).mId);
            if (mCursor == null) {
                SystemClock.sleep(3000);
                updateCursor(mPlaylist.get(mPlayPos).mId);
            }
            synchronized (this) {
                closeCursor();
                mOpenFailedCounter = 20;
                openCurrentAndNext();
            }
//            if (!mPlayer.isInitialized() && isTrackLocal()) {
//                mPlaylist.clear();
//                return;
//            }

            final long seekpos = mPreferences.getLong("seekpos", 0);
            seek(seekpos >= 0 && seekpos < duration() ? seekpos : 0);

            if (D) {
                Log.d(TAG, "restored queue, currently at position "
                        + position() + "/" + duration()
                        + " (requested " + seekpos + ")");
            }

            int repmode = mPreferences.getInt("repeatmode", REPEAT_ALL);
            if (repmode != REPEAT_ALL && repmode != REPEAT_CURRENT) {
                repmode = REPEAT_NONE;
            }
            mRepeatMode = repmode;

            int shufmode = mPreferences.getInt("shufflemode", SHUFFLE_NONE);
            if (shufmode != SHUFFLE_AUTO && shufmode != SHUFFLE_NORMAL) {
                shufmode = SHUFFLE_NONE;
            }
            if (shufmode != SHUFFLE_NONE) {
                mHistory = mPlaybackStateStore.getHistory(mPlaylist.size());
            }
            if (shufmode == SHUFFLE_AUTO) {
                if (!makeAutoShuffleList()) {
                    shufmode = SHUFFLE_NONE;
                }
            }
            mShuffleMode = shufmode;
        }
    }

    public boolean openFile(final String path) {
        if (D) Log.d(TAG, "openFile: path = " + path);
        synchronized (this) {
            if (path == null) {
                return false;
            }

            if (mCursor == null) {
                Uri uri = Uri.parse(path);
                boolean shouldAddToPlaylist = true;
                long id = -1;
                try {
                    id = Long.valueOf(uri.getLastPathSegment());
                } catch (NumberFormatException ex) {
                    // Ignore
                }

                if (id != -1 && path.startsWith(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString())) {
                    updateCursor(uri);

                } else if (id != -1 && path.startsWith(
                        MediaStore.Files.getContentUri("external").toString())) {
                    updateCursor(id);

                } else if (path.startsWith("content://downloads/")) {

                    String mpUri = getValueForDownloadedFile(this, uri, "mediaprovider_uri");
                    if (D) Log.i(TAG, "Downloaded file's MP uri : " + mpUri);
                    if (!TextUtils.isEmpty(mpUri)) {
                        if (openFile(mpUri)) {
                            notifyChange(META_CHANGED);
                            return true;
                        } else {
                            return false;
                        }
                    } else {
                        updateCursorForDownloadedFile(this, uri);
                        shouldAddToPlaylist = false;
                    }

                } else {
                    String where = MediaStore.Audio.Media.DATA + "=?";
                    String[] selectionArgs = new String[]{path};
                    updateCursor(where, selectionArgs);
                }
                try {
                    if (mCursor != null && shouldAddToPlaylist) {
                        mPlaylist.clear();
                        mPlaylist.add(new MusicTrack(
                                mCursor.getLong(IDCOLIDX), -1));
                        notifyChange(QUEUE_CHANGED);
                        mPlayPos = 0;
                        mHistory.clear();
                    }
                } catch (final UnsupportedOperationException ex) {
                    // Ignore
                }
            }

            mFileToPlay = path;
            mPlayer.setDataSource(mFileToPlay);
            if (mPlayer.isInitialized()) {
                mOpenFailedCounter = 0;
                return true;
            }

            String trackName = getTrackName();
            if (TextUtils.isEmpty(trackName)) {
                trackName = path;
            }
            sendErrorMessage(trackName);

            stop(true);
            return false;
        }
    }

    private void updateCursorForDownloadedFile(Context context, Uri uri) {
        synchronized (this) {
            closeCursor();
            MatrixCursor cursor = new MatrixCursor(PROJECTION_MATRIX);
            String title = getValueForDownloadedFile(this, uri, "title");
            cursor.addRow(new Object[]{
                    null,
                    null,
                    null,
                    title,
                    null,
                    null,
                    null,
                    null
            });
            mCursor = cursor;
            mCursor.moveToFirst();
        }
    }

    private String getValueForDownloadedFile(Context context, Uri uri, String column) {

        Cursor cursor = null;
        final String[] projection = {
                column
        };

        try {
            cursor = context.getContentResolver().query(uri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    public int getAudioSessionId() {
        synchronized (this) {
            return mPlayer.getAudioSessionId();
        }
    }

    public int getMediaMountedCount() {
        return mMediaMountedCount;
    }

    public int getShuffleMode() {
        return mShuffleMode;
    }

    public void setShuffleMode(final int shufflemode) {
        synchronized (this) {
            if (mShuffleMode == shufflemode && mPlaylist.size() > 0) {
                return;
            }

            mShuffleMode = shufflemode;
            if (mShuffleMode == SHUFFLE_AUTO) {
                if (makeAutoShuffleList()) {
                    mPlaylist.clear();
                    doAutoShuffleUpdate();
                    mPlayPos = 0;
                    openCurrentAndNext();
                    play();
                    notifyChange(META_CHANGED);
                    return;
                } else {
                    mShuffleMode = SHUFFLE_NONE;
                }
            } else {
                setNextTrack();
            }
            saveQueue(false);
            notifyChange(SHUFFLEMODE_CHANGED);
        }
    }

    public int getRepeatMode() {
        return mRepeatMode;
    }

    public void setRepeatMode(final int repeatmode) {
        synchronized (this) {
            mRepeatMode = repeatmode;
            setNextTrack();
            saveQueue(false);
            notifyChange(REPEATMODE_CHANGED);
        }
    }

    public int removeTrack(final long id) {
        int numremoved = 0;
        synchronized (this) {
            for (int i = 0; i < mPlaylist.size(); i++) {
                if (mPlaylist.get(i).mId == id) {
                    numremoved += removeTracksInternal(i, i);
                    i--;
                }
            }
        }
        if (numremoved > 0) {
            notifyChange(QUEUE_CHANGED);
        }
        return numremoved;
    }

    public boolean removeTrackAtPosition(final long id, final int position) {
        synchronized (this) {
            if (position >= 0 &&
                    position < mPlaylist.size() &&
                    mPlaylist.get(position).mId == id) {

                return removeTracks(position, position) > 0;
            }
        }
        return false;
    }

    public int removeTracks(final int first, final int last) {
        final int numremoved = removeTracksInternal(first, last);
        if (numremoved > 0) {
            notifyChange(QUEUE_CHANGED);
        }
        return numremoved;
    }

    public int getQueuePosition() {
        synchronized (this) {
            return mPlayPos;
        }
    }

    public void setQueuePosition(final int index) {
        synchronized (this) {
            stop(false);
            mPlayPos = index;
            openCurrentAndNext();
            play();
            notifyChange(META_CHANGED);
            if (mShuffleMode == SHUFFLE_AUTO) {
                doAutoShuffleUpdate();
            }
        }
    }

    public int getQueueHistorySize() {
        synchronized (this) {
            return mHistory.size();
        }
    }

    public int getQueueHistoryPosition(int position) {
        synchronized (this) {
            if (position >= 0 && position < mHistory.size()) {
                return mHistory.get(position);
            }
        }

        return -1;
    }

    public int[] getQueueHistoryList() {
        synchronized (this) {
            int[] history = new int[mHistory.size()];
            for (int i = 0; i < mHistory.size(); i++) {
                history[i] = mHistory.get(i);
            }

            return history;
        }
    }

    public String getPath() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.DATA));
        }
    }

    public String getAlbumName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.ALBUM));
        }
    }

    public String getAlbumPath(){
        synchronized (this) {
            if(mCursor == null){
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.MIME_TYPE));
        }
    }

    public String[] getAlbumPathAll(){

            if(mPlaylistInfo != null){
                int len = mPlaylistInfo.size();
                String[] albums = new String[len];
                long[] queue = getQueue();
                for(int i = 0 ; i< len; i++ ){
                    albums[i] = mPlaylistInfo.get(queue[i]).albumData;
                }
                return albums;
            }
        return null;
    }

    public String getTrackName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.TITLE));
        }
    }

    public boolean isTrackLocal(){
        synchronized (this) {
            MusicInfo info =  mPlaylistInfo.get(getAudioId());
            if ( info == null) {
                return true;
            }
            return info.getFavorite() == 0 ;
        }
    }

    public String getGenreName() {
        synchronized (this) {
            if (mCursor == null || mPlayPos < 0 || mPlayPos >= mPlaylist.size()) {
                return null;
            }
            String[] genreProjection = {MediaStore.Audio.Genres.NAME};
            Uri genreUri = MediaStore.Audio.Genres.getContentUriForAudioId("external",
                    (int) mPlaylist.get(mPlayPos).mId);
            Cursor genreCursor = getContentResolver().query(genreUri, genreProjection,
                    null, null, null);
            if (genreCursor != null) {
                try {
                    if (genreCursor.moveToFirst()) {
                        return genreCursor.getString(
                                genreCursor.getColumnIndexOrThrow(MediaStore.Audio.Genres.NAME));
                    }
                } finally {
                    genreCursor.close();
                }
            }
            return null;
        }
    }

    public String getArtistName() {
        synchronized (this) {
            if (mCursor == null) {
                return null;
            }
            return mCursor.getString(mCursor.getColumnIndexOrThrow(AudioColumns.ARTIST));
        }
    }

    public String getAlbumArtistName() {
        synchronized (this) {
            if (mAlbumCursor == null) {
                return null;
            }
            return mAlbumCursor.getString(mAlbumCursor.getColumnIndexOrThrow(AlbumColumns.ARTIST));
        }
    }

    public long getAlbumId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(AudioColumns.ALBUM_ID));
        }
    }

    public long getArtistId() {
        synchronized (this) {
            if (mCursor == null) {
                return -1;
            }
            return mCursor.getLong(mCursor.getColumnIndexOrThrow(AudioColumns.ARTIST_ID));
        }
    }

    public long getAudioId() {
        MusicTrack track = getCurrentTrack();
        if (track != null) {
            return track.mId;
        }

        return -1;
    }

    public MusicTrack getCurrentTrack() {
        return getTrack(mPlayPos);
    }

    public synchronized MusicTrack getTrack(int index) {
        if (index >= 0 && index < mPlaylist.size() && mPlayer.isInitialized()) {
            return mPlaylist.get(index);
        }

        return null;
    }

    public long getNextAudioId() {
        synchronized (this) {
            if (mNextPlayPos >= 0 && mNextPlayPos < mPlaylist.size() && mPlayer.isInitialized()) {
                return mPlaylist.get(mNextPlayPos).mId;
            }
        }
        return -1;
    }

    public long getPreviousAudioId() {
        synchronized (this) {
            if (mPlayer.isInitialized()) {
                int pos = getPreviousPlayPosition(false);
                if (pos >= 0 && pos < mPlaylist.size()) {
                    return mPlaylist.get(pos).mId;
                }
            }
        }
        return -1;
    }

    public long seek(long position) {
        if (mPlayer.isInitialized()) {
            if (position < 0) {
                position = 0;
            } else if (position > mPlayer.duration()) {
                position = mPlayer.duration();
            }
            long result = mPlayer.seek(position);
            notifyChange(POSITION_CHANGED);
            return result;
        }
        return -1;
    }

    public void seekRelative(long deltaInMs) {
        synchronized (this) {
            if (mPlayer.isInitialized()) {
                final long newPos = position() + deltaInMs;
                final long duration = duration();
                if (newPos < 0) {
                    prev(true);
                    // seek to the new duration + the leftover position
                    seek(duration() + newPos);
                } else if (newPos >= duration) {
                    gotoNext(true);
                    // seek to the leftover duration
                    seek(newPos - duration);
                } else {
                    seek(newPos);
                }
            }
        }
    }

    public long position() {
        if (mPlayer.isInitialized()) {
            return mPlayer.position();
        }
        return -1;
    }

    public long duration() {
        if (mPlayer.isInitialized()) {
            return mPlayer.duration();
        }
        return -1;
    }

    public long[] getQueue() {
        synchronized (this) {
            final int len = mPlaylist.size();
            final long[] list = new long[len];
            for (int i = 0; i < len; i++) {
                list[i] = mPlaylist.get(i).mId;
            }
            return list;
        }
    }

    public long getQueueItemAtPosition(int position) {
        synchronized (this) {
            if (position >= 0 && position < mPlaylist.size()) {
                return mPlaylist.get(position).mId;
            }
        }

        return -1;
    }

    public int getQueueSize() {
        synchronized (this) {
            return mPlaylist.size();
        }
    }

    public boolean isPlaying() {
        return mIsSupposedToBePlaying;
    }

    private void setIsSupposedToBePlaying(boolean value, boolean notify) {
        if (mIsSupposedToBePlaying != value) {
            mIsSupposedToBePlaying = value;


            if (!mIsSupposedToBePlaying) {
                scheduleDelayedShutdown();
                mLastPlayedTime = System.currentTimeMillis();
            }

            if (notify) {
                notifyChange(PLAYSTATE_CHANGED);
            }
        }
    }

    private boolean recentlyPlayed() {
        return isPlaying() || System.currentTimeMillis() - mLastPlayedTime < IDLE_DELAY;
    }

    public void open(final HashMap<Long,MusicInfo> infos , final long[] list, final int position) {
        synchronized (this) {

            mPlaylistInfo = infos;


//                try {
//                    FileInputStream fo = new FileInputStream(new File("/storage/emulated/0/playlist"));
//                    String c = readTextFromSDcard(fo);
//                    HashMap<Long,MusicInfo> play = gson.fromJson(c,new TypeToken<HashMap<Long,MusicInfo>>(){}.getType());
//                    if(play != null && play.size()> 0){
//                        mPlaylistInfo = play;
//                        Log.e("reload",mPlaylistInfo.keySet().toString());
//                    }
//
//                } catch (FileNotFoundException e) {
//                    e.printStackTrace();
//                } catch (Exception e) {
//                    e.printStackTrace();
//                }

                // mPlaylistInfo = infos;


            if (mShuffleMode == SHUFFLE_AUTO) {
                mShuffleMode = SHUFFLE_NORMAL;
            }
            final long oldId = getAudioId();
            final int listlength = list.length;
            boolean newlist = true;
            if (mPlaylist.size() == listlength) {
                newlist = false;
                for (int i = 0; i < listlength; i++) {
                    if (list[i] != mPlaylist.get(i).mId) {
                        newlist = true;
                        break;
                    }
                }
            }
            if (newlist) {
                addToPlayList(list, -1);
                notifyChange(QUEUE_CHANGED);
            }
            if (position >= 0) {
                mPlayPos = position;
            } else {
                mPlayPos = mShuffler.nextInt(mPlaylist.size());
            }


            mHistory.clear();
            openCurrentAndNext();
            if (oldId != getAudioId()) {
                notifyChange(META_CHANGED);
            }
        }
    }

    public void stop() {
        stop(true);
    }

    public void play() {
        play(true);
    }

    public void play(boolean createNewNextTrack) {
        int status = mAudioManager.requestAudioFocus(mAudioFocusListener,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        if (D) Log.d(TAG, "Starting playback: audio focus request status = " + status);

        if (status != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            return;
        }

        final Intent intent = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
        intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
        sendBroadcast(intent);

        mAudioManager.registerMediaButtonEventReceiver(new ComponentName(getPackageName(),
                MediaButtonIntentReceiver.class.getName()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            mSession.setActive(true);

        if (createNewNextTrack) {
            setNextTrack();
        } else {
            setNextTrack(mNextPlayPos);
        }

        if (mPlayer.isInitialized()) {
            final long duration = mPlayer.duration();
            if (mRepeatMode != REPEAT_CURRENT && duration > 2000
                    && mPlayer.position() >= duration - 2000) {
                gotoNext(true);
            }

            mPlayer.start();
            mPlayerHandler.removeMessages(FADEDOWN);
            mPlayerHandler.sendEmptyMessage(FADEUP);

            setIsSupposedToBePlaying(true, true);

            cancelShutdown();
            updateNotification();
            notifyChange(META_CHANGED);
        } else if (mPlaylist.size() <= 0) {
            setShuffleMode(SHUFFLE_AUTO);
        }
    }

    public void pause() {
        if (D) Log.d(TAG, "Pausing playback");
        synchronized (this) {
            mPlayerHandler.removeMessages(FADEUP);
            if (mIsSupposedToBePlaying) {
                final Intent intent = new Intent(
                        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, getAudioSessionId());
                intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, getPackageName());
                sendBroadcast(intent);

                mPlayer.pause();
                notifyChange(META_CHANGED);
                setIsSupposedToBePlaying(false, true);
            }
        }
    }

    public void gotoNext(final boolean force) {
        if (D) Log.d(TAG, "Going to next track");
        synchronized (this) {
            if (mPlaylist.size() <= 0) {
                if (D) Log.d(TAG, "No play queue");
                scheduleDelayedShutdown();
                return;
            }
            int pos = mNextPlayPos;
            if (pos < 0) {
                pos = getNextPosition(force);
            }

            if (pos < 0) {
                setIsSupposedToBePlaying(false, true);
                return;
            }

            stop(false);
            setAndRecordPlayPos(pos);
            openCurrentAndNext();
            play();
            notifyChange(META_CHANGED);
        }
    }

    public void setAndRecordPlayPos(int nextPos) {
        synchronized (this) {

            if (mShuffleMode != SHUFFLE_NONE) {
                mHistory.add(mPlayPos);
                if (mHistory.size() > MAX_HISTORY_SIZE) {
                    mHistory.remove(0);
                }
            }

            mPlayPos = nextPos;
        }
    }

    public void prev(boolean forcePrevious) {
        synchronized (this) {


            boolean goPrevious = getRepeatMode() != REPEAT_CURRENT &&
                    (position() < REWIND_INSTEAD_PREVIOUS_THRESHOLD || forcePrevious);

            if (goPrevious) {
                if (D) Log.d(TAG, "Going to previous track");
                int pos = getPreviousPlayPosition(true);

                if (pos < 0) {
                    return;
                }
                mNextPlayPos = mPlayPos;
                mPlayPos = pos;
                stop(false);
                openCurrent();
                play(false);
                notifyChange(META_CHANGED);
            } else {
                if (D) Log.d(TAG, "Going to beginning of track");
                seek(0);
                play(false);
            }
        }
    }

    public int getPreviousPlayPosition(boolean removeFromHistory) {
        synchronized (this) {
            if (mShuffleMode == SHUFFLE_NORMAL) {

                final int histsize = mHistory.size();
                if (histsize == 0) {
                    return -1;
                }
                final Integer pos = mHistory.get(histsize - 1);
                if (removeFromHistory) {
                    mHistory.remove(histsize - 1);
                }
                return pos.intValue();
            } else {
                if (mPlayPos > 0) {
                    return mPlayPos - 1;
                } else {
                    return mPlaylist.size() - 1;
                }
            }
        }
    }

    private void openCurrent() {
        openCurrentAndMaybeNext(false);
    }

    public void moveQueueItem(int index1, int index2) {
        synchronized (this) {
            if (index1 >= mPlaylist.size()) {
                index1 = mPlaylist.size() - 1;
            }
            if (index2 >= mPlaylist.size()) {
                index2 = mPlaylist.size() - 1;
            }

            if (index1 == index2) {
                return;
            }

            final MusicTrack track = mPlaylist.remove(index1);
            if (index1 < index2) {
                mPlaylist.add(index2, track);
                if (mPlayPos == index1) {
                    mPlayPos = index2;
                } else if (mPlayPos >= index1 && mPlayPos <= index2) {
                    mPlayPos--;
                }
            } else if (index2 < index1) {
                mPlaylist.add(index2, track);
                if (mPlayPos == index1) {
                    mPlayPos = index2;
                } else if (mPlayPos >= index2 && mPlayPos <= index1) {
                    mPlayPos++;
                }
            }
            notifyChange(QUEUE_CHANGED);
        }
    }

    public void enqueue(final long[] list, final int action) {
        synchronized (this) {
            if (action == NEXT && mPlayPos + 1 < mPlaylist.size()) {
                addToPlayList(list, mPlayPos + 1);
                mNextPlayPos = mPlayPos + 1;
                notifyChange(QUEUE_CHANGED);
            } else {
                addToPlayList(list, Integer.MAX_VALUE);
                notifyChange(QUEUE_CHANGED);
            }

            if (mPlayPos < 0) {
                mPlayPos = 0;
                openCurrentAndNext();
                play();
                notifyChange(META_CHANGED);
            }
        }
    }

    private void cycleRepeat() {
        if (mRepeatMode == REPEAT_NONE) {
            setRepeatMode(REPEAT_CURRENT);
            if (mShuffleMode != SHUFFLE_NONE) {
                setShuffleMode(SHUFFLE_NONE);
            }
        } else {
            setRepeatMode(REPEAT_NONE);
        }
    }

    private void cycleShuffle() {
        if (mShuffleMode == SHUFFLE_NONE) {
            setShuffleMode(SHUFFLE_NORMAL);
            if (mRepeatMode == REPEAT_CURRENT) {
                setRepeatMode(REPEAT_ALL);
            }
        } else if (mShuffleMode == SHUFFLE_NORMAL || mShuffleMode == SHUFFLE_AUTO) {
            setShuffleMode(SHUFFLE_NONE);
        }
    }

    public void refresh() {
        notifyChange(REFRESH);
    }

    public void playlistChanged() {
        notifyChange(PLAYLIST_CHANGED);
    }

    public void setLockscreenAlbumArt(boolean enabled) {
        mShowAlbumArtOnLockscreen = enabled;
        notifyChange(META_CHANGED);
    }

    public void timing(int time) {
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(PAUSE_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC, System.currentTimeMillis() + time, pendingIntent);

    }

    public interface TrackErrorExtra {

        String TRACK_NAME = "trackname";
    }

    private static final class MusicPlayerHandler extends Handler {
        private final WeakReference<MediaService> mService;
        private float mCurrentVolume = 1.0f;


        public MusicPlayerHandler(final MediaService service, final Looper looper) {
            super(looper);
            mService = new WeakReference<MediaService>(service);
        }


        @Override
        public void handleMessage(final Message msg) {
            final MediaService service = mService.get();
            if (service == null) {
                return;
            }

            synchronized (service) {
                switch (msg.what) {
                    case FADEDOWN:
                        mCurrentVolume -= .05f;
                        if (mCurrentVolume > .2f) {
                            sendEmptyMessageDelayed(FADEDOWN, 10);
                        } else {
                            mCurrentVolume = .2f;
                        }
                        service.mPlayer.setVolume(mCurrentVolume);
                        break;
                    case FADEUP:
                        mCurrentVolume += .01f;
                        if (mCurrentVolume < 1.0f) {
                            sendEmptyMessageDelayed(FADEUP, 10);
                        } else {
                            mCurrentVolume = 1.0f;
                        }
                        service.mPlayer.setVolume(mCurrentVolume);
                        break;
                    case SERVER_DIED:
                        if (service.isPlaying()) {
                            final TrackErrorInfo info = (TrackErrorInfo) msg.obj;
                            service.sendErrorMessage(info.mTrackName);


                            service.removeTrack(info.mId);
                        } else {
                            service.openCurrentAndNext();
                        }
                        break;
                    case TRACK_WENT_TO_NEXT:
                        service.setAndRecordPlayPos(service.mNextPlayPos);
                        service.setNextTrack();
                        if (service.mCursor != null) {
                            service.mCursor.close();
                            service.mCursor = null;
                        }
                        if(service.isNextLocal){
                            service.updateCursor(service.mPlaylist.get(service.mPlayPos).mId);
                        }else {
                            MusicInfo info = service.mPlaylistInfo.get(service.mPlaylist.get(service.mPlayPos).mId);
                            MatrixCursor cursor = new MatrixCursor(PROJECTION);
                            cursor.addRow(new Object[]{info.songId,info.artist,info.albumName,info.musicName
                                    ,info.data,info.albumData,info.albumId,info.artistId});
                            cursor.moveToFirst();
                            service.mCursor = cursor;
                            cursor.close();
                        }

                        service.notifyChange(META_CHANGED);
                        service.updateNotification();
                        break;
                    case TRACK_ENDED:
                        if (service.mRepeatMode == REPEAT_CURRENT) {
                            service.seek(0);
                            service.play();
                        } else {
                            service.gotoNext(false);
                        }
                        break;
                    case RELEASE_WAKELOCK:
                        service.mWakeLock.release();
                        break;
                    case FOCUSCHANGE:
                        if (D) Log.d(TAG, "Received audio focus change event " + msg.arg1);
                        switch (msg.arg1) {
                            case AudioManager.AUDIOFOCUS_LOSS:
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                if (service.isPlaying()) {
                                    service.mPausedByTransientLossOfFocus =
                                            msg.arg1 == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
                                }
                                service.pause();
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                removeMessages(FADEUP);
                                sendEmptyMessage(FADEDOWN);
                                break;
                            case AudioManager.AUDIOFOCUS_GAIN:
                                if (!service.isPlaying()
                                        && service.mPausedByTransientLossOfFocus) {
                                    service.mPausedByTransientLossOfFocus = false;
                                    mCurrentVolume = 0f;
                                    service.mPlayer.setVolume(mCurrentVolume);
                                    service.play();
                                } else {
                                    removeMessages(FADEDOWN);
                                    sendEmptyMessage(FADEUP);
                                }
                                break;
                            default:
                        }
                        break;
                    default:
                        break;
                }
            }
        }
    }

    private static final class Shuffler {

        private final LinkedList<Integer> mHistoryOfNumbers = new LinkedList<Integer>();

        private final TreeSet<Integer> mPreviousNumbers = new TreeSet<Integer>();

        private final Random mRandom = new Random();

        private int mPrevious;


        public Shuffler() {
            super();
        }


        public int nextInt(final int interval) {
            int next;
            do {
                next = mRandom.nextInt(interval);
            } while (next == mPrevious && interval > 1
                    && !mPreviousNumbers.contains(Integer.valueOf(next)));
            mPrevious = next;
            mHistoryOfNumbers.add(mPrevious);
            mPreviousNumbers.add(mPrevious);
            cleanUpHistory();
            return next;
        }


        private void cleanUpHistory() {
            if (!mHistoryOfNumbers.isEmpty() && mHistoryOfNumbers.size() >= MAX_HISTORY_SIZE) {
                for (int i = 0; i < Math.max(1, MAX_HISTORY_SIZE / 2); i++) {
                    mPreviousNumbers.remove(mHistoryOfNumbers.removeFirst());
                }
            }
        }
    }

    private static final class TrackErrorInfo {
        public long mId;
        public String mTrackName;

        public TrackErrorInfo(long id, String trackName) {
            mId = id;
            mTrackName = trackName;
        }
    }

    private static final class MultiPlayer implements MediaPlayer.OnErrorListener,
            MediaPlayer.OnCompletionListener,MediaPlayer.OnPreparedListener {

        private final WeakReference<MediaService> mService;

        private MediaPlayer mCurrentMediaPlayer = new MediaPlayer();

        private MediaPlayer mNextMediaPlayer;

        private Handler mHandler;

        private boolean mIsInitialized = false;

        private String mNextMediaPath;

        private boolean prepared = false;

        private boolean nextPrepared = false;


        public MultiPlayer(final MediaService service) {
            mService = new WeakReference<MediaService>(service);
            mCurrentMediaPlayer.setWakeMode(mService.get(), PowerManager.PARTIAL_WAKE_LOCK);

        }


        public void setDataSource(final String path) {
            mIsInitialized = setDataSourceImpl(mCurrentMediaPlayer, path);
            if (mIsInitialized) {
                setNextDataSource(null);
            }
        }


        private boolean setDataSourceImpl(final MediaPlayer player, final String path) {
            try {
                player.reset();
                player.setOnPreparedListener(this);
                if (path.startsWith("content://")) {
                    player.setDataSource(mService.get(), Uri.parse(path));
                } else {
                    player.setDataSource(path);
                }
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);

                player.prepare();
            } catch (final IOException todo) {

                return false;
            } catch (final IllegalArgumentException todo) {

                return false;
            }
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            return true;
        }

        private boolean setNextDataSourceImpl(final MediaPlayer player, final String path) {
            try {
                player.reset();
                player.setOnPreparedListener(new PreParedListener());
                if (path.startsWith("content://")) {
                    player.setDataSource(mService.get(), Uri.parse(path));
                } else {
                    player.setDataSource(path);
                }
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);

                player.prepareAsync();
            } catch (final IOException todo) {

                return false;
            } catch (final IllegalArgumentException todo) {

                return false;
            }
            player.setOnCompletionListener(this);
            player.setOnErrorListener(this);
            return true;
        }

       class PreParedListener implements MediaPlayer.OnPreparedListener{

           @Override
           public void onPrepared(MediaPlayer mp) {
               nextPrepared = true;
           }
       }




        public void setNextDataSource(final String path) {
            mNextMediaPath = null;
            try {
                mCurrentMediaPlayer.setNextMediaPlayer(null);
            } catch (IllegalArgumentException e) {
                Log.i(TAG, "Next media player is current one, continuing");
            } catch (IllegalStateException e) {
                Log.e(TAG, "Media player not initialized!");
                return;
            }
            if (mNextMediaPlayer != null) {
                mNextMediaPlayer.release();
                mNextMediaPlayer = null;
            }
            if (path == null) {
                return;
            }
            mNextMediaPlayer = new MediaPlayer();
            mNextMediaPlayer.setWakeMode(mService.get(), PowerManager.PARTIAL_WAKE_LOCK);
            mNextMediaPlayer.setAudioSessionId(getAudioSessionId());
            if (setDataSourceImpl(mNextMediaPlayer, path)) {
                mNextMediaPath = path;
                if(mCurrentMediaPlayer.isLooping() && mCurrentMediaPlayer.isPlaying()){
                    mCurrentMediaPlayer.setNextMediaPlayer(mNextMediaPlayer);
                }else {
                    try {
                        Thread.sleep(1000);
                        if(mCurrentMediaPlayer.isLooping() && mCurrentMediaPlayer.isPlaying())
                            mCurrentMediaPlayer.setNextMediaPlayer(mNextMediaPlayer);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            } else {
                if (mNextMediaPlayer != null) {
                    mNextMediaPlayer.release();
                    mNextMediaPlayer = null;
                }
            }
        }


        public void setHandler(final Handler handler) {
            mHandler = handler;
        }


        public boolean isInitialized() {
            return mIsInitialized;
        }

        Handler handler = new Handler();
        public void start() {
            mCurrentMediaPlayer.start();
           // handler.postDelayed(runnable,30);
        }

//        Runnable runnable = new Runnable() {
//            @Override
//            public void run() {
//                if(!prepared){
//                    handler.postDelayed(runnable,30);
//                }
//                mCurrentMediaPlayer.start();
//            }
//        };

        public void stop() {
            prepared = false;
            mCurrentMediaPlayer.reset();
            mIsInitialized = false;
        }


        public void release() {
            mCurrentMediaPlayer.release();
        }


        public void pause() {
            mCurrentMediaPlayer.pause();
        }


        public long duration() {
            return mCurrentMediaPlayer.getDuration();
        }


        public long position() {
            return mCurrentMediaPlayer.getCurrentPosition();
        }


        public long seek(final long whereto) {
            mCurrentMediaPlayer.seekTo((int) whereto);
            return whereto;
        }


        public void setVolume(final float vol) {
            mCurrentMediaPlayer.setVolume(vol, vol);
        }

        public int getAudioSessionId() {
            return mCurrentMediaPlayer.getAudioSessionId();
        }

        public void setAudioSessionId(final int sessionId) {
            mCurrentMediaPlayer.setAudioSessionId(sessionId);
        }

        @Override
        public void onPrepared(MediaPlayer mp) {
           prepared = true;
        }

        @Override
        public boolean onError(final MediaPlayer mp, final int what, final int extra) {
            prepared = false;
            Log.w(TAG, "Music Server Error what: " + what + " extra: " + extra);
            switch (what) {
                case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                    final MediaService service = mService.get();
                    final TrackErrorInfo errorInfo = new TrackErrorInfo(service.getAudioId(),
                            service.getTrackName());

                    mIsInitialized = false;
                    mCurrentMediaPlayer.release();
                    mCurrentMediaPlayer = new MediaPlayer();
                    mCurrentMediaPlayer.setWakeMode(service, PowerManager.PARTIAL_WAKE_LOCK);
                    Message msg = mHandler.obtainMessage(SERVER_DIED, errorInfo);
                    mHandler.sendMessageDelayed(msg, 2000);
                    return true;
                default:
                    break;
            }
            return false;
        }


        @Override
        public void onCompletion(final MediaPlayer mp) {
            prepared = false;

            if (mp == mCurrentMediaPlayer && mNextMediaPlayer != null) {
                mCurrentMediaPlayer.release();
                mCurrentMediaPlayer = mNextMediaPlayer;
                mNextMediaPath = null;
                mNextMediaPlayer = null;
                mHandler.sendEmptyMessage(TRACK_WENT_TO_NEXT);
            } else {
                mService.get().mWakeLock.acquire(30000);
                mHandler.sendEmptyMessage(TRACK_ENDED);
                mHandler.sendEmptyMessage(RELEASE_WAKELOCK);
            }
        }


    }

    private static final class ServiceStub extends MediaAidlInterface.Stub {

        private final WeakReference<MediaService> mService;

        private ServiceStub(final MediaService service) {
            mService = new WeakReference<MediaService>(service);
        }


        @Override
        public void openFile(final String path) throws RemoteException {
            mService.get().openFile(path);
        }

        @Override
        public void open(final Map infos , final long[] list, final int position)
                throws RemoteException {
            mService.get().open((HashMap<Long, MusicInfo>) infos,list, position);
        }

        @Override
        public void stop() throws RemoteException {
            mService.get().stop();
        }

        @Override
        public void pause() throws RemoteException {
            mService.get().pause();
        }


        @Override
        public void play() throws RemoteException {
            mService.get().play();
        }

        @Override
        public void prev(boolean forcePrevious) throws RemoteException {
            mService.get().prev(forcePrevious);
        }

        @Override
        public void next() throws RemoteException {
            mService.get().gotoNext(true);
        }

        @Override
        public void enqueue(final long[] list, final int action)
                throws RemoteException {
            mService.get().enqueue(list, action);
        }

        @Override
        public void moveQueueItem(final int from, final int to) throws RemoteException {
            mService.get().moveQueueItem(from, to);
        }

        @Override
        public void refresh() throws RemoteException {
            mService.get().refresh();
        }

        @Override
        public void playlistChanged() throws RemoteException {
            mService.get().playlistChanged();
        }

        @Override
        public boolean isPlaying() throws RemoteException {
            return mService.get().isPlaying();
        }

        @Override
        public long[] getQueue() throws RemoteException {
            return mService.get().getQueue();
        }

        @Override
        public long getQueueItemAtPosition(int position) throws RemoteException {
            return mService.get().getQueueItemAtPosition(position);
        }

        @Override
        public int getQueueSize() throws RemoteException {
            return mService.get().getQueueSize();
        }

        @Override
        public int getQueueHistoryPosition(int position) throws RemoteException {
            return mService.get().getQueueHistoryPosition(position);
        }

        @Override
        public int getQueueHistorySize() throws RemoteException {
            return mService.get().getQueueHistorySize();
        }

        @Override
        public int[] getQueueHistoryList() throws RemoteException {
            return mService.get().getQueueHistoryList();
        }

        @Override
        public long duration() throws RemoteException {
            return mService.get().duration();
        }

        @Override
        public long position() throws RemoteException {
            return mService.get().position();
        }

        @Override
        public long seek(final long position) throws RemoteException {
            return mService.get().seek(position);
        }

        @Override
        public void seekRelative(final long deltaInMs) throws RemoteException {
            mService.get().seekRelative(deltaInMs);
        }

        @Override
        public long getAudioId() throws RemoteException {
            return mService.get().getAudioId();
        }

        @Override
        public MusicTrack getCurrentTrack() throws RemoteException {
            return mService.get().getCurrentTrack();
        }

        @Override
        public MusicTrack getTrack(int index) throws RemoteException {
            return mService.get().getTrack(index);
        }

        @Override
        public long getNextAudioId() throws RemoteException {
            return mService.get().getNextAudioId();
        }

        @Override
        public long getPreviousAudioId() throws RemoteException {
            return mService.get().getPreviousAudioId();
        }

        @Override
        public long getArtistId() throws RemoteException {
            return mService.get().getArtistId();
        }

        @Override
        public long getAlbumId() throws RemoteException {
            return mService.get().getAlbumId();
        }

        @Override
        public String getArtistName() throws RemoteException {
            return mService.get().getArtistName();
        }

        @Override
        public String getTrackName() throws RemoteException {
            return mService.get().getTrackName();
        }

        @Override
        public boolean isTrackLocal() throws RemoteException {
            return mService.get().isTrackLocal();
        }

        @Override
        public String getAlbumName() throws RemoteException {
            return mService.get().getAlbumName();
        }

        @Override
        public String getAlbumPath() throws RemoteException {
            return mService.get().getAlbumPath();
        }

        @Override
        public String[] getAlbumPathtAll() throws RemoteException {
            return mService.get().getAlbumPathAll();
        }

        @Override
        public String getPath() throws RemoteException {
            return mService.get().getPath();
        }

        @Override
        public int getQueuePosition() throws RemoteException {
            return mService.get().getQueuePosition();
        }

        @Override
        public void setQueuePosition(final int index) throws RemoteException {
            mService.get().setQueuePosition(index);
        }

        @Override
        public int getShuffleMode() throws RemoteException {
            return mService.get().getShuffleMode();
        }

        @Override
        public void setShuffleMode(final int shufflemode) throws RemoteException {
            mService.get().setShuffleMode(shufflemode);
        }

        @Override
        public int getRepeatMode() throws RemoteException {
            return mService.get().getRepeatMode();
        }

        @Override
        public void setRepeatMode(final int repeatmode) throws RemoteException {
            mService.get().setRepeatMode(repeatmode);
        }

        @Override
        public int removeTracks(final int first, final int last) throws RemoteException {
            return mService.get().removeTracks(first, last);
        }


        @Override
        public int removeTrack(final long id) throws RemoteException {
            return mService.get().removeTrack(id);
        }


        @Override
        public boolean removeTrackAtPosition(final long id, final int position)
                throws RemoteException {
            return mService.get().removeTrackAtPosition(id, position);
        }


        @Override
        public int getMediaMountedCount() throws RemoteException {
            return mService.get().getMediaMountedCount();
        }


        @Override
        public int getAudioSessionId() throws RemoteException {
            return mService.get().getAudioSessionId();
        }


        @Override
        public void setLockscreenAlbumArt(boolean enabled) {
            mService.get().setLockscreenAlbumArt(enabled);
        }

        @Override
        public void exit() throws RemoteException {
            mService.get().exit();
        }

        @Override
        public void timing(int time) throws RemoteException {
            mService.get().timing(time);
        }

    }

    private class MediaStoreObserver extends ContentObserver implements Runnable {

        private static final long REFRESH_DELAY = 500;
        private Handler mHandler;

        public MediaStoreObserver(Handler handler) {
            super(handler);
            mHandler = handler;
        }

        @Override
        public void onChange(boolean selfChange) {


            mHandler.removeCallbacks(this);
            mHandler.postDelayed(this, REFRESH_DELAY);
        }

        @Override
        public void run() {

            Log.e("ELEVEN", "calling refresh!");
            refresh();
        }
    }


}
