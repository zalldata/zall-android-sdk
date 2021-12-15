/*
 * Created by guo on 2021/6/8.
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
package com.zalldata.zall.android.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.webkit.WebView;

import com.zalldata.zall.android.sdk.data.adapter.DbAdapter;
import com.zalldata.zall.android.sdk.data.adapter.DbParams;
import com.zalldata.zall.android.sdk.deeplink.ZallDataDeepLinkCallback;
import com.zalldata.zall.android.sdk.internal.rpc.ZallDataContentObserver;
import com.zalldata.zall.android.sdk.listener.ZAEventListener;
import com.zalldata.zall.android.sdk.listener.ZAFunctionListener;
import com.zalldata.zall.android.sdk.remote.BaseZallDataSDKRemoteManager;
import com.zalldata.zall.android.sdk.util.AopUtil;
import com.zalldata.zall.android.sdk.util.AppInfoUtils;
import com.zalldata.zall.android.sdk.advert.utils.ChannelUtils;
import com.zalldata.zall.android.sdk.advert.utils.OaidHelper;
import com.zalldata.zall.android.sdk.util.ZallDataUtils;
import com.zalldata.zall.android.sdk.util.TimeUtils;
import com.zalldata.zall.android.sdk.visual.property.VisualPropertiesManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.zalldata.zall.android.sdk.util.Base64Coder.CHARSET_UTF8;
import static com.zalldata.zall.android.sdk.util.ZADataHelper.assertKey;
import static com.zalldata.zall.android.sdk.util.ZADataHelper.assertPropertyLength;
import static com.zalldata.zall.android.sdk.util.ZADataHelper.assertPropertyTypes;
import static com.zalldata.zall.android.sdk.util.ZADataHelper.assertValue;

/**
 * Zall Data SDK
 */
public class ZallDataAPI extends AbstractZallDataAPI {
    // 可视化埋点功能最低 API 版本
    public static final int VTRACK_SUPPORTED_MIN_API = 16;
    // SDK 版本，此属性插件会进行访问，谨慎修改
    static final String VERSION = BuildConfig.SDK_VERSION;
    // 此属性插件会进行访问，谨慎删除。当前 SDK 版本所需插件最低版本号，设为空，意为没有任何限制
    static final String MIN_PLUGIN_VERSION = BuildConfig.MIN_PLUGIN_VERSION;
    /**
     * 插件版本号，插件会用到此属性，请谨慎修改
     */
    static String ANDROID_PLUGIN_VERSION = "";

    //private
    ZallDataAPI() {
        super();
    }

    ZallDataAPI(Context context, ZAConfigOptions configOptions, DebugMode debugMode) {
        super(context, configOptions, debugMode);
    }

    /**
     * 获取 ZallDataAPI 单例
     *
     * @param context App的Context
     * @return ZallDataAPI 单例
     */
    public static ZallDataAPI sharedInstance(Context context) {
        if (isSDKDisabled()) {
            return new ZallDataAPIEmptyImplementation();
        }

        if (null == context) {
            return new ZallDataAPIEmptyImplementation();
        }

        synchronized (sInstanceMap) {
            final Context appContext = context.getApplicationContext();
            ZallDataAPI instance = sInstanceMap.get(appContext);

            if (null == instance) {
                ZALog.i(TAG, "The static method sharedInstance(context, serverURL, debugMode) should be called before calling sharedInstance()");
                return new ZallDataAPIEmptyImplementation();
            }
            return instance;
        }
    }

    /**
     * 初始化卓尔 SDK
     *
     * @param context App 的 Context
     * @param zaConfigOptions SDK 的配置项
     */
    public static void startWithConfigOptions(Context context, ZAConfigOptions zaConfigOptions) {
        if (context == null || zaConfigOptions == null) {
            throw new NullPointerException("Context、ZAConfigOptions 不可以为 null");
        }
        ZallDataAPI zallDataAPI = getInstance(context, DebugMode.DEBUG_OFF, zaConfigOptions);
        if (!zallDataAPI.mSDKConfigInit) {
            zallDataAPI.applyZAConfigOptions();
        }
    }

    private static ZallDataAPI getInstance(Context context, DebugMode debugMode,
                                              ZAConfigOptions zaConfigOptions) {
        if (null == context) {
            return new ZallDataAPIEmptyImplementation();
        }

        synchronized (sInstanceMap) {
            final Context appContext = context.getApplicationContext();
            ZallDataAPI instance = sInstanceMap.get(appContext);
            if (null == instance) {
                instance = new ZallDataAPI(appContext, zaConfigOptions, debugMode);
                sInstanceMap.put(appContext, instance);
                if (context instanceof Activity) {
                    instance.delayExecution((Activity) context);
                }
            }
            return instance;
        }
    }

    private static ZallDataAPI getSDKInstance() {
        synchronized (sInstanceMap) {
            if (sInstanceMap.size() > 0) {
                Iterator<ZallDataAPI> iterator = sInstanceMap.values().iterator();
                if (iterator.hasNext()) {
                    return iterator.next();
                }
            }
            return new ZallDataAPIEmptyImplementation();
        }
    }

    public static ZallDataAPI sharedInstance() {
        if (isSDKDisabled()) {
            return new ZallDataAPIEmptyImplementation();
        }

        return getSDKInstance();
    }

