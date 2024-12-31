package com.sword.logger;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class AppLifecycleObserver implements Application.ActivityLifecycleCallbacks {
    private final AppLifecycleListener listener;
    private int activityCount = 0;
    
    public AppLifecycleObserver(AppLifecycleListener listener) {
        this.listener = listener;
    }
            
    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        activityCount ++;
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {}

    @Override
    public void onActivityResumed(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        activityCount--;
        if (activityCount == 0 && listener != null) {
            listener.onAppDestroy();
        }
    }

    public interface AppLifecycleListener {
        void onAppDestroy();
    }
}
