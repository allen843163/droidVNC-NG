package net.christianbeier.droidvnc_ng;

/*
 * DroidVNC-NG InputService that binds to the Android a11y API and posts input events sent by the native backend to Android.
 *
 * Its original version was copied from https://github.com/anyvnc/anyvnc/blob/master/apps/ui/android/src/com/anyvnc/AnyVncAccessibilityService.java at
 * f32015d9d29d2d022217f52a99f676ace90cc29e.
 *
 * Original author is Tobias Junghans <tobydox@veyon.io>
 *
 * Licensed under GPL-2.0 as per https://github.com/anyvnc/anyvnc/blob/master/COPYING.
 *
 * Swipe fixes and gesture handling by Christian Beier <info@christianbeier.net>.
 *
 */

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.PathMeasure;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IInterface;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.ViewConfiguration;
import android.graphics.Path;
import android.view.accessibility.AccessibilityNodeProvider;

import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;

public class InputService extends AccessibilityService {

	/**
	 * This globally tracks gesture completion status and is _not_ per gesture.
	 */


	private static final String TAG = "InputService";

	private static InputService instance;

	private boolean mIsButtonOneDown;
	private Path mPath;
	private long mLastGestureStartTime;

	private boolean mIsKeyCtrlDown;
	private boolean mIsKeyAltDown;
	private boolean mIsKeyShiftDown;
	private boolean mIsKeyDelDown;
	private boolean mIsKeyEscDown;

	private float mScaling;

	private final CommandController commandController = new CommandController();

	@Override
	public void onAccessibilityEvent( AccessibilityEvent event ) { }

	@Override
	public void onInterrupt() { }

	@Override
	public void onServiceConnected()
	{
		super.onServiceConnected();
		instance = this;
		Log.i(TAG, "onServiceConnected");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		instance = null;
		Log.i(TAG, "onDestroy");
	}

	public static boolean isEnabled()
	{
		return instance != null;
	}

