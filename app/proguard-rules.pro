# MetroCast release shrinking (R8).
# Shrink and optimize, but keep class/member names: stack traces stay readable and nothing that is
# looked up by name can break.
-dontobfuscate

# Created by Android or by libraries from a class name (manifest receivers/activities are kept
# automatically, these are listed anyway so the intent is clear).
-keep class com.metrocast.app.MetroCastApp { <init>(); }
-keep class com.metrocast.app.ui.MainActivity { <init>(); }
-keep class com.metrocast.app.widget.MetroWidgetReceiver { <init>(); }
-keep class com.metrocast.app.widget.PylonWidgetReceiver { <init>(); }
-keep class com.metrocast.app.widget.NextTrainWidgetReceiver { <init>(); }
-keep class com.metrocast.app.widget.TrackWidgetReceiver { <init>(); }
-keep class com.metrocast.app.widget.WidgetTicker { <init>(); }
-keep class com.metrocast.app.widget.WidgetConfigActivity { <init>(); }
-keep class com.metrocast.app.notify.LiveTripTicker { <init>(); }
-keep class com.metrocast.app.notify.BootReceiver { <init>(); }

# The widget's refresh button: Glance stores the callback's class name and creates it by reflection.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# WorkManager creates workers by class name with (Context, WorkerParameters).
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}
