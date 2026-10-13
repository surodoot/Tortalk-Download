package dev.gyeopdam.embeddedtor;

import android.annotation.TargetApi;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.widget.FrameLayout;

/** Public API30 adapter. Its body must be compiled with the real compile SDK, not API26 stubs. */
// The sole caller checks SDK_INT >= 30; retain a platform-only annotation without AndroidX.
@SuppressLint("UseRequiresApi")
@TargetApi(30)
final class TorTalkInsetsApi30 {
    static void install(Activity activity,FrameLayout host) {
        activity.getWindow().setDecorFitsSystemWindows(false);
        host.setOnApplyWindowInsetsListener((view,insets)->{
            int safeTypes=WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout();
            int handledTypes=safeTypes|WindowInsets.Type.ime();
            Insets occupied=insets.getInsets(handledTypes);
            // max-union insets physically reduce the child WebView's measured bounds,
            // so even older providers keep header, navigation and composer above the IME.
            if(host.getPaddingLeft()!=occupied.left||host.getPaddingTop()!=occupied.top
                    ||host.getPaddingRight()!=occupied.right||host.getPaddingBottom()!=occupied.bottom) {
                host.setPadding(occupied.left,occupied.top,occupied.right,occupied.bottom);
            }
            // Forward zeroed handled dimensions, not CONSUMED. Children still receive
            // every hide/show update and cannot retain ghost padding or double-resize.
            return new WindowInsets.Builder(insets)
                    .setInsets(handledTypes,Insets.NONE)
                    .setInsetsIgnoringVisibility(safeTypes,Insets.NONE)
                    .setDisplayCutout(null)
                    .build();
        });
        host.requestApplyInsets();
    }
}