	/**
	 * Set scaling factor that's applied to incoming pointer events by dividing coordinates by
	 * the given factor.
	 * @param scaling
	 * @return
	 */
	public static boolean setScaling(float scaling) {
		try {
			instance.mScaling = scaling;
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static void onPointerEvent(int buttonMask, int x, int y, long client) {

		try {
			x /= instance.mScaling;
			y /= instance.mScaling;


			/*
			    left mouse button
			 */

			// down, was up
			if ((buttonMask & (1 << 0)) != 0 && !instance.mIsButtonOneDown) {
				instance.mIsButtonOneDown = true;
				instance.startGesture(x, y);
			}

			// down, was down
			if ((buttonMask & (1 << 0)) != 0 && instance.mIsButtonOneDown) {
				instance.continueGesture(x, y);
			}

			// up, was down
			if ((buttonMask & (1 << 0)) == 0 && instance.mIsButtonOneDown) {
				instance.mIsButtonOneDown = false;

				instance.endGesture(x, y);
			}


			// right mouse button
			if ((buttonMask & (1 << 2)) != 0) {
				instance.longPress(x, y);
			}

			// scroll up
			if ((buttonMask & (1 << 3)) != 0) {
				DisplayMetrics displayMetrics = new DisplayMetrics();
				WindowManager wm = (WindowManager) instance.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
				wm.getDefaultDisplay().getRealMetrics(displayMetrics);

				instance.scroll(x, y, -displayMetrics.heightPixels / 2);
			}

			// scroll down
			if ((buttonMask & (1 << 4)) != 0) {
				DisplayMetrics displayMetrics = new DisplayMetrics();
				WindowManager wm = (WindowManager) instance.getApplicationContext().getSystemService(Context.WINDOW_SERVICE);
				wm.getDefaultDisplay().getRealMetrics(displayMetrics);

				instance.scroll(x, y, displayMetrics.heightPixels / 2);
			}
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onPointerEvent: failed: " + e.toString());
		}
	}

	public static void onKeyEvent(int down, long keysym, long client) {
		Log.d(TAG, "onKeyEvent: keysym " + keysym + " down " + down + " by client " + client);
		/*
			Special key handling.
		 */
		try {
			/*
				Save states of some keys for combo handling.
			 */
			if(keysym == 0xFFE3)
				instance.mIsKeyCtrlDown = down != 0;

			if(keysym == 0xFFE9 || keysym == 0xFF7E) // MacOS clients send Alt as 0xFF7E
				instance.mIsKeyAltDown = down != 0;

			if(keysym == 0xFFE1)
				instance.mIsKeyShiftDown = down != 0;

			if(keysym == 0xFFFF)
				instance.mIsKeyDelDown = down != 0;

			if(keysym == 0xFF1B)
				instance.mIsKeyEscDown = down != 0;

			/*
				Ctrl-Alt-Del combo.
		 	*/
			if(instance.mIsKeyCtrlDown && instance.mIsKeyAltDown && instance.mIsKeyDelDown) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Alt-Del");
				Handler mainHandler = new Handler(instance.getMainLooper());
				mainHandler.post(new Runnable() {
					@Override
					public void run() {
						MainService.togglePortraitInLandscapeWorkaround();
					}
				});
			}

			/*
				Ctrl-Shift-Esc combo.
		 	*/
			if(instance.mIsKeyCtrlDown && instance.mIsKeyShiftDown && instance.mIsKeyEscDown) {
				Log.i(TAG, "onKeyEvent: got Ctrl-Shift-Esc");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
			}

			/*
				Home/Pos1
		 	*/
			if (keysym == 0xFF50 && down != 0) {
				Log.i(TAG, "onKeyEvent: got Home/Pos1");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
			}

			/*
				Esc
			 */
			if(keysym == 0xFF1B && down != 0)  {
				Log.i(TAG, "onKeyEvent: got Home/Pos1");
				instance.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
			}

		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onKeyEvent: failed: " + e.toString());
		}
	}

	public static void onCutText(String text, long client) {
		Log.d(TAG, "onCutText: text '" + text + "' by client " + client);

		try {
			((ClipboardManager)instance.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(text, text));
		} catch (Exception e) {
			// instance probably null
			Log.e(TAG, "onCutText: failed: " + e.toString());
		}

	}

	private void startGesture(int x, int y) {
		mPath = new Path();
		mPath.moveTo( x, y );
		mLastGestureStartTime = System.currentTimeMillis();
	}

	private void continueGesture(int x, int y) {
		mPath.lineTo( x, y );
	}

	private void endGesture(int x, int y) {
		mPath.lineTo( x, y );

		switch (computeGesture(mPath)) {
			case Constants.GESTURE_TYPE_TAP:
				commandController.executeCommand("input tap " + x + " " + y);

				break;
			case Constants.GESTURE_TYPE_SWIPE:
				float[] startPointer = new float[2];

				float[] endPointer = new float[2];

				getPoint(mPath, 0.0f, startPointer);

				getPoint(mPath, new PathMeasure(mPath, false).getLength(), endPointer);

				commandController.executeCommand(
						"input swipe " +
								startPointer[0] + " " + startPointer[1] + " " +
								endPointer[0] + " " + endPointer[1] + " " + "100"
				);
				break;
		}

	}

	private int computeGesture(Path path) {
		PathMeasure pathMeasure = new PathMeasure(path, false);

		if(pathMeasure.getLength() > 2.0f) {
			return Constants.GESTURE_TYPE_SWIPE;
		}
		else {
			return Constants.GESTURE_TYPE_TAP;
		}

	}

	private void getPoint(Path path, float distance, float[] array) {
		PathMeasure pathMeasure = new PathMeasure(path, false);

		boolean result = pathMeasure.getPosTan(distance, array, null);
	}

	private  void longPress(int x, int y )
	{
		commandController.executeCommand("input swipe " + x + " " + y + " " + x + " " + y + " 2000");
	}
//
	private void scroll(int x, int y, int scrollAmount )
	{
			/*
			   Ignore if another gesture is still ongoing. Especially true for scroll events:
			   These mouse button 4,5 events come per each virtual scroll wheel click, an incoming
			   event would cancel the preceding one, only actually scrolling when the user stopped
			   scrolling.
			 */
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//			if(!mGestureCallback.mCompleted)
//				return;
//
//			mGestureCallback.mCompleted = false;
//			dispatchGesture(createSwipe(x, y, x, y - scrollAmount, ViewConfiguration.getScrollDefaultDelay()), mGestureCallback, null);
		}
		else {
//			executeCommand("input swipe " + x + " " + y + " " + x + " " + (y - scrollAmount));
		}
	}

	private static class CommandController {
		private boolean isCompleted = true;

		private int executeCommand(String cmd) {
			if(!isCompleted) {
				return -1;
			}

			isCompleted = false;

			try {
				Process process = Runtime.getRuntime().exec(new String[] {"su", "-c", cmd});

				InputStream inputStream = process.getInputStream();

				int waitValue = process.waitFor();

				if (inputStream.available() > 0) {
					byte[] result = new byte[inputStream.available()];

					inputStream.read(result);

					Log.d("Allen", "Result = " + new String(result));
				}

				Log.d("Allen", "with value = " + waitValue);

				Log.d("Allen", "exit value = " + process.exitValue());

				isCompleted = true;

				return process.exitValue();

			} catch (IOException | InterruptedException e) {
				e.printStackTrace();

				isCompleted = true;
				return -1;
			}
		}

		public boolean isCompleted() {
			return isCompleted;
		}
	}
}
