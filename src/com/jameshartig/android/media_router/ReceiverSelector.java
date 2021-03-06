/*
 * Copyright 2011 Harleen Sahni
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jameshartig.android.media_router;

import static com.jameshartig.android.media_router.Constants.TAG;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.jameshartig.android.media_router.receivers.MediaButtonReceiver;

/**
 * Allows the user to choose which media receiver will handle a media button
 * press. Can be navigated via touch screen or media button keys. Provides voice
 * feedback.
 * 
 * @author Harleen Sahni
 * @author James Hartig
 */
public class ReceiverSelector extends ListActivity implements AudioManager.OnAudioFocusChangeListener {

    private class SweepBroadcastReceiver extends BroadcastReceiver {
        String name;

        public SweepBroadcastReceiver(String name) {
            this.name = name;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Selector: After running broadcast receiver " + name + "have resultcode: "
                    + getResultCode() + " result Data: " + getResultData()); */
        }
    }

    /**
     * Number of seconds to wait before timing out and just cancelling.
     */
    private int timeoutTime;

    /**
     * The media button event that {@link MediaButtonReceiver} captured, and
     * that we will be forwarding to a music player's {@code BroadcastReceiver}
     * on selection.
     */
    private KeyEvent trappedKeyEvent;

    /**
     * The {@code BroadcastReceiver}'s registered in the system for *
     * {@link Intent.ACTION_MEDIA_BUTTON}.
     */
    private List<ResolveInfo> receivers;

    /** The intent filter for registering our local {@code BroadcastReceiver}. */
    private IntentFilter uiIntentFilter;

    /**
     * Whether we've done the start up announcement to the user using the text
     * to speech. Tracked so we don't repeat ourselves on orientation change.
     */
    private boolean announced;

    /**
     * Whether we've requested audio focus.
     */
    private boolean audioFocus;

    /**
     * ScheduledExecutorService used to time out and close activity if the user
     * doesn't make a selection within certain amount of time. Resets on user
     * interaction.
     */
    private ScheduledExecutorService timeoutExecutor;

    /**
     * ScheduledFuture of timeout.
     */
    private ScheduledFuture<?> timeoutScheduledFuture;

    /** The cancel button. */
    private View cancelButton;

    /** Ignore button */
    private View ignoreButton;

    /** The header */
    private TextView header;

    /** Used to figure out if music is playing and handle audio focus. */
    private AudioManager audioManager;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "Media Button Selector: On Create Called");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                             | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                             | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        setContentView(R.layout.media_button_list);

        uiIntentFilter = new IntentFilter(Intent.ACTION_MEDIA_BUTTON);
        uiIntentFilter.addAction(Constants.INTENT_ACTION_VIEW_MEDIA_LIST_KEYPRESS);
        uiIntentFilter.setPriority(Integer.MAX_VALUE);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

        audioManager = (AudioManager) this.getSystemService(AUDIO_SERVICE);

        // XXX can't use integer array, argh:
        // http://code.google.com/p/android/issues/detail?id=2096
        timeoutTime = Integer.valueOf(preferences.getString(Constants.TIMEOUT_KEY, "0"));

        receivers = Utils.getMediaReceivers(getPackageManager(), true, getApplicationContext());

        Boolean lastAnnounced = (Boolean) getLastNonConfigurationInstance();
        if (lastAnnounced != null) {
            announced = lastAnnounced;
        }

        // Remove our app's receiver from the list so users can't select it.
        // NOTE: Our local receiver isn't registered at this point so we don't
        // have to remove it.
        if (receivers != null) {
            for (int i = 0; i < receivers.size(); i++) {
                if (MediaButtonReceiver.class.getName().equals(receivers.get(i).activityInfo.name)) {
                    receivers.remove(i);
                    break;
                }
            }
        }
        // TODO MAYBE sort receivers by MRU so user doesn't have to skip as many
        // apps,
        // right now apps are sorted by priority (not set by the user, set by
        // the app authors.. )
        setListAdapter(new BaseAdapter() {

            @Override
            public int getCount() {
                return receivers.size();
            }

            @Override
            public Object getItem(int position) {
                return receivers.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {

                View view = convertView;
                if (view == null) {
                    LayoutInflater vi = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    view = vi.inflate(R.layout.media_receiver_view, null);
                }

                ResolveInfo resolveInfo = receivers.get(position);

                ImageView imageView = (ImageView) view.findViewById(R.id.receiverAppImage);
                imageView.setImageDrawable(resolveInfo.loadIcon(getPackageManager()));

                TextView textView = (TextView) view.findViewById(R.id.receiverAppName);
                textView.setText(Utils.getAppName(resolveInfo, getPackageManager()));
                return view;

            }
        });
        header = (TextView) findViewById(R.id.dialogHeader);
        cancelButton = findViewById(R.id.cancelButton);
        cancelButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ignoreButton = findViewById(R.id.ignoreButton);
        ignoreButton.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View v) {
                ignore();
            }
        });

        /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Selector: created."); */
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Media Button Selector: destroyed.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        getListView().invalidateViews();

        forwardToMediaReceiver(position);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "Media Button Selector: onPause");
        timeoutExecutor.shutdownNow();
        audioManager.abandonAudioFocus(this);
    }

    @Override
    protected void onStart() {

        super.onStart();
        Log.d(TAG, "Media Button Selector: On Start called");

        // TODO Originally thought most work should happen onResume and onPause.
        // I don't know if the onResume part is
        // right since you can't actually ever get back to this view, single
        // instance, and not shown in recents. Maybe it's possible if ANOTHER
        // dialog opens in front of ours?
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "Media Button Selector: onResume");

        requestAudioFocus();
        // TODO Clean this up, figure out which things need to be set on the list view and which don't.
        if (getIntent().getExtras() != null && getIntent().getExtras().get(Intent.EXTRA_KEY_EVENT) != null) {
            trappedKeyEvent = (KeyEvent) getIntent().getExtras().get(Intent.EXTRA_KEY_EVENT);

            /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Selector: handling event: " + trappedKeyEvent + " from intent:" + getIntent()); */

            getListView().setChoiceMode(ListView.CHOICE_MODE_SINGLE);
            getListView().setClickable(true);
            getListView().setFocusable(true);
            getListView().setFocusableInTouchMode(true);

            String action = "";
            int adjustedKeyCode = Utils.getAdjustedKeyCode(trappedKeyEvent);
            switch (adjustedKeyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    action = getString(audioManager.isMusicActive() ? R.string.pausePlay : R.string.play);
                break;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    action = getString(R.string.next);
                break;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    action = getString(R.string.prev);
                break;
                case KeyEvent.KEYCODE_MEDIA_STOP:
                    action = getString(R.string.stop);
                break;
                default:
                    //support for newer codes
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && adjustedKeyCode == KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK) {
                        action = getString(R.string.audio_track);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1 && adjustedKeyCode == KeyEvent.KEYCODE_MUSIC) {
                        action = getString(R.string.music);
                    }
                break;
            }

            header.setText(String.format(getString(R.string.dialog_header_with_action), action));
        } else {
            /* COMMENTED OUT FOR MARKET RELEASE Log.i(TAG, "Media Button Selector: launched without key event, started with intent: " + getIntent()); */

            trappedKeyEvent = null;
            getListView().setClickable(false);
            getListView().setChoiceMode(ListView.CHOICE_MODE_NONE);
            getListView().setFocusable(false);
            getListView().setFocusableInTouchMode(false);

        }

        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        resetTimeout();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object onRetainNonConfigurationInstance() {
        return announced;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // We're not supposed to show a menu since we show as a dialog,
        // according to google's ui guidelines. No other sane place to put this,
        // except maybe
        // a small configure button in the dialog header, but don't want users
        // to hit it by accident when selecting music app.
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.selector_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            startActivity(new Intent(this, MediaButtonConfigure.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Resets the timeout before the application is automatically dismissed.
     */
    private void resetTimeout() {
        if (timeoutScheduledFuture != null) {
            timeoutScheduledFuture.cancel(false);
        }

        if (timeoutTime == 0) {
            return;
        }
        timeoutScheduledFuture = timeoutExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        onTimeout();
                    }
                });

            }
        }, timeoutTime, TimeUnit.SECONDS);

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onUserInteraction() {
        super.onUserInteraction();

        // Reset timeout before we finish
        if (!timeoutExecutor.isShutdown()) {
            resetTimeout();
        }
    }

    /**
     * Forwards the {@code #trappedKeyEvent} to the receiver at specified
     * position.
     * 
     * @param position
     *            The index of the receiver to select. Must be in bounds.
     */
    private void forwardToMediaReceiver(int position) {
        ResolveInfo resolveInfo = receivers.get(position);
        if (resolveInfo != null) {
            if (trappedKeyEvent != null) {

                ComponentName selectedReceiver = new ComponentName(resolveInfo.activityInfo.packageName,
                        resolveInfo.activityInfo.name);
                Utils.forwardKeyCodeToComponent(this, selectedReceiver, true,
                        Utils.getAdjustedKeyCode(trappedKeyEvent),
                        new SweepBroadcastReceiver(selectedReceiver.toString()));
                finish();
            }
        }
    }



    /**
     * Onclick for ignore button
     */
    private void ignore() {
        Log.d(TAG, "Ignoring future selectors");
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
        preferences.edit().putString(Constants.LAST_MEDIA_BUTTON_RECEIVER, Constants.IGNORE_NEW_RECEIVER).commit();
        finish();
    }

    /**
     * Takes appropriate action to notify user and dismiss activity on timeout.
     */
    private void onTimeout() {
        /*Log.d(TAG, "Media Button Selector: Timed out waiting for user interaction, finishing activity");*/
        finish();
    }

    /**
     * Requests audio focus if necessary.
     */
    private void requestAudioFocus() {
        if (!audioFocus) {
            audioFocus = audioManager.requestAudioFocus(this, AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        // TODO Auto-generated method stub

    }
}