    /**
     * 关闭 SDK
     */
    public static void disableSDK() {
        ZALog.i(TAG, "call static function disableSDK");
        try {
            final ZallDataAPI zallDataAPI = sharedInstance();
            if (zallDataAPI instanceof ZallDataAPIEmptyImplementation ||
                    getConfigOptions() == null ||
                    getConfigOptions().isDisableSDK) {
                return;
            }
            final boolean isFromObserver = !ZallDataContentObserver.isDisableFromObserver;
            zallDataAPI.transformTaskQueue(new Runnable() {
                @Override
                public void run() {
                    if (isFromObserver) {
                        zallDataAPI.trackInternal("$AppDataTrackingClose", null);
                    }
                }
            });
            //禁止网络
            if (zallDataAPI.isNetworkRequestEnable()) {
                zallDataAPI.enableNetworkRequest(false);
                isChangeEnableNetworkFlag = true;
            } else {
                isChangeEnableNetworkFlag = false;
            }
            //关闭网络监听
            zallDataAPI.unregisterNetworkListener();
            zallDataAPI.clearTrackTimer();
            DbAdapter.getInstance().commitAppStartTime(0);
            getConfigOptions().disableSDK(true);
            //关闭日志
            ZALog.setDisableSDK(true);
            if (!ZallDataContentObserver.isDisableFromObserver) {
                zallDataAPI.getContext().getContentResolver().notifyChange(DbParams.getInstance().getDisableSDKUri(), null);
            }
            ZallDataContentObserver.isDisableFromObserver = false;
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    /**
     * 开启 SDK
     */
    public static void enableSDK() {
        ZALog.i(TAG, "call static function enableSDK");
        try {
            ZallDataAPI zallDataAPI = getSDKInstance();
            if (zallDataAPI instanceof ZallDataAPIEmptyImplementation ||
                    getConfigOptions() == null ||
                    !getConfigOptions().isDisableSDK) {
                return;
            }
            getConfigOptions().disableSDK(false);
            try {
                //开启日志
                ZALog.setDisableSDK(false);
                zallDataAPI.enableLog(ZALog.isLogEnabled());
                ZALog.i(TAG, "enableSDK, enable log");
                if (zallDataAPI.mFirstDay.get() == null) {
                    zallDataAPI.mFirstDay.commit(TimeUtils.formatTime(System.currentTimeMillis(), TimeUtils.YYYY_MM_DD));
                }
                zallDataAPI.delayInitTask();
                //开启网络请求
                if (isChangeEnableNetworkFlag) {
                    zallDataAPI.enableNetworkRequest(true);
                    isChangeEnableNetworkFlag = false;
                }
                //重新请求可视化全埋点
                if (ZallDataAPI.getConfigOptions().isVisualizedPropertiesEnabled()) {
                    VisualPropertiesManager.getInstance().requestVisualConfig();
                }
                //重新请求采集控制
                zallDataAPI.getRemoteManager().pullSDKConfigFromServer();
            } catch (Exception e) {
                ZALog.printStackTrace(e);
            }

            if (!ZallDataContentObserver.isEnableFromObserver) {
                zallDataAPI.getContext().getContentResolver().notifyChange(DbParams.getInstance().getEnableSDKUri(), null);
            }
            ZallDataContentObserver.isEnableFromObserver = false;
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    /**
     * 返回预置属性
     *
     * @return JSONObject 预置属性
     */
    @Override
    public JSONObject getPresetProperties() {
        JSONObject properties = new JSONObject();
        try {
            properties = mZAContextManager.getPresetProperties();
            properties.put("$is_first_day", isFirstDay(System.currentTimeMillis()));
        } catch (Exception ex) {
            ZALog.printStackTrace(ex);
        }
        return properties;
    }

    @Override
    public void enableLog(boolean enable) {
        ZALog.setEnableLog(enable);
    }

    @Override
    public long getMaxCacheSize() {
        return mZAConfigOptions.mMaxCacheSize;
    }

    @Override
    public void setMaxCacheSize(long maxCacheSize) {
        mZAConfigOptions.setMaxCacheSize(maxCacheSize);
    }

    @Override
    public void setFlushNetworkPolicy(int networkType) {
        mZAConfigOptions.setNetworkTypePolicy(networkType);
    }

    int getFlushNetworkPolicy() {
        return mZAConfigOptions.mNetworkTypePolicy;
    }

    @Override
    public int getFlushInterval() {
        return mZAConfigOptions.mFlushInterval;
    }

    @Override
    public void setFlushInterval(int flushInterval) {
        mZAConfigOptions.setFlushInterval(flushInterval);
    }

    @Override
    public int getFlushBulkSize() {
        return mZAConfigOptions.mFlushBulkSize;
    }

    @Override
    public void setFlushBulkSize(int flushBulkSize) {
        if (flushBulkSize < 0) {
            ZALog.i(TAG, "The value of flushBulkSize is invalid");
        }
        mZAConfigOptions.setFlushBulkSize(flushBulkSize);
    }

    @Override
    public int getSessionIntervalTime() {
        return mSessionTime;
    }

    @Override
    public void setSessionIntervalTime(int sessionIntervalTime) {
        if (DbAdapter.getInstance() == null) {
            ZALog.i(TAG, "The static method sharedInstance(context, serverURL, debugMode) should be called before calling sharedInstance()");
            return;
        }

        if (sessionIntervalTime < 10 * 1000 || sessionIntervalTime > 5 * 60 * 1000) {
            ZALog.i(TAG, "SessionIntervalTime:" + sessionIntervalTime + " is invalid, session interval time is between 10s and 300s.");
            return;
        }
        if (sessionIntervalTime != mSessionTime) {
            mSessionTime = sessionIntervalTime;
            DbAdapter.getInstance().commitSessionIntervalTime(sessionIntervalTime);
        }
    }

    @Override
    public void setGPSLocation(final double latitude, final double longitude) {
        setGPSLocation(latitude, longitude, null);
    }

    @Override
    public void setGPSLocation(final double latitude, final double longitude, final String coordinate) {
        try {
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mGPSLocation == null) {
                            mGPSLocation = new ZallDataGPSLocation();
                        }
                        mGPSLocation.setLatitude((long) (latitude * Math.pow(10, 6)));
                        mGPSLocation.setLongitude((long) (longitude * Math.pow(10, 6)));
                        mGPSLocation.setCoordinate(assertPropertyLength(coordinate));
                    } catch (Exception e) {
                        ZALog.printStackTrace(e);
                    }
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void clearGPSLocation() {
        try {
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    mGPSLocation = null;
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void enableTrackScreenOrientation(boolean enable) {
        try {
            if (enable) {
                if (mOrientationDetector == null) {
                    mOrientationDetector = new ZallDataScreenOrientationDetector(mContext, SensorManager.SENSOR_DELAY_NORMAL);
                }
                mOrientationDetector.enable();
            } else {
                if (mOrientationDetector != null) {
                    mOrientationDetector.disable();
                    mOrientationDetector = null;
                }
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void resumeTrackScreenOrientation() {
        try {
            if (mOrientationDetector != null) {
                mOrientationDetector.enable();
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void stopTrackScreenOrientation() {
        try {
            if (mOrientationDetector != null) {
                mOrientationDetector.disable();
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public String getScreenOrientation() {
        try {
            if (mOrientationDetector != null) {
                return mOrientationDetector.getOrientation();
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
        return null;
    }

    @Override
    public void setCookie(String cookie, boolean encode) {
        try {
            if (encode) {
                this.mCookie = URLEncoder.encode(cookie, CHARSET_UTF8);
            } else {
                this.mCookie = cookie;
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public String getCookie(boolean decode) {
        try {
            if (decode) {
                return URLDecoder.decode(this.mCookie, CHARSET_UTF8);
            } else {
                return this.mCookie;
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
            return null;
        }

    }

    @Override
    public void enableAutoTrack(List<AutoTrackEventType> eventTypeList) {
        try {
            if (eventTypeList == null || eventTypeList.isEmpty()) {
                return;
            }
            this.mAutoTrack = true;
            for (AutoTrackEventType autoTrackEventType : eventTypeList) {
                mZAConfigOptions.setAutoTrackEventType(mZAConfigOptions.mAutoTrackEventType | autoTrackEventType.eventValue);
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void disableAutoTrack(List<AutoTrackEventType> eventTypeList) {
        try {
            if (eventTypeList == null) {
                return;
            }

            if (mZAConfigOptions.mAutoTrackEventType == 0) {
                return;
            }

            for (AutoTrackEventType autoTrackEventType : eventTypeList) {
                if ((mZAConfigOptions.mAutoTrackEventType | autoTrackEventType.eventValue) == mZAConfigOptions.mAutoTrackEventType) {
                    mZAConfigOptions.setAutoTrackEventType(mZAConfigOptions.mAutoTrackEventType ^ autoTrackEventType.eventValue);
                }
            }

            if (mZAConfigOptions.mAutoTrackEventType == 0) {
                this.mAutoTrack = false;
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void disableAutoTrack(AutoTrackEventType autoTrackEventType) {
        try {
            if (autoTrackEventType == null) {
                return;
            }

            if (mZAConfigOptions.mAutoTrackEventType == 0) {
                return;
            }

            int union = mZAConfigOptions.mAutoTrackEventType | autoTrackEventType.eventValue;
            if (union == autoTrackEventType.eventValue) {
                mZAConfigOptions.setAutoTrackEventType(0);
            } else {
                mZAConfigOptions.setAutoTrackEventType(autoTrackEventType.eventValue ^ union);
            }

            if (mZAConfigOptions.mAutoTrackEventType == 0) {
                this.mAutoTrack = false;
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public boolean isAutoTrackEnabled() {
        if (isSDKDisabled()) {
            return false;
        }

        if (mRemoteManager != null) {
            Boolean isAutoTrackEnabled = mRemoteManager.isAutoTrackEnabled();
            if (isAutoTrackEnabled != null) {
                return isAutoTrackEnabled;
            }
        }
        return mAutoTrack;
    }

    @Override
    public void trackFragmentAppViewScreen() {
        mFragmentAPI.trackFragmentAppViewScreen();
    }

    @Override
    public boolean isTrackFragmentAppViewScreenEnabled() {
        return mFragmentAPI.isTrackFragmentAppViewScreenEnabled();
    }

    @Override
    public void showUpWebView(WebView webView, boolean isSupportJellyBean) {
        showUpWebView(webView, isSupportJellyBean, null);
    }

    @Override
    public void showUpWebView(WebView webView, boolean isSupportJellyBean, boolean enableVerify) {
        showUpWebView(webView, null, isSupportJellyBean, enableVerify);
    }

    @Override
    @Deprecated
    public void showUpWebView(WebView webView, JSONObject properties, boolean isSupportJellyBean, boolean enableVerify) {
        if (Build.VERSION.SDK_INT < 17 && !isSupportJellyBean) {
            ZALog.d(TAG, "For applications targeted to API level JELLY_BEAN or below, this feature NOT SUPPORTED");
            return;
        }

        if (webView != null) {
            webView.getSettings().setJavaScriptEnabled(true);
            webView.addJavascriptInterface(new AppWebViewInterface(mContext, properties, enableVerify, webView), "ZallData_APP_JS_Bridge");
            ZallDataAutoTrackHelper.addWebViewVisualInterface(webView);
        }
    }

    @Override
    @Deprecated
    public void showUpWebView(WebView webView, boolean isSupportJellyBean, JSONObject properties) {
        showUpWebView(webView, properties, isSupportJellyBean, false);
    }

    @Override
    @Deprecated
    public void showUpX5WebView(Object x5WebView, JSONObject properties, boolean isSupportJellyBean, boolean enableVerify) {
        try {
            if (Build.VERSION.SDK_INT < 17 && !isSupportJellyBean) {
                ZALog.d(TAG, "For applications targeted to API level JELLY_BEAN or below, this feature NOT SUPPORTED");
                return;
            }

            if (x5WebView == null) {
                return;
            }

            Class<?> clazz = x5WebView.getClass();
            Method addJavascriptInterface = clazz.getMethod("addJavascriptInterface", Object.class, String.class);
            if (addJavascriptInterface == null) {
                return;
            }
            addJavascriptInterface.invoke(x5WebView, new AppWebViewInterface(mContext, properties, enableVerify), "ZallData_APP_JS_Bridge");
            ZallDataAutoTrackHelper.addWebViewVisualInterface((View) x5WebView);
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void showUpX5WebView(Object x5WebView, boolean enableVerify) {
        try {
            if (x5WebView == null) {
                return;
            }

            Class<?> clazz = x5WebView.getClass();
            Method addJavascriptInterface = clazz.getMethod("addJavascriptInterface", Object.class, String.class);
            if (addJavascriptInterface == null) {
                return;
            }
            addJavascriptInterface.invoke(x5WebView, new AppWebViewInterface(mContext, null, enableVerify, (View) x5WebView), "ZallData_APP_JS_Bridge");
            ZallDataAutoTrackHelper.addWebViewVisualInterface((View) x5WebView);
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void showUpX5WebView(Object x5WebView) {
        showUpX5WebView(x5WebView, false);
    }

    @Override
    public void ignoreAutoTrackActivities(List<Class<?>> activitiesList) {
        if (activitiesList == null || activitiesList.size() == 0) {
            return;
        }

        if (mAutoTrackIgnoredActivities == null) {
            mAutoTrackIgnoredActivities = new ArrayList<>();
        }

        int hashCode;
        for (Class<?> activity : activitiesList) {
            if (activity != null) {
                hashCode = activity.hashCode();
                if (!mAutoTrackIgnoredActivities.contains(hashCode)) {
                    mAutoTrackIgnoredActivities.add(hashCode);
                }
            }
        }
    }

    @Override
    public void resumeAutoTrackActivities(List<Class<?>> activitiesList) {
        if (activitiesList == null || activitiesList.size() == 0) {
            return;
        }

        if (mAutoTrackIgnoredActivities == null) {
            mAutoTrackIgnoredActivities = new ArrayList<>();
        }

        try {
            int hashCode;
            for (Class activity : activitiesList) {
                if (activity != null) {
                    hashCode = activity.hashCode();
                    if (mAutoTrackIgnoredActivities.contains(hashCode)) {
                        mAutoTrackIgnoredActivities.remove(Integer.valueOf(hashCode));
                    }
                }
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void ignoreAutoTrackActivity(Class<?> activity) {
        if (activity == null) {
            return;
        }

        if (mAutoTrackIgnoredActivities == null) {
            mAutoTrackIgnoredActivities = new ArrayList<>();
        }

        try {
            int hashCode = activity.hashCode();
            if (!mAutoTrackIgnoredActivities.contains(hashCode)) {
                mAutoTrackIgnoredActivities.add(hashCode);
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void resumeAutoTrackActivity(Class<?> activity) {
        if (activity == null) {
            return;
        }

        if (mAutoTrackIgnoredActivities == null) {
            mAutoTrackIgnoredActivities = new ArrayList<>();
        }

        try {
            int hashCode = activity.hashCode();
            if (mAutoTrackIgnoredActivities.contains(hashCode)) {
                mAutoTrackIgnoredActivities.remove(Integer.valueOf(hashCode));
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void enableAutoTrackFragment(Class<?> fragment) {
        mFragmentAPI.enableAutoTrackFragment(fragment);
    }

    @Override
    public void enableAutoTrackFragments(List<Class<?>> fragmentsList) {
        mFragmentAPI.enableAutoTrackFragments(fragmentsList);
    }

    @Override
    public boolean isActivityAutoTrackAppViewScreenIgnored(Class<?> activity) {
        if (activity == null) {
            return false;
        }
        if (mAutoTrackIgnoredActivities != null &&
                mAutoTrackIgnoredActivities.contains(activity.hashCode())) {
            return true;
        }

        if (activity.getAnnotation(ZallDataIgnoreTrackAppViewScreenAndAppClick.class) != null) {
            return true;
        }

        return activity.getAnnotation(ZallDataIgnoreTrackAppViewScreen.class) != null;
    }

    @Override
    public boolean isFragmentAutoTrackAppViewScreen(Class<?> fragment) {
        return mFragmentAPI.isFragmentAutoTrackAppViewScreen(fragment);
    }

    @Override
    public void ignoreAutoTrackFragments(List<Class<?>> fragmentList) {
        mFragmentAPI.ignoreAutoTrackFragments(fragmentList);
    }

    @Override
    public void ignoreAutoTrackFragment(Class<?> fragment) {
        mFragmentAPI.ignoreAutoTrackFragment(fragment);
    }

    @Override
    public void resumeIgnoredAutoTrackFragments(List<Class<?>> fragmentList) {
        mFragmentAPI.resumeIgnoredAutoTrackFragments(fragmentList);
    }

    @Override
    public void resumeIgnoredAutoTrackFragment(Class<?> fragment) {
        mFragmentAPI.resumeIgnoredAutoTrackFragment(fragment);
    }

    @Override
    public boolean isActivityAutoTrackAppClickIgnored(Class<?> activity) {
        if (activity == null) {
            return false;
        }
        if (mAutoTrackIgnoredActivities != null &&
                mAutoTrackIgnoredActivities.contains(activity.hashCode())) {
            return true;
        }

        if (activity.getAnnotation(ZallDataIgnoreTrackAppViewScreenAndAppClick.class) != null) {
            return true;
        }

        return activity.getAnnotation(ZallDataIgnoreTrackAppClick.class) != null;

    }

    @Override
    public boolean isAutoTrackEventTypeIgnored(AutoTrackEventType eventType) {
        if (eventType == null) {
            return false;
        }
        return isAutoTrackEventTypeIgnored(eventType.eventValue);
    }

    @Override
    public boolean isAutoTrackEventTypeIgnored(int autoTrackEventType) {
        if (mRemoteManager != null) {
            Boolean isIgnored = mRemoteManager.isAutoTrackEventTypeIgnored(autoTrackEventType);
            if (isIgnored != null) {
                if (isIgnored) {
                    ZALog.i(TAG, "remote config: " + AutoTrackEventType.autoTrackEventName(autoTrackEventType) + " is ignored by remote config");
                }
                return isIgnored;
            }
        }

        return (mZAConfigOptions.mAutoTrackEventType | autoTrackEventType) != mZAConfigOptions.mAutoTrackEventType;
    }

    @Override
    public void setViewID(View view, String viewID) {
        if (view != null && !TextUtils.isEmpty(viewID)) {
            view.setTag(R.id.zall_data_tag_view_id, viewID);
        }
    }

    @Override
    public void setViewID(android.app.Dialog view, String viewID) {
        try {
            if (view != null && !TextUtils.isEmpty(viewID)) {
                if (view.getWindow() != null) {
                    view.getWindow().getDecorView().setTag(R.id.zall_data_tag_view_id, viewID);
                }
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void setViewID(Object alertDialog, String viewID) {
        try {
            if (alertDialog == null) {
                return;

            }

            Class<?> supportAlertDialogClass = null;
            Class<?> androidXAlertDialogClass = null;
            Class<?> currentAlertDialogClass;
            try {
                supportAlertDialogClass = Class.forName("android.support.v7.app.AlertDialog");
            } catch (Exception e) {
                //ignored
            }

            try {
                androidXAlertDialogClass = Class.forName("androidx.appcompat.app.AlertDialog");
            } catch (Exception e) {
                //ignored
            }

            if (supportAlertDialogClass != null) {
                currentAlertDialogClass = supportAlertDialogClass;
            } else {
                currentAlertDialogClass = androidXAlertDialogClass;
            }

            if (currentAlertDialogClass == null) {
                return;
            }

            if (!currentAlertDialogClass.isInstance(alertDialog)) {
                return;
            }

            if (!TextUtils.isEmpty(viewID)) {
                Method getWindowMethod = alertDialog.getClass().getMethod("getWindow");
                if (getWindowMethod == null) {
                    return;
                }

                Window window = (Window) getWindowMethod.invoke(alertDialog);
                if (window != null) {
                    window.getDecorView().setTag(R.id.zall_data_tag_view_id, viewID);
                }
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void setViewActivity(View view, Activity activity) {
        try {
            if (view == null || activity == null) {
                return;
            }
            view.setTag(R.id.zall_data_tag_view_activity, activity);
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void setViewFragmentName(View view, String fragmentName) {
        try {
            if (view == null || TextUtils.isEmpty(fragmentName)) {
                return;
            }
            view.setTag(R.id.zall_data_tag_view_fragment_name2, fragmentName);
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void ignoreView(View view) {
        if (view != null) {
            view.setTag(R.id.zall_data_tag_view_ignored, "1");
        }
    }

    @Override
    public void ignoreView(View view, boolean ignore) {
        if (view != null) {
            view.setTag(R.id.zall_data_tag_view_ignored, ignore ? "1" : "0");
        }
    }

    @Override
    public void setViewProperties(View view, JSONObject properties) {
        if (view == null || properties == null) {
            return;
        }

        view.setTag(R.id.zall_data_tag_view_properties, properties);
    }

    @Override
    public List<Class> getIgnoredViewTypeList() {
        if (mIgnoredViewTypeList == null) {
            mIgnoredViewTypeList = new ArrayList<>();
        }

        return mIgnoredViewTypeList;
    }

    @Override
    public void ignoreViewType(Class viewType) {
        if (viewType == null) {
            return;
        }

        if (mIgnoredViewTypeList == null) {
            mIgnoredViewTypeList = new ArrayList<>();
        }

        if (!mIgnoredViewTypeList.contains(viewType)) {
            mIgnoredViewTypeList.add(viewType);
        }
    }

    @Override
    public boolean isVisualizedAutoTrackActivity(Class<?> activity) {
        try {
            if (activity == null) {
                return false;
            }
            if (mVisualizedAutoTrackActivities.size() == 0) {
                return true;
            }
            if (mVisualizedAutoTrackActivities.contains(activity.hashCode())) {
                return true;
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
        return false;
    }

    @Override
    public void addVisualizedAutoTrackActivity(Class<?> activity) {
        try {
            if (activity == null) {
                return;
            }
            mVisualizedAutoTrackActivities.add(activity.hashCode());
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void addVisualizedAutoTrackActivities(List<Class<?>> activitiesList) {
        try {
            if (activitiesList == null || activitiesList.size() == 0) {
                return;
            }

            for (Class<?> activity : activitiesList) {
                if (activity != null) {
                    int hashCode = activity.hashCode();
                    if (!mVisualizedAutoTrackActivities.contains(hashCode)) {
                        mVisualizedAutoTrackActivities.add(hashCode);
                    }
                }
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public boolean isVisualizedAutoTrackEnabled() {
        return mZAConfigOptions.mVisualizedEnabled || mZAConfigOptions.mVisualizedPropertiesEnabled;
    }

    @Override
    public boolean isHeatMapActivity(Class<?> activity) {
        try {
            if (activity == null) {
                return false;
            }
            if (mHeatMapActivities.size() == 0) {
                return true;
            }
            if (mHeatMapActivities.contains(activity.hashCode())) {
                return true;
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
        return false;
    }

    @Override
    public void addHeatMapActivity(Class<?> activity) {
        try {
            if (activity == null) {
                return;
            }

            mHeatMapActivities.add(activity.hashCode());
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public void addHeatMapActivities(List<Class<?>> activitiesList) {
        try {
            if (activitiesList == null || activitiesList.size() == 0) {
                return;
            }

            for (Class<?> activity : activitiesList) {
                if (activity != null) {
                    int hashCode = activity.hashCode();
                    if (!mHeatMapActivities.contains(hashCode)) {
                        mHeatMapActivities.add(hashCode);
                    }
                }
            }
        } catch (Exception e) {
            com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
        }
    }

    @Override
    public boolean isHeatMapEnabled() {
        return mZAConfigOptions.mHeatMapEnabled;
    }

    @Override
    public String getDistinctId() {
        try {
            String loginId = getLoginId();
            if (!TextUtils.isEmpty(loginId)) {
                return loginId;
            }
            return getAnonymousId();
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
        return "";
    }

    @Override
    public String getAnonymousId() {
        try {
            synchronized (mDistinctId) {
                if (!mZAConfigOptions.isDataCollectEnable) {
                    return "";
                }
                return mDistinctId.get();
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
        return "";
    }

    @Override
    public void resetAnonymousId() {
        try {
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    synchronized (mDistinctId) {
                        ZALog.i(TAG, "resetAnonymousId is called");
                        String androidId = mZAContextManager.getAndroidId();
                        if (TextUtils.equals(androidId, mDistinctId.get())) {
                            ZALog.i(TAG, "DistinctId not change");
                            return;
                        }
                        String newDistinctId;
                        if (ZallDataUtils.isValidAndroidId(androidId)) {
                            newDistinctId = androidId;
                        } else {
                            newDistinctId = UUID.randomUUID().toString();
                        }
                        mDistinctId.commit(newDistinctId);
                        // 通知调用 resetAnonymousId 接口
                        try {
                            if (mEventListenerList != null) {
                                for (ZAEventListener eventListener : mEventListenerList) {
                                    eventListener.resetAnonymousId();
                                }
                            }
                        } catch (Exception e) {
                            ZALog.printStackTrace(e);
                        }

                        try {
                            if (mFunctionListenerList != null) {
                                JSONObject jsonObject = new JSONObject();
                                jsonObject.put("distinctId", newDistinctId);
                                for (ZAFunctionListener listener : mFunctionListenerList) {
                                    listener.call("resetAnonymousId", jsonObject);
                                }
                            }
                        } catch (Exception e) {
                            ZALog.printStackTrace(e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public String getLoginId() {
        if (AppInfoUtils.isTaskExecuteThread()) {
            return DbAdapter.getInstance().getLoginId();
        }
        return mLoginId;
    }

    @Override
    public void identify(final String distinctId) {
        try {
            assertValue(distinctId);
        } catch (Exception e) {
            ZALog.printStackTrace(e);
            return;
        }

        try {
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (mDistinctId) {
                            try {
                                ZALog.i(TAG, "identify is called");
                                if (!distinctId.equals(mDistinctId.get())) {
                                    mDistinctId.commit(distinctId);
                                    if (mEventListenerList != null) {
                                        for (ZAEventListener eventListener : mEventListenerList) {
                                            eventListener.identify();
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                ZALog.printStackTrace(e);
                            }

                            try {
                                if (mFunctionListenerList != null) {
                                    JSONObject jsonObject = new JSONObject();
                                    jsonObject.put("distinctId", distinctId);
                                    for (ZAFunctionListener listener : mFunctionListenerList) {
                                        listener.call("identify", jsonObject);
                                    }
                                }
                            } catch (Exception e) {
                                ZALog.printStackTrace(e);
                            }
                        }
                    } catch (Exception e) {
                        ZALog.printStackTrace(e);
                    }
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void login(final String loginId) {
        login(loginId, null);
    }

    @Override
    public void login(final String loginId, final JSONObject properties) {
        try {
            assertValue(loginId);
            synchronized (mLoginIdLock) {
                if (!loginId.equals(getAnonymousId())) {
                    mLoginId = loginId;
                    if (ZallDataContentObserver.isLoginFromObserver) {//区分是否由 Observer 发送过来
                        ZallDataContentObserver.isLoginFromObserver = false;
                        return;
                    }

                    mTrackTaskManager.addTrackEventTask(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (!loginId.equals(DbAdapter.getInstance().getLoginId())) {
                                    DbAdapter.getInstance().commitLoginId(loginId);
                                    trackEvent(EventType.TRACK_SIGNUP, "$SignUp", properties, getAnonymousId());
                                    // 通知调用 login 接口
                                    try {
                                        if (mEventListenerList != null) {
                                            for (ZAEventListener eventListener : mEventListenerList) {
                                                eventListener.login();
                                            }
                                        }
                                    } catch (Exception e) {
                                        ZALog.printStackTrace(e);
                                    }
                                    try {
                                        if (mFunctionListenerList != null) {
                                            JSONObject jsonObject = new JSONObject();
                                            jsonObject.put("distinctId", loginId);
                                            for (ZAFunctionListener listener : mFunctionListenerList) {
                                                listener.call("login", jsonObject);
                                            }
                                        }
                                    } catch (Exception e) {
                                        ZALog.printStackTrace(e);
                                    }
                                }
                            } catch (Exception e) {
                                ZALog.printStackTrace(e);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void logout() {
        try {
            mLoginId = null;
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        synchronized (mLoginIdLock) {
                            ZALog.i(TAG, "logout is called");
                            if (!TextUtils.isEmpty(DbAdapter.getInstance().getLoginId())) {
                                DbAdapter.getInstance().commitLoginId(null);
                                // 进行通知调用 logout 接口
                                try {
                                    if (mEventListenerList != null) {
                                        for (ZAEventListener eventListener : mEventListenerList) {
                                            eventListener.logout();
                                        }
                                    }
                                } catch (Exception e) {
                                    ZALog.printStackTrace(e);
                                }

                                try {
                                    if (mFunctionListenerList != null) {
                                        for (ZAFunctionListener listener : mFunctionListenerList) {
                                            listener.call("logout", null);
                                        }
                                    }
                                } catch (Exception e) {
                                    ZALog.printStackTrace(e);
                                }
                                ZALog.i(TAG, "Clean loginId");
                            }
                        }
                    } catch (Exception e) {
                        ZALog.printStackTrace(e);
                    }
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void trackInstallation(final String eventName, final JSONObject properties, final boolean disableCallback) {
        try {
            //只在主进程触发 trackInstallation
            final JSONObject eventProperties = new JSONObject();
            if (properties != null) {
                ZallDataUtils.mergeJSONObject(properties, eventProperties);
            }
            addTimeProperty(eventProperties);
            final String loginId = getLoginId();
            final String distinctId = getDistinctId();
            transformTaskQueue(new Runnable() {
                @Override
                public void run() {
                    if (!mIsMainProcess) {
                        return;
                    }
                    try {
                        boolean firstTrackInstallation;
                        if (disableCallback) {
                            firstTrackInstallation = mFirstTrackInstallationWithCallback.get();
                        } else {
                            firstTrackInstallation = mFirstTrackInstallation.get();
                        }
                        if (firstTrackInstallation) {
                            boolean isCorrectTrackInstallation = false;
                            try {
                                if (!ChannelUtils.hasUtmProperties(eventProperties)) {
                                    ChannelUtils.mergeUtmByMetaData(mContext, eventProperties);
                                }

                                if (!ChannelUtils.hasUtmProperties(eventProperties)) {
                                    String androidId = mZAContextManager.getAndroidId();
                                    String installSource;
                                    String oaid;
                                    if (eventProperties.has("$oaid")) {
                                        oaid = eventProperties.optString("$oaid");
                                        installSource = ChannelUtils.getDeviceInfo(mContext, androidId, oaid);
                                        ZALog.i(TAG, "properties has oaid " + oaid);
                                    } else {
                                        oaid = OaidHelper.getOAID(mContext);
                                        installSource = ChannelUtils.getDeviceInfo(mContext, androidId, oaid);
                                    }

                                    if (eventProperties.has("$gaid")) {
                                        installSource = String.format("%s##gaid=%s", installSource, eventProperties.optString("$gaid"));
                                    }
                                    isCorrectTrackInstallation = ChannelUtils.isGetDeviceInfo(mContext, androidId, oaid);
                                    eventProperties.put("$ios_install_source", installSource);
                                }
                                if (eventProperties.has("$oaid")) {
                                    eventProperties.remove("$oaid");
                                }

                                if (eventProperties.has("$gaid")) {
                                    eventProperties.remove("$gaid");
                                }

                                if (disableCallback) {
                                    eventProperties.put("$ios_install_disable_callback", disableCallback);
                                }
                            } catch (Exception e) {
                                ZALog.printStackTrace(e);
                            }
                            // 先发送 track
                            trackEvent(EventType.TRACK, eventName, eventProperties, null, distinctId, loginId, null);
                            // 再发送 profile_set_once 或者 profile_set
                            JSONObject profileProperties = new JSONObject();
                            // 用户属性需要去掉 $ios_install_disable_callback 字段
                            eventProperties.remove("$ios_install_disable_callback");
                            ZallDataUtils.mergeJSONObject(eventProperties, profileProperties);
                            profileProperties.put("$first_visit_time", new java.util.Date());
                            trackEvent(EventType.PROFILE_SET_ONCE, null, profileProperties, null, distinctId, loginId, null);

                            if (disableCallback) {
                                mFirstTrackInstallationWithCallback.commit(false);
                            } else {
                                mFirstTrackInstallation.commit(false);
                            }
                            ChannelUtils.saveCorrectTrackInstallation(mContext, isCorrectTrackInstallation);
                        }
                        flush();
                    } catch (Exception e) {
                        ZALog.printStackTrace(e);
                    }
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void trackInstallation(String eventName, JSONObject properties) {
        trackInstallation(eventName, properties, false);
    }

    @Override
    public void trackInstallation(String eventName) {
        trackInstallation(eventName, null, false);
    }

    @Override
    public void trackAppInstall(JSONObject properties, final boolean disableCallback) {
        trackInstallation("$AppInstall", properties, disableCallback);
    }

    @Override
    public void trackAppInstall(JSONObject properties) {
        trackAppInstall(properties, false);
    }

    @Override
    public void trackAppInstall() {
        trackAppInstall(null, false);
    }

    @Override
    void trackChannelDebugInstallation() {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {

                try {
                    JSONObject _properties = new JSONObject();
                    _properties.put("$ios_install_source", ChannelUtils.getDeviceInfo(mContext,
                            mZAContextManager.getAndroidId(), OaidHelper.getOAID(mContext)));
                    // 先发送 track
                    trackEvent(EventType.TRACK, "$ChannelDebugInstall", _properties, null);

                    // 再发送 profile_set_once 或者 profile_set
                    JSONObject profileProperties = new JSONObject();
                    ZallDataUtils.mergeJSONObject(_properties, profileProperties);
                    profileProperties.put("$first_visit_time", new java.util.Date());
                    trackEvent(EventType.PROFILE_SET_ONCE, null, profileProperties, null);
                    flush();
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void trackChannelEvent(String eventName) {
        trackChannelEvent(eventName, null);
    }

    @Override
    public void trackChannelEvent(final String eventName, JSONObject properties) {
        if (getConfigOptions().isAutoAddChannelCallbackEvent) {
            track(eventName, properties);
            return;
        }
        final JSONObject eventProperties = new JSONObject();
        if (properties != null) {
            ZallDataUtils.mergeJSONObject(properties, eventProperties);
        }
        addTimeProperty(eventProperties);
        transformTaskQueue(new Runnable() {
            @Override
            public void run() {
                try {
                    try {
                        eventProperties.put("$is_channel_callback_event", ChannelUtils.isFirstChannelEvent(eventName));
                        if (!ChannelUtils.hasUtmProperties(eventProperties)) {
                            ChannelUtils.mergeUtmByMetaData(mContext, eventProperties);
                        }
                        if (!ChannelUtils.hasUtmProperties(eventProperties)) {
                            if (eventProperties.has("$oaid")) {
                                String oaid = eventProperties.optString("$oaid");
                                eventProperties.put("$channel_device_info",
                                        ChannelUtils.getDeviceInfo(mContext, mZAContextManager.getAndroidId(), oaid));
                                ZALog.i(TAG, "properties has oaid " + oaid);
                            } else {
                                eventProperties.put("$channel_device_info",
                                        ChannelUtils.getDeviceInfo(mContext, mZAContextManager.getAndroidId(), OaidHelper.getOAID(mContext)));
                            }
                        }
                        if (eventProperties.has("$oaid")) {
                            eventProperties.remove("$oaid");
                        }
                    } catch (Exception e) {
                        ZALog.printStackTrace(e);
                    }

                    // 先发送 track
                    trackEvent(EventType.TRACK, eventName, eventProperties, null);
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void track(final String eventName, final JSONObject properties) {
        try {
            final JSONObject dynamicProperty = getDynamicProperty();
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    JSONObject _properties = ChannelUtils.checkOrSetChannelCallbackEvent(getConfigOptions().isAutoAddChannelCallbackEvent, eventName, properties, mContext);
                    trackEvent(EventType.TRACK, eventName, _properties, dynamicProperty, getDistinctId(), getLoginId(), null);
                }
            });
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void track(final String eventName) {
        track(eventName, null);
    }

    @Deprecated
    @Override
    public void trackTimer(final String eventName, final TimeUnit timeUnit) {
        final long startTime = SystemClock.elapsedRealtime();
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    assertKey(eventName);
                    synchronized (mTrackTimer) {
                        mTrackTimer.put(eventName, new EventTimer(timeUnit, startTime));
                    }
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void removeTimer(final String eventName) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    assertKey(eventName);
                    synchronized (mTrackTimer) {
                        mTrackTimer.remove(eventName);
                    }
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public String trackTimerStart(String eventName) {
        try {
            final String eventNameRegex = String.format("%s_%s_%s", eventName, UUID.randomUUID().toString().replace("-", "_"), "ZATimer");
            trackTimer(eventNameRegex, TimeUnit.SECONDS);
            trackTimer(eventName, TimeUnit.SECONDS);
            return eventNameRegex;
        } catch (Exception ex) {
            ZALog.printStackTrace(ex);
        }
        return "";
    }

    @Override
    public void trackTimerPause(String eventName) {
        trackTimerState(eventName, true);
    }

    @Override
    public void trackTimerResume(String eventName) {
        trackTimerState(eventName, false);
    }

    @Override
    public void trackTimerEnd(final String eventName, final JSONObject properties) {
        final long endTime = SystemClock.elapsedRealtime();
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                if (eventName != null) {
                    synchronized (mTrackTimer) {
                        EventTimer eventTimer = mTrackTimer.get(eventName);
                        if (eventTimer != null) {
                            eventTimer.setEndTime(endTime);
                        }
                    }
                }
                try {
                    JSONObject _properties = ChannelUtils.checkOrSetChannelCallbackEvent(getConfigOptions().isAutoAddChannelCallbackEvent, eventName, properties, mContext);
                    trackEvent(EventType.TRACK, eventName, _properties, null);
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void trackTimerEnd(final String eventName) {
        trackTimerEnd(eventName, null);
    }

    @Override
    public void clearTrackTimer() {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (mTrackTimer) {
                        mTrackTimer.clear();
                    }
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public String getLastScreenUrl() {
        return mLastScreenUrl;
    }

    @Override
    public void clearReferrerWhenAppEnd() {
        mClearReferrerWhenAppEnd = true;
    }

    @Override
    public void clearLastScreenUrl() {
        if (mClearReferrerWhenAppEnd) {
            mLastScreenUrl = null;
        }
    }

    @Override
    public JSONObject getLastScreenTrackProperties() {
        return mLastScreenTrackProperties;
    }

    @Override
    @Deprecated
    public void trackViewScreen(final String url, final JSONObject properties) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!TextUtils.isEmpty(url) || properties != null) {
                        String currentUrl = url;
                        JSONObject trackProperties = new JSONObject();
                        mLastScreenTrackProperties = properties;

                        if (mLastScreenUrl != null) {
                            trackProperties.put("$referrer", mLastScreenUrl);
                        }

                        mReferrerScreenTitle = mCurrentScreenTitle;
                        if (properties != null) {
                            if (properties.has("$title")) {
                                mCurrentScreenTitle = properties.getString("$title");
                            } else {
                                mCurrentScreenTitle = null;
                            }
                            if (properties.has("$url")) {
                                currentUrl = properties.optString("$url");
                            }
                        }
                        trackProperties.put("$url", currentUrl);
                        mLastScreenUrl = currentUrl;
                        if (properties != null) {
                            ZallDataUtils.mergeJSONObject(properties, trackProperties);
                        }
                        trackEvent(EventType.TRACK, "$AppViewScreen", trackProperties, null);
                    }
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void trackViewScreen(final Activity activity) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    if (activity == null) {
                        return;
                    }
                    JSONObject properties = AopUtil.buildTitleAndScreenName(activity);
                    trackViewScreen(ZallDataUtils.getScreenUrl(activity), properties);
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void trackViewScreen(final Object fragment) {
        if (fragment == null) {
            return;
        }

        Class<?> supportFragmentClass = null;
        Class<?> appFragmentClass = null;
        Class<?> androidXFragmentClass = null;

        try {
            try {
                supportFragmentClass = Class.forName("android.support.v4.app.Fragment");
            } catch (Exception e) {
                //ignored
            }

            try {
                appFragmentClass = Class.forName("android.app.Fragment");
            } catch (Exception e) {
                //ignored
            }

            try {
                androidXFragmentClass = Class.forName("androidx.fragment.app.Fragment");
            } catch (Exception e) {
                //ignored
            }
        } catch (Exception e) {
            //ignored
        }

        if (!(supportFragmentClass != null && supportFragmentClass.isInstance(fragment)) &&
                !(appFragmentClass != null && appFragmentClass.isInstance(fragment)) &&
                !(androidXFragmentClass != null && androidXFragmentClass.isInstance(fragment))) {
            return;
        }

        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject properties = new JSONObject();
                    String screenName = fragment.getClass().getCanonicalName();

                    String title = null;

                    if (fragment.getClass().isAnnotationPresent(ZallDataFragmentTitle.class)) {
                        ZallDataFragmentTitle zallDataFragmentTitle = fragment.getClass().getAnnotation(ZallDataFragmentTitle.class);
                        if (zallDataFragmentTitle != null) {
                            title = zallDataFragmentTitle.title();
                        }
                    }

                    if (Build.VERSION.SDK_INT >= 11) {
                        Activity activity = null;
                        try {
                            Method getActivityMethod = fragment.getClass().getMethod("getActivity");
                            if (getActivityMethod != null) {
                                activity = (Activity) getActivityMethod.invoke(fragment);
                            }
                        } catch (Exception e) {
                            //ignored
                        }
                        if (activity != null) {
                            if (TextUtils.isEmpty(title)) {
                                title = ZallDataUtils.getActivityTitle(activity);
                            }
                            screenName = String.format(Locale.CHINA, "%s|%s", activity.getClass().getCanonicalName(), screenName);
                        }
                    }

                    if (!TextUtils.isEmpty(title)) {
                        properties.put(AopConstants.TITLE, title);
                    }
                    properties.put("$screen_name", screenName);
                    if (fragment instanceof ScreenAutoTracker) {
                        ScreenAutoTracker screenAutoTracker = (ScreenAutoTracker) fragment;
                        JSONObject otherProperties = screenAutoTracker.getTrackProperties();
                        if (otherProperties != null) {
                            ZallDataUtils.mergeJSONObject(otherProperties, properties);
                        }
                    }
                    trackViewScreen(ZallDataUtils.getScreenUrl(fragment), properties);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void trackViewAppClick(View view) {
        trackViewAppClick(view, null);
    }

    @Override
    public void trackViewAppClick(final View view, JSONObject properties) {
        if (view == null) {
            return;
        }
        if (properties == null) {
            properties = new JSONObject();
        }
        if (AopUtil.injectClickInfo(view, properties, true)) {
            Activity activity = AopUtil.getActivityFromContext(view.getContext(), view);
            trackInternal(AopConstants.APP_CLICK_EVENT_NAME, properties, AopUtil.addViewPathProperties(activity, view, properties));
        }
    }

    @Override
    public void flush() {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    mMessages.flush();
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    /**
     * 将所有本地缓存的日志发送到 Zall Data.
     */
    @Override
    public void flushSync() {
        flush();
    }

    @Override
    public void flushScheduled() {
        try {
            mMessages.flushScheduled();
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void registerDynamicSuperProperties(ZallDataDynamicSuperProperties dynamicSuperProperties) {
        mDynamicSuperPropertiesCallBack = dynamicSuperProperties;
    }

    @Override
    public void setTrackEventCallBack(ZallDataTrackEventCallBack trackEventCallBack) {
        mTrackEventCallBack = trackEventCallBack;
    }

    @Override
    public void setDeepLinkCallback(ZallDataDeepLinkCallback deepLinkCallback) {
        mDeepLinkCallback = deepLinkCallback;
    }

    @Override
    public void stopTrackThread() {
        if (mTrackTaskManagerThread != null && !mTrackTaskManagerThread.isStopped()) {
            mTrackTaskManagerThread.stop();
            ZALog.i(TAG, "Data collection thread has been stopped");
        }
    }

    @Override
    public void startTrackThread() {
        if (mTrackTaskManagerThread == null || mTrackTaskManagerThread.isStopped()) {
            mTrackTaskManagerThread = new TrackTaskManagerThread();
            new Thread(mTrackTaskManagerThread).start();
            ZALog.i(TAG, "Data collection thread has been started");
        }
    }

    @Override
    @Deprecated
    public void enableDataCollect() {
        try {
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    if (!mZAConfigOptions.isDataCollectEnable) {
                        mContext.getContentResolver().notifyChange(DbParams.getInstance().getDataCollectUri(), null);
                    }
                    mZAConfigOptions.isDataCollectEnable = true;
                    // 同意合规时重新判断当前进程是否主进程
                    mIsMainProcess = AppInfoUtils.isMainProcess(mContext, null);
                    mZAContextManager.getDeviceInfo();
                    mTrackTaskManager.setDataCollectEnable(true);
                    // 同意合规时更新首日首次
                    if (mFirstDay.get() == null) {
                        mFirstDay.commit(TimeUtils.formatTime(System.currentTimeMillis(), TimeUtils.YYYY_MM_DD));
                    }
                    try {
                        if (mFunctionListenerList != null) {
                            for (ZAFunctionListener functionListener : mFunctionListenerList) {
                                functionListener.call("enableDataCollect", null);
                            }
                        }
                    } catch (Exception e) {
                        ZALog.printStackTrace(e);
                    }
                }
            });
            flush();
        } catch (Exception ex) {
            ZALog.printStackTrace(ex);
        }
    }

    @Override
    public void deleteAll() {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                mMessages.deleteAll();
            }
        });
    }

    @Override
    public JSONObject getSuperProperties() {
        synchronized (mSuperProperties) {
            try {
                return new JSONObject(mSuperProperties.get().toString());
            } catch (JSONException e) {
                ZALog.printStackTrace(e);
                return new JSONObject();
            }
        }
    }

    @Override
    public void registerSuperProperties(final JSONObject superProperties) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    if (superProperties == null) {
                        return;
                    }
                    assertPropertyTypes(superProperties);
                    synchronized (mSuperProperties) {
                        JSONObject properties = mSuperProperties.get();
                        mSuperProperties.commit(ZallDataUtils.mergeSuperJSONObject(superProperties, properties));
                    }
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void unregisterSuperProperty(final String superPropertyName) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (mSuperProperties) {
                        JSONObject superProperties = mSuperProperties.get();
                        superProperties.remove(superPropertyName);
                        mSuperProperties.commit(superProperties);
                    }
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void clearSuperProperties() {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                synchronized (mSuperProperties) {
                    mSuperProperties.commit(new JSONObject());
                }
            }
        });
    }

    @Override
    public void profileSet(final JSONObject properties) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_SET, null, properties, null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileSet(final String property, final Object value) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_SET, null, new JSONObject().put(property, value), null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileSetOnce(final JSONObject properties) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_SET_ONCE, null, properties, null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileSetOnce(final String property, final Object value) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_SET_ONCE, null, new JSONObject().put(property, value), null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileIncrement(final Map<String, ? extends Number> properties) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_INCREMENT, null, new JSONObject(properties), null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileIncrement(final String property, final Number value) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_INCREMENT, null, new JSONObject().put(property, value), null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileAppend(final String property, final String value) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONArray append_values = new JSONArray();
                    append_values.put(value);
                    final JSONObject properties = new JSONObject();
                    properties.put(property, append_values);
                    trackEvent(EventType.PROFILE_APPEND, null, properties, null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileAppend(final String property, final Set<String> values) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONArray append_values = new JSONArray();
                    for (String value : values) {
                        append_values.put(value);
                    }
                    final JSONObject properties = new JSONObject();
                    properties.put(property, append_values);
                    trackEvent(EventType.PROFILE_APPEND, null, properties, null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileUnset(final String property) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_UNSET, null, new JSONObject().put(property, true), null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileDelete() {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                try {
                    trackEvent(EventType.PROFILE_DELETE, null, null, null);
                } catch (Exception e) {
                    com.zalldata.zall.android.sdk.ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public boolean isDebugMode() {
        return mDebugMode.isDebugMode();
    }

    @Override
    public boolean isNetworkRequestEnable() {
        return mEnableNetworkRequest;
    }

    @Override
    public void enableNetworkRequest(boolean isRequest) {
        this.mEnableNetworkRequest = isRequest;
    }

    @Override
    public void setServerUrl(String serverUrl) {
        setServerUrl(serverUrl, false);
    }

    @Override
    public void setServerUrl(final String serverUrl, boolean isRequestRemoteConfig) {
        try {
            //请求远程配置
            if (isRequestRemoteConfig && mRemoteManager != null) {
                try {
                    mRemoteManager.requestRemoteConfig(BaseZallDataSDKRemoteManager.RandomTimeType.RandomTimeTypeWrite, false);
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
            //请求可视化全埋点自定义属性配置
            if (!TextUtils.equals(serverUrl, mOriginServerUrl) && ZallDataAPI.getConfigOptions().isVisualizedPropertiesEnabled()) {
                try {
                    VisualPropertiesManager.getInstance().requestVisualConfig();
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }

            mOriginServerUrl = serverUrl;
            if (TextUtils.isEmpty(serverUrl)) {
                mServerUrl = serverUrl;
                ZALog.i(TAG, "Server url is null or empty.");
                return;
            }

            final Uri serverURI = Uri.parse(serverUrl);
            mTrackTaskManager.addTrackEventTask(new Runnable() {
                @Override
                public void run() {
                    String hostServer = serverURI.getHost();
                    if (!TextUtils.isEmpty(hostServer) && hostServer.contains("_")) {
                        ZALog.i(TAG, "Server url " + serverUrl + " contains '_' is not recommend，" +
                                "see details: https://en.wikipedia.org/wiki/Hostname");
                    }
                }
            });

            if (mDebugMode != DebugMode.DEBUG_OFF) {
                String uriPath = serverURI.getPath();
                if (TextUtils.isEmpty(uriPath)) {
                    return;
                }

                int pathPrefix = uriPath.lastIndexOf('/');
                if (pathPrefix != -1) {
                    String newPath = uriPath.substring(0, pathPrefix) + "/debug";
                    // 将 URI Path 中末尾的部分替换成 '/debug'
                    mServerUrl = serverURI.buildUpon().path(newPath).build().toString();
                }
            } else {
                mServerUrl = serverUrl;
            }
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void trackEventFromH5(String eventInfo, boolean enableVerify) {
        try {
            if (TextUtils.isEmpty(eventInfo)) {
                return;
            }

            JSONObject eventObject = new JSONObject(eventInfo);
            if (enableVerify) {
                String serverUrl = eventObject.optString("server_url");
                if (!TextUtils.isEmpty(serverUrl)) {
                    if (!(new ServerUrl(serverUrl).check(new ServerUrl(mServerUrl)))) {
                        return;
                    }
                } else {
                    //防止 H5 集成的 JS SDK 版本太老，没有发 server_url
                    return;
                }
            }
            trackEventFromH5(eventInfo);
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
    }

    @Override
    public void trackEventFromH5(final String eventInfo) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                trackEventH5(eventInfo);
            }
        });
    }

    @Override
    public void profilePushId(final String pushTypeKey, final String pushId) {
        transformTaskQueue(new Runnable() {
            @Override
            public void run() {
                try {
                    assertKey(pushTypeKey);
                    if (TextUtils.isEmpty(pushId)) {
                        ZALog.d(TAG, "pushId is empty");
                        return;
                    }
                    String distinctId = getDistinctId();
                    String distinctPushId = distinctId + pushId;
                    SharedPreferences sp = ZallDataUtils.getSharedPreferences(mContext);
                    String spDistinctPushId = sp.getString("distinctId_" + pushTypeKey, "");
                    if (!spDistinctPushId.equals(distinctPushId)) {
                        profileSet(pushTypeKey, pushId);
                        sp.edit().putString("distinctId_" + pushTypeKey, distinctPushId).apply();
                    }
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void profileUnsetPushId(final String pushTypeKey) {
        transformTaskQueue(new Runnable() {
            @Override
            public void run() {
                try {
                    assertKey(pushTypeKey);
                    String distinctId = getDistinctId();
                    SharedPreferences sp = ZallDataUtils.getSharedPreferences(mContext);
                    String key = "distinctId_" + pushTypeKey;
                    String spDistinctPushId = sp.getString(key, "");

                    if (spDistinctPushId.startsWith(distinctId)) {
                        profileUnset(pushTypeKey);
                        sp.edit().remove(key).apply();
                    }
                } catch (Exception e) {
                    ZALog.printStackTrace(e);
                }
            }
        });
    }

    @Override
    public void itemSet(final String itemType, final String itemId, final JSONObject properties) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                trackItemEvent(itemType, itemId, EventType.ITEM_SET.getEventType(), System.currentTimeMillis(), properties);
            }
        });
    }

    @Override
    public void itemDelete(final String itemType, final String itemId) {
        mTrackTaskManager.addTrackEventTask(new Runnable() {
            @Override
            public void run() {
                trackItemEvent(itemType, itemId, EventType.ITEM_DELETE.getEventType(), System.currentTimeMillis(), null);
            }
        });
    }

    @Override
    public void enableDeepLinkInstallSource(boolean enable) {
        mEnableDeepLinkInstallSource = enable;
    }

    /**
     * 不能动位置，因为 SF 反射获取使用
     *
     * @return ServerUrl
     */
    @Override
    public String getServerUrl() {
        return mServerUrl;
    }

    @Override
    public void trackDeepLinkLaunch(String deepLinkUrl) {
        trackDeepLinkLaunch(deepLinkUrl, null);
    }

    @Override
    public void trackDeepLinkLaunch(final String deepLinkUrl, final String oaid) {
        final JSONObject properties = new JSONObject();
        final boolean isDeepLinkInstallSource = isDeepLinkInstallSource();
        try {
            properties.put("$deeplink_url", deepLinkUrl);
            properties.put("$time", new Date(System.currentTimeMillis()));
        } catch (Exception e) {
            ZALog.printStackTrace(e);
        }
        ZallDataAPI.sharedInstance().transformTaskQueue(new Runnable() {
            @Override
            public void run() {
                if (isDeepLinkInstallSource) {
                    try {
                        properties.put("$ios_install_source", ChannelUtils.getDeviceInfo(mContext,
                                mZAContextManager.getAndroidId(), oaid == null ? OaidHelper.getOAID(mContext) : oaid));
                    } catch (JSONException e) {
                        ZALog.printStackTrace(e);
                    }
                }
                trackInternal("$AppDeeplinkLaunch", properties);
            }
        });
    }

    /**
     * 获取 SDK 的版本号
     *
     * @return SDK 的版本号
     */
    public String getSDKVersion() {
        return VERSION;
    }

    /**
     * Debug 模式，用于检验数据导入是否正确。该模式下，事件会逐条实时发送到 Zall Data，并根据返回值检查
     * 数据导入是否正确。
     * Debug 模式的具体使用方式，请参考:
     * http://www.zalldata.cn/manual/debug_mode.html
     * Debug 模式有三种：
     * DEBUG_OFF - 关闭DEBUG模式
     * DEBUG_ONLY - 打开DEBUG模式，但该模式下发送的数据仅用于调试，不进行数据导入
     * DEBUG_AND_TRACK - 打开DEBUG模式，并将数据导入到ZallData中
     */
    public enum DebugMode {
        DEBUG_OFF(false, false),
        DEBUG_ONLY(true, false),
        DEBUG_AND_TRACK(true, true);

        private final boolean debugMode;
        private final boolean debugWriteData;

        DebugMode(boolean debugMode, boolean debugWriteData) {
            this.debugMode = debugMode;
            this.debugWriteData = debugWriteData;
        }

        boolean isDebugMode() {
            return debugMode;
        }

        boolean isDebugWriteData() {
            return debugWriteData;
        }
    }

    /**
     * AutoTrack 默认采集的事件类型
     */
    public enum AutoTrackEventType {
        APP_START(1),
        APP_END(1 << 1),
        APP_CLICK(1 << 2),
        APP_VIEW_SCREEN(1 << 3);
        private final int eventValue;

        AutoTrackEventType(int eventValue) {
            this.eventValue = eventValue;
        }

        static AutoTrackEventType autoTrackEventTypeFromEventName(String eventName) {
            if (TextUtils.isEmpty(eventName)) {
                return null;
            }

            switch (eventName) {
                case "$AppStart":
                    return APP_START;
                case "$AppEnd":
                    return APP_END;
                case "$AppClick":
                    return APP_CLICK;
                case "$AppViewScreen":
                    return APP_VIEW_SCREEN;
                default:
                    break;
            }

            return null;
        }

        static String autoTrackEventName(int eventType) {
            switch (eventType) {
                case 1:
                    return "$AppStart";
                case 2:
                    return "$AppEnd";
                case 4:
                    return "$AppClick";
                case 8:
                    return "$AppViewScreen";
                default:
                    return "";
            }
        }

        static boolean isAutoTrackType(String eventName) {
            if (!TextUtils.isEmpty(eventName)) {
                switch (eventName) {
                    case "$AppStart":
                    case "$AppEnd":
                    case "$AppClick":
                    case "$AppViewScreen":
                        return true;
                    default:
                        break;
                }
            }
            return false;
        }

        int getEventValue() {
            return eventValue;
        }
    }

    /**
     * 网络类型
     */
    public final class NetworkType {
        public static final int TYPE_NONE = 0;//NULL
        public static final int TYPE_2G = 1;//2G
        public static final int TYPE_3G = 1 << 1;//3G
        public static final int TYPE_4G = 1 << 2;//4G
        public static final int TYPE_WIFI = 1 << 3;//WIFI
        public static final int TYPE_5G = 1 << 4;//5G
        public static final int TYPE_ALL = 0xFF;//ALL
    }
}