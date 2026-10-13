package dev.gyeopdam.embeddedtor;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.widget.FrameLayout;

/** Keep system UI outside the unchanged packaged design's viewport. */
final class TorTalkInsets {
    static FrameLayout host(Activity activity,View content) {
        FrameLayout host=new FrameLayout(activity);
        host.setBackgroundColor(0xfff7f6f0);
        host.addView(content,new FrameLayout.LayoutParams(-1,-1));
        // Older releases retain normal decor fitting and the manifest's adjustResize behavior.
        // Only this guarded call can load API30-only platform types.
        if(Build.VERSION.SDK_INT>=30)TorTalkInsetsApi30.install(activity,host);
        return host;
    }
}
