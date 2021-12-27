/*
 * Created by guo on 2021/07/30.
 * Copyright 2015－2021 Zall Data Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zalldata.analytics.android.sdk.autotrack;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;

import com.zalldata.analytics.android.sdk.ZALog;
import com.zalldata.analytics.android.sdk.ZallDataAPI;
import com.zalldata.analytics.android.sdk.ZallDataActivityLifecycleCallbacks;
import com.zalldata.analytics.android.sdk.ZallDataExceptionHandler;
import com.zalldata.analytics.android.sdk.autotrack.utils.AutoTrackUtils;
import com.zalldata.analytics.android.sdk.util.AopUtil;
import com.zalldata.analytics.android.sdk.util.ZallDataUtils;
import com.zalldata.analytics.android.sdk.util.TimeUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;

/**
 * Activity 页面停留时长
 */
public class ActivityPageLeaveCallbacks implements ZallDataActivityLifecycleCallbacks.ZAActivityLifecycleCallbacks, ZallDataExceptionHandler.ZAExceptionListener {
    private static final String START_TIME = "za_start_time";
    private final HashMap<Integer, JSONObject> mResumedActivities = new HashMap<>();
    // 弹窗页面
    private final String DIALOG_ACTIVITY = "com.zalldata.sf.ui.view.DialogActivity";

    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {

    }

    @Override
    public void onActivityStarted(Activity activity) {

    }

    @Override
    public void onActivityResumed(Activity activity) {
        trackActivityStart(activity);
    }

    @Override
    public void onActivityPaused(Activity activity) {
        try {
            int hashCode = activity.hashCode();
            if (mResumedActivities.containsKey(hashCode)) {
                trackAppPageLeave(mResumedActivities.get(hashCode));
                mResumedActivities.remove(hashCode);
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {

    }

    @Override
    public void onActivityStopped(Activity activity) {

    }

    @Override
    public void onActivityDestroyed(Activity activity) {

    }

    @Override
    public void onNewIntent(Intent intent) {

    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            Iterator<Integer> keyCodes = mResumedActivities.keySet().iterator();
            while (keyCodes.hasNext()) {
                int hashCode = keyCodes.next();
                JSONObject properties = mResumedActivities.get(hashCode);
                if (properties == null) {
                    continue;
                }
                trackAppPageLeave(properties);
                keyCodes.remove();
            }
        } catch (Exception exception) {
            ZALog.printStackTrace(exception);
        }
    }

    private void trackActivityStart(Activity activity) {
        try {
            if (DIALOG_ACTIVITY.equals(activity.getClass().getCanonicalName())) {
                return;
            }
            JSONObject properties = AopUtil.buildTitleAndScreenName(activity);
            String url = ZallDataUtils.getScreenUrl(activity);
            properties.put("$url", url);
            String referrer = AutoTrackUtils.getLastScreenUrl();
            if (!TextUtils.isEmpty(referrer)) {
                properties.put("$referrer", referrer);
            }
            properties.put(START_TIME, SystemClock.elapsedRealtime());
            mResumedActivities.put(activity.hashCode(), properties);
            AutoTrackUtils.setLastScreenUrl(url);
        } catch (JSONException e) {
            ZALog.printStackTrace(e);
        }
    }

    private void trackAppPageLeave(JSONObject properties) {
        try {
            long resumeTime = properties.optLong(START_TIME);
            properties.remove(START_TIME);
            double duration = TimeUtils.duration(resumeTime, SystemClock.elapsedRealtime());
            if (duration < 0.05) {
                return;
            }
            properties.put("event_duration", duration);
            ZallDataAPI.sharedInstance().trackInternal("$AppPageLeave", properties);
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }
}
