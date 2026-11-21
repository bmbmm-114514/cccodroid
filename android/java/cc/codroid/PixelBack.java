package cc.codroid;

import android.app.Activity;
import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import androidx.annotation.RequiresApi;

/** Subscribe only while the window can handle Back. At the root Android owns the animation. */
final class PixelBack {
    interface Handler {
        default void start(boolean fromRight) {}
        default void progress(float fraction) {}
        default void cancel() {}
        void commit();
    }

    static Runnable attach(Activity activity, Handler handler) {
        if (Build.VERSION.SDK_INT < 33) return () -> {};
        return Api33.attach(activity, handler);
    }

    @RequiresApi(33)
    private static class Api33 {
        static Runnable attach(Activity activity, Handler handler) {
            OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            OnBackInvokedCallback callback = Build.VERSION.SDK_INT >= 34
                    ? Api34.callback(handler) : handler::commit;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return () -> dispatcher.unregisterOnBackInvokedCallback(callback);
        }
    }

    @RequiresApi(34)
    private static class Api34 {
        static OnBackAnimationCallback callback(Handler handler) {
            return new OnBackAnimationCallback() {
                @Override public void onBackStarted(BackEvent event) {
                    handler.start(event.getSwipeEdge() == BackEvent.EDGE_RIGHT);
                }
                @Override public void onBackProgressed(BackEvent event) { handler.progress(event.getProgress()); }
                @Override public void onBackCancelled() { handler.cancel(); }
                @Override public void onBackInvoked() { handler.commit(); }
            };
        }
    }

    private PixelBack() {}
}
