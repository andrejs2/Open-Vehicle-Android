/**
 * OVMS GCM listener
 *
 * (invoked by com.google.android.gms.gcm.GcmReceiver)
 */

package com.openvehicles.OVMS.receiver;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import com.google.android.gms.gcm.GcmListenerService;

import com.luttu.AppPrefes;
import com.openvehicles.OVMS.R;
import com.openvehicles.OVMS.api.ApiService;
import com.openvehicles.OVMS.entities.CarData;
import com.openvehicles.OVMS.ui.MainActivity;
import com.openvehicles.OVMS.ui.utils.Ui;
import com.openvehicles.OVMS.utils.CarsStorage;
import com.openvehicles.OVMS.utils.OVMSNotifications;
import com.openvehicles.OVMS.utils.Sys;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.locks.ReentrantLock;


public class MyGcmListenerService extends GcmListenerService {

    private static final String TAG = "MyGcmListenerService";

    AppPrefes appPrefes;

	// lock to prevent concurrent uses of OVMSNotifications:
	// 	(necessary for dupe check)
	private final ReentrantLock dbAccess = new ReentrantLock();

	// timestamp parser:
	SimpleDateFormat serverTime;

	@Override
	public void onCreate() {
		super.onCreate();

		appPrefes = new AppPrefes(this, "ovms");

		// create timestamp parser:
		serverTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		serverTime.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	@Override
    public void onMessageReceived(String from, Bundle data) {

        // get notification text:
		String contentTitle = data.getString("title");		// vehicle id
		String contentType = data.getString("type");		// A=alert / I=info / E=error
        String contentText = data.getString("message");
        String contentTime = data.getString("time");

        Log.i(TAG, "Notification received from=" + from
				+ ", title=" + contentTitle
				+ ", type=" + contentType
                + ", message=" + contentText
                + ", time=" + contentTime);

		if (contentTitle == null || contentText == null) {
            Log.w(TAG, "no title/message => abort");
            return;
        }
		if (contentType == null) {
			contentType = OVMSNotifications.guessType(contentText);
		}

		// parse timestamp:
		Date timeStamp = null;
		try {
			timeStamp = serverTime.parse(contentTime);
		} catch (Exception ignored) {
		}
		if (timeStamp == null)
			timeStamp = new Date();

		// identify the vehicle:
		CarData car = CarsStorage.get().getCarById(contentTitle);
		if (car == null) {
			Log.w(TAG, "vehicle ID '" + contentTitle + "' not found => drop message");
			return;
		}

		// add vehicle label to title:
		contentTitle += " (" + car.sel_vehicle_label + ")";

		// add notification to database:
		boolean is_new;
		dbAccess.lock();
		try {
			OVMSNotifications savedList = new OVMSNotifications(this);
			is_new = savedList.addNotification(contentType, contentTitle, contentText, timeStamp);
		} finally {
			dbAccess.unlock();
		}

		boolean ui_notified = false;
		if (is_new) {
			// Send system broadcast for Automagic / Tasker / ...
			if (appPrefes.getData("option_broadcast_enabled", "0").equals("1")) {
				Log.d(TAG, "onMessageReceived: sending broadcast");
				SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
				Intent intent = new Intent(ApiService.ACTION_NOTIFICATION);
				intent.putExtra("msg_notify_vehicleid", car.sel_vehicleid);
				intent.putExtra("msg_notify_vehicle_label", car.sel_vehicle_label);
				intent.putExtra("msg_notify_from", from);
				intent.putExtra("msg_notify_title", contentTitle);
				intent.putExtra("msg_notify_type", contentType);
				intent.putExtra("msg_notify_text", contentText);
				intent.putExtra("msg_notify_time", timeFormat.format(timeStamp));
				sendBroadcast(intent);
				ApiService.sendKustomBroadcast(this, intent);

				ui_notified = true;
			}
		}

		// prepare user notification filter check:
		boolean filterInfo = appPrefes.getData("notifications_filter_info").equals("on");
		boolean filterAlert = appPrefes.getData("notifications_filter_alert").equals("on");
		String selectedCarId = CarsStorage.get().getLastSelectedCarId();
		boolean isSelectedCar = car.sel_vehicleid.equals(selectedCarId);

		if (!is_new) {
			Log.d(TAG, "message is duplicate => skip user notification");
		} else if (!isSelectedCar && filterInfo && contentType.equals("I")) {
			Log.d(TAG, "info notify for other car => skip user notification");
		} else if (!isSelectedCar && filterAlert && !contentType.equals("I")) {
			Log.d(TAG, "alert/error notify for other car => skip user notification");
		} else {
            // add notification to system & UI:
			Log.d(TAG, "adding notification to system & UI");

            // create App launch Intent:
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            notificationIntent.putExtra("onNotification", true);
            PendingIntent launchOVMSIntent = PendingIntent.getActivity(this, 0, notificationIntent,
					Sys.getMutableFlags(PendingIntent.FLAG_ONE_SHOT, false));

            // determine icon for this car:
			int icon;
			if (car.sel_vehicle_image.startsWith("car_imiev_"))
				icon = R.drawable.map_car_imiev; // one map icon for all colors
			else if (car.sel_vehicle_image.startsWith("car_i3_"))
				icon = R.drawable.map_car_i3; // one map icon for all colors
			else if (car.sel_vehicle_image.startsWith("car_smart_"))
				icon = R.drawable.map_car_smart; // one map icon for all colors
			else if (car.sel_vehicle_image.startsWith("car_kianiro_"))
				icon = R.drawable.map_car_kianiro_grey; // one map icon for all colors
			else if (car.sel_vehicle_image.startsWith("car_kangoo_"))
				icon = R.drawable.map_car_kangoo; // one map icon for all colors
			else
				icon = Ui.getDrawableIdentifier(this,"map_" + car.sel_vehicle_image);

            // create Notification builder:
			NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(this, "default")
                            .setAutoCancel(true)
                            .setDefaults(Notification.DEFAULT_ALL)
                            .setSmallIcon((icon != 0) ? icon : R.drawable.map_car_default)
                            .setContentTitle(contentTitle)
                            .setContentText(contentText.replace('\r', '\n'))
                            .setContentIntent(launchOVMSIntent);

            // announce Notification via Android system:
            NotificationManager mNotificationManager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(1, mBuilder.build());

            // update UI (NotificationsFragment) if not done already:
			if (!ui_notified) {
				Intent uiNotify = new Intent(ApiService.ACTION_NOTIFICATION);
				sendBroadcast(uiNotify);
			}
        }

    }
}
